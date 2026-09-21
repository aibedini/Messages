package com.autonomousone.messages.eve

import android.content.Context
import android.util.Log
import com.autonomousone.messages.gateway.health.EveQueueHealth
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.PriorityQueue
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Priority send-queue implementing the EVE "Custom HTTP" provider contract.
 *
 * EVE POSTs /send with an Idempotency-Key; this queue persists a stable
 * requestId per key, processes messages highest-priority-first through a
 * single worker thread, and exposes per-request status/cancel plus queue
 * capacity — exactly what the EVE panel polls.
 *
 * Status flow: QUEUED -> ACTIVE -> SENT | FAILED (QUEUED -> CANCELLED).
 *
 * GMweb metadata-aware tasks (pulled by
 * [com.autonomousone.messages.gateway.OutboxPoller]) extend that flow with a
 * **mandatory final pre-send validation gate**:
 *
 *   QUEUED -> [FINAL /gateway/validate] -- valid       -> ACTIVE -> SENT
 *                                       |- superseded  -> SUPERSEDED (sender never called)
 *                                       '- unavailable -> DEFERRED   (backoff, retried)
 *
 * The gate lives in [drainOne] — the last code that runs before [senderFn]
 * (the native SmsManager funnel) is invoked. It is deliberately NOT applied
 * only at pull time: a message can go stale while it waits in this queue.
 */
object EveSmsQueue {

    private const val TAG = "EVE_QUEUE"
    const val ANNOUNCEMENT_LIMIT = 500
    const val RECOMMENDED_BATCH_SIZE = 50
    const val RETRY_AFTER_SECONDS = 30
    private const val MAX_IDEMPOTENCY_KEYS = 500
    private const val MAX_PERSISTED_RECORDS = 300

    /**
     * Fail-closed backoff ladder for the final validation gate. Bounded on
     * purpose: an unbounded fast retry would hammer GMweb during an outage,
     * and a deferred record must never silently become SENT.
     */
    const val VALIDATION_DEFER_BASE_MS = 15_000L
    const val VALIDATION_DEFER_MAX_MS = 300_000L

    /**
     * Canonical ACK outcomes reported to GMweb through POST /gateway/ack.
     * GMweb distinguishes exactly these three; the detailed transport/provider
     * cause always rides in the ACK "reason" field instead of inventing a
     * fourth outcome value.
     */
    const val OUTCOME_SENT = "sent"
    const val OUTCOME_FAILED = "failed"
    const val OUTCOME_SUPERSEDED = "superseded"

    /** Local status labels only — never emitted as an ACK outcome. */
    const val OUTCOME_DEFERRED = "deferred"
    const val OUTCOME_PENDING = "pending"

    /** Detailed ACK reason values (the pre-canonical alias lives here now). */
    const val REASON_DEVICE_SEND_FAILED = "device_send_failed"
    const val REASON_CANCELLED_LOCALLY = "cancelled_locally"
    const val REASON_VALIDATION_UNAVAILABLE = "validation_unavailable"

    val PRIORITY_LEVELS: Map<String, Int> = mapOf(
        "critical" to 1,
        "expired" to 3,
        "expiring" to 6,
        "announcement" to 10
    )

    enum class Status { QUEUED, ACTIVE, SENT, FAILED, CANCELLED, SUPERSEDED, DEFERRED }

    /**
     * Result of the mandatory final validation performed immediately before
     * the native SMS submission.
     */
    sealed interface ValidationDecision {
        /** GMweb confirmed the task is still current -> physical send may proceed. */
        object Valid : ValidationDecision

        /** GMweb says the task is no longer current -> terminal, never send. */
        data class Superseded(val reason: String?) : ValidationDecision

        /** Transport/behavioural failure -> fail closed, retry later with backoff. */
        data class Unavailable(val reason: String) : ValidationDecision
    }

    /**
     * The last safety gate before the radio. Implementations are blocking and
     * are invoked on the queue worker thread (never the main thread).
     */
    fun interface FinalValidator {
        fun validate(record: Record): ValidationDecision
    }

    /**
     * Structured observability sink. Field maps never contain message bodies,
     * API keys or other credentials.
     */
    fun interface Observer {
        fun onEvent(event: String, fields: Map<String, Any?>)
    }

