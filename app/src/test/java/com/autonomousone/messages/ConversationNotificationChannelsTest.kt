package com.autonomousone.messages

import com.autonomousone.messages.messaging.ConversationNotificationChannels
import com.autonomousone.messages.utils.NotificationHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Per-conversation notification channels (v3.4.0 FEATURE 4).
 *
 * Channel identity and the API<26 fallback are pure and unit-testable. Creation
 * itself needs a real NotificationManager, so it is asserted at the
 * instrumentation level; what matters here is that the id is STABLE for a given
 * thread and that an un-customised conversation keeps the global channel.
 */
class ConversationNotificationChannelsTest {

    @Test
    fun `channel id is stable and derived from the thread`() {
        assertEquals("conversation_42", ConversationNotificationChannels.channelId(42))
        assertEquals("conversation_-1", ConversationNotificationChannels.channelId(-1))
        // Calling twice must not produce a different id: the id is what Android
        // keys the user's sound/vibration choices on.
        assertEquals(
            ConversationNotificationChannels.channelId(42),
            ConversationNotificationChannels.channelId(42)
        )
    }

    @Test
    fun `different threads never collide on one channel`() {
        assertNotEquals(
            ConversationNotificationChannels.channelId(1),
            ConversationNotificationChannels.channelId(2)
        )
    }

    @Test
    fun `global channel id is the documented fallback`() {
        assertEquals(
            "messages_notification_channel",
            NotificationHelper.CHANNEL_ID
        )
    }
}