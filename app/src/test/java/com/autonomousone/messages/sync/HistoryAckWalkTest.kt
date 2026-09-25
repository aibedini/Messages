package com.autonomousone.messages.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contiguous history-ACK frontier (mission §19/§21/§26).
 *
 * This rule has been the cause of three separate defects during this work, every one of them found by
 * reasoning rather than by a failing test — because the rule lived inside a suspend function that only
 * an instrumented run could exercise. It is asserted directly here for that reason.
 *
 * The property that matters is that the frontier is CONTIGUOUS: it may only advance while every ordinal
 * below it has actually been delivered. Anything that removes, re-orders or re-stamps a row must stall
 * it rather than let it step over the hole, because a frontier that skips a hole claims delivery of
 * history nobody ever received.
 */
class HistoryAckWalkTest {

    private fun acked(ordinal: Long, date: Long = ordinal * 100, providerId: Long = ordinal) =
        HistoryAckCandidate(ordinal, HistoryAckWalk.ACKED_STATE, date, providerId)

    private fun row(
        ordinal: Long,
        state: String,
        date: Long = ordinal * 100,
        providerId: Long = ordinal
    ) = HistoryAckCandidate(ordinal, state, date, providerId)

    private val frontierAtZero = HistoryAckFrontier.initial()

    // ── The happy path ───────────────────────────────────────────────────────

    @Test
    fun `aContiguousRunAdvancesToItsLastAcknowledgedRow`() {
        val frontier = HistoryAckWalk.advanceFrom(
            ackedContiguousOrdinal = 0,
            ackedCursorDate = Long.MAX_VALUE,
            ackedCursorProviderId = Long.MAX_VALUE,
            rows = listOf(acked(1), acked(2), acked(3))
        )!!

        assertEquals(3, frontier.ordinal)
        assertEquals(300, frontier.date)
        assertEquals(3, frontier.providerId)
    }

    @Test
    fun `theCursorTravelsWithTheFrontierSoAResumedWalkDoesNotRestart`() {
        // The cursor is the (date, providerId) of the row the frontier stopped on. Without it a resumed
        // walk would have an ordinal but no idea which provider row that ordinal was, and the two
        // facts are what the checkpoint pairs together.
        val frontier = HistoryAckWalk.advanceFrom(
            ackedContiguousOrdinal = 0,
            ackedCursorDate = Long.MAX_VALUE,
            ackedCursorProviderId = Long.MAX_VALUE,
            rows = listOf(acked(1, date = 111, providerId = 9), acked(2, date = 222, providerId = 8))
        )!!

        assertEquals(2, frontier.ordinal)
        assertEquals(222, frontier.date)
        assertEquals(8, frontier.providerId)
    }

    @Test
    fun `aResumedWalkContinuesFromWhereItStopped`() {
        val first = HistoryAckWalk.advanceFrom(0, Long.MAX_VALUE, Long.MAX_VALUE, listOf(acked(1), acked(2)))!!
        val second = HistoryAckWalk.advanceFrom(
            first.ordinal, first.date, first.providerId,
            listOf(acked(3), acked(4))
        )!!

        assertEquals(4, second.ordinal)
    }

    // ── The trap: a gap must stop the walk, and closing it must resume ───────

    @Test
    fun `theWalkStopsAtTheFirstGap`() {
        // Ordinals 1, 2, 4 are ACKed and 3 is not. The frontier must stop at 2 — advancing to 4 would
        // claim ordinal 3 was delivered.
        val frontier = HistoryAckWalk.advanceFrom(
            ackedContiguousOrdinal = 0,
            ackedCursorDate = Long.MAX_VALUE,
            ackedCursorProviderId = Long.MAX_VALUE,
            rows = listOf(acked(1), acked(2), acked(4))
        )!!

        assertEquals(2, frontier.ordinal)
    }

    @Test
    fun `closingAGapAdvancesTheFrontierPastIt`() {
        // The reason the walk must re-read rather than trust its stored frontier: once ordinal 3 is
        // acknowledged, the SAME read advances to 4. A walk that persisted "we are at 2" and never
        // looked again would freeze history for ever at CATCHING_UP.
        val frontier = HistoryAckWalk.advanceFrom(
            ackedContiguousOrdinal = 2,
            ackedCursorDate = 200,
            ackedCursorProviderId = 2,
            rows = listOf(acked(3), acked(4))
        )!!

        assertEquals(4, frontier.ordinal)
    }