    data class Record(
        val requestId: String,
        val jobId: String,
        val to: String,
        val text: String,
        val priority: String,
        val priorityLevel: Int,
        val status: Status,
        val createdAt: Long,
        val sentAt: Long = 0L,
        val failedReason: String? = null,
        val submittedOnce: Boolean = false,
        // EVE/GMweb-compatible verification fields (panel/jobs/messaging.py parses them).
        // submitted -> confirmed on success; manual_review_required when sending failed.
        val verificationStatus: String? = null,
        val verificationAttempts: Int = 0,
        // ── GMweb metadata-aware task identity (absent on legacy/local sends) ──
        /** GMweb gateway requestId — the task's server-side identity. */
        val gatewayRequestId: String? = null,
        val source: String? = null,
        /** "eve:<serverId>:<clientUuid>" — scopes supersession to ONE account. */
        val serviceKey: String? = null,
        /** near_expiry | low_volume | expired | volume_ended | renew | created */
        val notificationKind: String? = null,
        val generation: Int = 0,
        val correlationId: String? = null,
        /** When true the record MUST pass the final pre-send validation gate. */
        val requiresValidation: Boolean = false,
        // ── Observability / gate bookkeeping ──
        val pulledAt: Long = 0L,
        val validatedAt: Long = 0L,
        val validationResult: String? = null,
        val validationAttempts: Int = 0,
        val deferredUntil: Long = 0L,
        val supersededReason: String? = null,
        /** Set immediately before the native sender is invoked (race diagnostics). */
        val nativeSubmitStartedAt: Long = 0L
    ) {
        val terminal: Boolean
            get() = status == Status.SENT || status == Status.FAILED ||
                status == Status.CANCELLED || status == Status.SUPERSEDED

        val successful: Boolean get() = status == Status.SENT

        /** Terminal, never sent, and deliberately not retryable. */
        val superseded: Boolean get() = status == Status.SUPERSEDED

        /** Non-terminal fail-closed holding state: validation could not be obtained. */
        val deferred: Boolean get() = status == Status.DEFERRED

        /**
         * GMweb-facing outcome for POST /gateway/ack, restricted to the
         * canonical set {sent, failed, superseded}. A locally cancelled task is
         * a failure whose reason is [REASON_CANCELLED_LOCALLY]; its local
         * [status] still says CANCELLED for the device-side status API.
         */
        val outcome: String
            get() = when (status) {
                Status.SENT -> OUTCOME_SENT
                Status.SUPERSEDED -> OUTCOME_SUPERSEDED
                Status.FAILED, Status.CANCELLED -> OUTCOME_FAILED
                Status.DEFERRED -> OUTCOME_DEFERRED
                Status.QUEUED, Status.ACTIVE -> OUTCOME_PENDING
            }
    }

    /** GMweb task metadata that makes a record "metadata-aware". */
    data class GatewayMeta(
        val gatewayRequestId: String,
        val source: String? = null,
        val serviceKey: String? = null,
        val notificationKind: String? = null,
        val generation: Int = 0,
        val correlationId: String? = null,
        val requiresValidation: Boolean = false,
        val pulledAt: Long = 0L
    )

    /** Pluggable persistence so the queue core stays JVM-unit-testable. */
    interface Store {
        fun load(): Pair<List<Record>, Map<String, String>>
        fun save(records: List<Record>, idempotency: Map<String, String>)
    }

    class MemoryStore : Store {
        var lastRecords: List<Record> = emptyList()
            private set
        var lastIdem: Map<String, String> = emptyMap()
            private set
        override fun load(): Pair<List<Record>, Map<String, String>> = emptyList<Record>() to emptyMap()
        override fun save(records: List<Record>, idempotency: Map<String, String>) {
            lastRecords = records.toList()
            lastIdem = idempotency.toMap()
        }
    }

    /** SharedPreferences-backed store used at runtime on the device. */
    class SharedPrefsStore(context: Context) : Store {
        private val prefs = context.getSharedPreferences("eve_queue_prefs", Context.MODE_PRIVATE)

