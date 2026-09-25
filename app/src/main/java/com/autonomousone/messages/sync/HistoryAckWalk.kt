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
     * of order, that skips an ordinal, or that is not TERMINAL all stop the walk.
     *
     * WHAT "TERMINAL" MEANS, AND WHY IT IS NOT JUST "ACKED"
     *
     * Terminal is `ACKED` **or** `DEAD_LETTER`: delivered, or permanently refused by the server.
     * `PENDING`, `RETRY_WAIT` and `SENDING` are NOT terminal and still stop the walk — so the
     * frontier continues to imply what the mission actually needs from it, that there is no
     * outstanding work below this point (Pending = 0 and Retryable = 0).
     *
     * This deliberately REVERSES an earlier decision to stall on a dead letter, and the reason is a
     * measured defect rather than a preference. Stalling meant `ackedContiguousOrdinal` froze at the
     * position before a permanently failed row *forever*, and since retention only deletes a history
     * row at or below that watermark, every acknowledged history row produced afterwards became
     * permanently undeletable: one failed event turned retention off for the rest of the history
     * (4,001 rows pinned in `OutboxRetentionSqlTest`). It also made the diagnostic report those
     * delivered rows as PENDING work.
     *
     * The old rationale was that stepping over the hole would hide the failure. That is not what
     * actually hid it — the frontier was never the failure signal. The failure signal is
     * `historyDeadLetters` (counted and reported) plus [isDelivered], which still requires ZERO dead
     * letters and therefore keeps "delivered" an honest, strict claim. A frontier that is merely the
     * last resolved position, with failures counted beside it, hides nothing.
     *
     * Safe under rescue: the watermark advances AS the dead position is crossed, so if the row is
     * later rescued and acknowledged, nothing above it is needed — retention may already have
     * removed those rows, and the walk never has to look back.
     *
     * @return the last terminal ordinal reached, or null when the very next ordinal is not terminal.
     */
    fun advance(from: HistoryAckFrontier, rows: List<HistoryAckCandidate>): HistoryAckFrontier? {
        var current: HistoryAckFrontier? = null
        var expected = from.ordinal
        for (row in rows) {
            // A gap or an out-of-order row ends the walk here.
            if (row.ordinal != expected + 1) break
            // Outstanding work ends the walk: the frontier must never imply that a row still in the
            // queue has been dealt with.
            if (!isTerminal(row.state)) break
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
     * The server permanently refused this row.
     *
     * Terminal for the FRONTIER (see [advance]) but never for [isDelivered]: a permanent failure is
     * something to count and report, not something to call delivered.
     */
    const val DEAD_LETTER_STATE = "DEAD_LETTER"

    /** Whether a row is finished with: delivered, or permanently refused. */
    fun isTerminal(state: String): Boolean =
        state == ACKED_STATE || state == DEAD_LETTER_STATE

    /**
     * Whether a source's history is fully DELIVERED, given its frontier and its dead letters
     * (mission §26).
     *
     * This is the STRICT claim, and it is deliberately stricter than the frontier: the frontier says
     * "everything below this point is resolved (delivered or permanently failed)", while this says
     * "everything was delivered". That difference is the whole reason a permanent failure cannot be
     * mistaken for success — the frontier may cross a dead letter so that retention keeps working,
     * but this predicate still refuses to call that source delivered.
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

    /**
     * Whether a source is CAUGHT UP: nothing is left to do (mission §26, §L).
     *
     * This is the state the UI reports as `CAUGHT_UP`, and it is deliberately weaker than
     * [isDelivered] by exactly one clause: a permanent failure does not block it, because a permanent
     * failure is something a human has to act on rather than something the device is still working
     * through. Waiting for a dead letter to become acknowledged is waiting forever, which is how
     * `CAUGHT_UP` used to be unreachable on any source that had ever lost one event — a terminal
     * state that could never be reached is not a stricter guarantee, it is a stuck indicator.
     *
     * The failure is not hidden by this: it stays visible as the counted `deadLetters`, which the
     * diagnostics report beside the state, and [isDelivered] keeps saying `false` so the strict claim
     * is still available and still honest. "Nothing left to do" and "everything delivered" are two
     * facts, and the whole reason this file exists is that they must not be conflated.
     *
     * Deliberately does NOT take `deadLetters`: the caller cannot forget to pass it, and no future
     * reader can mistake this for the strict rule.
     */
    fun isResolved(
        sourceExhausted: Boolean,
        nextOrdinal: Long,
        ackedContiguousOrdinal: Long,
    ): Boolean = sourceExhausted && ackedContiguousOrdinal == nextOrdinal - 1
}
