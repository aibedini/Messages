package com.autonomousone.messages.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import com.autonomousone.messages.event.SmsEventBus
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.repository.ContactRepository
import com.autonomousone.messages.utils.NotificationHelper

/**
 * Production-ready BroadcastReceiver for real-time incoming SMS processing.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        try {
            val messages: Array<SmsMessage>? = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isNullOrEmpty()) return

            // Extract sender address and timestamp from the first message segment
            val firstMsg = messages.firstOrNull() ?: return
            val sender = firstMsg.originatingAddress ?: "Unknown"
            val timestamp = if (firstMsg.timestampMillis > 0) firstMsg.timestampMillis else System.currentTimeMillis()

            // Extract subscription ID (SIM card) if present
            val subId = intent.extras?.getInt("subscription", -1) ?: -1

            // Concatenate message body segments for long/multipart SMS
            val bodyBuilder = StringBuilder()
            for (msg in messages) {
                val bodyChunk = msg.messageBody
                if (!bodyChunk.isNullOrEmpty()) {
                    bodyBuilder.append(bodyChunk)
                }
            }
            val fullBody = bodyBuilder.toString()
            if (fullBody.isBlank()) return

            Log.d("SMS_RECEIVER", "Incoming SMS from $sender (subId=$subId): $fullBody")

            val incomingSms = Sms(
                id = System.currentTimeMillis(),
                threadId = 0L,
                sender = sender,
                message = fullBody,
                date = timestamp,
                unread = true,
                type = 1 // 1 = Telephony.Sms.MESSAGE_TYPE_INBOX
            )

            // 1. Emit incoming SMS event to reactive event bus
            SmsEventBus.emitSms(incomingSms)

            // 2. Check foreground / active conversation status for notifications
            val isForeground = SmsEventBus.isAppInForeground
            val activePhone = SmsEventBus.activeConversationPhone

            val normalizedSender = ContactRepository.normalizePhone(sender)
            val normalizedActivePhone = ContactRepository.normalizePhone(activePhone)

            val isViewingThisConversation = isForeground &&
                    activePhone.isNotBlank() &&
                    (normalizedSender == normalizedActivePhone || sender == activePhone)

            // Post notification if app is backgrounded OR user is on another screen
            if (!isViewingThisConversation) {
                NotificationHelper.showSmsNotification(context, incomingSms)
            }

        } catch (e: Exception) {
            Log.e("SMS_RECEIVER", "Error processing incoming SMS PDU", e)
        }
    }
}
