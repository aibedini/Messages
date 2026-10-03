package com.autonomousone.messages.gateway

/**
 * Whether the phone's telemetry heartbeat is actually reaching GMweb.
 *
 * **The defect this exists for.** `DeviceTelemetry.report()` returned a boolean that the loop threw
 * away, and nothing recorded when it last succeeded. A phone whose telemetry had been failing for
 * twenty minutes looked exactly like a healthy one: the gateway was "connected", the SIM list on
 * GMweb was simply stale, and the screen explained nothing. "GMweb looks connected while the phone
 * is actually not reporting" is a user-visible failure, and the fix starts with keeping the facts.
 *
 * In-memory on purpose: this is observation of a running process, and the only thing it could
 * usefully be persisted for is a post-mortem that the durable diagnostic log already covers. No
 * payload, no phone number, no SIM identifier is kept here — only timings, an HTTP status and a
 * stable error code.
 */
object TelemetryHealth {

    /** Stable, machine-readable cause of the last telemetry failure. */
    object ErrorCode {
        const val HTTP_STATUS = "http_status"
        const val TRANSPORT = "transport_failure"
        const val NOT_ELIGIBLE = "not_eligible"
    }

    /** Upper bound on a stored failure detail. Long enough to name a cause, short enough to be safe. */
    internal const val MAX_DETAIL_CHARS = 160

    data class Snapshot(
        val running: Boolean,
        val lastTrigger: String?,
        val lastAttemptAt: Long?,
        val lastSuccessAt: Long?,
        val lastHttpStatus: Int?,
        val lastErrorCode: String?,
        /** WHICH stage the last failure happened at: PAYLOAD, SIGNING, HTTP, NETWORK, TIMEOUT, … */
        val lastFailureStage: String?,
        /** Sanitised, bounded detail: an exception class name or a short message. Never a payload. */
        val lastFailureDetail: String?,
        /** The exception CLASS of the last failure, when there was one. */
        val lastFailureExceptionClass: String?,
        val attempts: Long,
        val successes: Long,
        val failures: Long,
        /** Triggers that arrived while no reporter was running. Not failures — nothing was attempted. */
        val skipped: Long,
        val lastSubscriptionChangeAt: Long?,
        val subscriptionChangeCount: Long,
        /** Live: the last observed active-subscription count, or null when it could not be read. */
        val lastSubscriptionCount: Int?,
        val lastSubscriptionReason: String?,
        /** How many web-requested refreshes have completed (successfully or not). */
        val remoteRefreshCount: Long,
        val lastRemoteRefreshAt: Long?,
        /** `success` or the stable `TELEMETRY_*` code of the last web-requested refresh. */
        val lastRemoteRefreshResult: String?
    ) {
        /** Age of the last successful report, or null when none has ever succeeded. */
        fun secondsSinceSuccess(now: Long): Long? =
            lastSuccessAt?.let { ((now - it) / 1000L).coerceAtLeast(0) }
    }

    private val lock = Any()
    private var running = false
    private var lastTrigger: String? = null
    private var lastAttemptAt: Long? = null
    private var lastSuccessAt: Long? = null
    private var lastHttpStatus: Int? = null
    private var lastErrorCode: String? = null
    private var lastFailureStage: String? = null
    private var lastFailureDetail: String? = null
    private var lastFailureExceptionClass: String? = null
    private var attempts = 0L
    private var successes = 0L
    private var failures = 0L
    private var skipped = 0L
    private var lastSubscriptionChangeAt: Long? = null
    private var subscriptionChangeCount = 0L
    private var lastSubscriptionCount: Int? = null
    private var lastSubscriptionReason: String? = null
    private var remoteRefreshCount = 0L
    private var lastRemoteRefreshAt: Long? = null
    private var lastRemoteRefreshResult: String? = null

    fun setRunning(value: Boolean) = synchronized(lock) { running = value }

    fun onAttempt(trigger: TelemetryTrigger, at: Long = System.currentTimeMillis()) = synchronized(lock) {
        attempts++
        lastTrigger = trigger.wireValue
        lastAttemptAt = at
    }

    fun onSuccess(at: Long = System.currentTimeMillis(), httpStatus: Int? = null) = synchronized(lock) {
        successes++
        lastSuccessAt = at
        lastHttpStatus = httpStatus
        lastErrorCode = null
        lastFailureStage = null
        lastFailureDetail = null
        lastFailureExceptionClass = null
    }

    /**
     * A failed attempt, with the STAGE and the sanitised detail.
     *
     * The stage and detail are what turned "TRANSPORT_ERROR" into a lead: a payload-build exception
     * and a socket timeout both used to arrive as the same word, so the on-device card could not say
     * which one the phone was hitting.
     */
    fun onFailure(
        at: Long = System.currentTimeMillis(),
        httpStatus: Int? = null,
        errorCode: String = if (httpStatus == null) ErrorCode.TRANSPORT else ErrorCode.HTTP_STATUS,
        stage: String? = null,
        detail: String? = null
    ) = synchronized(lock) {
        failures++
        lastHttpStatus = httpStatus
        lastErrorCode = errorCode
        lastFailureStage = stage
        lastFailureDetail = sanitizeDetail(detail)
        lastFailureExceptionClass = exceptionClassOf(detail)
    }

