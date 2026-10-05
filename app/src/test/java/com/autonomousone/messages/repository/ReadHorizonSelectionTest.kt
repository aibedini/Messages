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
    fun `without a horizon it degrades to the previous thread-scoped behaviour`() {
        val (selection, args) = readHorizonSelection(threadColumn, readColumn, idColumn, 5L, null)

        assertEquals("thread_id = ? AND read = 0", selection)
        assertEquals(listOf("5"), args.toList())
    }

    @Test
    fun `a non-positive horizon is treated as no horizon, never as a zero bound`() {
        for (bad in listOf(0L, -1L, Long.MIN_VALUE)) {
            val (selection, _) = readHorizonSelection(threadColumn, readColumn, idColumn, 5L, bad)
            assertEquals(
                "horizon $bad must not bound the update to nothing",
                "thread_id = ? AND read = 0",
                selection
            )
        }
    }

    @Test
    fun `the read filter is always part of the selection`() {
        // Marking an already-read row again is harmless but must never be the reason a row is touched.
        for (horizon in listOf(null, 10L)) {
            val (selection, _) = readHorizonSelection(threadColumn, readColumn, idColumn, 5L, horizon)
            assertTrue(selection.contains("$readColumn = 0"))
        }
    }
}
