package com.autonomousone.messages.gateway

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.autonomousone.messages.eve.EveSmsQueue
import com.autonomousone.messages.gateway.health.GatewayFailureKind
import com.autonomousone.messages.gateway.health.GatewayHealthRecorder
import com.autonomousone.messages.gateway.health.GatewayHealthText
import com.autonomousone.messages.gateway.health.GatewayLog
import com.autonomousone.messages.gateway.health.GatewayPullFailure
import com.autonomousone.messages.utils.DiagnosticLog
import com.autonomousone.messages.utils.PhoneToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pull-based bridge to a GMweb-API server (github.com/aibedini/GMweb-API).
 *
 * The phone dials OUT to the server over HTTPS — no tunnel, no inbound port,
 * no static IP. The server hands over one queued send per request; the SMS is
 * delivered through the existing local [EveSmsQueue] (priority, persistence,
 * native SIM send) and the outcome is acked so the server's ledger updates.
 *
 *   GET  {gmwebUrl}/gateway/pull?waitMs=…  → {"task":{requestId,to,text,meta}} | {"task":null}
 *   POST {gmwebUrl}/gateway/validate       → {"valid":true|false,"status":…,"reason":…}
 *   POST {gmwebUrl}/gateway/ack            → {"ok":true}
 *
 * Auth uses the phone's own gateway API key as X-API-Key; the GMweb side is
 * configured with the same key (GMWEB_ANDROID_DEVICE_KEY).
 *
 * ── Metadata-aware tasks and the two-phase validation ──
 * A task whose meta has requiresValidation=true is validated TWICE:
 *
 *  1. here, right after the pull — an OPTIMISATION that avoids enqueueing work
 *     that GMweb already knows is stale;
 *  2. inside [EveSmsQueue.drainOne], immediately before the native SmsManager
 *     funnel — the CORRECTNESS BARRIER, because a task can go stale while it
 *     waits in the local queue.
 *
 * Only (2) can prevent a physical send.
 *
 * ── ACKs are driven by LOCAL terminal state ──
 * A task whose validation was unavailable is parked DEFERRED and retried by the
 * queue's own backoff/sweep. Its ACK therefore must not wait for GMweb to
 * redeliver it: every pulled task is registered in [GatewayAckTracker] and its
 * ACK is emitted the moment the local record reaches a terminal outcome, even
 * across process death and reboot. Server redelivery of the same
 * gatewayRequestId is treated as a duplicate and only ever refreshes the ledger.
 */
