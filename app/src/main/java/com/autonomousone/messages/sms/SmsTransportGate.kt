package com.autonomousone.messages.sms

import com.autonomousone.messages.utils.DiagnosticLog
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.min

/**
 * THE single physical-send gate — now with REAL burst closure.
 *
 * ## Serialization was not enough
 *
 * The previous version guaranteed only that two `SmsManager` calls never overlapped. That still allowed
 * this, because the API returns long before the radio answers:
 *
 * ```text
 * t=0ms    submit A        t=3ms  gate released
 * t=4ms    submit B        t=7ms  submit C      t=10ms submit D
 * t=150ms  SENT callback for A: RESULT_RIL_REQUEST_RATE_LIMITED
 * ```
 *
 * Four submits had already been pushed at a radio that was going to refuse them — the exact burst this
 * project has been chasing. The fix is NOT a guessed carrier delay (`750 ms for MCI` would be invented
 * evidence, which this project forbids): it is to dispatch **one logical SMS per SIM at a time** and
 * wait for its SENT evidence to resolve before the next one is handed to the radio.
 *
 * ## Lane model
 *
 * ```text
 * IDLE ──dispatch──> WAITING_SENT_CALLBACKS ──all parts reported──> resolve ──> IDLE
 *                              │                                  └─────> COOLDOWN (throttled)
 *                              └──no callback within the bounded timeout──> resolve AMBIGUOUS ──> IDLE
 * ```
 *
 * The lane lock is held only while a lane is dispatching or waiting for ITS OWN evidence, and that wait
 * is **bounded** (see [SENT_CALLBACK_TIMEOUT_MS]): a broken OEM callback can delay the next SMS on that
 * SIM, but it can never freeze it for ever. Other SIMs are entirely independent.
 *
 * ## Recovery rule
 *
 * Only a fully aggregated [LogicalSendOutcome.SENT_CONFIRMED] heals the lane. A single `RESULT_OK` part
 * of a 3-part message is not evidence that the SIM is healthy, and it must not clear a cooldown.
 *
 * ## Callback safety
 *
 * `onSentCallback` never takes the lane lock (the waiting dispatcher holds it); it records into the
 * in-flight tally under that send's own monitor and signals completion. Duplicate callbacks overwrite
 * the same part index, so a repeat can neither double-count nor resolve the send twice, and a callback
 * that arrives after a timeout finds no matching in-flight send and cannot release the next one.
 */
object SmsTransportGate {

    private const val TAG = "SMS_GATE"

    /**
     * Cooldown ladder after a THROTTLED logical send. A POLICY choice (stop feeding a radio that just
     * refused a request for being too frequent), not a claimed carrier requirement.
     */
    private val COOLDOWN_LADDER_MS = longArrayOf(5_000L, 10_000L, 20_000L, 60_000L)

    /**
     * How long a logical send may wait for its SENT callbacks before it is resolved as AMBIGUOUS.
     *
     * Missing evidence is NOT failure: it means "we cannot prove what happened", so the outcome is
     * [SmsSendFailure.SendCallbackTimeout] with [RetrySafety.POSSIBLE_DUPLICATE] and no automatic
     * resend. The lane then proceeds, because one lost callback must not freeze a SIM.
     */
    internal const val SENT_CALLBACK_TIMEOUT_MS = 30_000L

    /** How long a caller may wait for a busy/frozen lane before giving up honestly. */
    internal const val MAX_WAIT_MS = 15_000L

    private const val RECENT_WINDOW_MS = 60_000L
    private const val RECENT_CAPACITY = 64

    private val lanes = ConcurrentHashMap<Int, Lane>()

    internal fun resetForTest() {
        lanes.clear()
    }

    enum class Health { HEALTHY, COOLDOWN, THROTTLED, DEGRADED, UNKNOWN }

    enum class LaneState { IDLE, WAITING_SENT_CALLBACKS, COOLDOWN }

    data class Snapshot(
        val subscriptionId: Int,
        val state: Health,
        val laneState: LaneState,
        val throttledUntil: Long?,
        val consecutiveFailures: Int,
        val recentSegments: Int,
        val lastSubmitAt: Long?,
        val lastSuccessAt: Long?,
        val lastFailureAt: Long?,
        val lastFailureCode: String?,
        val lastResultCode: Int?,
        val lastRadioErrorCode: Int?,
        /** The logical send currently in flight on this SIM, if any. */
        val inFlightRowId: Long?,
        val inFlightParts: Int,
        val inFlightResolvedParts: Int
    )

