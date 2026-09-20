package com.autonomousone.messages.repository

import com.autonomousone.messages.model.Sms
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v3.4.5 P0 — the initial-open authority contract.
 *
 * The device bug: Home showed a conversation and its latest snippet, but
 * opening it sometimes showed no history at all, or only ONE bubble at the
 * bottom. That single bubble is Home's `ConversationLaunchStore` snapshot — the
 * first-paint bridge — which means `ConversationViewModel.messages` was empty.
 * Closing and reopening the app "fixed" it, which is the signature of an
 * ordering race rather than missing data.
 *
 * Three separate defects produced it, and this class pins the fix for each:
 *
 *   A. the authority clock was GLOBAL, so a paint in conversation A discarded
 *      conversation B's cache ([ThreadMessageCacheAuthorityTest] pins the clock
 *      itself; `authorityFromThreadA_doesNotDiscardThreadB` there);
 *   B. an EMPTY Room tail claimed authority (`emptyRoomTail_doesNotMarkInitialAuthority`);
 *   C. a DISCARDED cache could still finish the load
 *      (`cacheFreshButNotPainted_mustNotSkipProvider`,
 *       `discardedCache_continuesProviderFallback`).
 *
 * …while the v3.4.3 protection stays intact
 * (`freshCache_roomNewerWins_cacheCannotOverwrite`).
 */
class ConversationOpenLoadTest {

    private val thread = 10L
    private val phone = "+989121234567"

    @Before
    fun setUp() = ThreadMessageCache.resetForTest()

    @After
    fun tearDown() = ThreadMessageCache.resetForTest()

    private fun sms(id: Long, date: Long, threadId: Long = thread) = Sms(
        id = id,
        threadId = threadId,
        sender = phone,
        message = "m$id",
        date = date,
        unread = false,
        type = 1,
        status = -1,
        dateSent = 0
    )

    private fun cached() = listOf(sms(1, 100), sms(2, 200))

    // ── FIX E / B: an empty Room emission must not blank the conversation ────

    @Test
    fun `freshCache_emptyRoomEmission_doesNotBlankConversation`() {
        ThreadMessageCache.put(thread, phone, cached())
        val load = ConversationOpenLoad(thread, phone)
        val stale = ThreadMessageCache.getStale(thread, phone)!!
        assertFalse("the cached entry must start fresh", stale.second)

        // Room's FIRST emission is [] — the shadow has not caught up yet.
        assertFalse(
            "an empty Room tail must not be published as a window",
            load.onRoomTail(0)
        )
        assertFalse("an empty Room tail must not claim authority", load.authorityMoved)
        assertFalse(load.roomPainted)

        // …so the ONLY usable source still gets to paint.
        assertTrue("the fresh cache must still be allowed to paint", load.mayPaintCache())
        load.onCachePainted(stale.first.size)

        assertTrue(load.hasUsableSource)
        assertTrue(
            "a painted fresh cache may finish the load",
            load.mayFinishInitialLoad(cacheFresh = true)
        )
        assertEquals(ConversationOpenLoad.Source.CACHE, load.source)
    }

    // ── FIX D: the original v3.4.3 regression stays fixed ───────────────────

    @Test
    fun `freshCache_roomNewerWins_cacheCannotOverwrite`() {
        // cache [A, B] ...
        ThreadMessageCache.put(thread, phone, cached())
        val load = ConversationOpenLoad(thread, phone)
        val stale = ThreadMessageCache.getStale(thread, phone)!!
        assertFalse(stale.second)

        // ... Room emits the NEWER [A, B, C] first. `mayFinishInitialLoad`
        // below must reflect that Room, not the cache, owns the window.
        assertTrue(load.onRoomTail(3))

        // The late cache read is discarded: Room's C cannot disappear.
        assertFalse(
            "a cache read that raced a same-conversation Room paint must be discarded",
            load.mayPaintCache()
        )
        assertTrue("the non-empty Room window is authoritative", load.authorityMoved)
        assertTrue(load.hasUsableSource)
        assertTrue(load.mayFinishInitialLoad(cacheFresh = true))
        assertEquals(ConversationOpenLoad.Source.ROOM, load.source)
    }

    // ── FIX F: a discarded cache must continue to the provider ──────────────

    @Test
    fun `discardedCache_continuesProviderFallback`() {
        ThreadMessageCache.put(thread, phone, cached())
        val load = ConversationOpenLoad(thread, phone)

        // Something authoritative for THIS conversation lands while the cache
        // read is in flight (another open of the same chat in the back stack).
        ThreadMessageCache.markAuthoritativePaint(thread, phone)

        assertTrue(load.authorityMoved)
        assertFalse(load.mayPaintCache())
        // THE v3.4.5 EARLY-RETURN FIX: a cache that was never painted proves
        // nothing, so a fresh one may not end the load either.
        assertFalse(load.mayFinishInitialLoad(cacheFresh = true))
        assertFalse(load.hasUsableSource)

        // …so the bounded provider page runs and owns the window.
        load.onProviderResult(3)

        assertTrue(load.providerAttempted)
        assertTrue(load.hasUsableSource)
        assertEquals(ConversationOpenLoad.Source.PROVIDER, load.source)
        assertEquals(3, load.paintedRows)
    }

    // ── FIX B: only a NON-EMPTY tail is authoritative ───────────────────────