    @Test
    fun `nothingContiguousLeavesTheFrontierAlone`() {
        // Null rather than a frontier equal to the old one: the caller must not write a checkpoint row
        // when nothing moved, or the walk would touch the table on every ack of every unrelated event.
        assertNull(
            HistoryAckWalk.advanceFrom(
                ackedContiguousOrdinal = 2,
                ackedCursorDate = 200,
                ackedCursorProviderId = 2,
                rows = listOf(acked(4))
            )
        )
        assertNull(HistoryAckWalk.advanceFrom(0, Long.MAX_VALUE, Long.MAX_VALUE, emptyList()))
    }

    @Test
    fun `anOutstandingNextRowStopsTheWalkEvenWhenItIsTheRightOrdinal`() {
        // Outstanding work stops the frontier. This is what keeps the frontier meaning "there is
        // nothing left to do below here" — Pending = 0 and Retryable = 0 — even after a dead letter
        // became crossable (see the dead-letter test below).
        listOf("PENDING", "SENDING", "RETRY_WAIT").forEach { state ->
            assertNull(
                "a $state row must not advance the frontier",
                HistoryAckWalk.advanceFrom(0, Long.MAX_VALUE, Long.MAX_VALUE, listOf(row(1, state)))
            )
        }
    }

    @Test
    fun `aDeadLetterIsCrossedSoTheFrontierDoesNotFreezeForever`() {
        // REVERSED DELIBERATELY (round 47). This walk used to stall on a dead letter, and the cost
        // was measured: the watermark froze for good, and because retention only deletes a history
        // row at or below the watermark, every acknowledged row produced afterwards became
        // permanently undeletable — 4,001 rows pinned in OutboxRetentionSqlTest. The diagnostic also
        // reported those delivered rows as PENDING work.
        //
        // A permanent failure is TERMINAL for the frontier: it will never change without human
        // action, so waiting for it is waiting forever. It is NOT terminal for `delivered`, which
        // still requires zero dead letters — so crossing it here cannot turn a failure into success.
        val frontier = HistoryAckWalk.advanceFrom(
            ackedContiguousOrdinal = 0,
            ackedCursorDate = Long.MAX_VALUE,
            ackedCursorProviderId = Long.MAX_VALUE,
            rows = listOf(acked(1), row(2, "DEAD_LETTER"), acked(3))
        )!!

        assertEquals(3, frontier.ordinal)
    }

    @Test
    fun `aDeadLetterIsCrossedButOutstandingWorkBehindItStillStopsTheWalk`() {
        // The safety property of the reversal, and both halves are visible in one assertion: the
        // walk crosses the dead letter at 1 (so the frontier is not frozen by a permanent failure)
        // and stops AT the outstanding row at 2 (so the frontier still means "nothing left to do
        // below here"). If it had reached 3, the frontier would claim the RETRY_WAIT row was dealt
        // with — which is the claim that must never be made.
        val frontier = HistoryAckWalk.advanceFrom(
            ackedContiguousOrdinal = 0,
            ackedCursorDate = Long.MAX_VALUE,
            ackedCursorProviderId = Long.MAX_VALUE,
            rows = listOf(row(1, "DEAD_LETTER"), row(2, "RETRY_WAIT"), acked(3))
        )!!

        assertEquals(
            "crossed the dead letter, stopped at the outstanding row",
            1,
            frontier.ordinal
        )
    }

    @Test
    fun `anOutOfOrderRowStopsTheWalk`() {
        // A row returned out of ordinal order breaks the contiguity argument: the walk cannot know that
        // the ordinal it expects is not simply later in the list.
        val frontier = HistoryAckWalk.advanceFrom(
            ackedContiguousOrdinal = 1,
            ackedCursorDate = 100,
            ackedCursorProviderId = 1,
            rows = listOf(acked(3), acked(2))
        )

        assertNull(frontier)
    }

    @Test
    fun `aDuplicateOrdinalStopsTheWalk`() {
        // Two rows claiming ordinal 2 means one of them is not the row the frontier thinks it is —
        // re-stamping is exactly how an ordinal gets reused, and stepping over it would lose the other.
        val frontier = HistoryAckWalk.advanceFrom(
            ackedContiguousOrdinal = 1,
            ackedCursorDate = 100,
            ackedCursorProviderId = 1,
            rows = listOf(acked(2), acked(2))
        )!!

        assertEquals("the frontier advances once, to 2", 2, frontier.ordinal)
    }

