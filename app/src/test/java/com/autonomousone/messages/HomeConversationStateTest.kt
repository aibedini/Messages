package com.autonomousone.messages

import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.viewmodel.HomeConversationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 8 — the Home optimistic-overlay merge rules.
 *
 * Home must have ONE durable state owner: after the Room bootstrap window is
 * ready, `ConversationDao.observeAll()` is authoritative and every realtime
 * event is layered on top as an optimistic override. These tests pin the merge
 * contract of [HomeConversationState] without Android, Compose, Room or a
 * coroutine dispatcher:
 *
 *  - an older Room emission can never overwrite a newer optimistic override;
 *  - an override is retired the moment Room catches up with it (no immortal
 *    overlay);
 *  - a brand-new conversation is visible from an exact override and reconciled
 *    exactly once when Room observes it;
 *  - pinned/archived membership survives an incoming update;
 *  - an immediate delete overlay removes the row until the durable delete lands.
 *
 * WRITTEN BUT NOT EXECUTED. Gradle is deliberately frozen for this branch.
 */
class HomeConversationStateTest {

    private fun sms(
        threadId: Long,
        date: Long,
        unread: Boolean = false,
        message: String = "m" + date,
        type: Int = 1
    ): Sms = Sms(
        id = date,
        threadId = threadId,
        sender = "+1555000" + threadId,
        message = message,
        date = date,
        unread = unread,
        type = type
    )

    // ── Precedence: an older Room emission cannot clobber a newer overlay ──

    @Test
    fun `an older room emission cannot overwrite a newer mark read overlay`() {
        val state = HomeConversationState()
        state.recordMarkRead(1L)

        val rendered = state.render(
            room = listOf(sms(1L, 100L, unread = true)),
            archived = emptySet(),
            pinned = emptySet()
        )

        assertEquals(1, rendered.main.size)
        assertFalse("the optimistic read must win over the stale Room row", rendered.main[0].unread)
    }

    @Test
    fun `an older room row cannot overwrite a newer incoming overlay`() {
        val state = HomeConversationState()
        state.recordIncoming(sms(7L, 200L, message = "new"))

        val rendered = state.render(
            room = listOf(sms(7L, 150L, message = "old")),
            archived = emptySet(),
            pinned = emptySet()
        )

        assertEquals(1, rendered.main.size)
        assertEquals("new", rendered.main[0].message)
        assertEquals(200L, rendered.main[0].date)
    }

    @Test
    fun `an outgoing pending overlay wins until room contains the message`() {
        val state = HomeConversationState()
        state.recordOutgoingPending(sms(3L, 500L, type = 2, message = "sent"))

        var rendered = state.render(
            room = listOf(sms(3L, 400L, message = "prev")),
            archived = emptySet(),
            pinned = emptySet()
        )
        assertEquals("sent", rendered.main[0].message)

        // Room commits the outgoing row.
        state.onRoomConversations(listOf(sms(3L, 500L, type = 2, message = "sent")))
        assertEquals("Room caught up", 0, state.overrideCount)

        rendered = state.render(
            room = listOf(sms(3L, 500L, type = 2, message = "sent")),
            archived = emptySet(),
            pinned = emptySet()
        )
        assertEquals("sent", rendered.main[0].message)
        assertEquals(2, rendered.main[0].type)
    }

    @Test
    fun `the newest optimistic prediction for a thread wins`() {
        val state = HomeConversationState()
        state.recordIncoming(sms(6L, 100L, message = "first"))
        state.recordIncoming(sms(6L, 300L, message = "second"))

        val rendered = state.render(
            room = listOf(sms(6L, 50L)),
            archived = emptySet(),
            pinned = emptySet()
        )

        assertEquals(1, rendered.main.size)
        assertEquals("second", rendered.main[0].message)
        assertEquals(300L, rendered.main[0].date)
    }

    // ── Retirement: Room catching up removes the overlay ──

    @Test
    fun `a mark read overlay is retired once room reports the thread read`() {
        val state = HomeConversationState()
        state.recordMarkRead(1L)

        state.onRoomConversations(listOf(sms(1L, 100L, unread = false)))

        assertFalse(state.hasOverride(1L))
        assertEquals(0, state.overrideCount)
        assertFalse(
            state.render(listOf(sms(1L, 100L)), emptySet(), emptySet()).main[0].unread
        )
    }

    @Test
    fun `a brand new incoming overlay is reconciled when room catches up`() {
        val state = HomeConversationState()
        state.recordIncoming(sms(7L, 200L, message = "hello"))

        state.onRoomConversations(listOf(sms(7L, 200L, message = "hello")))

        assertEquals(0, state.overrideCount)
        val rendered = state.render(
            room = listOf(sms(7L, 200L, message = "hello")),
            archived = emptySet(),
            pinned = emptySet()
        )
        assertEquals("exactly one row for the thread", 1, rendered.main.size)
        assertEquals(200L, rendered.main[0].date)
    }

