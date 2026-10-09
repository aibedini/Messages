package com.autonomousone.messages.model

data class Sms(
    val id: Long,
    val threadId: Long,
    val sender: String,
    val message: String,
    val date: Long,
    val unread: Boolean,
    val type: Int,
    /**
     * Delivery status from Telephony.Sms.STATUS:
     * -1 = sent (no report), 0 = delivered, 32 = pending, 64 = failed.
     * Only meaningful for outgoing messages when delivery reports are enabled.
     */
    val status: Int = -1,
    /**
     * Delivery timestamp from Telephony.Sms.DATE_SENT (epoch ms).
     * The platform fills this when a delivery report arrives; 0 = unknown.
     */
    val dateSent: Long = 0,
    /**
     * The app-owned UI state of this message as a
     * [com.autonomousone.messages.sms.SmsUiState] name, or null when it needs no attention.
     *
     * ## Why this is not [status]
     *
     * [status] is the PROVIDER's own delivery field, and it cannot express what actually happened:
     * `STATUS_PENDING` means both "still waiting for the radio" and "an ambiguous result arrived", and
     * it holds no carrier TP-Status. This field carries the app's own verdict, derived once by
     * [com.autonomousone.messages.sms.SmsStatusPresentationMapper], so the conversation list and the
     * chat bubble cannot disagree about one message.
     *
     * Only set where the surface has a reason to show something: the conversation row's newest
     * message. Null is the quiet case — inbound, delivered, or a historical row with no app evidence —
     * and is the same "nothing to say" that `conversationSummary` returns for success states.
     */
    val uiState: String? = null
)
