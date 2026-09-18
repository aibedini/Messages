package com.autonomousone.messages.repository

data class ConversationParticipantState(
    val phone: String,
    val normalizedPhone: String,
    val displayName: String,
    val isKnownContact: Boolean,
    /** String form keeps this state pure/JVM-testable. */
    val contactLookupUri: String? = null
) {
    val hasDialableNumber: Boolean
        get() = normalizedPhone.isNotBlank() && !normalizedPhone.contains(',') &&
            !normalizedPhone.contains(';')
}

enum class ParticipantContactAction {
    ADD_TO_CONTACTS,
    VIEW_CONTACT,
    NONE
}

object ConversationParticipantActions {
    fun primaryContactAction(state: ConversationParticipantState): ParticipantContactAction =
        when {
            !state.hasDialableNumber -> ParticipantContactAction.NONE
            state.isKnownContact && !state.contactLookupUri.isNullOrBlank() ->
                ParticipantContactAction.VIEW_CONTACT
            else -> ParticipantContactAction.ADD_TO_CONTACTS
        }
}
