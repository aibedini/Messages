package com.autonomousone.messages.sms

/**
 * The logical (whole-message) verdict of a multipart send.
 *
 * A logical SMS with `partCount = 3` produces THREE SENT callbacks, and a naive "first RESULT_OK wins"
 * reading is how a message whose part 3 was rate-limited still showed as sent. This is the one rule that
 * combines them.
 */
enum class LogicalSendOutcome {
    /** Every required part confirmed. The ONLY outcome that may heal SIM health. */
    SENT_CONFIRMED,

    /** At least one part was refused because the transport asked us to slow down. */
    THROTTLED,

    /** At least one part was definitely refused, and nothing indicates throttling. */
    SENT_REJECTED,

    /** Evidence is inconclusive: a generic/vendor result, or a part whose callback never arrived. */
    SENT_AMBIGUOUS
}

/**
 * One aggregated logical send result, with the duplicate-risk semantics preserved.
 *
 * [failure] is the canonical [SmsSendFailure] winner; [failureCode] is what gets persisted.
 */
data class LogicalSendVerdict(
    val outcome: LogicalSendOutcome,
    val failure: SmsSendFailure?,
    val retrySafety: RetrySafety,
    val confirmedParts: Int,
    val rejectedParts: Int,
    val ambiguousParts: Int,
    val throttledParts: Int,
    val totalParts: Int
) {
    /** Only a full, confirmed logical send may clear the lane's throttle/degraded state. */
    val healsSimHealth: Boolean get() = outcome == LogicalSendOutcome.SENT_CONFIRMED
}

/**
 * Severity-ordered aggregation of per-part SENT evidence.
 *
 * ```text
 * THROTTLED > DEFINITE_REJECTION > AMBIGUOUS > CONFIRMED
 * ```
 *
 * The rules, exactly as the mission states them:
 *
 * ```text
 * OK + OK                     => SENT_CONFIRMED
 * OK + RATE_LIMITED           => THROTTLED        (never success)
 * OK + GENERIC_FAILURE        => SENT_AMBIGUOUS   (never success, duplicate risk)
 * GENERIC_FAILURE + GENERIC   => SENT_AMBIGUOUS
 * NO_SERVICE + NO_SERVICE     => SENT_REJECTED
 * ```
 *
 * Order-independent by construction (counts, not a sequence) and idempotent per part index, so a
 * duplicate callback can neither double-count nor resolve the message twice.
 *
 * Pure: no Android, no clock, no state — so the policy is testable and there is exactly one of it.
 */
object SmsLogicalSendAggregator {

    /** The accumulator for one logical send. */
    class Tally(val totalParts: Int) {
        private val byPart = HashMap<Int, SmsTransportVerdict>()
        var ambiguousMissingParts: Int = 0
            private set

        /**
         * Record a callback for [partIndex]. A duplicate simply overwrites the same key: the message
         * cannot be completed twice, and a repeated callback cannot inflate any counter.
         *
         * @return true when this was the first callback for that part index.
         */
        fun record(partIndex: Int, verdict: SmsTransportVerdict): Boolean {
            val first = !byPart.containsKey(partIndex)
            byPart[partIndex] = verdict
            return first
        }

        fun resolvedParts(): Int = byPart.size

        /** True when every required part has reported, so a verdict can be computed. */
        fun isComplete(): Boolean = byPart.size >= totalParts

        fun verdicts(): List<SmsTransportVerdict> = byPart.values.toList()

        /** Parts that never reported — folded in as ambiguous when a timeout resolves the send. */
        fun remainingParts(): Int = (totalParts - byPart.size).coerceAtLeast(0)
    }

    /**
     * Aggregate the evidence collected so far.
     *
     * [timedOut] folds in any part whose callback never arrived as AMBIGUOUS evidence rather than
     * failure: a missing callback means "we cannot prove what happened", and a blind retry could
     * duplicate a message that did leave the phone.
     */
    fun aggregate(tally: Tally, timedOut: Boolean = false): LogicalSendVerdict {
        val verdicts = tally.verdicts().toMutableList()
        val missing = if (timedOut) tally.remainingParts() else 0
        repeat(missing) {
            verdicts += SmsTransportVerdict(
                failure = SmsSendFailure.SendCallbackTimeout,
                evidence = SendEvidence.AMBIGUOUS,
                retrySafety = RetrySafety.POSSIBLE_DUPLICATE,
                shouldThrottle = false,
                resultCode = 0,
                resultCodeName = "NO_CALLBACK",
                radioErrorCode = null
            )
        }

        val throttled = verdicts.filter { it.shouldThrottle }
        val rejected = verdicts.filter { it.evidence == SendEvidence.REJECTED && !it.shouldThrottle }
        val ambiguous = verdicts.filter { it.evidence == SendEvidence.AMBIGUOUS }
        val confirmed = verdicts.filter { it.evidence == SendEvidence.CONFIRMED }

        fun build(
            outcome: LogicalSendOutcome,
            winner: SmsTransportVerdict?
        ) = LogicalSendVerdict(
            outcome = outcome,
            failure = winner?.failure,
            retrySafety = winner?.retrySafety ?: RetrySafety.NOT_APPLICABLE,
            confirmedParts = confirmed.size,
            rejectedParts = rejected.size,
            ambiguousParts = ambiguous.size,
            throttledParts = throttled.size,
            totalParts = tally.totalParts
        )

        return when {
            // A throttle anywhere outranks everything: the SIM asked us to slow down, and no part of
            // this message may be reported as a success.
            throttled.isNotEmpty() -> build(LogicalSendOutcome.THROTTLED, throttled.first())

            // A definite refusal that is not a throttle.
            rejected.isNotEmpty() -> build(LogicalSendOutcome.SENT_REJECTED, rejected.first())

            // Inconclusive evidence — including a part whose callback never came.
            ambiguous.isNotEmpty() -> build(LogicalSendOutcome.SENT_AMBIGUOUS, ambiguous.first())

            // Every required part confirmed.
            confirmed.size >= tally.totalParts -> build(LogicalSendOutcome.SENT_CONFIRMED, null)

            // Nothing recorded at all (or only part of a multipart with no evidence yet).
            else -> build(LogicalSendOutcome.SENT_AMBIGUOUS, null)
        }
    }
}
