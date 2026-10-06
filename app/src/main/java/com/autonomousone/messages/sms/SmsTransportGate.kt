package com.autonomousone.messages.sms

import com.autonomousone.messages.utils.DiagnosticLog
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * THE single physical-send gate.
 *
 * ## Why this exists
 *
 * Every route into the radio — the composer, GMweb/`GatewayOutgoingPipeline`, the EVE queue, delayed
 * send, resend, headless — ends at `SmsSender.dispatch()`, and that is the only place `SmsManager` is
 * touched. What was missing was any serialization between those callers: two sends on the same SIM
 * could enter the radio back to back, and the old `SendRateLimiter` (which only the legacy
 * `SmsSender.send(...)` path called) neither covered them nor prevented a concurrent wake-up burst.
 *
 * This gate provides exactly two things, deliberately no more:
 *
 * 1. **One physical submit per SIM at a time.** A per-`subscriptionId` lane; SIM 1 and SIM 2 never
 *    block each other.
 * 2. **A bounded cooldown after the radio says "slow down"** (`RESULT_ERROR_LIMIT_EXCEEDED`,
 *    `RESULT_RIL_REQUEST_RATE_LIMITED`). During the cooldown, new sends on THAT SIM wait; they are not
 *    failed and the modem is not hammered.
 *
 * ## What it deliberately does NOT do
 *
 * There is **no carrier-specific pacing** here. No "Hamrah-e Aval needs 750 ms" constant: we have no
 * evidence for any such number, and inventing one would be exactly the guesswork this project forbids.
 * The only timing rule is the cooldown below, whose values are a policy choice, not a carrier
 * requirement.
 *
 * ## Blocking discipline
 *
 * `submit` is called from worker threads (`SmsSender.requireOffMainThread()`), never from Main. It waits
 * on a `ReentrantLock` with a bounded `tryLock` — no `Thread.sleep`, no unbounded parking, and a caller
 * that cannot get the lane within [MAX_WAIT_MS] gives up honestly rather than blocking for ever.
 *
 * The lock is held only for the physical submission. SENT/DELIVERY callbacks arrive later, on the
 * broadcast thread, and are reported through [onSentCallback] — the lock is never held while waiting
 * for the radio to answer.
 */
object SmsTransportGate {

    private const val TAG = "SMS_GATE"

    /**
     * Cooldown ladder after a throttling result. A POLICY, not a carrier requirement: it exists so the
     * app stops feeding a radio that just refused a request for being too frequent. Bounded, then
     * capped.
     */
    private val COOLDOWN_LADDER_MS = longArrayOf(5_000L, 10_000L, 20_000L, 60_000L)

    /** How long a caller may wait for its SIM's lane before giving up. */
    internal const val MAX_WAIT_MS = 15_000L

    /** Submissions inside this window, for the health snapshot only. */
    private const val RECENT_WINDOW_MS = 60_000L
    private const val RECENT_CAPACITY = 64

    private val lanes = ConcurrentHashMap<Int, Lane>()

    internal fun resetForTest() {
        lanes.clear()
    }

    private class Lane {
        val lock = ReentrantLock(true)
        var throttledUntil: Long = 0L
        var throttleOpens: Int = 0
        var consecutiveFailures: Int = 0
        var lastSubmitAt: Long? = null
        var lastSuccessAt: Long? = null
        var lastFailureAt: Long? = null
        var lastFailureCode: String? = null
        var lastResultCode: Int? = null
        var lastRadioErrorCode: Int? = null
        val recentSubmits = ArrayDeque<Long>()

        fun trim(now: Long) {
            while (recentSubmits.isNotEmpty() && now - recentSubmits.first() > RECENT_WINDOW_MS) {
                recentSubmits.removeFirst()
            }
            while (recentSubmits.size > RECENT_CAPACITY) recentSubmits.removeFirst()
        }
    }

    private fun lane(subscriptionId: Int): Lane = lanes.getOrPut(subscriptionId) { Lane() }

    enum class Health { HEALTHY, COOLDOWN, THROTTLED, DEGRADED, UNKNOWN }

    data class Snapshot(
        val subscriptionId: Int,
        val state: Health,
        val throttledUntil: Long?,
        val consecutiveFailures: Int,
        val recentSegments: Int,
        val lastSubmitAt: Long?,
        val lastSuccessAt: Long?,
        val lastFailureAt: Long?,
        val lastFailureCode: String?,
        val lastResultCode: Int?,
        val lastRadioErrorCode: Int?
    )