        override fun load(): Pair<List<Record>, Map<String, String>> = try {
            val list = mutableListOf<Record>()
            val arr = JSONArray(prefs.getString(KEY_RECORDS, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                // Per-record isolation: one unreadable record (e.g. written by a
                // different app build) must never discard the whole queue.
                try {
                    list.add(EveQueueCodec.decode(arr.getJSONObject(i)))
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping unreadable EVE queue record index " + i, e)
                }
            }
            val idemObj = JSONObject(prefs.getString(KEY_IDEM, "{}") ?: "{}")
            val idem = mutableMapOf<String, String>()
            idemObj.keys().forEach { idem[it] = idemObj.getString(it) }
            list to idem
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load EVE queue state", e)
            emptyList<Record>() to emptyMap()
        }

        override fun save(records: List<Record>, idempotency: Map<String, String>) {
            try {
                val arr = JSONArray()
                records.takeLast(MAX_PERSISTED_RECORDS).forEach { r ->
                    arr.put(EveQueueCodec.encode(r))
                }
                val idemObj = JSONObject()
                idempotency.forEach { (k, v) -> idemObj.put(k, v) }
                prefs.edit()
                    .putString(KEY_RECORDS, arr.toString())
                    .putString(KEY_IDEM, idemObj.toString())
                    .apply()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist EVE queue state", e)
            }
        }

        companion object {
            private const val KEY_RECORDS = "records_json"
            private const val KEY_IDEM = "idempotency_json"
        }
    }

    private data class Job(val requestId: String, val level: Int, val seq: Long)

    data class EnqueueResult(val record: Record, val created: Boolean)
    data class CancelResult(val ok: Boolean, val reason: String? = null)

    private val records = LinkedHashMap<String, Record>()
    private val idempotency = HashMap<String, String>()
    // Highest priority first; FIFO within the same priority via insertion seq.
    private val queue = PriorityQueue<Job>(11, compareBy({ it.level }, { it.seq }))
    private var seq = 0L

