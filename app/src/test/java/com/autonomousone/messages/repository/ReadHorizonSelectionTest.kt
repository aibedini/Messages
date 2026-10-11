package com.autonomousone.messages.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The READ HORIZON: a mark-read must not swallow a message that arrived after the user's decision.
 *
 * `UPDATE ... WHERE thread = ? AND read = 0` is one statement, but the world does not stop for it: an
 * SMS landing between the decision and the write would be marked read although nobody saw it — the
 * same family of bug as the visibility one, one layer down.
 *
 * NOTE: written and run as part of the targeted verification for this change (pure, no Android).
 */
class ReadHorizonSelectionTest {

    private val threadColumn = "thread_id"
    private val readColumn = "read"
    private val idColumn = "_id"

    @Test
    fun `with a horizon the update is bounded to rows that already existed`() {
        val (selection, args) = readHorizonSelection(threadColumn, readColumn, idColumn, threadId = 5L, horizonId = 100L)

        assertEquals("thread_id = ? AND read = 0 AND _id <= ?", selection)
        assertEquals(listOf("5", "100"), args.toList())
    }

    @Test
    fun `a message arriving after the decision is outside the horizon`() {
        val (selection, _) = readHorizonSelection(threadColumn, readColumn, idColumn, 5L, 100L)

        // Row 101 does not satisfy `_id <= 100`, which is the whole point.
        assertTrue(selection.contains("${idColumn} <= ?"))
        assertFalse("the unbounded form would swallow it", selection == "thread_id = ? AND read = 0")
    }

    @Test
    fun `without a horizon an unbounded read is REFUSED by default`() {
        // This is the P0 rule. A read with no established boundary must not fall back to the whole
        // thread, because the message it would swallow is one that arrived after the user left the
        // conversation — nobody saw it, so marking it read is the same class of bug as the visibility
        // one.
        val (selection, args) = readHorizonSelection(threadColumn, readColumn, idColumn, 5L, null)

        assertEquals("1 = 0", selection)
        assertTrue("no row may be touched", args.isEmpty())
    }

    @Test
    fun `an explicit user or remote action may still read the whole thread`() {
        // "Mark this conversation read" is a decision about the CONVERSATION, not about a snapshot of
        // it, so it keeps the unbounded form — but only when the caller says so explicitly, never as a
        // silent fallback.
        val (selection, args) =
            readHorizonSelection(threadColumn, readColumn, idColumn, 5L, null, allowUnbounded = true)

        assertEquals("thread_id = ? AND read = 0", selection)
        assertEquals(listOf("5"), args.toList())
    }

    @Test
    fun `a non-positive horizon never authorises an unbounded read`() {
        for (bad in listOf(0L, -1L, Long.MIN_VALUE)) {
            val (selection, _) = readHorizonSelection(threadColumn, readColumn, idColumn, 5L, bad)
            assertEquals(
                "horizon $bad must not become an unbounded read",
                "1 = 0",
                selection
            )
        }
    }

    @Test
    fun `the read filter is always part of a bounded selection`() {
        // Marking an already-read row again is harmless but must never be the reason a row is touched.
        // The refused form touches nothing at all, so the filter is only meaningful when a bound exists.
        val (selection, _) = readHorizonSelection(threadColumn, readColumn, idColumn, 5L, 10L)
        assertTrue(selection.contains("$readColumn = 0"))
        assertEquals("1 = 0", readHorizonSelection(threadColumn, readColumn, idColumn, 5L, null).first)
    }
}
