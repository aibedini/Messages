package com.autonomousone.messages.sync

/**
 * Mission §70, made arithmetic: `Eligible = Enqueued + Skipped + Failed`, with every discrepancy
 * explainable.
 *
 * This is the formal statement of the invariant the whole replication effort exists to satisfy —
 * *no SMS/MMS known to the device may be silently lost* — and until now nothing measured it. The
 * scan logged `SYNC_REPORT eligible=N queued=N`, which is not a measurement: `queued` was
 * incremented for every row *offered* to the enqueue path, including rows the ADR-006 firewall
 * refused, so it could only ever equal `eligible`. A number that cannot differ from another number
 * cannot detect anything.
 *
 * Everything here is pure, because a number that is supposed to detect loss must be reproducible
 * off-device in a test.
 */

/**
 * What became of one provider row the history scan considered.
 *
 * The list is deliberately exhaustive rather than a pair of booleans: every way a row can fail to
 * replicate is a named state a reviewer can check off, and adding a new one is a visible act.
 */
enum class HistoryRowOutcome {

    /** A durable event now exists for this row and this session is why. */
    ENQUEUED,

    /**
     * A durable event already existed AND was already attributed to history by an earlier pass.
     *
     * Carries no counter: noticing the same row twice is not two rows, and counting it would inflate
     * `eligible` past the number of messages the phone actually holds. It exists as a state so
     * "nothing happened" is distinguishable from "nothing was examined".
     */
    ALREADY_ACCOUNTED,

    /** The user's policy keeps this message on the device. Correct behaviour, not a defect. */
    SKIPPED_LOCAL_ONLY,

    /**
     * The financial-notification policy is ASK and the user has not answered (§16 fail-closed).
     *
     * Separate from [SKIPPED_LOCAL_ONLY] because it is *provisional*: the same row may become
     * eligible once the user answers, whereas a LOCAL_ONLY decision is final for that message.
     */
    SKIPPED_ASK_PENDING,

    /** Drafts and unknown provider types never enter cloud history. */
    SKIPPED_NO_DIRECTION,

    /** Sync was switched off mid-scan; the row was not examined to a conclusion. */
    SKIPPED_SYNC_OFF,

    /**
     * Eligible, not skipped, and no durable event exists.
     *
     * This is the number that must be zero. Anything else in this enum is an explained outcome;
     * this one is an unexplained one, and reporting it is the entire point of the exercise.
     */
    FAILED;

    val isEnqueued: Boolean get() = this == ENQUEUED

    val isSkipped: Boolean
        get() = this == SKIPPED_LOCAL_ONLY || this == SKIPPED_ASK_PENDING ||
            this == SKIPPED_NO_DIRECTION || this == SKIPPED_SYNC_OFF

    /** True when the row contributes to the arithmetic at all. */
    val isAccounted: Boolean get() = this != ALREADY_ACCOUNTED
}

/**
 * What the event pipeline reported for one enqueue attempt.
 *
 * Distinct from [HistoryRowOutcome] on purpose: this is the *pipeline's* vocabulary (it knows
 * nothing about history sessions) and the accounting maps it onto the mission's. Collapsing the two
 * would put history semantics inside the firewall's return value.
 */
enum class EnqueueAttempt {
    INSERTED,
    ALREADY_PRESENT,
    SKIPPED_LOCAL_ONLY,
    SKIPPED_ASK_PENDING,
    SKIPPED_SYNC_OFF,
}

/**
 * The raw facts about one row, before they are interpreted.
 *
 * Held as an observation rather than as loose booleans at the call site so the interpretation has
 * one home: `stamped` is only meaningful for an event that pre-existed, and a pair of independent
 * booleans would let a caller pass a meaningless combination without the compiler noticing.
 */
sealed interface HistoryRowObservation {

    /** The provider row has no cloud direction (draft, unknown type). */
    data object NoDirection : HistoryRowObservation