    @Test
    fun `emptyRoomTail_doesNotMarkInitialAuthority`() {
        val load = ConversationOpenLoad(thread, phone)
        val before = ThreadMessageCache.authorityRevision(thread, phone)

        assertFalse(load.onRoomTail(0))

        assertEquals(
            "an empty emission must not move the authority clock",
            before,
            ThreadMessageCache.authorityRevision(thread, phone)
        )
        assertFalse(load.roomPainted)
        assertFalse(load.hasUsableSource)
        assertFalse(load.mayFinishInitialLoad(cacheFresh = true))
    }

    @Test
    fun `sameThreadNonEmptyRoom_marksAuthority`() {
        val load = ConversationOpenLoad(thread, phone)

        assertTrue(load.onRoomTail(4))

        assertTrue(load.roomPainted)
        assertTrue(load.authorityMoved)
        assertTrue(load.hasUsableSource)
        assertTrue(load.mayFinishInitialLoad(cacheFresh = false))
        assertEquals(4, load.paintedRows)
    }

    // ── FIX C: painted vs discarded cache ───────────────────────────────────

    @Test
    fun `cacheFreshAndPainted_maySkipProvider`() {
        ThreadMessageCache.put(thread, phone, cached())
        val load = ConversationOpenLoad(thread, phone)

        assertTrue(load.mayPaintCache())
        load.onCachePainted(2)

        assertTrue(load.mayFinishInitialLoad(cacheFresh = true))
        assertFalse("no provider attempt should be needed", load.providerAttempted)
    }

    @Test
    fun `cacheFreshButNotPainted_mustNotSkipProvider`() {
        ThreadMessageCache.put(thread, phone, cached())
        val load = ConversationOpenLoad(thread, phone)

        // "Fresh" is not the same as "painted": a discarded read leaves the
        // screen empty even though the entry never went stale.
        ThreadMessageCache.markAuthoritativePaint(thread, phone)

        assertFalse(load.cachePainted)
        assertFalse(
            "a fresh cache that was never painted must NOT end the initial load",
            load.mayFinishInitialLoad(cacheFresh = true)
        )
    }

    // ── FIX A: authority isolation across conversations ─────────────────────

    @Test
    fun `authorityFromThreadA_doesNotDiscardThreadB`() {
        val otherThread = 20L
        val otherPhone = "+989120000000"
        ThreadMessageCache.put(otherThread, otherPhone, listOf(sms(9, 900, otherThread)))

        val loadB = ConversationOpenLoad(otherThread, otherPhone)
        val staleB = ThreadMessageCache.getStale(otherThread, otherPhone)!!
        assertFalse(staleB.second)

        // Conversation A (still alive in the navigation back stack) paints.
        val loadA = ConversationOpenLoad(thread, phone)
        assertTrue(loadA.onRoomTail(2))

        assertFalse("B's clock must not have moved", loadB.authorityMoved)
        assertTrue("B's fresh cache must still paint", loadB.mayPaintCache())
        loadB.onCachePainted(staleB.first.size)
        assertTrue(loadB.mayFinishInitialLoad(cacheFresh = true))
        assertEquals(ConversationOpenLoad.Source.CACHE, loadB.source)
    }

    // ── Generation guard: rapid switches ────────────────────────────────────

    @Test
    fun `rapidlyOpenABC_noStaleLoadPaintsAnotherConversation`() {
        val generation = ConversationOpenGeneration()

        val genA = generation.next()
        val loadA = ConversationOpenLoad(101L, "+989110000001")
        val genB = generation.next()
        val loadB = ConversationOpenLoad(202L, "+989110000002")
        val genC = generation.next()
        val loadC = ConversationOpenLoad(303L, "+989110000003")

        assertFalse(generation.isCurrent(genA))
        assertFalse(generation.isCurrent(genB))
        assertTrue(generation.isCurrent(genC))

        // A's late Room tail lands now. It may claim authority for ITS OWN
        // conversation, but its generation is stale so the caller drops the
        // paint — and it must not touch C's open.
        assertTrue(loadA.onRoomTail(2))
        assertTrue(loadA.roomPainted)
        assertFalse("C's open is untouched by A's late paint", loadC.roomPainted)
        assertFalse(loadC.authorityMoved)
        assertTrue(loadC.mayPaintCache())
        assertFalse(loadB.cachePainted)
    }

    // ── FIX: process recreation must not depend on the in-memory cache ──────

    @Test
    fun `processRecreation_loadsWithoutInMemoryCache`() {
        // Simulate a cold start: the process-wide cache is empty.
        ThreadMessageCache.resetForTest()

        val load = ConversationOpenLoad(thread, phone)
        assertEquals(0L, load.capturedRevision)
        assertEquals(null, ThreadMessageCache.getStale(thread, phone))

        // Room's first emission is empty as well.
        assertFalse(load.onRoomTail(0))
        assertFalse(
            "with no cache and no Room window the load may NOT finish early",
            load.mayFinishInitialLoad(cacheFresh = true)
        )

        load.onProviderResult(5)

        assertTrue("the provider page is the usable source", load.hasUsableSource)
        assertEquals(ConversationOpenLoad.Source.PROVIDER, load.source)
        assertEquals(5, load.paintedRows)
    }

    // ── A failed provider page must be visible, not silently empty ──────────

    @Test
    fun `providerFailure_leavesNoUsableSource`() {
        val load = ConversationOpenLoad(thread, phone)

        load.onFailed()

        assertEquals(ConversationOpenLoad.Source.ERROR, load.source)
        assertFalse(load.hasUsableSource)
        assertEquals(0, load.paintedRows)
        assertFalse(load.mayFinishInitialLoad(cacheFresh = true))
    }
}
