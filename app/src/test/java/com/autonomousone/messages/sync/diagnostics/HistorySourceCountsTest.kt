package com.autonomousone.messages.sync.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The per-source history arithmetic, and the specific lie it used to tell.
 *
 * The collector reported `acked = ackedContiguousOrdinal` and `pending = produced - acked`. The
 * watermark is a contiguous frontier, so as soon as one history event failed permanently the
 * frontier froze and the report said `acked = 2, pending = 4000` for a source with 4,000 rows
 * delivered and exactly one permanent failure — inventing 4,000 pending events and leaving `failed`
 * unmeasured even though the count existed.
 *
 * These tests pin the invariant and the failure visibility, both of which the old formula violated.
 */
class HistorySourceCountsTest {

    @Test
    fun `a permanent failure is reported as failed rather than as pending work`() {
        // The reported defect, in the exact shape it appeared: one dead letter during a 4,000-row
        // history. The old formula produced acked=2 (the frozen watermark) and pending=4000.
        val counts = historySourceCounts(produced = 4_002, ackedCount = 4_001, deadLetters = 1)

        assertEquals("every acknowledged row is counted", 4_001L, counts.acked)
        assertEquals("exactly one row failed", 1L, counts.failed)
        assertEquals("nothing is left to do", 0L, counts.pending)
    }

    @Test
    fun `the accounting invariant holds whenever all three inputs are measured`() {
        val cases = listOf(
            Triple(4_002L, 4_001L, 1L),   // one permanent failure at the end
            Triple(1_000L, 400L, 3L),     // work in progress, a few failures
            Triple(0L, 0L, 0L),           // nothing produced yet
            Triple(500L, 500L, 0L),       // fully delivered
            Triple(500L, 100L, 0L)        // mostly pending
        )
        cases.forEach { (produced, acked, failed) ->
            val counts = historySourceCounts(produced, acked, failed)
            assertEquals(
                "produced must equal acked + pending + failed for ($produced, $acked, $failed)",
                produced,
                (counts.acked ?: 0L) + (counts.pending ?: 0L) + (counts.failed ?: 0L)
            )
        }
    }

    @Test
    fun `an unmeasured input yields unmeasured outputs rather than zeroes`() {
        // "We did not look" must never render as "nothing is wrong" — the rule the whole model
        // follows, and the one a `?: 0` would quietly break at a call site.
        assertNull(historySourceCounts(null, 400L, 0L).pending)
        assertNull(historySourceCounts(4_002L, null, 0L).pending)
        assertNull(historySourceCounts(4_002L, 4_001L, null).pending)

        // The parts that ARE measured are still reported, so a partial read is still useful — and
        // the part that is NOT measured stays null even when its neighbours are known.
        assertEquals(400L, historySourceCounts(null, 400L, 0L).acked)
        assertEquals(0L, historySourceCounts(null, 400L, 0L).failed)
        assertNull(historySourceCounts(4_002L, null, 3L).acked)
        assertEquals(3L, historySourceCounts(4_002L, null, 3L).failed)
    }

    @Test
    fun `disagreeing inputs cannot produce negative pending work`() {
        // acked + failed exceeding produced means the checkpoint and the outbox disagree. Reporting 0
        // is the safe direction: inventing pending work is the defect being fixed, and a negative
        // count would render as nonsense.
        val counts = historySourceCounts(produced = 10, ackedCount = 9, deadLetters = 5)
        assertEquals(0L, counts.pending)
    }

    @Test
    fun `a negative input from a corrupt row is not propagated as negative work`() {
        val counts = historySourceCounts(produced = 10, ackedCount = -1, deadLetters = -1)
        assertEquals(0L, counts.acked)
        assertEquals(0L, counts.failed)
        assertEquals(10L, counts.pending)
    }
}