    /** The enqueue path ran and reported what it did. */
    data class Attempted(val attempt: EnqueueAttempt) : HistoryRowObservation

    /**
     * An event already existed and this pass stamped history metadata onto it.
     *
     * The row was discovered by realtime first, which knows nothing about ordinals — so history is
     * claiming it now, and that is a successful replication, not a duplicate.
     */
    data object AdoptedExistingEvent : HistoryRowObservation

    /** An event already existed WITH history metadata: an earlier pass counted it. */
    data object AlreadyAccounted : HistoryRowObservation

    /** The enqueue path reported success but no event is present afterwards. */
    data object EventMissingAfterInsert : HistoryRowObservation
}

/**
 * The durable outcome of one history-sync session (mission §25 + §70).
 *
 * A pure mirror of `HistorySyncSessionEntity`, so the arithmetic can be tested without Room.
 * `skipped` and the balance are **derived**, never stored: a stored total and stored parts are two
 * copies of one fact and will eventually disagree.
 */
data class HistorySyncSession(
    val sessionId: String,
    val source: String,
    val generation: Long,
    val startedAt: Long,
    val finishedAt: Long,
    val eligible: Long,
    val enqueued: Long,
    val failed: Long,
    val skippedLocalOnly: Long,
    val skippedAskPending: Long,
    val skippedNoDirection: Long,
    val skippedSyncOff: Long,
    val scanExhausted: Boolean,
) {
    val isOpen: Boolean get() = finishedAt == 0L

    val skipped: Long
        get() = skippedLocalOnly + skippedAskPending + skippedNoDirection + skippedSyncOff

    /** Everything the session can account for. */
    val accounted: Long get() = enqueued + skipped + failed

    /**
     * Mission §70's identity. False means rows were examined and not explained — the one state that
     * may never be reported as healthy.
     */
    val balances: Boolean get() = eligible == accounted

    /**
     * `eligible - accounted`. Zero when balanced; negative would mean counting rows that were never
     * read, which is a bug in the accounting rather than in replication.
     */
    val residual: Long get() = eligible - accounted

    /**
     * True only when the scan finished the source AND every single row it read is explained.
     *
     * The conjunction is the point: a balanced arithmetic on a scan that stopped halfway is not
     * "history replicated", it is "the part we looked at was replicated". Conflating those is
     * Blocker 9's mistake (`SCAN_COMPLETE` reported as `CAUGHT_UP`) in a new place.
     */
    val fullyAccounted: Boolean get() = scanExhausted && balances
}

/** A set of outcomes to fold into a session, already tallied. */
data class HistoryScanDelta(
    val eligible: Long = 0,
    val enqueued: Long = 0,
    val failed: Long = 0,
    val skippedLocalOnly: Long = 0,
    val skippedAskPending: Long = 0,
    val skippedNoDirection: Long = 0,
    val skippedSyncOff: Long = 0,
) {
    val skipped: Long
        get() = skippedLocalOnly + skippedAskPending + skippedNoDirection + skippedSyncOff

    val accounted: Long get() = enqueued + skipped + failed

    val balances: Boolean get() = eligible == accounted

    companion object {
        /**
         * Tally outcomes.
         *
         * The `eligible` increment is derived from the outcome rather than passed in, so a caller
         * cannot forget it for one branch — which is exactly how `eligible == queued` came to look
         * like a measurement.
         */
        fun of(outcomes: Iterable<HistoryRowOutcome>): HistoryScanDelta {
            var eligible = 0L
            var enqueued = 0L
            var failed = 0L
            var localOnly = 0L
            var askPending = 0L
            var noDirection = 0L
            var syncOff = 0L
            for (outcome in outcomes) {
                if (!outcome.isAccounted) continue
                eligible++
                when (outcome) {
                    HistoryRowOutcome.ENQUEUED -> enqueued++
                    HistoryRowOutcome.FAILED -> failed++
                    HistoryRowOutcome.SKIPPED_LOCAL_ONLY -> localOnly++
                    HistoryRowOutcome.SKIPPED_ASK_PENDING -> askPending++
                    HistoryRowOutcome.SKIPPED_NO_DIRECTION -> noDirection++
                    HistoryRowOutcome.SKIPPED_SYNC_OFF -> syncOff++
                    HistoryRowOutcome.ALREADY_ACCOUNTED -> error("filtered above")
                }
            }
            return HistoryScanDelta(
                eligible = eligible,
                enqueued = enqueued,
                failed = failed,
                skippedLocalOnly = localOnly,
                skippedAskPending = askPending,
                skippedNoDirection = noDirection,
                skippedSyncOff = syncOff,
            )
        }
    }
}

