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

    data class Snapshot(
        val running: Boolean,
        val lastTrigger: String?,
        val lastAttemptAt: Long?,
        val lastSuccessAt: Long?,
        val lastHttpStatus: Int?,
        val lastErrorCode: String?,
        val attempts: Long,
        val successes: Long,
        val failures: Long,
        /** Triggers that arrived while no reporter was running. Not failures — nothing was attempted. */
        val skipped: Long,
        val lastSubscriptionChangeAt: Long?,
        val subscriptionChangeCount: Long,
        /** Live: the last observed active-subscription count, or null when it could not be read. */
        val lastSubscriptionCount: Int?,
        val lastSubscriptionReason: String?
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
    private var attempts = 0L
    private var successes = 0L
    private var failures = 0L
    private var skipped = 0L
    private var lastSubscriptionChangeAt: Long? = null
    private var subscriptionChangeCount = 0L
    private var lastSubscriptionCount: Int? = null
    private var lastSubscriptionReason: String? = null

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
    }

    /**
     * A failed attempt. [httpStatus] is null for a transport failure that never produced a response,
     * in which case the cause is [ErrorCode.TRANSPORT] rather than a fabricated status.
     */
    fun onFailure(
        at: Long = System.currentTimeMillis(),
        httpStatus: Int? = null,
        errorCode: String = if (httpStatus == null) ErrorCode.TRANSPORT else ErrorCode.HTTP_STATUS
    ) = synchronized(lock) {
        failures++
        lastHttpStatus = httpStatus
        lastErrorCode = errorCode
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

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(
            running = running,
            lastTrigger = lastTrigger,
            lastAttemptAt = lastAttemptAt,
            lastSuccessAt = lastSuccessAt,
            lastHttpStatus = lastHttpStatus,
            lastErrorCode = lastErrorCode,
            attempts = attempts,
            successes = successes,
            failures = failures,
            skipped = skipped,
            lastSubscriptionChangeAt = lastSubscriptionChangeAt,
            subscriptionChangeCount = subscriptionChangeCount,
            lastSubscriptionCount = lastSubscriptionCount,
            lastSubscriptionReason = lastSubscriptionReason
        )
    }

    internal fun resetForTest() = synchronized(lock) {
        running = false
        lastTrigger = null
        lastAttemptAt = null
        lastSuccessAt = null
        lastHttpStatus = null
        lastErrorCode = null
        attempts = 0
        successes = 0
        failures = 0
        skipped = 0
        lastSubscriptionChangeAt = null
        subscriptionChangeCount = 0
        lastSubscriptionCount = null
        lastSubscriptionReason = null
    }
}