    /**
     * Run ONE physical submission for [subscriptionId] under that SIM's lane.
     *
     * @param partCount the number of carrier segments this logical message became. Not used for pacing
     *   (no policy exists yet) but carried through the gate — and logged — so a future segment-aware
     *   policy has the number it needs without re-plumbing every caller.
     * @param rowId the provider row, for privacy-safe correlation in the log.
     */
    fun <T> submit(
        subscriptionId: Int,
        partCount: Int,
        rowId: Long,
        block: () -> T
    ): T {
        val lane = lane(subscriptionId)
        val now = System.currentTimeMillis()

        // ── cooldown: wait OUTSIDE the lock so other lanes and the lock itself stay free ──
        val waitMs = (lane.throttledUntil - now).coerceAtLeast(0L)
        if (waitMs > 0L) {
            DiagnosticLog.event(
                "SMS_GATE",
                "row=$rowId sub=$subscriptionId parts=$partCount state=COOLDOWN waitMs=$waitMs"
            )
        }

        val acquired = try {
            lane.lock.tryLock(waitMs + MAX_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SmsGateRejected("interrupted while waiting for the SMS lane", null)
        }
        if (!acquired) {
            // Honest refusal instead of an unbounded wait: the caller decides what to do (usually keep
            // the message pending), and nothing is submitted to the radio.
            DiagnosticLog.event(
                "SMS_FAILURE",
                "row=$rowId sub=$subscriptionId code=QUEUE_BUSY detail=gate_timeout"
            )
            throw SmsGateRejected("SMS lane busy for subscription $subscriptionId", null)
        }
        try {
            val submitAt = System.currentTimeMillis()
            lane.lastSubmitAt = submitAt
            lane.recentSubmits.addLast(submitAt)
            lane.trim(submitAt)
            DiagnosticLog.event(
                "SMS_DISPATCH",
                "row=$rowId sub=$subscriptionId parts=$partCount queueRecent=${lane.recentSubmits.size}"
            )
            return block()
        } finally {
            lane.lock.unlock()
        }
    }

    /**
     * Report ONE SENT callback for [subscriptionId].
     *
     * This is the only thing that can open a cooldown, and a confirmed success is the only thing that
     * clears one — NOT "SmsManager did not throw", which proves nothing about the radio.
     */
    fun onSentCallback(
        subscriptionId: Int,
        verdict: SmsTransportVerdict,
        rowId: Long,
        partIndex: Int,
        partCount: Int,
        errorCode: Int?
    ) {
        val lane = lane(subscriptionId)
        val now = System.currentTimeMillis()
        synchronized(lane) {
            lane.lastResultCode = verdict.resultCode
            lane.lastRadioErrorCode = verdict.radioErrorCode ?: errorCode
            when {
                verdict.isSuccess -> heal(lane, now)
                verdict.shouldThrottle -> openCooldown(lane, subscriptionId, verdict, now)
                else -> {
                    lane.consecutiveFailures++
                    lane.lastFailureAt = now
                    lane.lastFailureCode = verdict.failureCode
                }
            }
        }
        DiagnosticLog.event(
            "SMS_SENT_CALLBACK",
            "row=$rowId sub=$subscriptionId part=${partIndex + 1}/$partCount " +
                "result=${verdict.resultCode} name=${verdict.resultCodeName} " +
                "errorCode=${verdict.radioErrorCode ?: errorCode ?: -1} " +
                "evidence=${verdict.evidence.name} code=${verdict.failureCode ?: "none"}"
        )
        if (!verdict.isSuccess) {
            DiagnosticLog.event(
                "SMS_FAILURE",
                "row=$rowId sub=$subscriptionId code=${verdict.failureCode ?: "unknown"} " +
                    "retry=${verdict.retrySafety.name}"
            )
        }
    }

    /** A real, confirmed success clears the throttle and the failure streak. */
    private fun heal(lane: Lane, now: Long) {
        lane.consecutiveFailures = 0
        lane.throttleOpens = 0
        lane.throttledUntil = 0L
        lane.lastSuccessAt = now
        lane.lastFailureCode = null
    }

    private fun openCooldown(
        lane: Lane,
        subscriptionId: Int,
        verdict: SmsTransportVerdict,
        now: Long
    ) {
        val index = lane.throttleOpens.coerceAtMost(COOLDOWN_LADDER_MS.size - 1)
        val cooldown = COOLDOWN_LADDER_MS[index]
        lane.throttleOpens++
        lane.throttledUntil = now + cooldown
        lane.consecutiveFailures++
        lane.lastFailureAt = now
        lane.lastFailureCode = verdict.failureCode
        DiagnosticLog.event(
            "SMS_THROTTLE",
            "sub=$subscriptionId until=${lane.throttledUntil} cause=${verdict.failureCode} " +
                "cooldownMs=$cooldown opens=${lane.throttleOpens}"
        )
    }

    /** Reset only the transient per-SIM health — used when subscription state changes. */
    fun onSubscriptionChanged(subscriptionId: Int) {
        lanes.remove(subscriptionId)
        DiagnosticLog.event("SMS_TRANSPORT_HEALTH", "sub=$subscriptionId state=UNKNOWN reason=sim_changed")
    }

    /** Current health for one SIM, for logs/diagnostics. Never blocks. */
    fun health(subscriptionId: Int, now: Long = System.currentTimeMillis()): Snapshot {
        val lane = lane(subscriptionId)
        synchronized(lane) {
            lane.trim(now)
            val throttled = lane.throttledUntil > now
            val state = when {
                throttled && lane.throttleOpens > 1 -> Health.THROTTLED
                throttled -> Health.COOLDOWN
                lane.consecutiveFailures >= 2 -> Health.DEGRADED
                // A confirmed success is health evidence in its own right, even if this lane has not
                // submitted anything since the process started.
                lane.lastSubmitAt == null && lane.lastSuccessAt == null -> Health.UNKNOWN
                else -> Health.HEALTHY
            }
            return Snapshot(
                subscriptionId = subscriptionId,
                state = state,
                throttledUntil = lane.throttledUntil.takeIf { it > 0L },
                consecutiveFailures = lane.consecutiveFailures,
                recentSegments = lane.recentSubmits.size,
                lastSubmitAt = lane.lastSubmitAt,
                lastSuccessAt = lane.lastSuccessAt,
                lastFailureAt = lane.lastFailureAt,
                lastFailureCode = lane.lastFailureCode,
                lastResultCode = lane.lastResultCode,
                lastRadioErrorCode = lane.lastRadioErrorCode
            )
        }
    }

    /** Every SIM lane with recent activity, for the diagnostics log. */
    fun healthSnapshot(): List<Snapshot> = lanes.keys.sorted().map { health(it) }
}

/** Thrown when the gate refused to submit (busy lane / interrupted). Nothing reached the radio. */
class SmsGateRejected(message: String, cause: Throwable?) : RuntimeException(message, cause)
