package com.autonomousone.messages.messaging

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The read authority, pinned.
 *
 * Production showed a newly received SMS marked READ although the user never opened that
 * conversation: the read path trusted `currentPhone`, a ViewModel field that survives navigation and
 * backgrounding, and the visibility tracker was driven by *composition and data loading* rather than
 * by the screen's lifecycle. These tests state the rule that replaced it: a conversation may only be
 * read while ITS screen is RESUMED and the app is in the foreground.
 *
 * NOTE: the whole point of this bug is that the old tests passed; the assertions below fail against
 * the old behaviour by construction (they require visibility to be explicitly granted by a RESUMED
 * transition, which the old tracker never required).
 */
class VisibleConversationTrackerTest {

    @Before
    fun setUp() = VisibleConversationTracker.resetForTest()

    @After
    fun tearDown() = VisibleConversationTracker.resetForTest()

    // ── the production regression ────────────────────────────────────────────

    @Test
    fun `a conversation that was merely opened earlier is NOT visible`() {
        // Co5-p91 scenario: the ViewModel remembers the thread, the screen is gone.
        VisibleConversationTracker.onScreenResumed(threadId = 91L)
        VisibleConversationTracker.onScreenPaused(threadId = 91L)

        assertFalse(VisibleConversationTracker.isVisible(91L))
        assertNull(VisibleConversationTracker.identity())
    }

    @Test
    fun `a surviving view model cannot grant visibility by loading data`() {
        // The deprecated hooks are the old ViewModel call sites: they must not make a thread visible.
        @Suppress("DEPRECATION")
        VisibleConversationTracker.onOpened(91L)

        assertFalse("loading a conversation is not looking at it", VisibleConversationTracker.isVisible(91L))
    }

    @Test
    fun `the tracker starts empty after process recreation`() {
        VisibleConversationTracker.resetForTest()

        assertNull(VisibleConversationTracker.identity())
        assertFalse(VisibleConversationTracker.isVisible(91L))
    }

    // ── the four allowed states ──────────────────────────────────────────────

    @Test
    fun `a RESUMED conversation is visible`() {
        VisibleConversationTracker.onScreenResumed(threadId = 7L, normalizedAddress = "+989120000007")

        assertTrue(VisibleConversationTracker.isVisible(7L))
        val identity = VisibleConversationTracker.identity()
        assertNotNull(identity)
        assertEquals(7L, identity!!.threadId)
        assertEquals("+989120000007", identity.normalizedAddress)
    }

    @Test
    fun `home visible means nothing is visible`() {
        VisibleConversationTracker.onScreenResumed(7L)
        VisibleConversationTracker.onScreenPaused(7L) // navigated back to Home

        assertNull(VisibleConversationTracker.visibleThreadId.value)
        assertFalse(VisibleConversationTracker.isVisible(7L))
    }

    @Test
    fun `a conversation switch leaves only the new conversation visible`() {
        VisibleConversationTracker.onScreenResumed(7L)
        VisibleConversationTracker.onScreenPaused(7L)
        VisibleConversationTracker.onScreenResumed(8L)

        assertTrue(VisibleConversationTracker.isVisible(8L))
        assertFalse("A must not stay visible after A -> B", VisibleConversationTracker.isVisible(7L))
    }

    @Test
    fun `a fast A to B transition cannot let A's teardown hide B`() {
        VisibleConversationTracker.onScreenResumed(7L)
        VisibleConversationTracker.onScreenResumed(8L)
        // A's dispose arrives late, after B is already on screen.
        VisibleConversationTracker.onScreenPaused(7L)

        assertTrue("B is still on screen", VisibleConversationTracker.isVisible(8L))
    }

    @Test
    fun `backgrounding the app clears visibility`() {
        VisibleConversationTracker.onScreenResumed(7L)
        VisibleConversationTracker.onAppBackgrounded()

        assertFalse(VisibleConversationTracker.isVisible(7L))
        assertNull(VisibleConversationTracker.identity())
    }

    // ── identity rules ───────────────────────────────────────────────────────

    @Test
    fun `an unknown thread id never matches anything`() {
        VisibleConversationTracker.onScreenResumed(7L)

        for (candidate in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertFalse("thread $candidate must never be visible", VisibleConversationTracker.isVisible(candidate))
        }
    }

    @Test
    fun `a different thread is never visible just because one is open`() {
        VisibleConversationTracker.onScreenResumed(91L)

        // The bank short-code conversation, exactly the one that stayed unread in production.
        assertFalse(VisibleConversationTracker.isVisible(92L))
    }

    @Test
    fun `address fallback only applies while nothing else is visible`() {
        VisibleConversationTracker.onScreenResumed(7L, "+989120000007")

        assertFalse(
            "a known thread id must not fall back to address matching",
            VisibleConversationTracker.isVisibleForAddress("+989120000009")
        )
        assertFalse(
            "the open thread's OWN address is also matched by thread id, not by string",
            VisibleConversationTracker.isVisibleForAddress("+989120000007")
        )
    }

    @Test
    fun `the lifecycle generation advances on every transition`() {
        VisibleConversationTracker.onScreenResumed(7L)
        val first = VisibleConversationTracker.identity()!!.lifecycleGeneration
        VisibleConversationTracker.onScreenPaused(7L)
        VisibleConversationTracker.onScreenResumed(7L)
        val second = VisibleConversationTracker.identity()!!.lifecycleGeneration

        assertTrue("a stale reader must be able to tell generations apart", second > first)
    }

    @Test
    fun `read causes and skip reasons are stable, distinct vocabularies`() {
        val causes = ReadCause.entries.map { it.name }
        val skips = ReadSkipReason.entries.map { it.name }

        assertEquals(causes.size, causes.toSet().size)
        assertEquals(skips.size, skips.toSet().size)
        assertTrue(causes.containsAll(
            listOf("USER_OPEN_RESUMED", "USER_MARK_READ", "REMOTE_MARK_READ", "INCOMING_WHILE_VISIBLE")
        ))
        assertTrue(skips.containsAll(
            listOf("SCREEN_NOT_VISIBLE", "STALE_GENERATION", "CONVERSATION_MISMATCH", "UNKNOWN_THREAD")
        ))
    }

    @Test
    fun `the audit token is short and does not contain the thread id`() {
        val token = ConversationReadAudit.token(91L)

        assertTrue(token.startsWith("t_"))
        assertEquals(10, token.length)
        assertFalse("a diagnostic line must not carry the raw thread id", token.contains("91"))
    }
}
