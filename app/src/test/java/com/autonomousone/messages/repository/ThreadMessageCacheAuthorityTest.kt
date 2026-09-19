package com.autonomousone.messages.repository

import com.autonomousone.messages.model.Sms
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The CACHE-vs-AUTHORITY contract (v3.4.3, P0-B).
 *
 * Production bug: Home's snippet showed the newest message, but opening that
 * conversation did not. The sequence was
 *
 *   Room tail emits [A, B, C]            (authoritative, newest = C)
 *   slower cache read publishes [A, B]   (stale)
 *   messages.clear(); addAll([A, B])     (C disappears)
 *
 * and killing/restarting the app made C appear again, because the in-memory cache was
 * gone and Room painted [A, B, C].
 *
 * The fix has two halves, and both are pinned here at the cache level (the cache is
 * Android-free by design so this contract is testable on the JVM):
 *
 *  1. [ThreadMessageCache.authorityRevision] lets the ViewModel DISCARD a cache read
 *     that started before an authoritative paint.
 *  2. An incoming message invalidates exactly its own thread, so the next open cannot
 *     paint a window that predates it.
 */
class ThreadMessageCacheAuthorityTest {

    private val thread = 10L
    private val phone = "+989121234567"

    @Before
    fun setUp() = ThreadMessageCache.resetForTest()

    @After
    fun tearDown() = ThreadMessageCache.resetForTest()

    private fun sms(id: Long, date: Long, body: String = "m$id") = Sms(
        id = id,
        threadId = thread,
        sender = phone,
        message = body,
        date = date,
        unread = false,
        type = 1,
        status = -1,
        dateSent = 0
    )

    // ── The authority clock ─────────────────────────────────────────────────

    @Test
    fun `an authoritative paint advances the authority revision`() {
        val before = ThreadMessageCache.authorityRevision

        ThreadMessageCache.markAuthoritativePaint()

        assertEquals(before + 1, ThreadMessageCache.authorityRevision)
    }

    @Test
    fun `the authority revision is monotonic`() {
        val start = ThreadMessageCache.authorityRevision
        repeat(3) { ThreadMessageCache.markAuthoritativePaint() }

        assertEquals(start + 3, ThreadMessageCache.authorityRevision)
    }

    /**
     * The exact production sequence, expressed as the decision the ViewModel makes.
     */
    @Test
    fun `a cache read that started before an authoritative paint is discarded`() {
        // 1. The cache holds the older window [A, B].
        ThreadMessageCache.put(thread, phone, listOf(sms(1, 100), sms(2, 200)))

        // 2. The ViewModel snapshots the authority clock BEFORE reading the cache.
        val cacheReadRevision = ThreadMessageCache.authorityRevision
        val stale = ThreadMessageCache.getStale(thread, phone)
        assertNotNull(stale)

        // 3. Room's authoritative tail arrives with [A, B, C] while that read is
        //    still in flight — this is the emission the user can already see.
        ThreadMessageCache.markAuthoritativePaint()

        // 4. The cache result must now be DISCARDED, so C cannot disappear.
        val authorityMoved = ThreadMessageCache.authorityRevision != cacheReadRevision
        assertTrue(
            "a cache read that raced an authoritative paint must be discarded",
            authorityMoved
        )
    }

    @Test
    fun `a cache read with no intervening authoritative paint is published`() {
        ThreadMessageCache.put(thread, phone, listOf(sms(1, 100), sms(2, 200)))

        val cacheReadRevision = ThreadMessageCache.authorityRevision
        val stale = ThreadMessageCache.getStale(thread, phone)
        assertNotNull(stale)

        // Nothing authoritative happened in between: the cache still paints first.
        assertEquals(cacheReadRevision, ThreadMessageCache.authorityRevision)
        assertFalse(ThreadMessageCache.authorityRevision != cacheReadRevision)
    }

    @Test
    fun `the cache still serves the window it was given`() {
        ThreadMessageCache.put(thread, phone, listOf(sms(1, 100), sms(2, 200)))

        val (messages, _) = ThreadMessageCache.getStale(thread, phone)!!

        assertEquals(listOf(1L, 2L), messages.map { it.id })
    }

    // ── Ingest invalidation (the second half of the fix) ────────────────────

    @Test
    fun `a new message makes its own thread's cache stale`() {
        ThreadMessageCache.put(thread, phone, listOf(sms(1, 100), sms(2, 200)))
        assertFalse("freshly stored entry starts fresh", ThreadMessageCache.getStale(thread, phone)!!.second)

        // Exactly what IncomingMessageDispatcher does after the Room upsert commits.
        ThreadMessageCache.invalidateThread(thread)

        assertTrue(
            "the next conversation open must not paint a window that predates the new message",
            ThreadMessageCache.getStale(thread, phone)!!.second
        )
    }

    @Test
    fun `invalidating one thread leaves other threads fresh`() {
        val other = 20L
        ThreadMessageCache.put(thread, phone, listOf(sms(1, 100)))
        ThreadMessageCache.put(other, "+989120000000", listOf(sms(3, 300)))

        ThreadMessageCache.invalidateThread(thread)

        assertTrue(ThreadMessageCache.getStale(thread, phone)!!.second)
        assertFalse(
            "activity in one conversation must not invalidate another",
            ThreadMessageCache.getStale(other, "+989120000000")!!.second
        )
    }

    @Test
    fun `the global fallback invalidates every thread`() {
        val other = 20L
        ThreadMessageCache.put(thread, phone, listOf(sms(1, 100)))
        ThreadMessageCache.put(other, "+989120000000", listOf(sms(3, 300)))

        // Used only when a message has no resolvable thread id.
        ThreadMessageCache.invalidateAll()

        assertTrue(ThreadMessageCache.getStale(thread, phone)!!.second)
        assertTrue(ThreadMessageCache.getStale(other, "+989120000000")!!.second)
    }

    @Test
    fun `the cache never drops the newest row it was given`() {
        // A 3-row window stays a 3-row window; the guard's job is to stop an OLDER
        // window from replacing it, not to trim the newest message.
        ThreadMessageCache.put(thread, phone, listOf(sms(1, 100), sms(2, 200), sms(3, 300)))

        val (messages, _) = ThreadMessageCache.getStale(thread, phone)!!

        assertEquals(3, messages.size)
        assertEquals(
            "the newest canonical row must still be present",
            3L,
            messages.maxByOrNull { it.date }?.id
        )
    }
}