    @Test
    fun `room catching up with a newer row retires the overlay`() {
        val state = HomeConversationState()
        state.recordIncoming(sms(7L, 200L, message = "optimistic"))

        state.onRoomConversations(listOf(sms(7L, 250L, message = "newer durable")))

        assertEquals(0, state.overrideCount)
        val rendered = state.render(
            room = listOf(sms(7L, 250L, message = "newer durable")),
            archived = emptySet(),
            pinned = emptySet()
        )
        assertEquals("newer durable", rendered.main[0].message)
    }

    // ── Deletion overlay ──

    @Test
    fun `a removal overlay hides the row and is retired once room removes it`() {
        val state = HomeConversationState()
        state.recordRemoval(4L)

        var rendered = state.render(
            room = listOf(sms(4L, 100L)),
            archived = emptySet(),
            pinned = emptySet()
        )
        assertTrue("the deleted row must disappear immediately", rendered.main.isEmpty())

        state.onRoomConversations(emptyList())
        assertEquals("the durable delete retires the overlay", 0, state.overrideCount)

        rendered = state.render(emptyList(), emptySet(), emptySet())
        assertTrue(rendered.main.isEmpty())
    }

    @Test
    fun `a removal overlay survives a stale room emission that still contains the row`() {
        val state = HomeConversationState()
        state.recordRemoval(4L)

        state.onRoomConversations(listOf(sms(4L, 100L)))

        assertTrue("Room has not caught up yet", state.hasOverride(4L))
        assertTrue(
            state.render(listOf(sms(4L, 100L)), emptySet(), emptySet()).main.isEmpty()
        )
    }

    @Test
    fun `clearing a removal restores the durable row`() {
        val state = HomeConversationState()
        state.recordRemoval(4L)

        state.clearRemoval(4L)

        assertFalse(state.hasOverride(4L))
        val rendered = state.render(
            room = listOf(sms(4L, 100L)),
            archived = emptySet(),
            pinned = emptySet()
        )
        assertEquals(1, rendered.main.size)
        assertEquals(4L, rendered.main[0].threadId)
    }

    // ── Pin / archive survive an incoming update ──

    @Test
    fun `pinned survives an incoming update`() {
        val state = HomeConversationState()
        state.recordIncoming(sms(9L, 50L, message = "pin me"))

        val rendered = state.render(
            room = listOf(sms(1L, 900L), sms(2L, 800L)),
            archived = emptySet(),
            pinned = setOf(9L)
        )

        assertEquals("the pinned incoming thread must sort first", 9L, rendered.main[0].threadId)
        assertEquals(3, rendered.main.size)
    }

    @Test
    fun `archived survives an incoming update`() {
        val state = HomeConversationState()
        state.recordIncoming(sms(5L, 100L))

        val rendered = state.render(
            room = emptyList(),
            archived = setOf(5L),
            pinned = emptySet()
        )

        assertTrue("an archived thread must not leak into the main tab", rendered.main.isEmpty())
        assertEquals(1, rendered.archived.size)
        assertEquals(5L, rendered.archived[0].threadId)
    }

    @Test
    fun `an archived row is not duplicated by an override`() {
        val state = HomeConversationState()
        state.recordMarkRead(5L)

        val rendered = state.render(
            room = listOf(sms(5L, 100L, unread = true)),
            archived = setOf(5L),
            pinned = emptySet()
        )

        assertTrue(rendered.main.isEmpty())
        assertEquals(1, rendered.archived.size)
        assertFalse(rendered.archived[0].unread)
    }

    // ── Mark-read preserves the durable row identity ──

    @Test
    fun `mark read preserves the durable snippet and date`() {
        val state = HomeConversationState()
        state.recordMarkRead(2L)

        val rendered = state.render(
            room = listOf(sms(2L, 777L, unread = true, message = "durable")),
            archived = emptySet(),
            pinned = emptySet()
        )

        assertEquals("durable", rendered.main[0].message)
        assertEquals(777L, rendered.main[0].date)
        assertEquals(2L, rendered.main[0].threadId)
        assertFalse(rendered.main[0].unread)
    }

    @Test
    fun `an incoming overlay can be read locally before room commits`() {
        val state = HomeConversationState()
        state.recordIncoming(sms(8L, 400L, unread = true))
        state.recordMarkRead(8L)

        val rendered = state.render(emptyList(), emptySet(), emptySet())

        assertEquals(1, rendered.main.size)
        assertFalse("the thread the user is reading must not flash unread", rendered.main[0].unread)
    }

    @Test
    fun `an override without a thread identity is ignored`() {
        val state = HomeConversationState()
        state.recordIncoming(sms(0L, 100L))
        state.recordMarkRead(0L)
        state.recordRemoval(0L)

        assertEquals(0, state.overrideCount)
        assertTrue(state.render(emptyList(), emptySet(), emptySet()).main.isEmpty())
    }
}
