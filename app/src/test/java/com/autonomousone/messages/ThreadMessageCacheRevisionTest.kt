package com.autonomousone.messages

import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.repository.ThreadMessageCache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure-JVM tests for the per-thread cache revision (PHASE 12).
 *
 * Invariants:
 *  - a known thread mutation invalidates ONLY that thread (A does not touch
 *    B/C/D);
 *  - a global/unknown recovery invalidates everything;
 *  - an entry stamped with an older epoch/revision is never served as fresh.
 */
class ThreadMessageCacheRevisionTest {

    private fun sms(id: Long, date: Long) =
        Sms(
            id = id,
            threadId = id,
            sender = "+98912",
            message = "m$id",
            date = date,
            unread = false,
            type = 1
        )

    @Before
    fun setUp() = ThreadMessageCache.resetForTest()

    @After
    fun tearDown() = ThreadMessageCache.resetForTest()

    @Test
    fun `invalidating thread A leaves B C D fresh`() {
        ThreadMessageCache.put(1L, "a", listOf(sms(1, 1_000)))
        ThreadMessageCache.put(2L, "b", listOf(sms(2, 2_000)))
        ThreadMessageCache.put(3L, "c", listOf(sms(3, 3_000)))
        ThreadMessageCache.put(4L, "d", listOf(sms(4, 4_000)))

        ThreadMessageCache.invalidateThread(1L)

        assertNull(ThreadMessageCache.getIfFresh(1L, "a"))
        assertNotNull(ThreadMessageCache.getIfFresh(2L, "b"))
        assertNotNull(ThreadMessageCache.getIfFresh(3L, "c"))
        assertNotNull(ThreadMessageCache.getIfFresh(4L, "d"))
    }

    @Test
    fun `global epoch invalidates every thread`() {
        ThreadMessageCache.put(1L, "a", listOf(sms(1, 1_000)))
        ThreadMessageCache.put(2L, "b", listOf(sms(2, 2_000)))

        ThreadMessageCache.invalidateAll()

        assertNull(ThreadMessageCache.getIfFresh(1L, "a"))
        assertNull(ThreadMessageCache.getIfFresh(2L, "b"))
    }

    @Test
    fun `an entry from an older epoch is never served as fresh`() {
        ThreadMessageCache.put(5L, "e", listOf(sms(5, 5_000)))

        val before = ThreadMessageCache.getStale(5L, "e")
        assertNotNull(before)
        assertFalse(before!!.second)

        ThreadMessageCache.invalidateAll()

        assertNull(ThreadMessageCache.getIfFresh(5L, "e"))
        val after = ThreadMessageCache.getStale(5L, "e")
        assertNotNull(after)
        assertTrue(after!!.second)
        // Stale entries are still paintable — invalidation is not deletion.
        assertEquals(listOf(5L), after.first.map { it.id })
        assertNull(ThreadMessageCache.getIfFresh(5L, "e"))
    }

    @Test
    fun `append keeps its own thread fresh without touching others`() {
        ThreadMessageCache.put(1L, "a", listOf(sms(1, 1_000)))
        ThreadMessageCache.put(2L, "b", listOf(sms(2, 2_000)))

        ThreadMessageCache.append(1L, "a", sms(11, 1_100))

        val a = ThreadMessageCache.getIfFresh(1L, "a")
        assertNotNull(a)
        assertEquals(listOf(1L, 11L), a!!.map { it.id })
        assertNotNull(ThreadMessageCache.getIfFresh(2L, "b"))
    }

    @Test
    fun `thread revision is keyed by thread id not the phone cache key`() {
        ThreadMessageCache.put(7L, "+98912", listOf(sms(7, 7_000)))

        // A provider burst carries a thread id, not the phone key the entry
        // happened to be stored under.
        ThreadMessageCache.invalidateThread(7L)

        assertNull(ThreadMessageCache.getIfFresh(7L, "+98912"))
    }

    @Test
    fun `phone-only entries use the phone revision key`() {
        ThreadMessageCache.put(0L, "+98912", listOf(sms(9, 9_000)))

        assertNotNull(ThreadMessageCache.getIfFresh(0L, "+98912"))

        ThreadMessageCache.invalidateThread(0L, "+98912")

        assertNull(ThreadMessageCache.getIfFresh(0L, "+98912"))
        // A different phone-only conversation stays valid.
        ThreadMessageCache.put(0L, "+98913", listOf(sms(10, 10_000)))
        assertNotNull(ThreadMessageCache.getIfFresh(0L, "+98913"))
    }
}
