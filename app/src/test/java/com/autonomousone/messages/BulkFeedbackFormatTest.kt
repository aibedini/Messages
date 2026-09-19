package com.autonomousone.messages

import com.autonomousone.messages.repository.BulkFailure
import com.autonomousone.messages.repository.BulkFeedback
import com.autonomousone.messages.repository.BulkResult
import com.autonomousone.messages.ui.selection.formatBulkFeedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FEATURE 10 — partial-failure reporting.
 *
 * The one behaviour that must never regress: an action that could not apply to
 * every selected item can NEVER be presented as plain success. The UI is told
 * "12 updated, 2 could not be updated" — not "done".
 */
class BulkFeedbackFormatTest {

    private companion object {
        const val SUCCESS = "%1\$d updated"
        const val PARTIAL = "%1\$d updated, %2\$d could not be updated"
        const val FAILURE = "%1\$d could not be updated"
    }

    private fun text(succeeded: Int, failed: Int): String =
        formatBulkFeedback(SUCCESS, PARTIAL, FAILURE, succeeded, failed)

    @Test
    fun `a fully applied action uses the success template`() {
        assertEquals("12 updated", text(succeeded = 12, failed = 0))
    }

    @Test
    fun `a partial result reports BOTH the applied and the failed count`() {
        val message = text(succeeded = 12, failed = 2)
        assertEquals("12 updated, 2 could not be updated", message)
        assertFalse("a partial result must not read as plain success", message == text(12, 0))
        assertTrue(message.contains("could not be updated"))
    }

    @Test
    fun `a wholly failed action reports only the failure count`() {
        assertEquals("3 could not be updated", text(succeeded = 0, failed = 3))
        assertFalse(text(0, 3) == text(3, 0))
    }

    @Test
    fun `result bookkeeping never claims success when anything failed`() {
        val partial = BulkResult(
            requested = 14,
            succeeded = 12,
            failed = listOf(
                BulkFailure(threadId = 3L, reason = "provider"),
                BulkFailure(threadId = 4L, reason = "provider")
            )
        )

        assertFalse(partial.isFullSuccess)
        assertTrue(partial.isPartial)
        assertEquals(2, partial.failedCount)
        assertEquals(setOf(3L, 4L), partial.failedThreadIds())

        val feedback = BulkFeedback.from(partial)
        assertEquals(12, feedback.succeeded)
        assertEquals(2, feedback.failed)
        assertFalse(feedback.isFullSuccess)
    }

    @Test
    fun `a wholly failed result is a complete failure, never a success`() {
        val failure = BulkResult(
            requested = 3,
            succeeded = 0,
            failed = listOf(BulkFailure(threadId = 1L, reason = "room"))
        )

        assertTrue(failure.isCompleteFailure)
        assertFalse(failure.isFullSuccess)
        assertTrue(BulkFeedback.from(failure).isCompleteFailure)
    }

    @Test
    fun `an empty selection produces an empty result that is not a success claim`() {
        val empty = BulkResult.empty()

        assertTrue(empty.isEmpty)
        assertFalse(empty.isFullSuccess)
        assertEquals(0, empty.requested)
        assertTrue(empty.failed.isEmpty())
    }

    @Test
    fun `a fully applied result is the only success`() {
        val success = BulkResult.success(requested = 5)

        assertTrue(success.isFullSuccess)
        assertFalse(success.isCompleteFailure)
        assertFalse(success.isPartial)
        assertEquals(5, BulkFeedback.from(success).succeeded)
    }
}
