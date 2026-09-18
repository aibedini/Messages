package com.autonomousone.messages

import com.autonomousone.messages.data.ConversationEntity
import com.autonomousone.messages.navigation.ExternalComposeResolver
import com.autonomousone.messages.navigation.ExternalComposeTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalComposeResolverTest {
    private fun conversation(
        threadId: Long = 7L,
        normalizedAddress: String = "09121234567",
        rawAddress: String = normalizedAddress
    ) = ConversationEntity(
        threadId = threadId,
        normalizedAddress = normalizedAddress,
        rawAddress = rawAddress,
        snippet = "last",
        lastMessageDate = 1_000L,
        unreadCount = 0
    )

    @Test
    fun `exact existing phone resolves to its conversation with draft intact`() {
        val row = conversation()
        assertTrue(ExternalComposeResolver.candidateMatches("09121234567", row))

        val target = ExternalComposeResolver.targetFor("09121234567", "draft", row)
            as ExternalComposeTarget.ExistingConversation

        assertEquals(7L, target.threadId)
        assertEquals("draft", target.draft)
    }

    @Test
    fun `country code and local prefix safely resolve to the same thread`() {
        assertTrue(
            ExternalComposeResolver.candidateMatches(
                "+989121234567",
                conversation(normalizedAddress = "09121234567")
            )
        )
    }

    @Test
    fun `short codes never suffix match`() {
        assertFalse(
            ExternalComposeResolver.candidateMatches(
                "112",
                conversation(normalizedAddress = "+98112")
            )
        )
    }

    @Test
    fun `unknown number remains a new conversation and preserves empty draft`() {
        val target = ExternalComposeResolver.targetFor("09350000000", "", null)
            as ExternalComposeTarget.NewConversation
        assertEquals("09350000000", target.phone)
        assertEquals("", target.draft)
    }
}