    /**
     * Bounded, privacy-safe failure detail.
     *
     * IMPORTANT: this method runs while handling an already-failed telemetry report, so it must be
     * impossible for the sanitiser itself to throw. Android's ICU regex engine does not support all
     * inline java.util.regex flags (the old `(?U)` pattern crashed API 35 on every telemetry error).
     * Phone-like runs are therefore redacted with a tiny scanner instead of a regex.
     */
    private fun sanitizeDetail(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val flat = raw.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')
        val out = StringBuilder(minOf(flat.length, MAX_DETAIL_CHARS))
        var i = 0
        while (i < flat.length && out.length < MAX_DETAIL_CHARS) {
            val first = flat[i]
            if (first == '+' || first.isDigit()) {
                var j = i
                var digits = 0
                if (flat[j] == '+') j++
                while (j < flat.length) {
                    val ch = flat[j]
                    when {
                        ch.isDigit() -> {
                            digits++
                            j++
                        }
                        ch == ' ' || ch == '(' || ch == ')' || ch == '-' -> j++
                        else -> break
                    }
                }
                if (digits >= 7) {
                    val redacted = "<number>"
                    out.append(redacted, 0, minOf(redacted.length, MAX_DETAIL_CHARS - out.length))
                    i = j
                    continue
                }
            }
            out.append(first)
            i++
        }
        return out.toString().ifBlank { null }
    }

    /** The exception class a `Class: message` style detail starts with, when it looks like one. */
    private fun exceptionClassOf(detail: String?): String? {
        val head = detail?.substringBefore(':')?.trim().orEmpty()
        return head.takeIf { it.endsWith("Exception") || it.endsWith("Error") }
    }

    /** A subscription change was observed (SIM inserted/removed, eSIM toggled, default changed). */
    fun onSubscriptionChange(at: Long = System.currentTimeMillis()) = synchronized(lock) {
        subscriptionChangeCount++
        lastSubscriptionChangeAt = at
    }

    /**
     * A trigger arrived while no reporter was running (the gateway is switched off).
     *
     * Counted separately from [onFailure] on purpose: nothing was attempted, so reporting it as a
     * failed report would make a deliberately-disabled gateway look like a broken one.
     */
    fun onSkipped(trigger: TelemetryTrigger) = synchronized(lock) {
        skipped++
        lastTrigger = trigger.wireValue
    }

    /** The live subscription count/reason of the most recent discovery, for the SIM diagnostics row. */
    fun onSubscriptionDiscovery(count: Int?, reason: String?) = synchronized(lock) {
        lastSubscriptionCount = count
        lastSubscriptionReason = reason
    }

    /**
     * The outcome of a web-requested telemetry refresh.
     *
     * Recorded even on failure: "the browser asked and the phone could not deliver" is exactly the
     * fact that was missing while GMweb showed a three-day-old report.
     */
    fun onRemoteRefresh(at: Long, succeeded: Boolean, resultCode: String) = synchronized(lock) {
        remoteRefreshCount++
        lastRemoteRefreshAt = at
        lastRemoteRefreshResult = if (succeeded) "success" else resultCode
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(
            running = running,
            lastTrigger = lastTrigger,
            lastAttemptAt = lastAttemptAt,
            lastSuccessAt = lastSuccessAt,
            lastHttpStatus = lastHttpStatus,
            lastErrorCode = lastErrorCode,
            lastFailureStage = lastFailureStage,
            lastFailureDetail = lastFailureDetail,
            lastFailureExceptionClass = lastFailureExceptionClass,
            attempts = attempts,
            successes = successes,
            failures = failures,
            skipped = skipped,
            lastSubscriptionChangeAt = lastSubscriptionChangeAt,
            subscriptionChangeCount = subscriptionChangeCount,
            lastSubscriptionCount = lastSubscriptionCount,
            lastSubscriptionReason = lastSubscriptionReason,
            remoteRefreshCount = remoteRefreshCount,
            lastRemoteRefreshAt = lastRemoteRefreshAt,
            lastRemoteRefreshResult = lastRemoteRefreshResult
        )
    }

    internal fun resetForTest() = synchronized(lock) {
        running = false
        lastTrigger = null
        lastAttemptAt = null
        lastSuccessAt = null
        lastHttpStatus = null
        lastErrorCode = null
        lastFailureStage = null
        lastFailureDetail = null
        lastFailureExceptionClass = null
        attempts = 0
        successes = 0
        failures = 0
        skipped = 0
        lastSubscriptionChangeAt = null
        subscriptionChangeCount = 0L
        lastSubscriptionCount = null
        lastSubscriptionReason = null
        remoteRefreshCount = 0L
        lastRemoteRefreshAt = null
        lastRemoteRefreshResult = null
    }
}