    private lateinit var store: Store
    @Volatile private var senderFn: ((String, String) -> Boolean)? = null
    @Volatile private var validatorFn: FinalValidator? = null
    @Volatile private var running = false
    private var worker: Thread? = null
    private val persistExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "eve-persist").apply { isDaemon = true }
    }

    /** Test seam: injectable clock so backoff is deterministic in unit tests. */
    @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }

    @Volatile var observer: Observer? = null

    val isRunning: Boolean get() = running

    private fun now(): Long = clock()

    /**
     * Starts the queue against device persistence. No-op when already running.
     *
     * [validator] is the mandatory final pre-send gate for metadata-aware
     * (requiresValidation=true) records. When null, such records fail closed.
     */
    @Synchronized
    fun start(
        context: Context,
        sender: (String, String) -> Boolean,
        validator: FinalValidator? = null
    ) {
        if (running) return
        bootstrap(SharedPrefsStore(context.applicationContext), sender, validator)
    }

    /** Shared setup used by both production start and tests. */
    internal fun bootstrap(
        storeImpl: Store,
        sender: (String, String) -> Boolean,
        validator: FinalValidator? = null
    ) {
        store = storeImpl
        senderFn = sender
        validatorFn = validator
        val nowMs = now()
        synchronized(records) {
            records.clear()
            idempotency.clear()
            queue.clear()
            seq = 0
            val (loaded, loadedIdem) = store.load()
            for (r in loaded.sortedBy { it.createdAt }) {
                var rec = r
                if (!rec.terminal && rec.status == Status.ACTIVE) {
                    rec = rec.copy(status = Status.QUEUED) // interrupted mid-send -> requeue
                }
                records[rec.requestId] = rec
                if (!rec.terminal) {
                    // A record still inside its validation backoff window waits
                    // for sweepDeferred(); everything else is offered now.
                    val heldByBackoff = rec.status == Status.DEFERRED && rec.deferredUntil > nowMs
                    if (!heldByBackoff) offer(rec.requestId, rec.priorityLevel)
                }
            }
            idempotency.putAll(loadedIdem)
        }
        running = true
        worker = Thread {
            while (running) {
                try {
                    if (!drainOne()) {
                        // Never busy-loop on deferred records: only re-offer the
                        // ones whose backoff has actually elapsed.
                        if (sweepDeferred() == 0) Thread.sleep(400)
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Worker loop error", e)
                }
            }
        }.apply {
            name = "eve-sender"
            isDaemon = true
            start()
        }
        Log.i(TAG, "EVE queue started")
    }

    @Synchronized
    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }

    /**
     * Accepts a send request. When [idempotencyKey] was seen before, returns
     * the ORIGINAL record with created=false — no duplicate SMS is created.
     *
     * When [meta] carries a GMweb [GatewayMeta.gatewayRequestId] that is
     * already known locally, the existing record is returned instead of a new
     * one: the same gateway task can never produce two physical SMS, including
     * across process death and reboot (the identity lives on the record).
     *
     * [startDeferred] parks a requiresValidation record straight into the
     * fail-closed DEFERRED state (used when the pull-time validation check
     * could not reach GMweb) instead of offering it to the sender.
     */
    fun enqueue(
        to: String,
        text: String,
        priority: String,
        idempotencyKey: String?,
        meta: GatewayMeta? = null,
        startDeferred: Boolean = false
    ): EnqueueResult {
        synchronized(records) {
            if (!idempotencyKey.isNullOrBlank()) {
                idempotency[idempotencyKey]?.let { existingId ->
                    records[existingId]?.let { return EnqueueResult(it, created = false) }
                }
            }
            if (meta != null) {
                records.values.firstOrNull { it.gatewayRequestId == meta.gatewayRequestId }?.let {
                    return EnqueueResult(it, created = false)
                }
            }
            val level = PRIORITY_LEVELS[priority] ?: PRIORITY_LEVELS.getValue("announcement")
            val requestId = newId("sms_", 20)
            val createdAt = now()
            val requiresValidation = meta?.requiresValidation == true
            val deferred = startDeferred && requiresValidation
            val record = Record(
                requestId = requestId,
                jobId = newId("job_", 12),
                to = to,
                text = text,
                priority = PRIORITY_LEVELS.entries.firstOrNull { it.value == level }?.key ?: "announcement",
                priorityLevel = level,
                status = if (deferred) Status.DEFERRED else Status.QUEUED,
                createdAt = createdAt,
                gatewayRequestId = meta?.gatewayRequestId,
                source = meta?.source,
                serviceKey = meta?.serviceKey,
                notificationKind = meta?.notificationKind,
                generation = meta?.generation ?: 0,
                correlationId = meta?.correlationId,
                requiresValidation = requiresValidation,
                pulledAt = meta?.pulledAt ?: 0L,
                deferredUntil = if (deferred) createdAt + VALIDATION_DEFER_BASE_MS else 0L,
                validationResult = if (deferred) "unavailable" else null
            )
            records[requestId] = record
            if (!idempotencyKey.isNullOrBlank()) {
                idempotency[idempotencyKey] = requestId
                trimIdempotency()
            }
            if (!deferred) offer(requestId, level)
            persistAsync()
            return EnqueueResult(record, created = true)
        }
    }

    fun status(requestId: String): Record? = synchronized(records) { records[requestId] }

    /** Look up a record by its GMweb gateway requestId (idempotency probe). */
    fun statusByGatewayRequestId(gatewayRequestId: String): Record? = synchronized(records) {
        records.values.firstOrNull { it.gatewayRequestId == gatewayRequestId }
    }

    /**
     * Tasks pulled from GMweb that have NOT reached a terminal outcome yet.
     *
     * This is the durable source the poller re-seeds its local ACK ledger from:
     * a task parked DEFERRED before a process death or reboot must still be
     * retried — and eventually acknowledged — by this queue's own backoff, with
     * no dependency on the server redelivering it.
     */
    fun outstandingGatewayRecords(): List<Record> = synchronized(records) {
        records.values.filter { it.gatewayRequestId != null && !it.terminal }
    }

    /** Cancels a QUEUED or DEFERRED (never-submitted) message. */
    fun cancel(requestId: String): CancelResult? = synchronized(records) {
        val rec = records[requestId] ?: return null
        when (rec.status) {
            Status.QUEUED, Status.DEFERRED -> {
                records[requestId] = rec.copy(status = Status.CANCELLED, deferredUntil = 0L)
                if (rec.status == Status.QUEUED) queue.removeAll { it.requestId == requestId }
                persistAsync()
                CancelResult(ok = true)
            }
            else -> CancelResult(ok = false, reason = "not_cancellable")
        }
    }

    /** Pending (queued) counts per priority name. */
    fun pendingByPriority(): Map<String, Int> = synchronized(records) {
        val counts = linkedMapOf(
            "critical" to 0, "expired" to 0, "expiring" to 0, "announcement" to 0
        )
        records.values.forEach {
            if (it.status == Status.QUEUED) counts[it.priority] = (counts[it.priority] ?: 0) + 1
        }
        counts
    }

    fun totalPending(): Int = synchronized(records) {
        records.values.count {
            it.status == Status.QUEUED || it.status == Status.ACTIVE || it.status == Status.DEFERRED
        }
    }

    /**
     * The queue as the gateway status card needs it (v3.4.x P0).
     *
     * Counts and timestamps only. Never a body and never a full number, so this is safe to
     * put on screen, in a log line and in an exported diagnostic report. The single request
     * identity that leaves here is a SHORT token of the last pulled gateway request, which is
     * what lets the user line up an EVE send with the chain
     * `GMweb queued → Android pulled → Validation passed → Local queue → SIM submitted → GMweb ACK`.
     *
     * [EveQueueHealth.lastGatewayAckAt] is deliberately NOT set here: the ACK leg is owned by
     * whoever performs it, so the two can never disagree about when it happened.
     */
    fun healthSnapshot(now: Long = now()): EveQueueHealth = synchronized(records) {
        val all = records.values
        var queued = 0
        var active = 0
        var deferred = 0
        var sent = 0
        var failed = 0
        var cancelled = 0
        var lastTransition = 0L
        var lastSubmit = 0L
        var lastPulled: Record? = null
        all.forEach { rec ->
            when (rec.status) {
                Status.QUEUED -> queued++
                Status.ACTIVE -> active++
                Status.DEFERRED -> deferred++
                Status.SENT -> sent++
                Status.FAILED -> failed++
                Status.CANCELLED -> cancelled++
                Status.SUPERSEDED -> cancelled++
            }
            // `sentAt` is the closest thing to a transition stamp the record carries; the
            // creation time covers a row that has not moved yet.
            val transition = maxOf(rec.sentAt, rec.createdAt, rec.pulledAt, rec.validatedAt)
            if (transition > lastTransition) lastTransition = transition
            if (rec.nativeSubmitStartedAt > lastSubmit) lastSubmit = rec.nativeSubmitStartedAt
            if (rec.gatewayRequestId != null &&
                (lastPulled == null || rec.pulledAt >= lastPulled!!.pulledAt)
            ) {
                lastPulled = rec
            }
        }
        EveQueueHealth(
            queued = queued,
            active = active,
            deferred = deferred,
            // "Recent" means "still in the in-memory window": the queue trims terminal
            // records, so these are the outcomes of this session, not a lifetime total.
            sentRecent = sent,
            failedRecent = failed,
            cancelledRecent = cancelled,
            lastPulledRequestToken = lastPulled?.gatewayRequestId?.let { shortToken(it) },
            lastLocalTransitionAt = lastTransition.takeIf { it > 0L },
            lastNativeSubmitAt = lastSubmit.takeIf { it > 0L }
        )
    }

    /** A short, non-reversible handle for a request id. Never the id itself. */
    private fun shortToken(value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }

    /**
     * Takes the next queued job (highest priority first) and sends it.
     * Returns false when nothing was processed. Also driven directly by tests.
     *
     * ── THE FINAL PRE-SEND VALIDATION GATE LIVES HERE ──
     * The gate runs after the job is dequeued and immediately before
     * [senderFn] — the native SmsManager funnel — with no disk, network or UI
     * work in between other than the in-memory ACTIVE flip (persisted
     * asynchronously, off this thread).
     */
    fun drainOne(): Boolean {
        val job = synchronized(records) { queue.poll() } ?: return false
        val current = synchronized(records) { records[job.requestId] } ?: return true
        if (current.status != Status.QUEUED) return true // cancelled/superseded meanwhile

        if (!finalValidationPassed(current)) return true

        val submitAt = now()
        val active = synchronized(records) {
            val base = records[job.requestId] ?: current
            val updated = base.copy(
                status = Status.ACTIVE,
                submittedOnce = true,
                nativeSubmitStartedAt = submitAt
            )
            records[job.requestId] = updated
            persistAsync()
            updated
        }
        emit("NATIVE_SUBMIT_STARTED", active, mapOf("nativeSubmitStartedAt" to submitAt))

        val fn = senderFn
        val ok = try {
            fn?.invoke(active.to, active.text) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Send failed for request " + job.requestId, e)
            false
        }
        val finished = synchronized(records) {
            val base = records[job.requestId] ?: active
            val updated = if (ok) {
                base.copy(
                    status = Status.SENT, sentAt = now(),
                    verificationStatus = "confirmed"
                )
            } else {
                base.copy(
                    status = Status.FAILED, failedReason = "provider_error",
                    verificationStatus = "manual_review_required"
                )
            }
            records[job.requestId] = updated
            persistAsync()
            updated
        }
        if (ok) emit("NATIVE_SEND_CONFIRMED", finished, mapOf("sentAt" to finished.sentAt))
        return true
    }

    /**
     * The mandatory final validation gate.
     *
     * Returns true when the physical send may proceed. A metadata-aware record
     * whose validation is superseded or unobtainable NEVER reaches the native
     * sender.
     */
    private fun finalValidationPassed(rec: Record): Boolean {
        if (!rec.requiresValidation) return true

        val validator = validatorFn
        val decision: ValidationDecision = try {
            validator?.validate(rec) ?: ValidationDecision.Unavailable(REASON_VALIDATION_UNAVAILABLE)
        } catch (e: Exception) {
            Log.w(TAG, "Final validation threw for request " + rec.requestId, e)
            ValidationDecision.Unavailable("validator_exception")
        }
        val at = now()

        return when (decision) {
            is ValidationDecision.Valid -> {
                mutate(rec.requestId) {
                    it.copy(
                        validatedAt = at,
                        validationResult = "valid",
                        validationAttempts = it.validationAttempts + 1,
                        deferredUntil = 0L
                    )
                }
                emit("VALIDATION_VALID", rec, mapOf("phase" to "pre_send", "validationAt" to at))
                true
            }

            is ValidationDecision.Superseded -> {
                val reason = decision.reason?.ifBlank { null } ?: "superseded"
                val updated = mutate(rec.requestId) {
                    it.copy(
                        status = Status.SUPERSEDED,
                        validatedAt = at,
                        validationResult = "superseded",
                        validationAttempts = it.validationAttempts + 1,
                        supersededReason = reason,
                        deferredUntil = 0L,
                        failedReason = null,
                        verificationStatus = null
                    )
                }
                emit(
                    "VALIDATION_SUPERSEDED", updated ?: rec,
                    mapOf("phase" to "pre_send", "validationAt" to at, "reason" to reason)
                )
                false
            }

            is ValidationDecision.Unavailable -> {
                val attempts = rec.validationAttempts + 1
                val backoff = deferralBackoffMs(attempts)
                val until = at + backoff
                val updated = mutate(rec.requestId) {
                    it.copy(
                        status = Status.DEFERRED,
                        validatedAt = at,
                        validationResult = "unavailable",
                        validationAttempts = attempts,
                        deferredUntil = until
                    )
                }
                val target = updated ?: rec
                emit(
                    "VALIDATION_UNAVAILABLE", target,
                    mapOf(
                        "phase" to "pre_send",
                        "reason" to decision.reason,
                        "attempts" to attempts,
                        "deferredUntil" to until
                    )
                )
                emit("LOCAL_DEFERRED", target, mapOf("deferredUntil" to until, "backoffMs" to backoff))
                false
            }
        }
    }

    /** Bounded exponential backoff: 15s, 30s, 60s, ... capped at 5 minutes. */
    internal fun deferralBackoffMs(attempts: Int): Long {
        val shift = (attempts - 1).coerceIn(0, 6)
        return minOf(VALIDATION_DEFER_BASE_MS shl shift, VALIDATION_DEFER_MAX_MS)
    }

    /**
     * Re-offers DEFERRED records whose backoff window has elapsed. Never
     * validates eagerly and never touches records still waiting, so a
     * validation outage cannot turn into a request storm.
     */
    fun sweepDeferred(nowMs: Long = now()): Int {
        val due = synchronized(records) {
            records.values.filter { it.status == Status.DEFERRED && it.deferredUntil <= nowMs }
        }
        if (due.isEmpty()) return 0
        var requeued = 0
        for (rec in due) {
            val moved = synchronized(records) {
                val live = records[rec.requestId] ?: return@synchronized false
                if (live.status != Status.DEFERRED) return@synchronized false
                records[rec.requestId] = live.copy(status = Status.QUEUED)
                persistAsync()
                true
            }
            if (moved) {
                offer(rec.requestId, rec.priorityLevel)
                requeued++
            }
        }
        return requeued
    }

    private fun mutate(requestId: String, transform: (Record) -> Record): Record? =
        synchronized(records) {
            val base = records[requestId] ?: return@synchronized null
            val updated = transform(base)
            records[requestId] = updated
            persistAsync()
            updated
        }

    /** Structured event emission (never includes message bodies or secrets). */
    private fun emit(event: String, rec: Record?, extra: Map<String, Any?>) {
        val fields = linkedMapOf<String, Any?>(
            "localRequestId" to rec?.requestId,
            "gatewayRequestId" to rec?.gatewayRequestId,
            "serviceKey" to rec?.serviceKey,
            "notificationKind" to rec?.notificationKind,
            "generation" to rec?.generation,
            "correlationId" to rec?.correlationId,
            "pulledAt" to rec?.pulledAt
        )
        fields.putAll(extra)
        val sink = observer
        if (sink != null) {
            try {
                sink.onEvent(event, fields)
            } catch (_: Exception) {
                // Observability must never break the send pipeline.
            }
        }
        Log.i(TAG, event + " " + fields.entries.joinToString(" ") { it.key + "=" + it.value })
    }

    /** Emits a structured lifecycle event that has no local record (e.g. PULL_RECEIVED). */
    fun trace(event: String, fields: Map<String, Any?>) = emit(event, null, fields)

    private fun offer(requestId: String, level: Int) {
        seq += 1
        queue.offer(Job(requestId, level, seq))
    }

    private fun trimIdempotency() {
        val iterator = idempotency.entries.iterator()
        while (idempotency.size > MAX_IDEMPOTENCY_KEYS && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }

    private fun persistAsync() {
        // Capture the store alongside the snapshot: an asynchronously queued
        // write must land in the store it was taken from, never in a store that
        // was installed later (queue restart / test rebootstrap).
        val target = store
        val snapshot = synchronized(records) { records.values.toList() to idempotency.toMap() }
        persistExecutor.execute { target.save(snapshot.first, snapshot.second) }
    }

    /**
     * Test hook: blocks until every persist scheduled so far has been written,
     * so "process death -> reload" is deterministic in unit tests.
     */
    internal fun awaitPersistence() {
        val latch = CountDownLatch(1)
        persistExecutor.execute { latch.countDown() }
        latch.await(5, TimeUnit.SECONDS)
    }

    private fun newId(prefix: String, hexLength: Int): String =
        prefix + java.util.UUID.randomUUID().toString().replace("-", "").take(hexLength)

    /** Test hook: clears all state and installs the given store/sender/validator. */
    fun resetForTest(
        storeImpl: Store = MemoryStore(),
        sender: (String, String) -> Boolean = { _, _ -> true },
        validator: FinalValidator? = null
    ) {
        stop()
        synchronized(records) {
            records.clear(); idempotency.clear(); queue.clear(); seq = 0
        }
        store = storeImpl
        senderFn = sender
        validatorFn = validator
        observer = null
        clock = { System.currentTimeMillis() }
    }
}

