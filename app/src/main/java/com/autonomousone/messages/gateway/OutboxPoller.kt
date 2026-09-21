package com.autonomousone.messages.gateway

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.autonomousone.messages.eve.EveSmsQueue
import com.autonomousone.messages.gateway.health.GatewayFailureKind
import com.autonomousone.messages.gateway.health.GatewayHealthRecorder
import com.autonomousone.messages.gateway.health.GatewayHealthText
import com.autonomousone.messages.gateway.health.GatewayPullFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

    private var pollJob: Job? = null
    @Volatile private var running = false

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
        if (running) return
        running = true
        GatewayHealthRecorder.setPollerRunning(true)
        // Re-seed the local ACK ledger from the durable queue so a task parked
        // DEFERRED before a reboot is still retried and acked by local backoff.
        seedAckLedger()
        pollJob = scope.launch {
            _stateFlow.value = State.POLLING
            GatewayHealthRecorder.setPollerState(State.POLLING.name)
            Log.i(TAG, "Outbox poller started")
            while (isActive) {
                if (!GatewayAccessPolicy.canTransmit(prefs.hasGatewayConsent, prefs.isEnabled)) {
                    _stateFlow.value = State.IDLE
                    GatewayHealthRecorder.setPollerState(State.IDLE.name)
                    break
                }
                // Network gate: while there is no validated route, hang up
                // ZERO HTTP requests (each would burn a 40 s long-poll timeout
                // against a dead radio). Wake the instant the network returns —
                // event-driven via the callback flow, not a poll timer.
                if (!networkMonitor.isOnline()) {
                    _stateFlow.value = State.IDLE
                    GatewayHealthRecorder.setPollerState(State.IDLE.name)
                    onLog("📴 Outbox poller paused: waiting for network")
                    networkMonitor.onlineFlow().first { online -> online }
                    if (!isActive) break
                    _stateFlow.value = State.POLLING
                    GatewayHealthRecorder.setPollerState(State.POLLING.name)
                    onLog("🌐 Network back — resuming outbox poll immediately")
                }
                try {
                    acquireCycleWakeLock()
                    try {
                        // Local terminal outcomes are acked BEFORE dialling
                        // again: a deferred task never waits for a redelivery.
                        ackPending()
                        cycle()
                        _stateFlow.value = State.POLLING
                        GatewayHealthRecorder.setPollerState(State.POLLING.name)
                    } finally {
                        releaseCycleWakeLock()
                    }
                } catch (e: Exception) {
                    _stateFlow.value = State.ERROR
                    GatewayHealthRecorder.setPollerState(State.ERROR.name)
                    onLog("⚠️ Pull failed: " + (e.message ?: "network error") + " — retry in " + (ERROR_RETRY_MS / 1000) + "s")
                    delay(ERROR_RETRY_MS)
                }
            }
        }
    }

    fun stop() {
        running = false
        pollJob?.cancel()
        pollJob = null
        _stateFlow.value = State.IDLE
        GatewayHealthRecorder.setPollerRunning(false)
        GatewayHealthRecorder.setPollerState(State.IDLE.name)
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
        onLog("📨 Pulled " + task.requestId + " → " + task.to)

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
            if (task == null) {
                GatewayHealthRecorder.onPullEmpty(completedAt, status)
            } else {
                GatewayHealthRecorder.onPullTask(completedAt, status)
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
            throw failure
        } catch (error: Throwable) {
            GatewayHealthRecorder.onPullFailure(
                kind = GatewayFailureKind.classify(
                    error = error,
                    networkValidated = networkMonitor.isOnline()
                ),
                safeDetail = GatewayHealthText.safeDetail(error.message),
                retryInMs = ERROR_RETRY_MS
            )
            throw error
        } finally {
            conn?.disconnect()
        }
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
    }

    private fun ackForTask(task: Task, outcome: String, reason: String?) {
        ackInternal(task.requestId, outcome, reason, traceFields(task))
    }

    private fun ackRecord(rec: EveSmsQueue.Record, outcome: String, reason: String?) {
        val gatewayRequestId = rec.gatewayRequestId ?: return
        ackInternal(gatewayRequestId, outcome, reason, traceFields(rec))
    }

    private fun ackInternal(
        gatewayRequestId: String,
        outcome: String,
        reason: String?,
        extra: Map<String, Any?>
    ) {
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
                } else {
                    Log.w(TAG, "ack HTTP " + status + " for " + gatewayRequestId)
                    GatewayHealthRecorder.onAckFailure(
                        kind = GatewayFailureKind.fromHttpStatus(status)
                            ?: GatewayFailureKind.UNKNOWN,
                        httpStatus = status,
                        safeDetail = "HTTP $status",
                        at = nowMs
                    )
                }
            } catch (e: Exception) {
                // A lost ack must NOT re-send locally; the server times the task out.
                Log.w(TAG, "ack failed for " + gatewayRequestId + ": " + e.message)
                GatewayHealthRecorder.onAckFailure(
                    kind = GatewayFailureKind.classify(
                        error = e,
                        networkValidated = networkMonitor.isOnline()
                    ),
                    safeDetail = GatewayHealthText.safeDetail(e.message),
                    at = nowMs
                )
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
