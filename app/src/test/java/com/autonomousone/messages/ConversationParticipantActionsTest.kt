package com.autonomousone.messages

import com.autonomousone.messages.repository.ConversationParticipantActions
import com.autonomousone.messages.repository.ConversationParticipantState
import com.autonomousone.messages.repository.ParticipantContactAction
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationParticipantActionsTest {
    @Test
    fun `known contact exposes view contact`() {
        val state = ConversationParticipantState(
            phone = "+989121234567",
            normalizedPhone = "+989121234567",
            displayName = "Ali",
            isKnownContact = true,
            contactLookupUri = "content://contacts/lookup/key/1"
        )
        assertEquals(
            ParticipantContactAction.VIEW_CONTACT,
            ConversationParticipantActions.primaryContactAction(state)
        )
    }

    @Test
    fun `unknown number exposes add contact`() {
        val state = ConversationParticipantState(
            phone = "+989121234567",
            normalizedPhone = "+989121234567",
            displayName = "+989121234567",
            isKnownContact = false
        )
        assertEquals(
            ParticipantContactAction.ADD_TO_CONTACTS,
            ConversationParticipantActions.primaryContactAction(state)
        )
    }

    @Test
    fun `empty or group participant suppresses single-contact action`() {
        val empty = ConversationParticipantState("", "", "", false)
        val group = ConversationParticipantState("0912,0935", "0912,0935", "Group", false)
        assertEquals(ParticipantContactAction.NONE, ConversationParticipantActions.primaryContactAction(empty))
        assertEquals(ParticipantContactAction.NONE, ConversationParticipantActions.primaryContactAction(group))
    }
}