object HistoryScanAccounting {

    /** Interpret one observation. The single place the pipeline's answer becomes an outcome. */
    fun classify(observation: HistoryRowObservation): HistoryRowOutcome = when (observation) {
        HistoryRowObservation.NoDirection -> HistoryRowOutcome.SKIPPED_NO_DIRECTION
        HistoryRowObservation.AdoptedExistingEvent -> HistoryRowOutcome.ENQUEUED
        HistoryRowObservation.AlreadyAccounted -> HistoryRowOutcome.ALREADY_ACCOUNTED
        HistoryRowObservation.EventMissingAfterInsert -> HistoryRowOutcome.FAILED
        is HistoryRowObservation.Attempted -> when (observation.attempt) {
            EnqueueAttempt.INSERTED -> HistoryRowOutcome.ENQUEUED
            // An event was already present when the insert was attempted. Since this branch is only
            // reached when no event existed before the call, the row did replicate — the insert lost
            // a race to a concurrent writer of the SAME event id, which is dedup working.
            EnqueueAttempt.ALREADY_PRESENT -> HistoryRowOutcome.ENQUEUED
            EnqueueAttempt.SKIPPED_LOCAL_ONLY -> HistoryRowOutcome.SKIPPED_LOCAL_ONLY
            EnqueueAttempt.SKIPPED_ASK_PENDING -> HistoryRowOutcome.SKIPPED_ASK_PENDING
            EnqueueAttempt.SKIPPED_SYNC_OFF -> HistoryRowOutcome.SKIPPED_SYNC_OFF
        }
    }

    /** Fold a delta into a session, preserving the derived-not-stored rule. */
    fun add(session: HistorySyncSession, delta: HistoryScanDelta): HistorySyncSession = session.copy(
        eligible = session.eligible + delta.eligible,
        enqueued = session.enqueued + delta.enqueued,
        failed = session.failed + delta.failed,
        skippedLocalOnly = session.skippedLocalOnly + delta.skippedLocalOnly,
        skippedAskPending = session.skippedAskPending + delta.skippedAskPending,
        skippedNoDirection = session.skippedNoDirection + delta.skippedNoDirection,
        skippedSyncOff = session.skippedSyncOff + delta.skippedSyncOff,
    )

    /**
     * The sentence a human needs, or null when there is nothing worth saying.
     *
     * Precedence: a tally that does not balance is reported FIRST, because `failed` is counted by the
     * same tally — announcing "N messages were lost" from books that do not add up would be a
     * fabricated conclusion, and a reader who saw the loss line would rightly stop reading. Then the
     * loss itself, which is the finding that matters. A balanced session with no failures says
     * nothing: a diagnostic that always warns is a diagnostic nobody reads.
     */
    fun alarm(session: HistorySyncSession): String? {
        if (!session.balances) {
            return "the scan accounts for ${session.accounted} of ${session.eligible} message(s) " +
                "it read (residual ${session.residual}). The arithmetic does not balance, so the " +
                "scan's own accounting is wrong and its other numbers cannot be trusted."
        }
        if (session.failed > 0) {
            return "${session.failed} message(s) in this scan were eligible but have NO durable " +
                "event. That is unexplained loss, not a policy decision."
        }
        return null
    }
}