/**
 * JSON codec for the persisted EVE queue.
 *
 * Decoding is deliberately tolerant: every field added after the original
 * schema is optional, an unknown "status" name degrades instead of throwing,
 * and one unreadable record never discards the rest of the queue. A queue
 * written by an older (or newer) build therefore still loads.
 */
internal object EveQueueCodec {

    fun decode(o: JSONObject): EveSmsQueue.Record = EveSmsQueue.Record(
        requestId = o.getString("requestId"),
        jobId = o.getString("jobId"),
        to = o.getString("to"),
        text = o.getString("text"),
        priority = o.getString("priority"),
        priorityLevel = o.getInt("priorityLevel"),
        status = parseStatus(o.optString("status", "")),
        createdAt = o.getLong("createdAt"),
        sentAt = o.optLong("sentAt", 0L),
        failedReason = o.optString("failedReason", "").ifBlank { null },
        submittedOnce = o.optBoolean("submittedOnce", false),
        verificationStatus = o.optString("verificationStatus", "").ifBlank { null },
        verificationAttempts = o.optInt("verificationAttempts", 0),
        // ── absent on records persisted by older app versions ──
        gatewayRequestId = o.optString("gatewayRequestId", "").ifBlank { null },
        source = o.optString("source", "").ifBlank { null },
        serviceKey = o.optString("serviceKey", "").ifBlank { null },
        notificationKind = o.optString("notificationKind", "").ifBlank { null },
        generation = o.optInt("generation", 0),
        correlationId = o.optString("correlationId", "").ifBlank { null },
        requiresValidation = o.optBoolean("requiresValidation", false),
        pulledAt = o.optLong("pulledAt", 0L),
        validatedAt = o.optLong("validatedAt", 0L),
        validationResult = o.optString("validationResult", "").ifBlank { null },
        validationAttempts = o.optInt("validationAttempts", 0),
        deferredUntil = o.optLong("deferredUntil", 0L),
        supersededReason = o.optString("supersededReason", "").ifBlank { null },
        nativeSubmitStartedAt = o.optLong("nativeSubmitStartedAt", 0L)
    )

