package com.autonomousone.messages.sync.diagnostics

/**
 * The per-source history numbers, derived honestly from three facts.
 *
 * WHY THIS IS A SEPARATE PURE FUNCTION
 *
 * The collector used to report `acked = ackedContiguousOrdinal` and `pending = produced - acked`.
 * The watermark is a **contiguous frontier**, not a count of acknowledged events, and the two
 * coincide only while nothing in the sequence has permanently failed. With a single dead letter the
 * frozen watermark made the report say `acked = 2, pending = 4000` for a source that had 4,000 rows
 * delivered and exactly one permanently failed: it under-reported delivered work and **invented
 * 4,000 pending events** — while `failed` was reported as unmeasured even though
 * `historyDeadLetters(source, generation)` already existed to answer it.
 *
 * Failure visibility is the whole point of §70 and of mission §26: a permanent failure must appear
 * as a permanent failure, not as work in progress. So the numbers are derived from *counts of rows
 * in each state*, and the invariant enforced here is the mission's own:
 *
 *     produced = acked + pending + failed
 *
 * UNKNOWN IS NULL, NEVER ZERO — the rule the whole diagnostics model follows. If any input is
 * unmeasured, the outputs that depend on it are unmeasured too rather than silently zero.
 */
data class HistorySourceCounts(
    val acked: Long?,
    val pending: Long?,
    val failed: Long?
)

/**
 * @param produced    ordinals assigned by the scan (`nextOrdinal - 1`), or null when unknown.
 * @param ackedCount  rows in state ACKED for this source and generation — a COUNT, not the
 *                    watermark. Null when not measured.
 * @param deadLetters rows permanently failed for this source and generation. Null when not measured.
 */
fun historySourceCounts(
    produced: Long?,
    ackedCount: Long?,
    deadLetters: Long?
): HistorySourceCounts {
    if (produced == null || ackedCount == null || deadLetters == null) {
        // Partial knowledge is reported as partial: an unmeasured count stays null rather than
        // becoming a zero that reads as "nothing is wrong".
        return HistorySourceCounts(
            acked = ackedCount?.coerceAtLeast(0),
            pending = null,
            failed = deadLetters?.coerceAtLeast(0)
        )
    }
    val acked = ackedCount.coerceAtLeast(0)
    val failed = deadLetters.coerceAtLeast(0)
    // `pending` is the REMAINDER, so the invariant holds by construction. The clamp only matters if
    // acked + failed exceeds produced, which means the checkpoint and the outbox disagree; reporting
    // 0 there is the safe direction, because inventing pending work is the defect being fixed.
    val pending = (produced - acked - failed).coerceAtLeast(0)
    return HistorySourceCounts(acked = acked, pending = pending, failed = failed)
}
