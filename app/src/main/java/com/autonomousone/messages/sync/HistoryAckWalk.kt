package com.autonomousone.messages.sync

/**
 * How far the contiguous history-ACK frontier may advance (mission §19/§21/§27).
 *
 * **Why this is its own rule.** `advanceHistoryAckWatermarks` walks the history rows forward from the
 * last acknowledged ordinal and stops at the first one that is not acknowledged. That "stop at the
 * first gap" behaviour is what makes the watermark mean *contiguous*, and it is also the single most
 * fragile thing in this subsystem: anything that removes, re-orders or re-stamps a row freezes history
 * at `CATCHING_UP` for ever, and nothing in the schema declares the dependency. It has been the cause
 * of three separate defects during this work, each found only by reasoning about it.
 *
 * Extracted and made pure so the rule can be asserted directly instead of inferred from an
 * instrumented run, and so the repository has exactly one place to get it wrong.
 */
data class HistoryAckCandidate(
    val ordinal: Long,
    /** A `GatewayEventOutboxEntity.STATE_*` name. Only `ACKED` advances the frontier. */
    val state: String,
    val date: Long,
    val providerId: Long,
)

/** Where the frontier ends up after a walk. */
data class HistoryAckFrontier(
    val ordinal: Long,
    val date: Long,
    val providerId: Long,
) {
    companion object {
        /**
         * The frontier as the checkpoint stores it.
         *
         * `Long.MAX_VALUE` for both cursors matches `newCheckpoint()`: a checkpoint that has never
         * advanced carries the "nothing acknowledged yet" sentinel, not the epoch. Using 0 there would
         * be a real date and would let a repair path mistake it for a cursor that had been advanced to
         * the beginning of time.
         */
        fun initial(ordinal: Long = 0L) =
            HistoryAckFrontier(ordinal = ordinal, date = Long.MAX_VALUE, providerId = Long.MAX_VALUE)
    }
}

object HistoryAckWalk {

    /**
     * The ordinal this walk may advance to, or null when nothing more is contiguous.
     *
     * [rows] must be ordered ascending by ordinal, as `historyAfter` returns them. A row that is out
     * of order, that skips an ordinal, or that is not ACKED all stop the walk — each for the same
     * reason: the frontier is only meaningful if every ordinal below it has actually been delivered.
     *
     * A DEAD_LETTER row stops the walk too, deliberately. It is not a loss of the frontier's
     * integrity but a statement that this position will never be acknowledged without human action,
     * and the honest observable for that is a scan that reports `scanComplete` while `delivered` stays
     * false — not a frontier that silently steps over the hole.
     *
     * @return the last contiguous ordinal, or null when the very next ordinal is not acknowledged.
     */
    fun advance(from: HistoryAckFrontier, rows: List<HistoryAckCandidate>): HistoryAckFrontier? {
        var current: HistoryAckFrontier? = null
        var expected = from.ordinal
        for (row in rows) {
            // A gap, an out-of-order row, or a row that is not acknowledged ends the walk here.
            if (row.ordinal != expected + 1) break
            if (row.state != ACKED_STATE) break
            expected = row.ordinal
            current = HistoryAckFrontier(
                ordinal = row.ordinal,
                date = row.date,
                providerId = row.providerId,
            )
        }
        return current
    }

    /**
     * Folds [advance] over a checkpoint's own values, which is how the repository uses it.
     *
     * @param ackedContiguousOrdinal the checkpoint's stored frontier ordinal.
     */
    fun advanceFrom(
        ackedContiguousOrdinal: Long,
        ackedCursorDate: Long,
        ackedCursorProviderId: Long,
        rows: List<HistoryAckCandidate>,
    ): HistoryAckFrontier? = advance(
        from = HistoryAckFrontier(
            ordinal = ackedContiguousOrdinal,
            date = ackedCursorDate,
            providerId = ackedCursorProviderId,
        ),
        rows = rows,
    )

    /**
     * The outbox state name that may advance the frontier.
     *
     * A local constant rather than a reference to the entity, so this file stays free of the data layer
     * and can be tested without it — and so the value is visible next to the rule that depends on it.
     */
    const val ACKED_STATE = "ACKED"

    /**
     * Whether a source's history is fully delivered, given its frontier and its dead letters
     * (mission §26).
     *
     * Deliberately takes three facts rather than reading them: the rule that `SCAN_COMPLETE` is not
     * `CAUGHT_UP` is the one this project has already got wrong once, and the dead-letter clause is the
     * part that gets dropped whenever it is re-derived at a call site.
     */
    fun isDelivered(
        sourceExhausted: Boolean,
        nextOrdinal: Long,
        ackedContiguousOrdinal: Long,
        deadLetters: Int,
    ): Boolean =
        sourceExhausted && ackedContiguousOrdinal == nextOrdinal - 1 && deadLetters == 0
}
