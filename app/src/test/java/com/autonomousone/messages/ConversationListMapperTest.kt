package com.autonomousone.messages

import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.ui.conversation.ChatListItem
import com.autonomousone.messages.ui.conversation.buildReverseChatItems
import com.autonomousone.messages.ui.conversation.chatItemKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * ConversationListMapper is the single owner of the reverse-layout flip
 * (v2.6.7): ViewModel messages are canonical ASC, and the LazyColumn runs
 * with reverseLayout = true, so index 0 of the mapped list MUST be the
 * newest message — that is what makes opening a conversation land on the
 * latest row with no scroll command at all.
 */
class ConversationListMapperTest {

    private fun sms(id: Long, date: Long, body: String = "m$id") =
        Sms(id = id, threadId = 7L, sender = "+98912", message = body, date = date, unread = false, type = 1)

    /** Local noon — immune to midnight/DST flakiness around "Today". */
    private fun noon(daysAgo: Int): Long =
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -daysAgo)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `empty input maps to empty output`() {
        assertTrue(buildReverseChatItems(emptyList()).isEmpty())
    }

    @Test
    fun `index 0 is the newest message - open lands on latest`() {
        val messages = listOf(sms(1, noon(2)), sms(2, noon(1)), sms(3, noon(0))) // ASC
        val rows = buildReverseChatItems(messages)

        val first = rows.first()
        assertTrue(first is ChatListItem.MessageItem)
        assertEquals(3L, (first as ChatListItem.MessageItem).sms.id)
    }

    @Test
    fun `single-day window ends with exactly one separator trailing the group`() {
        val messages = listOf(sms(1, noon(0) - 60_000), sms(2, noon(0))) // same day ASC
        val rows = buildReverseChatItems(messages)

        assertEquals(
            listOf(
                ChatListItem.MessageItem::class,
                ChatListItem.MessageItem::class,
                ChatListItem.DateSeparator::class
            ),
            rows.map { it::class }
        )
        val sep = rows.last() as ChatListItem.DateSeparator
        assertEquals("Today", sep.dateText)
    }

    @Test
    fun `multi-day window interleaves messages and closes each day group with its separator`() {
        // Three days: today (1 msg), yesterday (1 msg), two-days-ago (1 msg) — ASC in.
        val messages = listOf(sms(1, noon(2)), sms(2, noon(1)), sms(3, noon(0)))
        val rows = buildReverseChatItems(messages)

        val kinds = rows.map {
            when (it) {
                is ChatListItem.MessageItem -> "m"
                is ChatListItem.DateSeparator -> it.dateText
            }
        }
        // Newest first, each group trailing with its own header; oldest group
        // closes the data (paints at the very top of the reversed list).
        assertEquals(listOf("m", "Today", "m", "Yesterday", "m", noonLabel(2)), kinds)
    }

    /** Non-today/yesterday labels are locale-formatted; compare against the same helper. */
    private fun noonLabel(daysAgo: Int): String =
        com.autonomousone.messages.utils.formatDateHeader(noon(daysAgo))

    @Test
    fun `keys are identity-based not text-based`() {
        val messages = listOf(sms(5, 1234L))
        val rows = buildReverseChatItems(messages)

        assertEquals("msg_5_1234_1", chatItemKey(rows.first()))
        val sep = ChatListItem.DateSeparator(dayKey = "2026-100", dateText = "whatever")
        assertEquals("date_2026-100", chatItemKey(sep))
    }

    @Test
    fun `negative MMS model ids never collide with their positive mirror`() {
        val pos = sms(7, 1000L)
        val neg = sms(-7, 1000L)
        assertTrue(chatItemKey(ChatListItem.MessageItem(pos)) != chatItemKey(ChatListItem.MessageItem(neg)))
    }

    @Test
    fun `duplicate provider row from self sms race is emitted once`() {
        val first = sms(42, 1_000L, "self")
        val refreshed = first.copy(status = android.provider.Telephony.Sms.STATUS_COMPLETE)

        val rows = buildReverseChatItems(listOf(first, refreshed))
        val messages = rows.filterIsInstance<ChatListItem.MessageItem>()

        assertEquals(1, messages.size)
        assertEquals(android.provider.Telephony.Sms.STATUS_COMPLETE, messages.single().sms.status)
    }
}
