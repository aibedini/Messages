package com.autonomousone.messages.gateway

import android.content.Context
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
    companion object {
        @Volatile
        private var instance: ConnectionSupervisor? = null

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
        val stopEventUploader: () -> Unit = {}
    )

    enum class State {
        DISABLED,            // user/consent says off
        WAITING_FOR_NETWORK, // desired + consent, but no validated internet
        CONNECTING,          // bringing server/heartbeat/poller up
        CONNECTED,           // everything desired is running
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

    init {
        desiredEnabled = prefs.gatewayDesiredEnabled && prefs.hasGatewayConsent
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /** Begin supervising (idempotent). Called from every ACTION_START entry:
     *  user toggle, boot receiver, START_STICKY revival. */
    fun start() {
        desiredEnabled = true
        prefs.gatewayDesiredEnabled = true
        ensureLoop()
        reconcileNow()
    }

    /** User intent OFF: stop components and stay down until start() again.
     *  Does NOT stop the service — ACTION_STOP's own path does that. */
    fun stop() {
        desiredEnabled = false
        prefs.gatewayDesiredEnabled = false
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
                }
            }
        }
    }

    private fun reconcile() {
        if (!desiredEnabled || !prefs.hasGatewayConsent) {
            if (_stateFlow.value != State.DISABLED) {
                prefs.isEnabled = false // runtime gate off FIRST: components stop transmitting
                components.stopPoller()
                components.stopHeartbeat()
                components.stopEventUploader()
                server?.stop()
                server = null
                boundIp = null
                onLog("🛑 Gateway stopped (disabled)")
            }
            _stateFlow.value = State.DISABLED
            return
        }

        val online = networkMonitor.isOnline()
        if (!online) {
            if (_stateFlow.value != State.WAITING_FOR_NETWORK) {
                prefs.isEnabled = false // gate transmission while offline (poller/heartbeat stop below)
                components.stopPoller()   // gate: ZERO HTTP requests while offline
                components.stopHeartbeat()
                onLog("📴 Gateway waiting for network…")
            }
            _stateFlow.value = State.WAITING_FOR_NETWORK
            return
        }

        val wasDegraded = _stateFlow.value in setOf(State.RECONNECTING, State.ERROR, State.WAITING_FOR_NETWORK)
        if (wasDegraded) _stateFlow.value = State.RECONNECTING

        // ── LAN server: (re)bind when down or stale ────────────────────────
        val currentIp = if (prefs.bindAllInterfaces) "0.0.0.0" else GatewayServer.getLocalIpAddress()
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

        // ── Cloud + GMweb + shadow sync (idempotent starts) ────────────────
        components.startHeartbeat()
        components.startEventUploader() // PR-02: durable outbox → GMweb transmitter
        if (prefs.gmwebUrl.isNotBlank()) components.startPoller()
        components.startSync()

        backoffMs = 5_000L
        lastError = null
        prefs.isEnabled = true // runtime state — now DERIVED by the supervisor, never clobbered elsewhere
        _stateFlow.value = State.CONNECTED
    }
}