    fun encode(r: EveSmsQueue.Record): JSONObject = JSONObject()
        .put("requestId", r.requestId)
        .put("jobId", r.jobId)
        .put("to", r.to)
        .put("text", r.text)
        .put("priority", r.priority)
        .put("priorityLevel", r.priorityLevel)
        .put("status", r.status.name)
        .put("createdAt", r.createdAt)
        .put("sentAt", r.sentAt)
        .put("failedReason", r.failedReason ?: "")
        .put("submittedOnce", r.submittedOnce)
        .put("verificationStatus", r.verificationStatus ?: "")
        .put("verificationAttempts", r.verificationAttempts)
        // Additive keys: old builds ignore them, new builds restore the gateway
        // task identity after process death / reboot.
        .put("gatewayRequestId", r.gatewayRequestId ?: "")
        .put("source", r.source ?: "")
        .put("serviceKey", r.serviceKey ?: "")
        .put("notificationKind", r.notificationKind ?: "")
        .put("generation", r.generation)
        .put("correlationId", r.correlationId ?: "")
        .put("requiresValidation", r.requiresValidation)
        .put("pulledAt", r.pulledAt)
        .put("validatedAt", r.validatedAt)
        .put("validationResult", r.validationResult ?: "")
        .put("validationAttempts", r.validationAttempts)
        .put("deferredUntil", r.deferredUntil)
        .put("supersededReason", r.supersededReason ?: "")
        .put("nativeSubmitStartedAt", r.nativeSubmitStartedAt)

    private fun parseStatus(raw: String): EveSmsQueue.Status = try {
        if (raw.isBlank()) EveSmsQueue.Status.FAILED else EveSmsQueue.Status.valueOf(raw)
    } catch (_: IllegalArgumentException) {
        EveSmsQueue.Status.FAILED
    }
}

/** ISO-8601 UTC timestamp for EVE status responses ("2026-08-23T10:00:02Z"). */
internal fun eveIsoTimestamp(epochMillis: Long): String? {
    if (epochMillis <= 0) return null
    val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    fmt.timeZone = TimeZone.getTimeZone("UTC")
    return fmt.format(Date(epochMillis))
}