    @Test
    fun `theWalkNeverMovesBackwards`() {
        // Stated over the shape rather than by example: whatever the rows, the resulting ordinal is at
        // least the one it started from. A backwards frontier would re-send acknowledged history and
        // could never converge.
        val cases = listOf(
            emptyList(),
            listOf(acked(1)),
            listOf(acked(2)),
            listOf(acked(1), acked(2), acked(3)),
            listOf(row(1, "PENDING"))
        )
        cases.forEach { rows ->
            val frontier = HistoryAckWalk.advanceFrom(5, 500, 5, rows)
            if (frontier != null) {
                assertTrue("the frontier must not go backwards", frontier.ordinal > 5)
            }
        }
        // And it is null for every case above, because the walk expects ordinal 6.
        cases.forEach { rows ->
            assertNull(HistoryAckWalk.advanceFrom(5, 500, 5, rows))
        }
    }

    // ── The frontier's initial value ─────────────────────────────────────────

    @Test
    fun `theInitialCursorIsTheSentinelNotTheEpoch`() {
        // Zero is a real date. A checkpoint initialised to it would look like a frontier advanced to the
        // beginning of time, and a repair path comparing cursors would treat it as progress.
        val initial = HistoryAckFrontier.initial()

        assertEquals(0L, initial.ordinal)
        assertEquals(Long.MAX_VALUE, initial.date)
        assertEquals(Long.MAX_VALUE, initial.providerId)
    }

    // ── Delivered is not the same as scanned (mission §26) ───────────────────

    @Test
    fun `deliveredRequiresTheDeadLetterClause`() {
        // The clause that gets dropped whenever this rule is re-derived at a call site, which is how a
        // source with permanently failed rows was once reported as CAUGHT_UP.
        assertTrue(HistoryAckWalk.isDelivered(sourceExhausted = true, nextOrdinal = 6, ackedContiguousOrdinal = 5, deadLetters = 0))
        assertFalse(
            "one dead letter is not delivered",
            HistoryAckWalk.isDelivered(sourceExhausted = true, nextOrdinal = 6, ackedContiguousOrdinal = 5, deadLetters = 1)
        )
    }

    // ── Resolved (caught up) is not the same as delivered (mission §L) ────────

    @Test
    fun `aPermanentFailureDoesNotBlockBeingCaughtUp`() {
        // The reversal, and the reason for it: a dead letter waits on a PERSON, not on this device.
        // Blocking CAUGHT_UP on it made the terminal state unreachable for any source that had ever
        // lost one event — a stuck indicator, not a stricter guarantee.
        //
        // It is still not DELIVERED (the assertion above), so nothing here calls a failure a success.
        assertTrue(
            HistoryAckWalk.isResolved(sourceExhausted = true, nextOrdinal = 6, ackedContiguousOrdinal = 5)
        )
        assertFalse(
            "the same state is still not delivered — the strict claim is unchanged",
            HistoryAckWalk.isDelivered(sourceExhausted = true, nextOrdinal = 6, ackedContiguousOrdinal = 5, deadLetters = 1)
        )
    }

    @Test
    fun `caughtUpStillRequiresTheScanToHaveFinishedAndNoOutstandingWork`() {
        // Weakening the rule by ONE clause must not weaken it by two: a scan still running, or a
        // frontier that has not reached the end, is not caught up.
        assertFalse(
            "a running scan is not caught up",
            HistoryAckWalk.isResolved(sourceExhausted = false, nextOrdinal = 6, ackedContiguousOrdinal = 5)
        )
        assertFalse(
            "an outstanding row below the end is not caught up",
            HistoryAckWalk.isResolved(sourceExhausted = true, nextOrdinal = 6, ackedContiguousOrdinal = 4)
        )
    }

    @Test
    fun `anEmptySourceIsCaughtUpOnceScanned`() {
        assertTrue(
            HistoryAckWalk.isResolved(sourceExhausted = true, nextOrdinal = 1, ackedContiguousOrdinal = 0)
        )
    }

    @Test
    fun `deliveredRequiresTheScanToHaveFinished`() {
        assertFalse(
            "a scan still running has not delivered anything yet",
            HistoryAckWalk.isDelivered(sourceExhausted = false, nextOrdinal = 6, ackedContiguousOrdinal = 5, deadLetters = 0)
        )
    }

    @Test
    fun `deliveredRequiresEveryOrdinalToBeAcknowledged`() {
        assertFalse(
            HistoryAckWalk.isDelivered(sourceExhausted = true, nextOrdinal = 6, ackedContiguousOrdinal = 4, deadLetters = 0)
        )
    }

    @Test
    fun `anEmptySourceIsDeliveredOnceScanned`() {
        // `nextOrdinal` starts at 1, so a source with no history has a frontier of 0 and no dead
        // letters: `0 == 1 - 1`. That must read as delivered, not as an off-by-one failure.
        assertTrue(
            HistoryAckWalk.isDelivered(sourceExhausted = true, nextOrdinal = 1, ackedContiguousOrdinal = 0, deadLetters = 0)
        )
    }
}
