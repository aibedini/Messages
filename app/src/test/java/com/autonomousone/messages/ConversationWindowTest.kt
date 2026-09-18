package com.autonomousone.messages

import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.repository.ConversationWindow
import com.autonomousone.messages.repository.MessageIdentity
import com.autonomousone.messages.repository.ThreadPager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM regression tests for the windowed conversation open (PHASE 11).
 *
 * Covers: bounded open window, Room reactive tail merge that never drops the
 * older pages already on screen, composite SMS/MMS identity, and the
 * deterministic equal-timestamp ordering shared with Home.
 */
class ConversationWindowTest {

    private fun sms(id: Long, date: Long, body: String = "m$id") =
        Sms(
            id = id,
            threadId = 7L,
            sender = "+98912",
            message = body,
            date = date,
            unread = false,
            type = 1
        )

    @Test
    fun `opening a 100000-message conversation reads only a page-sized window`() {
        val hugeThread = (1L..100_000L).map { sms(it, it * 1_000L) }

        val window = ConversationWindow.boundedNewest(hugeThread, ConversationWindow.OPEN_WINDOW)

        assertEquals(ConversationWindow.OPEN_WINDOW, window.size)
        assertEquals(100_000L, window.last().id)
        assertEquals(
            100_000L - ConversationWindow.OPEN_WINDOW + 1,
            window.first().id
        )
    }

    @Test
    fun `open window and older page sizes are the documented bounds`() {
        assertTrue(ConversationWindow.OPEN_WINDOW in 12..24)
        assertEquals(40, ConversationWindow.OLDER_PAGE)
        assertEquals(ThreadPager.PAGE_PER_SOURCE, ConversationWindow.OLDER_PAGE)
    }

    @Test
    fun `a newer Room tail emission does not drop already-loaded older pages`() {
        val olderLoadedPages = (1L..30L).map { sms(it, it * 1_000L) }
        val roomTailNewest = (40L..60L).map { sms(it, it * 1_000L) }

        val merged = ConversationWindow.mergeRoomTail(olderLoadedPages, roomTailNewest)

        assertTrue(merged.containsAll(olderLoadedPages))
        assertEquals(olderLoadedPages.size + roomTailNewest.size, merged.size)
        assertEquals(olderLoadedPages.first().id, merged.first().id)
        assertEquals(roomTailNewest.last().id, merged.last().id)
    }

    @Test
    fun `a Room tail emission refreshes read and status of a recent row`() {
        val onScreen = sms(52, 5_000, body = "hello").copy(type = 2, status = 32)
        val roomTail = onScreen.copy(status = 0, dateSent = 5_500)

        val merged = ConversationWindow.mergeRoomTail(listOf(onScreen), listOf(roomTail))

        assertEquals(1, merged.size)
        assertEquals(0, merged.single().status)
        assertEquals(5_500L, merged.single().dateSent)
    }

    @Test
    fun `SMS 52 and MMS 52 are distinct identities and both survive a merge`() {
        assertEquals(MessageIdentity.SOURCE_SMS, MessageIdentity.sourceOf(52L))
        assertEquals(MessageIdentity.SOURCE_MMS, MessageIdentity.sourceOf(-52L))
        assertEquals(52L, MessageIdentity.providerIdOf(-52L))
        assertNotEquals(MessageIdentity.keyOf(52L), MessageIdentity.keyOf(-52L))

        val merged = ConversationWindow.mergeRoomTail(
            visible = listOf(sms(52, 1_000, body = "sms-52")),
            roomTail = listOf(sms(-52, 1_000, body = "mms-52"))
        )

        assertEquals(2, merged.size)
        assertEquals(
            2,
            merged.map { MessageIdentity.keyOf(it.id) }.distinct().size
        )
    }

    @Test
    fun `outgoing event reuses provider identity and cannot create a second bubble`() {
        val providerId = 52L
        val eventDate = 9_000L
        assertEquals(providerId, MessageIdentity.outgoingEventId(providerId, eventDate))
        assertEquals(eventDate, MessageIdentity.outgoingEventId(null, eventDate))

        val eventRow = sms(
            MessageIdentity.outgoingEventId(providerId, eventDate),
            eventDate,
            body = "renewed"
        ).copy(type = 2, status = 32)
        // Provider timestamps need not equal the event time. Stable row
        // identity still lets the authoritative delivered copy replace it.
        val deliveredProviderRow = sms(providerId, 7_000L, body = "renewed")
            .copy(type = 2, status = 0)

        val merged = ConversationWindow.mergeRoomTail(
            visible = listOf(eventRow),
            roomTail = listOf(deliveredProviderRow)
        )

        assertEquals(1, merged.size)
        assertEquals(providerId, merged.single().id)
        assertEquals(0, merged.single().status)
    }

    @Test
    fun `equal-timestamp ordering is deterministic and matches Home's newest pick`() {
        val smsNewest = sms(52, 1_000).copy(type = 1)
        val smsOlder = sms(3, 1_000).copy(type = 1)
        val mmsRow = sms(-7, 1_000, body = "mms").copy(type = 1)

        val a = listOf(mmsRow, smsNewest, smsOlder).sortedWith(ConversationWindow.canonical)
        val b = listOf(smsOlder, smsNewest, mmsRow).sortedWith(ConversationWindow.canonical)

        // Comparator is total/deterministic: input order cannot change output.
        assertEquals(a.map { it.id }, b.map { it.id })
        // Home picks date DESC, source DESC ("sms" > "mms"), providerId DESC.
        // In ascending order the LAST row at an equal timestamp must be the
        // highest SMS provider id — otherwise the conversation would show a
        // different "newest" row than the Home list.
        assertEquals(52L, a.last().id)
        assertEquals(MessageIdentity.SOURCE_MMS, MessageIdentity.sourceOf(a.first().id))
    }
}
