package com.autonomousone.messages

import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.repository.ThreadMerge
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for the live-consistency bug (v2.1.x):
 *  - conversation refresh used to REPLACE the windowed list with the full
 *    thread (content changed shape on every open/close);
 *  - loadOlder could inject duplicates after a refresh;
 *  - pull-to-refresh merges must be idempotent.
 */
class ThreadMergeTest {

    private fun sms(id: Long, date: Long, body: String = "m$id") =
        Sms(id = id, threadId = 7L, sender = "+98912", message = body, date = date, unread = false, type = 1)

    @Test
    fun `mergeTail appends unseen newer rows sorted by date`() {
        val existing = listOf(sms(1, 100), sms(2, 200))
        val newer = listOf(sms(3, 300), sms(4, 250))
        val merged = ThreadMerge.mergeTail(existing, newer)
        assertEquals(listOf(1L, 2L, 4L, 3L), merged.map { it.id })
    }

    @Test
    fun `mergeTail is idempotent - re-delivered rows are dropped`() {
        val existing = listOf(sms(1, 100), sms(2, 200))
        val newer = listOf(sms(2, 200), sms(3, 300))
        val merged = ThreadMerge.mergeTail(existing, newer)
        assertEquals(listOf(1L, 2L, 3L), merged.map { it.id })
        // running it twice must not grow the list
        assertEquals(merged, ThreadMerge.mergeTail(merged, newer))
    }

    @Test
    fun `mergeTail keeps optimistic synthetic-id rows visible`() {
        // optimistic send uses System.currentTimeMillis() as a synthetic id
        val existing = listOf(sms(1, 100), sms(999, 220, body = "hello"))
        val confirmed = listOf(sms(1, 100), sms(999, 220, body = "hello"), sms(5, 300))
        val merged = ThreadMerge.mergeTail(existing, confirmed)
        assertEquals(listOf(1L, 999L, 5L), merged.map { it.id })
    }

    @Test
    fun `mergeTail refreshes status when provider row id and date are unchanged`() {
        val pending = sms(9, 220, body = "hello").copy(type = 2, status = 32)
        val delivered = pending.copy(status = 0, dateSent = 300)

        val merged = ThreadMerge.mergeTail(listOf(pending), listOf(delivered))

        assertEquals(0, merged.single().status)
        assertEquals(300L, merged.single().dateSent)
    }

    @Test
    fun `prependOlder drops rows already on screen`() {
        val existing = listOf(sms(40, 400), sms(41, 410))
        val olderPage = listOf(sms(38, 380), sms(39, 390), sms(40, 400))
        val result = ThreadMerge.prependOlder(existing, olderPage)
        assertEquals(listOf(38L, 39L, 40L, 41L), result.map { it.id })
    }

    @Test
    fun `tailWindow keeps the newest n messages`() {
        val all = (1L..50L).map { sms(it, it * 10) }
        val window = ThreadMerge.tailWindow(all, 40)
        assertEquals(40, window.size)
        assertEquals(11L, window.first().id)
        assertEquals(50L, window.last().id)
    }
}
