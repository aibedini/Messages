package com.autonomousone.messages

import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.repository.ThreadSnippet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Regression tests for the "list and chat disagree" bug (the S3-1560 case).
 *
 * Observed: the conversation showed an outgoing message at 16:25 while the Home
 * list row for the same thread still showed an incoming snippet from 14:01 and
 * sorted at that older position — the Threads table row was never updated for
 * the outgoing send.
 */
class ThreadSnippetTest {

    private fun row(date: Long, body: String, type: Int = 1, unread: Boolean = false) =
        Sms(id = 1560, threadId = 1560, sender = "+989124066822", message = body, date = date, unread = unread, type = type)

    @Test
    fun `newer outgoing message replaces a stale incoming snippet`() {
        val stale = row(date = 14_01_000, body = "٩٩٩٥", type = 1, unread = false)
        val newest = row(date = 16_25_000, body = "تمدید شد", type = 2)

        val fixed = ThreadSnippet.reconcile(stale, newest)

        assertEquals("تمدید شد", fixed.message)
        assertEquals(16_25_000L, fixed.date)
        assertEquals(2, fixed.type)
    }

    @Test
    fun `own outgoing message clears the unread badge`() {
        val staleUnread = row(date = 100, body = "incoming", type = 1, unread = true)
        val myReply = row(date = 200, body = "my reply", type = 2)

        assertFalse(ThreadSnippet.reconcile(staleUnread, myReply).unread)
    }

    @Test
    fun `an incoming message does not silently mark the thread read`() {
        val unreadRow = row(date = 100, body = "first", type = 1, unread = true)
        val newerIncoming = row(date = 300, body = "second", type = 1, unread = true)

        assertEquals(true, ThreadSnippet.reconcile(unreadRow, newerIncoming).unread)
    }

    @Test
    fun `an older or equal message never overwrites the row`() {
        val current = row(date = 500, body = "current")
        assertSame(current, ThreadSnippet.reconcile(current, row(date = 500, body = "same age")))
        assertSame(current, ThreadSnippet.reconcile(current, row(date = 200, body = "older")))
        assertSame(current, ThreadSnippet.reconcile(current, null))
    }

    @Test
    fun `reconcileAll only touches threads it has newer data for`() {
        val rows = listOf(
            Sms(id = 1, threadId = 1, sender = "a", message = "old-a", date = 100, unread = false, type = 1),
            Sms(id = 2, threadId = 2, sender = "b", message = "keep-b", date = 100, unread = false, type = 1)
        )
        val newest = mapOf(
            1L to Sms(id = 9, threadId = 1, sender = "a", message = "new-a", date = 900, unread = false, type = 2)
        )

        val out = ThreadSnippet.reconcileAll(rows, newest)

        assertEquals(listOf("new-a", "keep-b"), out.map { it.message })
        assertEquals(listOf(900L, 100L), out.map { it.date })
    }
}
