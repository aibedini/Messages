package com.autonomousone.messages

import com.autonomousone.messages.data.ConversationReadProjection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The conversation unread counter after a read.
 *
 * The read path used to end with an unconditional `unreadCount = 0`. Correct for an unbounded read,
 * wrong for a bounded one: a message that arrived after the user left stays unread in `messages`, so a
 * zeroed counter makes the conversation row disagree with its own messages and the badge lies until the
 * next reconcile.
 */
class ConversationReadProjectionTest {

    private fun projection(bounded: Boolean, count: Int): Int = runBlocking {
        ConversationReadProjection.remainingUnreadAfter(bounded) { count }
    }

    @Test
    fun `an unbounded read reads the whole thread so the count is genuinely zero`() {
        assertEquals(0, projection(bounded = false, count = 7))
    }

    @Test
    fun `an unbounded read never asks for a count it does not need`() {
        var asked = false
        val result = runBlocking {
            ConversationReadProjection.remainingUnreadAfter(bounded = false) {
                asked = true
                3
            }
        }

        assertFalse("no indexed count should run on the common path", asked)
        assertEquals(0, result)
    }

    @Test
    fun `a bounded read keeps the messages that are still unread`() {
        assertEquals(2, projection(bounded = true, count = 2))
    }

    @Test
    fun `a bounded read that covered everything still resolves to zero`() {
        // The horizon was the newest message, so nothing is left. Zero here is the TRUTH, not a
        // blanket reset — the count proves it.
        assertEquals(0, projection(bounded = true, count = 0))
    }

    @Test
    fun `a failed count clamps to zero rather than rendering a negative badge`() {
        // A DAO hiccup must not become "-1 unread". Zero is the safe direction for the COUNTER, because
        // the messages themselves still carry the truth and the next reconcile repairs the projection.
        assertEquals(0, projection(bounded = true, count = -1))
        assertEquals(0, projection(bounded = true, count = Int.MIN_VALUE))
    }

    @Test
    fun `a new message beyond the horizon keeps the conversation unread`() {
        // The scenario this exists for: the user opened the chat, left, and a new SMS arrived. The read
        // is bounded, so the projection must still report unread.
        val remaining = projection(bounded = true, count = 1)

        assertTrue("the newer message must stay unread", remaining > 0)
    }

    @Test
    fun `the bounded answer is derived from the count, never hardcoded`() {
        // Two different thread states must produce two different counters; a constant would pass a
        // single-value assertion by accident.
        val a = projection(bounded = true, count = 1)
        val b = projection(bounded = true, count = 5)

        assertEquals(1, a)
        assertEquals(5, b)
        assertTrue(a != b)
    }
}