class OutboxPoller(
    private val context: Context,
    private val prefs: GatewayPreferences,
    private val scope: CoroutineScope,
    private val onLog: (String) -> Unit = {},
    private val networkMonitor: NetworkMonitor = NetworkMonitor.get(context),
    private val validator: GmwebTaskValidator = GmwebTaskValidator.from(prefs, networkMonitor)
) {
    companion object {
        private const val TAG = "OUTBOX_POLLER"
        private const val LONG_POLL_MS = 25_000L     // server hold when queue empty
        private const val ERROR_RETRY_MS = 5_000L    // backoff after a failed cycle
        private const val ACK_TIMEOUT_MS = 15_000L
        private const val PULL_TIMEOUT_MS = 40_000L   // long-poll + margin
        private const val DRAIN_TIMEOUT_MS = 120_000L
        /**
         * How long one pull cycle will sit on a record that is fail-closed
         * DEFERRED. Once it elapses the cycle ends WITHOUT an ack and the task
         * stays registered in [GatewayAckTracker]; the local worker keeps
         * retrying it under its own backoff and the ACK follows from that local
         * terminal state.
         */
        private const val DEFERRED_WAIT_MS = 30_000L

        /**
         * How long the network gate waits before re-checking.
         *
         * Bounded deliberately. The gate used to suspend on `onlineFlow().first { it }` with no
         * timeout, which is a LIVE job that issues no requests — the same silent bridge as the zombie
         * bug, reached by a different route, and invisible to `isActive`. Re-checking keeps the loop
         * observably alive and lets the freshness watchdog tell "waiting for network" from "wedged".
         */
        private const val NETWORK_WAIT_RECHECK_MS = 60_000L

        /** Terminal outcome of one delivery attempt, as seen locally. */
        internal enum class Drain { SENT, SUPERSEDED, FAILED, CANCELLED, DEFERRED, TIMEOUT }

        /**
         * Parses the GMweb pull payload. Throws when a task is present but
         * malformed, so the cycle backs off instead of silently dropping it
         * (a dropped task would leave the server's ledger hanging).
         */
        internal fun parseTask(json: JSONObject): Task? {
            val t = json.optJSONObject("task") ?: return null
            val requestId = t.getString("requestId").trim()
            require(requestId.isNotEmpty()) { "task.requestId is blank" }
            val to = t.getString("to").trim()
            require(to.isNotEmpty()) { "task.to is blank" }
            val text = t.getString("text")
            return Task(
                requestId = requestId,
                to = to,
                text = text,
                priority = t.optString("priority", "announcement"),
                meta = parseMeta(t.optJSONObject("meta"))
            )
        }

        /**
         * Parses task.meta. Returns null for older GMweb instances that send no
         * meta at all — those tasks keep the pre-existing behaviour exactly.
         */
        internal fun parseMeta(meta: JSONObject?): TaskMeta? {
            if (meta == null) return null
            return TaskMeta(
                source = meta.optString("source", "").trim().ifBlank { null },
                serviceKey = meta.optString("serviceKey", "").trim().ifBlank { null },
                notificationKind = meta.optString("notificationKind", "").trim().ifBlank { null },
                generation = meta.optInt("generation", 0),
                correlationId = meta.optString("correlationId", "").trim().ifBlank { null },
                requiresValidation = meta.optBoolean("requiresValidation", false)
            )
        }

        /**
         * The /gateway/ack body.
         *
         * Outcomes are the canonical set {sent, failed, superseded}; the
         * detailed transport/provider cause rides in "reason" (e.g.
         * "provider_error", "device_send_failed", "cancelled_locally", or the
         * business reason "renewed").
         *
         * "sentAt" is populated ONLY when a physical/native submission actually
         * produced a sent outcome. Every outcome carries "ackAt" as the generic
         * terminal timestamp instead.
         */
        internal fun ackPayload(
            requestId: String,
            outcome: String,
            reason: String?,
            nowMs: Long
        ): JSONObject {
            val ok = outcome == EveSmsQueue.OUTCOME_SENT
            val payload = JSONObject()
                .put("requestId", requestId)
                .put("ok", ok)
                .put("outcome", outcome)
                .put("ackAt", nowMs)
            if (ok) payload.put("sentAt", nowMs)
            if (!ok && !reason.isNullOrBlank()) payload.put("reason", reason)
            return payload
        }
    }

    enum class State { IDLE, POLLING, DELIVERING, ERROR }

    private val _stateFlow = MutableStateFlow(State.IDLE)
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    /**
     * The single ACK path. Terminal outcomes produced by the local queue —
     * including a DEFERRED task that resolves on its own backoff — are acked
     * here, exactly once, with no dependency on server redelivery.
     */
    private val ackTracker = GatewayAckTracker(
        statusOf = { localRequestId -> EveSmsQueue.status(localRequestId) },
        sendAck = { rec, outcome, reason -> ackRecord(rec, outcome, reason) }
    )
    /**
     * v2.6.11 Doze resilience: a partial wake lock held ONLY while a pull
     * cycle is actually in flight (the 25s long-poll + delivery + ack). In
     * Doze the CPU sleeps between maintenance windows and an in-flight
     * long-poll socket dies silently — the server then reports
     * "no device polling" and every Eve send 503s. Holding the lock through
     * the active part of each cycle keeps the radio+CPU alive exactly when
     * we promised the server we are reachable; the device sleeps normally
     * in the idle gap between cycles (lock released, ~5s), so battery cost
     * stays proportional to actual bridge duty, not to wall-clock time.
     */
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Messages:OutboxPoller")

    /**
     * Owns the loop's lifetime, so "is it running?" is answered by a live job rather than by a
     * remembered flag. See [PollLoop] for the production defect this replaced: the loop could exit
     * (policy `break`, an escaped exception) leaving `running = true`, after which `start()` returned
     * early for ever and the supervisor's self-healing call did nothing — a zombie bridge that
     * reported `Running: yes · State: POLLING` with its last poll nine hours old.
     */
    private val loop = PollLoop(scope)

    /** Interrupts the failure backoff so a manual reconnect polls NOW instead of waiting out
     * the 5-second ladder. Conflated: ten impatient taps are one wake-up. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /**
     * True while the poll loop is alive.
     *
     * Delegates to the loop's own job. A loop that ended — for any reason — reports false at once,
     * which is what makes `ConnectionSupervisor.retryNow()` able to repair it.
     */
    val isRunning: Boolean get() = loop.isActive

    /**
     * Lifecycle telemetry for the diagnostic (requirement 12). Timings and reasons only.
     */
    val pollLoopGeneration: Long get() = loop.currentGeneration
    val pollLoopStartedAt: Long get() = loop.startedAt
    val pollLoopCompletedAt: Long get() = loop.finishedAt
    val pollLoopEndReason: String get() = loop.end.name
    val lastCycleStartedAt: Long get() = loop.lastCycleStartedAt
    val lastCycleCompletedAt: Long get() = loop.lastCycleFinishedAt

    /**
     * True when the loop is active but has not polled for longer than a healthy cycle can take.
     *
     * An active job is not proof of liveness: the network-wait branch and a hung socket both leave a
     * live job that issues no requests. See [PollFreshness] for the derived window.
     */
    fun isStalled(now: Long = System.currentTimeMillis()): Boolean =
        PollFreshness.isStalled(
            now = now,
            isActive = loop.isActive,
            lastActivityAt = loop.lastActivityAt(),
            online = networkMonitor.isOnline()
        )

    // ── The facts the poll state is DERIVED from (requirement 13) ────────────
    // Each is a plain observation, set only where it is true. The label is computed from them, so
    // "POLLING" can no longer stand in for six different situations.
    @Volatile private var requestInFlight = false
    @Volatile private var backingOff = false
    @Volatile private var waitingForNetwork = false

    /** What the bridge is actually doing right now. See [PollStatePolicy] for the precedence. */
    fun pollState(now: Long = System.currentTimeMillis()): PollState =
        PollStatePolicy.derive(
            isActive = loop.isActive,
            stalled = isStalled(now),
            requestInFlight = requestInFlight,
            backingOff = backingOff,
            waitingForNetwork = waitingForNetwork
        )

    /** Publishes the derived state so the diagnostic reports a fact rather than a guess. */
    private fun publishPollState() {
        val state = pollState()
        // One call, because `running` and `state` are two views of the same fact: the nine-hour lie
        // was a `running = true` left behind by a loop that had ended, and two separate setters are
        // how that happens.
        GatewayHealthRecorder.setPollerLifecycle(
            running = loop.isActive,
            state = state.name,
            generation = loop.currentGeneration,
            startedAt = loop.startedAt,
            completedAt = loop.finishedAt,
            completionReason = if (loop.isActive) null else loop.end.name,
            lastCycleStartedAt = loop.lastCycleStartedAt,
            lastCycleCompletedAt = loop.lastCycleFinishedAt
        )
    }

    /**
     * Try again immediately: cancel any pending backoff and poll now.
     *
     * Does NOT start a second poller — [start] is idempotent and this only nudges the
     * existing loop.
     */
    fun retryNow() {
        wake.trySend(Unit)
        GatewayHealthRecorder.setPollerState(_stateFlow.value.name)
    }

    private fun acquireCycleWakeLock() {
        try {
            // 90s > longest legal cycle (40s pull timeout + 120s drain is the
            // extreme; re-acquired per cycle anyway). Timeout is the safety
            // net against a leaked lock; normal release happens in finally.
            wakeLock?.acquire(90 * 1000L)
        } catch (_: Exception) { /* never block the bridge on a lock failure */ }
    }

    private fun releaseCycleWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock.release()
        } catch (_: Exception) { }
    }

    fun start() {
        // Idempotent against an ACTIVE loop, not against "start was once called". A dead loop is
        // always replaceable — that is the whole fix — and a live one is never duplicated.
        val started = loop.start(onFinished = { end -> onLoopFinished(end) }) { generation ->
            runLoop(generation)
        }
        if (!started) return
        GatewayHealthRecorder.setPollerRunning(true)
        // Re-seed the local ACK ledger from the durable queue so a task parked
        // DEFERRED before a reboot is still retried and acked by local backoff.
        seedAckLedger()
    }

    /**
     * The loop's own body: everything that used to be `scope.launch { … }` directly.
     *
     * It runs inside [PollLoop]'s try/finally, so every way out of here — the policy `break`, a throw
     * from the network gate, cancellation — reaches [onLoopFinished]. There is deliberately no
     * cleanup in this function: one place owns it.
     */
    private suspend fun runLoop(generation: Long) {
        _stateFlow.value = State.POLLING
        publishPollState()
        Log.i(TAG, "Outbox poller started (generation=$generation)")
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            // One pure decision per iteration, so the precedence between intent and the radio is
            // asserted in a test rather than read out of two branches here.
            when (
                PollStatePolicy.nextIteration(
                    canTransmit = GatewayAccessPolicy.canTransmit(
                        prefs.hasGatewayConsent,
                        prefs.isEnabled
                    ),
                    online = networkMonitor.isOnline()
                )
            ) {
                PollStatePolicy.Iteration.EXIT -> {
                    // The user's intent outranks the radio: exit, and let the supervisor start a
                    // fresh loop if eligibility ever returns.
                    _stateFlow.value = State.IDLE
                    return
                }
                PollStatePolicy.Iteration.WAIT_FOR_NETWORK -> {
                    waitingForNetwork = true
                    _stateFlow.value = State.IDLE
                    publishPollState()
                    onLog("📴 Outbox poller paused: waiting for network")
                    // Bounded on purpose: an unbounded wait here is a live job that issues no
                    // requests, which is exactly the silent bridge the freshness watchdog exists to
                    // catch. Re-checking every few minutes keeps the loop observably alive.
                    val cameBack = withTimeoutOrNull(NETWORK_WAIT_RECHECK_MS) {
                        networkMonitor.onlineFlow().first { online -> online }
                    }
                    if (cameBack == null) continue // still offline: loop again, do not exit
                    waitingForNetwork = false
                    _stateFlow.value = State.POLLING
                    publishPollState()
                    onLog("🌐 Network back — resuming outbox poll immediately")
                }
                PollStatePolicy.Iteration.TRANSMIT -> Unit
            }
            try {
                acquireCycleWakeLock()
                try {
                    loop.onCycleStarted()
                    // Local terminal outcomes are acked BEFORE dialling
                    // again: a deferred task never waits for a redelivery.
                    ackPending()
                    requestInFlight = true
                    publishPollState()
                    cycle()
                    _stateFlow.value = State.POLLING
                    publishPollState()
                } finally {
                    requestInFlight = false
                    loop.onCycleFinished()
                    releaseCycleWakeLock()
                }
            } catch (e: Exception) {
                _stateFlow.value = State.ERROR
                // The backoff below is a legitimate silence, and it gets its OWN label so a reader
                // does not have to read the last error to know why nothing is being requested.
                backingOff = true
                publishPollState()
                onLog("⚠️ Pull failed: " + (e.message ?: "network error") + " — retry in " + (ERROR_RETRY_MS / 1000) + "s")
                // Interruptible: a manual reconnect must not wait out the backoff.
                withTimeoutOrNull(ERROR_RETRY_MS) { wake.receive() }
                backingOff = false
                publishPollState()
            }
        }
    }

    /**
     * Clears everything that says "the bridge is running" when a loop ends for any reason.
     *
     * The health flag is the part the old code never cleared: `setPollerRunning(true)` was written on
     * start and only ever undone by `stop()`, so a loop that ended on its own left the diagnostic
     * claiming `Running: yes` while nothing polled.
     */
    private fun onLoopFinished(end: PollLoop.End) {
        if (end == PollLoop.End.NOT_STARTED) return
        requestInFlight = false
        backingOff = false
        waitingForNetwork = false
        _stateFlow.value = State.IDLE
        // Sets pollerRunning=false and the state to POLL_LOOP_DEAD, which is what the report must say
        // when nothing is polling — the nine-hour lie was a stale `true` here.
        publishPollState()
        Log.i(TAG, "Outbox poller ended: $end")
        onLog("⏹ Outbox poller stopped ($end) — a reconcile will restart it")
    }

    fun stop() {
        requestInFlight = false
        backingOff = false
        waitingForNetwork = false
        loop.stop()
        _stateFlow.value = State.IDLE
        publishPollState()
    }

    /** One pull → deliver → ack round trip. Throws only on transport errors. */
    private suspend fun cycle() {
        val base = prefs.gmwebUrl.trimEnd('/')
        if (base.isBlank()) {
            // No GMweb configured — idle quietly; the LAN/cloud features still work.
            delay(30_000)
            return
        }
        _stateFlow.value = State.POLLING
        val task = pull(base) ?: return // long-poll returned empty

        _stateFlow.value = State.DELIVERING
        val requiresValidation = task.meta?.requiresValidation == true
        trace("PULL_RECEIVED", task, mapOf("pullAt" to System.currentTimeMillis()))
        // The recipient is reported as a TOKEN, never the dialable number (mission §43): an in-app
        // log line is a durable copy, and this one is captured for every pulled task.
        onLog("📨 Pulled " + task.requestId + " → " + PhoneToken.of(task.to))

        // ── Phase 1: pull-time validation (optimisation only) ────────────────
        // Cheap rejection of a task GMweb already knows is stale. It does NOT
        // replace the final gate: the customer can renew during the wait.
        var startDeferred = false
        if (requiresValidation) {
            when (val early = validator.validateRequestId(task.requestId)) {
                is EveSmsQueue.ValidationDecision.Superseded -> {
                    trace(
                        "VALIDATION_SUPERSEDED", task,
                        mapOf("phase" to "pull", "reason" to early.reason)
                    )
                    ackForTask(task, EveSmsQueue.OUTCOME_SUPERSEDED, early.reason)
                    onLog("🚫 Superseded at pull time: " + task.requestId)
                    return
                }
                is EveSmsQueue.ValidationDecision.Unavailable -> {
                    trace(
                        "VALIDATION_UNAVAILABLE", task,
                        mapOf("phase" to "pull", "reason" to early.reason)
                    )
                    // Fail closed, but still park the task locally so the queue
                    // worker keeps retrying under its own backoff.
                    startDeferred = true
                }
                is EveSmsQueue.ValidationDecision.Valid -> {
                    trace("VALIDATION_VALID", task, mapOf("phase" to "pull"))
                }
            }
        }

        // Deliver through the SAME priority queue the /send endpoint uses:
        // persistence across reboot, highest-first ordering, radio-level result.
        val result = EveSmsQueue.enqueue(
            to = task.to,
            text = task.text,
            priority = task.priority.ifBlank { "announcement" },
            idempotencyKey = null,
            meta = task.meta?.let { m ->
                EveSmsQueue.GatewayMeta(
                    gatewayRequestId = task.requestId,
                    source = m.source,
                    serviceKey = m.serviceKey,
                    notificationKind = m.notificationKind,
                    generation = m.generation,
                    correlationId = m.correlationId,
                    requiresValidation = m.requiresValidation,
                    pulledAt = System.currentTimeMillis()
                )
            },
            startDeferred = startDeferred
        )
        if (!result.created) {
            // Same gateway requestId pulled twice — reuse the existing record so
            // the task can never produce a second physical SMS.
            onLog("↺ Duplicate gateway task " + task.requestId + " → reusing " + result.record.requestId)
        }
        // Local ledger: the ACK for this task is emitted from local terminal
        // state, whether that arrives inside this cycle or much later.
        ackTracker.track(task.requestId, result.record.requestId)

        val outcome = drainUntilTerminal(result.record.requestId)

        // The queue moved (a native submit happened, or the record became terminal), so the
        // queue dimension is refreshed before the ACK — the card must not show "queued: 1"
        // for a task that has already reached the radio.
        GatewayHealthRecorder.setEveQueue(EveSmsQueue.healthSnapshot())
        EveSmsQueue.status(result.record.requestId)?.let { rec ->
            if (rec.nativeSubmitStartedAt > 0L) {
                GatewayHealthRecorder.onEveNativeSubmit(rec.nativeSubmitStartedAt)
            }
        }

        if (outcome == Drain.TIMEOUT) {
            // The record never reached a terminal state inside the window.
            // Report the transport-level failure once and stop tracking it.
            ackForTask(task, EveSmsQueue.OUTCOME_FAILED, EveSmsQueue.REASON_DEVICE_SEND_FAILED)
            ackTracker.forget(task.requestId)
            onLog("⌛ Timed out waiting for " + task.requestId)
            return
        }

        // The single ACK path — also covers a record that resolved locally
        // while the cycle was still waiting on it.
        ackPending()

        when (outcome) {
            Drain.SENT -> onLog("✅ Delivered " + task.requestId)
            Drain.SUPERSEDED -> onLog("🚫 Superseded before native send: " + task.requestId)
            Drain.FAILED -> onLog("❌ Failed " + task.requestId)
            Drain.CANCELLED -> onLog("✋ Cancelled locally " + task.requestId)
            Drain.DEFERRED -> onLog("⏳ Validation unavailable — deferred locally, still retrying: " + task.requestId)
            Drain.TIMEOUT -> Unit // handled above
        }
    }

    private suspend fun pull(base: String): Task? {
        // ── v3.4.x P0: health instrumentation ────────────────────────────────
        // The pull is the ONLY path that delivers an EVE request to this phone, and until
        // now its success was invisible: nothing distinguished "never polled" from
        // "polling fine" from "polling and being rejected". Every exit point below records
        // what happened, with the HTTP status kept as a VALUE rather than folded into a
        // message string (which is what made a 401 indistinguishable from a timeout).
        val startedAt = System.currentTimeMillis()
        GatewayHealthRecorder.onPullStart(startedAt)
        GatewayLog.pullStarted(startedAt)
        // The local send queue is part of the delivery chain the user is trying to read:
        // "GMweb queued → Android pulled → validation → local queue → SIM → ACK" stops
        // somewhere, and this is what lets the card say WHERE.
        GatewayHealthRecorder.setEveQueue(EveSmsQueue.healthSnapshot(startedAt))
        var conn: HttpURLConnection? = null
        try {
            conn = open(base + "/gateway/pull?waitMs=" + LONG_POLL_MS, "GET", PULL_TIMEOUT_MS)
            val status = conn.responseCode
            if (status !in 200..299) {
                val kind = GatewayFailureKind.fromHttpStatus(status)
                    ?: GatewayFailureKind.UNKNOWN
                throw GatewayPullFailure(kind, status, "HTTP $status")
            }
            val body = conn.inputStream.use { it.bufferedReader().readText() }
            val task = try {
                parseTask(JSONObject(body))
            } catch (malformed: Exception) {
                // A response we cannot read is an API/protocol problem, not a network one:
                // reporting it as a timeout would send the user to look at their radio.
                throw GatewayPullFailure(
                    GatewayFailureKind.INVALID_RESPONSE,
                    status,
                    GatewayHealthText.safeDetail(malformed.message) ?: "malformed pull response"
                )
            }
            val completedAt = System.currentTimeMillis()
            // HTTP 200 with {"task": null} IS a success: the phone reached GMweb,
            // authenticated, was recognised as a gateway and got a well-formed answer.
            val wasNeverSuccessful =
                GatewayHealthRecorder.rawSnapshot(completedAt).pullBridge.lastSuccessfulPollAt == null
            val wasFailing =
                GatewayHealthRecorder.rawSnapshot(completedAt).pullBridge.lastFailure != null
            if (task == null) {
                GatewayHealthRecorder.onPullEmpty(completedAt, status)
                GatewayLog.pullCompletedEmpty(completedAt - startedAt, completedAt)
            } else {
                GatewayHealthRecorder.onPullTask(completedAt, status)
                GatewayLog.pullReceivedTask(shortToken(task.requestId), completedAt)
            }
            // ── Durable transitions only ─────────────────────────────────────
            // A successful empty long-poll happens roughly twice a minute, so logging
            // every one would bury the diagnostic export in noise. What is worth keeping
            // is the FIRST success of this process and any RECOVERY from a failure —
            // together with the failures themselves, that is a complete story of the
            // bridge without a single redundant line.
            if (wasNeverSuccessful) {
                DiagnosticLog.event(
                    "GATEWAY_PULL",
                    "firstSuccess status=$status task=${task != null} " +
                        "host=${GatewayHealthRecorder.currentEndpoint()?.displayHost ?: "none"}"
                )
            } else if (wasFailing) {
                DiagnosticLog.event("GATEWAY_PULL", "recovered status=$status task=${task != null}")
            }
            return task
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: GatewayPullFailure) {
            GatewayHealthRecorder.onPullFailure(
                kind = failure.kind,
                httpStatus = failure.httpStatus,
                safeDetail = failure.safeDetail,
                retryInMs = ERROR_RETRY_MS
            )
            logPullFailure(failure.kind, failure.httpStatus, failure.safeDetail)
            throw failure
        } catch (error: Throwable) {
            val kind = GatewayFailureKind.classify(
                error = error,
                networkValidated = networkMonitor.isOnline()
            )
            val detail = GatewayHealthText.safeDetail(error.message)
            GatewayHealthRecorder.onPullFailure(
                kind = kind,
                safeDetail = detail,
                retryInMs = ERROR_RETRY_MS
            )
            logPullFailure(kind, null, detail)
            throw error
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * One durable line per pull failure.
     *
     * Failures are rare compared with successful long-polls, so every one is kept: the
     * consecutive-failure count is what separates a transient blip from a broken route,
     * and the classified kind is what tells the user whether to check their key, their URL
     * or their network. The caption is already redacted by [GatewayHealthText].
     */
    private fun logPullFailure(
        kind: GatewayFailureKind,
        httpStatus: Int?,
        safeDetail: String?
    ) {
        val bridge = GatewayHealthRecorder.rawSnapshot().pullBridge
        DiagnosticLog.event(
            "GATEWAY_PULL",
            "failed kind=${kind.name} status=${httpStatus ?: "n/a"} " +
                "consecutive=${bridge.consecutiveFailures} " +
                "detail=${safeDetail ?: "none"}"
        )
        GatewayLog.pullFailed(kind, httpStatus, safeDetail)
    }

    /** A short, non-reversible handle for a request id. Never the id itself. */
    private fun shortToken(value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }

    /**
     * Wait for the local EveSmsQueue record to reach a terminal status.
     * ponytail: bounded polling of an in-memory map — cheap and exact enough;
     * no callback plumbing needed for a single-record wait.
     */
    private suspend fun drainUntilTerminal(localRequestId: String): Drain {
        val deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS
        var deferredSince = 0L
        while (System.currentTimeMillis() < deadline) {
            val rec = EveSmsQueue.status(localRequestId) ?: return Drain.FAILED
            when (rec.status) {
                EveSmsQueue.Status.SENT -> return Drain.SENT
                EveSmsQueue.Status.SUPERSEDED -> return Drain.SUPERSEDED
                EveSmsQueue.Status.FAILED -> return Drain.FAILED
                EveSmsQueue.Status.CANCELLED -> return Drain.CANCELLED
                EveSmsQueue.Status.DEFERRED -> {
                    val nowMs = System.currentTimeMillis()
                    if (deferredSince == 0L) deferredSince = nowMs
                    // Do not hold the pull cycle hostage to a long backoff: the
                    // record stays tracked and is acked when it resolves locally.
                    if (nowMs - deferredSince >= DEFERRED_WAIT_MS) return Drain.DEFERRED
                }
                EveSmsQueue.Status.QUEUED, EveSmsQueue.Status.ACTIVE -> Unit
            }
            delay(500)
        }
        return Drain.TIMEOUT
    }

    /**
     * Re-seeds the ledger from the durable queue and emits the ACK for every
     * tracked task that has reached a terminal outcome.
     *
     * This is the mechanism that makes DEFERRED correct without GMweb
     * redelivery: the retry, the terminal state and the ACK all come from the
     * local queue and this ledger, never from a second /gateway/pull of the
     * same task.
     */
    private fun ackPending(): Int {
        seedAckLedger()
        val emitted = ackTracker.drain()
        if (emitted > 0) onLog("📤 Acked " + emitted + " gateway task(s)")
        return emitted
    }

    private fun seedAckLedger() {
        EveSmsQueue.outstandingGatewayRecords().forEach { rec ->
            rec.gatewayRequestId?.let { ackTracker.track(it, rec.requestId) }
        }
        // And the ones that already FINISHED but whose report GMweb never accepted — a task can send
        // and then lose the connection before telling the server. Without this the report died with the
        // process: the ledger is in memory, and the outstanding list above deliberately excludes
        // terminal records. Re-reporting is idempotent, so this cannot cause a second SMS.
        EveSmsQueue.unreportedGatewayRecords().forEach { rec ->
            rec.gatewayRequestId?.let { ackTracker.track(it, rec.requestId) }
        }
    }

    /**
     * Reports an outcome for a task that has no durable record to fall back on.
     *
     * The return value is deliberately IGNORED here, unlike [ackRecord]. Both callers are covered by
     * something better: a task superseded at pull time was never queued, and a drain timeout leaves a
     * NON-terminal record that the next cycle re-seeds and eventually reports the real outcome for. In
     * neither case is a retry of this particular report the right action.
     */
    private fun ackForTask(task: Task, outcome: String, reason: String?) {
        ackInternal(task.requestId, outcome, reason, traceFields(task))
    }

    /** Reports one record's outcome and says whether GMweb accepted it. */
    private fun ackRecord(rec: EveSmsQueue.Record, outcome: String, reason: String?): Boolean {
        val gatewayRequestId = rec.gatewayRequestId ?: return false
        val accepted = ackInternal(gatewayRequestId, outcome, reason, traceFields(rec))
        if (accepted) {
            // Durable "GMweb has this outcome", so the report survives a restart even though the ACK
            // ledger does not. Written only after a 2xx.
            EveSmsQueue.markGatewayReported(rec.requestId, System.currentTimeMillis())
        }
        return accepted
    }

    private fun ackInternal(
        gatewayRequestId: String,
        outcome: String,
        reason: String?,
        extra: Map<String, Any?>
    ): Boolean {
        val nowMs = System.currentTimeMillis()
        val payload = ackPayload(gatewayRequestId, outcome, reason, nowMs)
        val base = prefs.gmwebUrl.trim().trimEnd('/')
        var accepted = false
        if (base.isBlank()) {
            Log.w(TAG, "ack skipped for " + gatewayRequestId + ": no GMweb URL configured")
        } else {
            var conn: HttpURLConnection? = null
            try {
                conn = open(base + "/gateway/ack", "POST", ACK_TIMEOUT_MS)
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val status = conn.responseCode
                accepted = status in 200..299
                if (accepted) {
                    // The result reached GMweb: the last leg of the delivery chain. This
                    // single call also stamps the queue's gateway-ack time.
                    GatewayHealthRecorder.onAckSuccess(nowMs)
                    GatewayLog.ackSucceeded(nowMs)
                } else {
                    Log.w(TAG, "ack HTTP " + status + " for " + gatewayRequestId)
                    GatewayHealthRecorder.onAckFailure(
                        kind = GatewayFailureKind.fromHttpStatus(status)
                            ?: GatewayFailureKind.UNKNOWN,
                        httpStatus = status,
                        safeDetail = "HTTP $status",
                        at = nowMs
                    )
                    GatewayLog.ackFailed(
                        GatewayFailureKind.fromHttpStatus(status) ?: GatewayFailureKind.UNKNOWN,
                        status,
                        "HTTP $status",
                        nowMs
                    )
                }
            } catch (e: Exception) {
                // A lost ack must NOT re-send locally; the server times the task out.
                Log.w(TAG, "ack failed for " + gatewayRequestId + ": " + e.message)
                val kind = GatewayFailureKind.classify(
                    error = e,
                    networkValidated = networkMonitor.isOnline()
                )
                val detail = GatewayHealthText.safeDetail(e.message)
                GatewayHealthRecorder.onAckFailure(
                    kind = kind,
                    safeDetail = detail,
                    at = nowMs
                )
                GatewayLog.ackFailed(kind, null, detail, nowMs)
            } finally {
                conn?.disconnect()
            }
        }
        val fields = linkedMapOf<String, Any?>()
        fields.putAll(extra)
        fields["outcome"] = outcome
        fields["reason"] = reason
        fields["accepted"] = accepted
        fields["ackAt"] = nowMs
        EveSmsQueue.trace("ACK_SENT", fields)
        // The caller uses this to decide whether the report still needs sending. An unaccepted report
        // stays queued for the next cycle instead of being recorded as delivered.
        return accepted
    }

    /** Structured lifecycle event for a pull-time task (never the message body). */
    private fun trace(event: String, task: Task, extra: Map<String, Any?>) {
        val fields = linkedMapOf<String, Any?>()
        fields.putAll(traceFields(task))
        fields.putAll(extra)
        EveSmsQueue.trace(event, fields)
    }

    private fun traceFields(task: Task): Map<String, Any?> = linkedMapOf(
        "gatewayRequestId" to task.requestId,
        "serviceKey" to task.meta?.serviceKey,
        "notificationKind" to task.meta?.notificationKind,
        "generation" to task.meta?.generation,
        "correlationId" to task.meta?.correlationId,
        "requiresValidation" to (task.meta?.requiresValidation == true)
    )

    private fun traceFields(rec: EveSmsQueue.Record): Map<String, Any?> = linkedMapOf(
        "gatewayRequestId" to rec.gatewayRequestId,
        "localRequestId" to rec.requestId,
        "serviceKey" to rec.serviceKey,
        "notificationKind" to rec.notificationKind,
        "generation" to rec.generation,
        "correlationId" to rec.correlationId,
        "requiresValidation" to rec.requiresValidation,
        "pulledAt" to rec.pulledAt
    )

    private fun open(url: String, method: String, timeoutMs: Long): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = method
            setRequestProperty("X-API-Key", prefs.apiKey)
            setRequestProperty("Accept", "application/json")
            connectTimeout = 15_000
            readTimeout = timeoutMs.toInt()
            if (method == "POST") {
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
        }
        return conn
    }

    data class Task(
        val requestId: String,
        val to: String,
        val text: String,
        val priority: String,
        val meta: TaskMeta? = null
    )

    /** task.meta as returned by GMweb. Absent entirely on older servers. */
    data class TaskMeta(
        val source: String?,
        val serviceKey: String?,
        val notificationKind: String?,
        val generation: Int,
        val correlationId: String?,
        val requiresValidation: Boolean
    )
}
