package com.autonomousone.messages.gateway

import android.content.Context
import com.autonomousone.messages.gateway.health.GatewayHealthRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Gateway self-healing supervisor.
 *
 * The gateway used to rely on "just retry": a heartbeat stuck in a 5-minute
 * backoff after a WiFi blip would not retry when the network returned; a LAN
 * server bound to the old DHCP address stayed unreachable after the phone
 * moved networks; the OutboxPoller burned HTTP attempts against a dead radio.
 *
 * This supervisor replaces all of that with ONE reconcile loop over a
 * declarative desired state:
 *
 *   state = f(desiredEnabled, hasConsent, online, serverIsUp, boundIp == nowIp)
 *
 * Every input change (user toggle, consent, network callback, IP change)
 * simply nudges [reconcileNow] on a CONFLATED channel; the loop re-derives
 * the whole truth and acts. No duplicated state machines, no missed event
 * during an in-flight action (conflated → the next pass sees the newest facts).
 *
 * Reconcile actions, all idempotent:
 *  - not desired / no consent / offline → stop components, show the waiting
 *    state (offline is WAITING_FOR_NETWORK, never ERROR — it is expected);
 *  - network back → retry IMMEDIATELY: cancel whatever backoff the heartbeat
 *    is sitting on and reconcile;
 *  - LAN server down (or bound to a stale IP after a network change — the
 *    DHCP rebind) → replace the GatewayServer instance (its accept executor
 *    is shutdownNow'd on stop(), so it is not restartable by design);
 *  - cloud/GMweb components down → start them;
 *  - a bind failure (port taken) retries with exponential backoff on the loop
 *    itself — the loop is the backoff, so start() can stay non-blocking and
 *    non-throwing.
 *
 * Reboot recovery is automatic: BootGatewayReceiver (or START_STICKY's null
 * intent) sees [GatewayPreferences.gatewayDesiredEnabled] + consent and calls
 * start(), which flips desired on and reconciles.
 */
class ConnectionSupervisor private constructor(
    context: Context,
    private val prefs: GatewayPreferences,
    private val networkMonitor: NetworkMonitor,
    private val scope: CoroutineScope,
    private val newServer: () -> GatewayServer,
    private val components: ManagedComponents,
    private val onLog: (String) -> Unit
) {
    enum class DeliveryIntake { LEGACY_PULL, CONTROL_PLANE_COMMANDS }

    companion object {
        @Volatile
        private var instance: ConnectionSupervisor? = null

        /**
         * Shortest gap between post-reconnect reconciliations.
         *
         * Ten minutes: reconnexions can happen in bursts, and a bounded reconciliation that finds
         * nothing is not worth repeating every tick.
         */
        private const val RECONCILE_MIN_INTERVAL_MS = 10 * 60_000L

        /** One verification page per source per two minutes; see [lastVerifyRequestAt]. */
        private const val VERIFY_MIN_INTERVAL_MS = 2 * 60_000L

        fun get(
            context: Context,
            prefs: GatewayPreferences,
            networkMonitor: NetworkMonitor,
            scope: CoroutineScope,
            newServer: () -> GatewayServer,
            components: ManagedComponents,
            onLog: (String) -> Unit
        ): ConnectionSupervisor = instance ?: synchronized(this) {
            instance ?: ConnectionSupervisor(
                context.applicationContext, prefs, networkMonitor, scope, newServer, components, onLog
            ).also { instance = it }
        }

        /** The live supervisor, or null before the service created it. */
        fun peek(): ConnectionSupervisor? = instance
    }

    /** The components the supervisor owns the lifecycle of. */
    class ManagedComponents(
        val startHeartbeat: () -> Unit,
        val stopHeartbeat: () -> Unit,
        val retryHeartbeat: () -> Unit,
        val startPoller: () -> Unit,
        val stopPoller: () -> Unit,
        val startSync: () -> Unit,
        /** PR-02: the durable event outbox worker (cloud transmitter). */
        val startEventUploader: () -> Unit = {},
        val stopEventUploader: () -> Unit = {},
        val startTrustPublisher: () -> Unit = {},
        val stopTrustPublisher: () -> Unit = {},
        val retryTrustPublisher: () -> Unit = {},
        /** PR-10: the strategic SecureCommandPoller (/api/v1 agent bridge). */
        val startCommandPoller: () -> Unit = {},
        val stopCommandPoller: () -> Unit = {},
        /**
         * Reports whether the delivery poller's LOOP is alive, and wakes it.
         *
         * "Start was called once" and "the loop is running now" are different facts: a job
         * that exited (gate disabled, consent revoked, cancellation) leaves nothing polling
         * while the supervisor still believes the gateway is up. A reconnect has to be able
         * to tell the two apart, and to poke the loop it already has rather than build a
         * second one.
         */
        val isPollerRunning: () -> Boolean = { false },
        /**
         * Whether the poller is running but demonstrably not polling.
         *
         * An active coroutine job is NOT proof of a live bridge: the network-wait branch and a hung
         * socket both leave a job that issues no requests. Before this existed the supervisor could
         * only see "running", so a stalled loop looked healthy for the nine hours it was silent.
         */
        val isPollerStalled: () -> Boolean = { false },
        val wakePoller: () -> Unit = {},
        /**
         * Nudges the OUTBOUND event uploader. Saving a new GMweb server must reach it
         * immediately, not on its next invalidation — otherwise the phone keeps uploading to
         * the old server for a while after the user has been told the change took effect.
         */
        val retryUploader: () -> Unit = {},
        /**
         * Reconciles the mirror against the outbox after a reconnect (mission §34).
         *
         * The supervisor owns the MOMENT — "we are online again, which is when a gap is most likely
         * and most fixable" — and the parent owns the work. Throttled here rather than in the
         * caller, because only the supervisor knows how often it ticks.
         */
        val reconcileMissedEvents: () -> Unit = {},
        /**
         * Walks one bounded page of the full-mirror verification, once per source (mission §35).
         *
         * Separate from [reconcileMissedEvents] because it answers a different question: the window
         * check asks "did anything RECENT fail to replicate?", while this one asks, over the whole
         * mirror and resumably, "is there any message, however old, with no durable event?" — the
         * question the 48-hour window structurally cannot ask.
         */
        val verifyMirror: () -> Unit = {},
        val deliveryIntake: DeliveryIntake = DeliveryIntake.LEGACY_PULL
    )

    enum class State {
        DISABLED,            // user/consent says off
        WAITING_FOR_NETWORK, // desired + consent, but no validated internet
        CONNECTING,          // bringing server/heartbeat/poller up
        /**
         * Every component the gateway WANTS is running.
         *
         * READ THIS BEFORE COLOURING ANYTHING GREEN: it means the components were
         * STARTED, not that GMweb and this phone are actually exchanging anything. The
         * two claims came apart in production — `/api/v1/agent/events/batch` was ACKing
         * events while `/gateway/pull` never succeeded, and the screen was green through
         * it. End-to-end truth is [com.autonomousone.messages.gateway.health.GatewayHealthRecorder],
         * which is derived from what the components OBSERVED.
         */
        CONNECTED,
        RECONNECTING,        // a live component dropped (network flap, stale IP bind)
        ERROR                // bind failed with retries pending — self-healing continues
    }

    private val _stateFlow = MutableStateFlow(State.DISABLED)
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    private val reconciles = Channel<Unit>(Channel.CONFLATED)
    private var loopJob: Job? = null

    /** The user's intent — persisted (gatewayDesiredEnabled), separate from
     *  the runtime server state. start()/stop() flip this; everything else
     *  reacts. */
    @Volatile
    var desiredEnabled: Boolean = false
        private set

    private var server: GatewayServer? = null
    private var boundIp: String? = null
    private var backoffMs = 5_000L
    @Volatile private var lastError: String? = null

    /** When the last post-reconnect reconciliation was requested. Throttles the tick. */
    private var lastReconcileRequestAt = 0L

    /**
     * How often one page of the full-mirror verification may be walked (mission §35).
     *
     * Much shorter than the recovery window, because a sweep of a large mirror is many pages and the
     * only way it ever finishes is by making steady progress. One page of 500 rows per source per two
     * minutes covers a 360k-message mirror in roughly 24 hours of online time, spread across page
     * loads — deliberately unambitious, because this is a background audit and must never compete
     * with delivering a message.
     */
    private var lastVerifyRequestAt = 0L

    init {
        desiredEnabled = prefs.gatewayDesiredEnabled && prefs.hasGatewayConsent
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /** Begin supervising (idempotent). Called from every ACTION_START entry:
     *  user toggle, boot receiver, START_STICKY revival. */
    fun start() {
        val phaseStart = System.currentTimeMillis()
        desiredEnabled = true
        prefs.gatewayDesiredEnabled = true
        GatewayHealthRecorder.setDesired(prefs.hasGatewayConsent)
        GatewayHealthRecorder.setEndpointUrl(prefs.gmwebServerOrigin)
        ensureLoop()
        reconcileNow()
        // HOW LONG DID THE ENTRY POINT ITSELF TAKE? `start()` only flips desired state
        // and nudges the conflated reconcile, so this number is expected to be ~0ms; if
        // it is not, the caller is doing synchronous work on the UI thread.
        com.autonomousone.messages.utils.DiagnosticLog.event(
            "GATEWAY_START",
            "phase=SUPERVISOR_START durationMs=${System.currentTimeMillis() - phaseStart}"
        )
    }

    /** User intent OFF: stop components and stay down until start() again.
     *  Does NOT stop the service — ACTION_STOP's own path does that. */
    fun stop() {
        desiredEnabled = false
        prefs.gatewayDesiredEnabled = false
        GatewayHealthRecorder.setDesired(false)
        reconcileNow()
    }

    /** Nudge the loop to re-derive everything. Never blocks. */
    fun reconcileNow() {
        reconciles.trySend(Unit)
    }

    /**
     * Called when the network FLIPS online (validated). Cancels any backoff
     * the heartbeat is sitting on — the whole point: no more waiting out a
     * 5-minute ladder after a 5-second WiFi re-association.
     */
    fun retryNow() {
        if (!desiredEnabled || !prefs.hasGatewayConsent) return
        if (!networkMonitor.isOnline()) return
        backoffMs = 5_000L
        lastError = null
        onLog("🌐 Network available — retrying gateway connections now")
        // Revive the reconcile loop if it died (service freshly rebuilt by
        // onStartCommand without ACTION_START, or a cancelled job).
        // ensureLoop() is idempotent.
        ensureLoop()
        // HeartbeatManager.retryNow() resets the ladder AND wakes the
        // pending backoff sleep; start() alone no-ops while the job is
        // alive, which silently kept the old backoff in force.
        components.retryHeartbeat()
        components.retryTrustPublisher()
        // The OUTBOUND leg gets the same treatment: a server change or a manual reconnect must
        // reach the uploader now, not on its next invalidation, or the phone keeps talking to
        // the previous server after the user was told the change took effect.
        components.retryUploader()
        // The delivery bridge is the leg the user is almost always asking about, and a
        // retry that only reset a backoff in a component whose loop had already EXITED
        // would recolour the card without polling anything. `startPoller` is idempotent, so
        // restarting a dead loop cannot produce a second poller.
        if (prefs.gmwebServerOrigin.isNotBlank()) {
            if (!components.isPollerRunning()) {
                onLog("🔁 Delivery poller had stopped — restarting it")
                components.startPoller()
            }
            components.wakePoller()
        }
        // The reconnect is a REQUEST, not a result: clearing the recorded error lets the
        // card say "reconnecting", and it deliberately does not claim success.
        GatewayHealthRecorder.onReconnectRequested()
        reconcileNow()
    }

    /** Release everything (service onDestroy). Clears the singleton. */
    fun shutdown() {
        loopJob?.cancel()
        loopJob = null
        server?.stop()
        server = null
        boundIp = null
        components.stopHeartbeat()
        components.stopPoller()
        components.stopEventUploader()
        components.stopTrustPublisher()
        components.stopCommandPoller()
        _stateFlow.value = State.DISABLED
        synchronized(this) { instance = null }
    }

    // ── The loop ───────────────────────────────────────────────────────────

    private fun ensureLoop() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            // Network transitions nudge the same conflated reconcile.
            launch { networkMonitor.onlineFlow().collect { online ->
                if (online && desiredEnabled) retryNow() else reconcileNow()
            } }
            // A LAN IPv4 change is what a network switch looks like locally;
            // the compare against boundIp inside reconcile() IS the rebind.
            launch { while (isActive) { delay(10_000); reconcileNow() } }
            for (nudge in reconciles) {
                try {
                    reconcile()
                } catch (e: Exception) {
                    lastError = e.message
                    _stateFlow.value = State.ERROR
                    onLog("⚠️ Gateway reconcile failed: ${e.message ?: "unknown"} — retry pending")
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(300_000L)
                    // Do not depend on the periodic health tick to continue
                    // the retry ladder. A failed reconcile explicitly queues
                    // its own successor until consent or user intent turns it off.
                    if (GatewayAccessPolicy.shouldAutoReconnect(
                            prefs.hasGatewayConsent,
                            desiredEnabled
                        )
                    ) {
                        reconcileNow()
                    }
                }
            }
        }
    }

    private fun reconcile() {
        // PER-PHASE TIMING. The "gateway never turns green after an update" report
        // needs to point at a phase, not at a suspicion: each step below logs its own
        // duration, so a stall localises to network validation, LAN bind or component
        // start instead of being an opaque hang.
        val reconcileStart = System.currentTimeMillis()
        var phaseStart = reconcileStart
        fun notePhase(phase: String) {
            val now = System.currentTimeMillis()
            com.autonomousone.messages.utils.DiagnosticLog.event(
                "GATEWAY_START",
                "phase=$phase durationMs=${now - phaseStart} totalMs=${now - reconcileStart}"
            )
            phaseStart = now
        }

        if (!desiredEnabled || !prefs.hasGatewayConsent) {
            if (_stateFlow.value != State.DISABLED) {
                prefs.isEnabled = false // runtime gate off FIRST: components stop transmitting
                components.stopPoller()
                components.stopHeartbeat()
                components.stopEventUploader()
                components.stopTrustPublisher()
                components.stopCommandPoller()
                server?.stop()
                server = null
                boundIp = null
                onLog("🛑 Gateway stopped (disabled)")
            }
            _stateFlow.value = State.DISABLED
            return
        }

        val online = networkMonitor.isOnline()
        notePhase("NETWORK_SNAPSHOT")
        if (!online) {
            if (_stateFlow.value != State.WAITING_FOR_NETWORK) {
                prefs.isEnabled = false // gate transmission while offline (poller/heartbeat stop below)
                components.stopPoller()   // gate: ZERO HTTP requests while offline
                components.stopHeartbeat()
                components.stopTrustPublisher()
                onLog("📴 Gateway waiting for network…")
            }
            _stateFlow.value = State.WAITING_FOR_NETWORK
            // Publish BEFORE returning. This branch used to skip publishHealthContext(), which is
            // only reached at the end of the online path, so the health registry kept the last
            // online reading and a diagnostic could report "Internet: validated" while the device
            // had no network at all.
            publishHealthContext()
            return
        }

        val wasDegraded = _stateFlow.value in setOf(State.RECONNECTING, State.ERROR, State.WAITING_FOR_NETWORK)
        if (wasDegraded) _stateFlow.value = State.RECONNECTING

        // ── LAN server: (re)bind when down or stale ────────────────────────
        val currentIp = if (prefs.bindAllInterfaces) "0.0.0.0" else GatewayServer.getLocalIpAddress()
        notePhase("LAN_ADDRESS_LOOKUP")
        val s = server
        val needsBind = s?.isRunning() != true || (boundIp != "0.0.0.0" && currentIp != "127.0.0.1" && boundIp != currentIp)
        if (needsBind) {
            if (s != null && s.isRunning()) {
                onLog("🔁 LAN address changed ($boundIp → $currentIp) — rebinding server")
            }
            s?.stop()
            val fresh = newServer()
            fresh.start() // GatewayServer.start is non-throwing; it logs internally
            if (!fresh.isRunning()) {
                throw IllegalStateException("bind failed on port ${prefs.port}")
            }
            server = fresh
            boundIp = currentIp
            onLog("🚀 Gateway server on http://$currentIp:${prefs.port}")
        }
        notePhase("LAN_BIND")

        // ── Cloud + GMweb + shadow sync (idempotent starts) ────────────────
        // These are the prime suspect for a slow first start: each one may touch the
        // DB or the network, and `components.startSync()` kicks TelephonySyncCoordinator
        // (tail delta / history backfill). Timing them separately is what separates a
        // slow heartbeat from a slow history sweep.
        components.startHeartbeat()
        notePhase("HEARTBEAT_START")
        components.startEventUploader() // PR-02: durable outbox → GMweb transmitter
        notePhase("EVENT_UPLOADER_START")
        components.startTrustPublisher()
        notePhase("TRUST_PUBLISHER_START")
        // A SEND_SMS command must have exactly one intake owner. Keep the
        // migration switch explicit; never run both consumers concurrently.
        when (components.deliveryIntake) {
            DeliveryIntake.LEGACY_PULL -> {
                components.stopCommandPoller()
                if (prefs.gmwebServerOrigin.isNotBlank()) {
                    // Requirement 10: bounded controlled recovery. `startPoller` alone cannot fix a
                    // loop that is ACTIVE and silent — an active job is not proof of a live bridge —
                    // so a stalled one is replaced. The DECISION comes from the tested rule rather
                    // than being restated here as a compound boolean: a rule at a call site loses
                    // clauses, and this one decides whether a silent bridge is restarted at all.
                    //
                    // The recovery is self-limiting: a restart resets the loop's activity clock, so
                    // this can fire at most once per healthy window and cannot become a hot restart
                    // loop.
                    when (
                        PollStatePolicy.recovery(
                            isActive = components.isPollerRunning(),
                            stalled = components.isPollerStalled()
                        )
                    ) {
                        PollStatePolicy.Recovery.RESTART -> {
                            onLog("♻️ Pull loop stalled — replacing it")
                            com.autonomousone.messages.utils.DiagnosticLog.event(
                                "GATEWAY_PULL", "stalled_restart"
                            )
                            components.stopPoller()
                            components.startPoller()
                        }
                        PollStatePolicy.Recovery.START -> components.startPoller()
                        PollStatePolicy.Recovery.NONE -> Unit
                    }
                }
            }
            DeliveryIntake.CONTROL_PLANE_COMMANDS -> {
                components.stopPoller()
                components.startCommandPoller()
            }
        }
        notePhase("POLLER_OR_COMMAND_START")
        // Mission §34: reconcile after a reconnect, which is when a missed message is most likely
        // (the device was offline or the process was dead) and most useful to recover. Throttled,
        // because reconcile() runs on a short tick and a bounded reconciliation on every tick would
        // be pointless work for no new information.
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastReconcileRequestAt >= RECONCILE_MIN_INTERVAL_MS) {
            lastReconcileRequestAt = nowMs
            components.reconcileMissedEvents()
            // The deep walk rides the same moment for the same reason — we are online and the
            // reconcile tick is the only thing that knows how often this happens — but it is
            // separately throttled and separately gated, so a slow verification can never slow down
            // the repair of a fresh gap.
            if (nowMs - lastVerifyRequestAt >= VERIFY_MIN_INTERVAL_MS) {
                lastVerifyRequestAt = nowMs
                components.verifyMirror()
            }
        }
        components.startSync()
        notePhase("SYNC_START_REQUEST")

        backoffMs = 5_000L
        lastError = null
        prefs.isEnabled = true // runtime state — now DERIVED by the supervisor, never clobbered elsewhere
        _stateFlow.value = State.CONNECTED
        notePhase("CONNECTED")
        // Publish the INTENT and the TARGET, never a verdict. `State.CONNECTED` above says
        // the components were started; the health card's answer comes from what those
        // components go on to observe (a successful poll, a rejected key, a TLS failure).
        // Deliberately no "healthy" write here — that is exactly the lie this replaces.
        publishHealthContext()
    }

    /**
     * Records what the supervisor legitimately knows: whether the gateway is wanted, which
     * endpoint is configured, and whether the OS reports a validated route.
     *
     * It does NOT record success. A component that has not run yet has not proven anything,
     * and the derived overall state stays STARTING until one of them does.
     */
    private fun publishHealthContext() {
        GatewayHealthRecorder.setDesired(desiredEnabled && prefs.hasGatewayConsent)
        GatewayHealthRecorder.setEndpointUrl(prefs.gmwebServerOrigin)
        GatewayHealthRecorder.onNetwork(
            validated = networkMonitor.isOnline(),
            transport = networkMonitor.transportLabel()
        )
    }
}
