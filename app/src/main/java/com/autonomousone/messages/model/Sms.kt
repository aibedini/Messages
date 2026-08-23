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
    val dateSent: Long = 0
)