    /** One logical SMS awaiting its SENT evidence. */
    internal class InFlight(
        val rowId: Long,
        val subscriptionId: Int,
        val partCount: Int,
        val startedAt: Long,
        val deadlineAt: Long
    ) {
        val tally = SmsLogicalSendAggregator.Tally(partCount)
        var resolved: LogicalSendVerdict? = null
        var duplicateCallbacks: Int = 0

        fun record(partIndex: Int, verdict: SmsTransportVerdict) {
            if (!tally.record(partIndex, verdict)) duplicateCallbacks++
        }
    }

    private class Lane {
        val lock = ReentrantLock(true)

        /** Dedicated monitor for cooldown parking (never the lane lock). */
        val cooldownMonitor = java.lang.Object()
        @Volatile var inFlight: InFlight? = null
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

        fun laneState(now: Long): LaneState = when {
            inFlight != null -> LaneState.WAITING_SENT_CALLBACKS
            throttledUntil > now -> LaneState.COOLDOWN
            else -> LaneState.IDLE
        }
    }

    private fun lane(subscriptionId: Int): Lane = lanes.getOrPut(subscriptionId) { Lane() }

    /**
     * Dispatch ONE logical SMS on [subscriptionId] through the lane.
     *
     * Blocks — on a worker thread — until this SIM's previous logical send has resolved (SENT evidence
     * for every part, a definite outcome, or the bounded timeout), then performs the physical submit.
     * The lane stays in [LaneState.WAITING_SENT_CALLBACKS] until [onSentCallback] resolves it, which is
     * what prevents the burst: a second caller cannot reach `SmsManager` before that.
     *
     * @param partCount the number of carrier segments ([android.telephony.SmsManager.divideMessage]).
     */
    fun <T> submit(
        subscriptionId: Int,
        partCount: Int,
        rowId: Long,
        block: () -> T
    ): T {
        val lane = lane(subscriptionId)
        val parts = partCount.coerceAtLeast(1)

        // Cooldown wait happens OUTSIDE the lock so a throttled SIM does not block other lanes.
        val cooldownWait = waitForCooldown(lane, subscriptionId, rowId, parts)

        val acquired = try {
            lane.lock.tryLock(cooldownWait + MAX_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SmsGateRejected("interrupted while waiting for the SMS lane", null)
        }
        if (!acquired) {
            DiagnosticLog.event("SMS_FAILURE", "row=$rowId sub=$subscriptionId code=QUEUE_BUSY detail=gate_timeout")
            throw SmsGateRejected("SMS lane busy for subscription $subscriptionId", null)
        }
        try {
            awaitPreviousSend(lane, subscriptionId, rowId)

            val now = System.currentTimeMillis()
            val flight = InFlight(
                rowId = rowId,
                subscriptionId = subscriptionId,
                partCount = parts,
                startedAt = now,
                deadlineAt = now + SENT_CALLBACK_TIMEOUT_MS
            )
            lane.inFlight = flight
            lane.lastSubmitAt = now
            lane.recentSubmits.addLast(now)
            lane.trim(now)
            DiagnosticLog.event(
                "SMS_GATE",
                "row=$rowId sub=$subscriptionId parts=$parts state=DISPATCHING " +
                    "recent=${lane.recentSubmits.size}"
            )

            val result = block()
            DiagnosticLog.event("SMS_DISPATCH", "row=$rowId sub=$subscriptionId parts=$parts")
            return result
        } finally {
            lane.lock.unlock()
        }
    }

    /** Bounded wait for the lane's cooldown. Returns how long it waited (for the lock budget). */
    private fun waitForCooldown(lane: Lane, subscriptionId: Int, rowId: Long, parts: Int): Long {
        val waitMs = (lane.throttledUntil - System.currentTimeMillis()).coerceAtLeast(0L)
        if (waitMs <= 0L) return 0L
        DiagnosticLog.event(
            "SMS_GATE",
            "row=$rowId sub=$subscriptionId parts=$parts state=COOLDOWN waitMs=$waitMs"
        )
        // Bounded park on this (worker) thread's own monitor — no Thread.sleep, no busy loop. The
        // dispatcher is not holding the lane lock here, so other lanes and callbacks stay free.
        synchronized(lane.cooldownMonitor) {
            runCatching { lane.cooldownMonitor.wait(min(waitMs, MAX_WAIT_MS)) }
        }
        return waitMs
    }

    /**
     * Hold the lane until the previous logical send resolves, or its bounded timeout expires.
     *
     * Runs while the caller owns [Lane.lock], which is what makes "one logical send per SIM" true. The
     * wait itself is on the in-flight object's monitor, so [onSentCallback] can signal it without
     * needing the lane lock (which would deadlock against this reader).
     */
    private fun awaitPreviousSend(lane: Lane, subscriptionId: Int, rowId: Long) {
        while (true) {
            val flight = lane.inFlight ?: return
            val remaining = flight.deadlineAt - System.currentTimeMillis()
            if (remaining <= 0L) {
                resolveTimedOut(lane, flight)
                return
            }
            synchronized(flight) {
                if (flight.resolved == null) {
                    runCatching { (flight as java.lang.Object).wait(min(remaining, 250L)) }
                }
            }
            if (flight.resolved != null) {
                lane.inFlight = null
                DiagnosticLog.event(
                    "SMS_GATE",
                    "row=$rowId sub=$subscriptionId state=IDLE previous=${flight.rowId} " +
                        "outcome=${flight.resolved?.outcome?.name}"
                )
                return
            }
        }
    }

    /** The callback never came: resolve as AMBIGUOUS, keep the raw truth, and never auto-resend. */
    private fun resolveTimedOut(lane: Lane, flight: InFlight) {
        val verdict = SmsLogicalSendAggregator.aggregate(flight.tally, timedOut = true)
        flight.resolved = verdict
        lane.inFlight = null
        lane.consecutiveFailures++
        lane.lastFailureAt = System.currentTimeMillis()
        lane.lastFailureCode = verdict.failure?.code ?: SmsSendFailure.SendCallbackTimeout.code
        DiagnosticLog.event(
            "SMS_FAILURE",
            "row=${flight.rowId} sub=${flight.subscriptionId} code=${SmsSendFailure.SendCallbackTimeout.code} " +
                "retry=${RetrySafety.POSSIBLE_DUPLICATE.name} detail=no_sent_callback " +
                "parts=${flight.tally.resolvedParts()}/${flight.partCount}"
        )
    }

    /**
     * Report ONE SENT callback. Evidence is recorded against the in-flight logical send for that SIM;
     * the send resolves only when every required part has reported (or the timeout does it).
     */
    fun onSentCallback(
        subscriptionId: Int,
        rowId: Long,
        partIndex: Int,
        resultCode: Int,
        errorCode: Int? = null
    ) {
        val verdict = SmsTransportClassifier.classify(resultCode, errorCode)
        val lane = lane(subscriptionId)
        val flight = lane.inFlight

        DiagnosticLog.event(
            "SMS_SENT_CALLBACK",
            "row=$rowId sub=$subscriptionId part=${partIndex + 1}/${flight?.partCount ?: 1} " +
                "result=$resultCode name=${verdict.resultCodeName} errorCode=${errorCode ?: -1} " +
                "evidence=${verdict.evidence.name} code=${verdict.failureCode ?: "none"}"
        )

        // A callback for a send that already timed out (or for an older row) must not resolve the
        // CURRENT in-flight send: correlation is by rowId first.
        if (flight == null || flight.rowId != rowId) {
            DiagnosticLog.event(
                "SMS_SENT_CALLBACK",
                "row=$rowId sub=$subscriptionId decision=UNCORRELATED " +
                    "current=${flight?.rowId ?: -1} code=${verdict.failureCode ?: "none"}"
            )
            return
        }

        synchronized(flight) {
            flight.record(partIndex, verdict)
            if (flight.tally.isComplete() && flight.resolved == null) {
                val aggregate = SmsLogicalSendAggregator.aggregate(flight.tally)
                flight.resolved = aggregate
                applyResolution(lane, subscriptionId, flight, aggregate)
                // Release the lane here as well as in the waiting dispatcher: a resolved send must not
                // leave the SIM parked when no dispatcher happens to be waiting on it (the lane would
                // otherwise look busy for ever after the last message).
                if (lane.inFlight === flight) lane.inFlight = null
            }
            (flight as java.lang.Object).notifyAll()
        }
    }

    /** Apply an aggregated logical result to the SIM's health. */
    private fun applyResolution(
        lane: Lane,
        subscriptionId: Int,
        flight: InFlight,
        verdict: LogicalSendVerdict
    ) {
        val now = System.currentTimeMillis()
        lane.lastResultCode = flight.tally.verdicts().lastOrNull()?.resultCode
        lane.lastRadioErrorCode = flight.tally.verdicts().lastOrNull()?.radioErrorCode
        when (verdict.outcome) {
            LogicalSendOutcome.SENT_CONFIRMED -> heal(lane, now)
            LogicalSendOutcome.THROTTLED -> openCooldown(lane, subscriptionId, verdict, now)
            else -> {
                lane.consecutiveFailures++
                lane.lastFailureAt = now
                lane.lastFailureCode = verdict.failure?.code ?: verdict.outcome.name
            }
        }
        DiagnosticLog.event(
            "SMS_GATE",
            "row=${flight.rowId} sub=$subscriptionId outcome=${verdict.outcome.name} " +
                "confirmed=${verdict.confirmedParts}/${verdict.totalParts} " +
                "rejected=${verdict.rejectedParts} throttled=${verdict.throttledParts} " +
                "ambiguous=${verdict.ambiguousParts} duplicates=${flight.duplicateCallbacks} " +
                "code=${verdict.failure?.code ?: "none"} retry=${verdict.retrySafety.name}"
        )
    }

    /**
     * ONLY a fully confirmed logical send heals the lane.
     *
     * A single `RESULT_OK` part of a multipart message is not evidence that the SIM is healthy: the
     * other parts may have been rate-limited, which is exactly the case that must NOT clear a cooldown.
     */
    private fun heal(lane: Lane, now: Long) {
        lane.consecutiveFailures = 0
        lane.throttleOpens = 0
        lane.throttledUntil = 0L
        lane.lastSuccessAt = now
        lane.lastFailureCode = null
    }

    private fun openCooldown(lane: Lane, subscriptionId: Int, verdict: LogicalSendVerdict, now: Long) {
        val index = lane.throttleOpens.coerceAtMost(COOLDOWN_LADDER_MS.size - 1)
        val cooldown = COOLDOWN_LADDER_MS[index]
        lane.throttleOpens++
        lane.throttledUntil = now + cooldown
        lane.consecutiveFailures++
        lane.lastFailureAt = now
        lane.lastFailureCode = verdict.failure?.code
        DiagnosticLog.event(
            "SMS_THROTTLE",
            "sub=$subscriptionId until=${lane.throttledUntil} cause=${verdict.failure?.code} " +
                "cooldownMs=$cooldown opens=${lane.throttleOpens} parts=${verdict.throttledParts}/${verdict.totalParts}"
        )
    }

    /** Reset only this SIM's transient health — used when subscription state changes. */
    fun onSubscriptionChanged(subscriptionId: Int) {
        lanes.remove(subscriptionId)
        DiagnosticLog.event("SMS_TRANSPORT_HEALTH", "sub=$subscriptionId state=UNKNOWN reason=sim_changed")
    }

    /** Current health for one SIM. Never blocks. */
    fun health(subscriptionId: Int, now: Long = System.currentTimeMillis()): Snapshot {
        val lane = lane(subscriptionId)
        synchronized(lane) {
            lane.trim(now)
            val throttled = lane.throttledUntil > now
            val state = when {
                throttled && lane.throttleOpens > 1 -> Health.THROTTLED
                throttled -> Health.COOLDOWN
                lane.consecutiveFailures >= 2 -> Health.DEGRADED
                lane.lastSubmitAt == null && lane.lastSuccessAt == null -> Health.UNKNOWN
                else -> Health.HEALTHY
            }
            val flight = lane.inFlight
            return Snapshot(
                subscriptionId = subscriptionId,
                state = state,
                laneState = lane.laneState(now),
                throttledUntil = lane.throttledUntil.takeIf { it > 0L },
                consecutiveFailures = lane.consecutiveFailures,
                recentSegments = lane.recentSubmits.size,
                lastSubmitAt = lane.lastSubmitAt,
                lastSuccessAt = lane.lastSuccessAt,
                lastFailureAt = lane.lastFailureAt,
                lastFailureCode = lane.lastFailureCode,
                lastResultCode = lane.lastResultCode,
                lastRadioErrorCode = lane.lastRadioErrorCode,
                inFlightRowId = flight?.rowId,
                inFlightParts = flight?.partCount ?: 0,
                inFlightResolvedParts = flight?.tally?.resolvedParts() ?: 0
            )
        }
    }

    /** Every SIM lane with activity, for the diagnostics log. */
    fun healthSnapshot(): List<Snapshot> = lanes.keys.sorted().map { health(it) }
}

/** Thrown when the gate refused to submit (busy/frozen lane, or interrupted). Nothing reached the radio. */
class SmsGateRejected(message: String, cause: Throwable?) : RuntimeException(message, cause)
