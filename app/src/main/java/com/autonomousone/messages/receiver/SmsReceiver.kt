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
 * Receives incoming SMS broadcasts.
 *
 * NOTE: We do NOT insert into Telephony.Sms.Inbox here.
 * The system/default SMS app is responsible for persistence.
 * We only:
 *   1. Emit to SmsEventBus for immediate optimistic UI display.
 *   2. Post a notification.
 *   3. Let SmsContentObserver detect the DB write (done by system) and trigger a clean reload.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        try {
            val pdus: Array<SmsMessage>? = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (pdus.isNullOrEmpty()) return

            val firstMsg = pdus.first()
            val sender = firstMsg.originatingAddress ?: "Unknown"
            val timestamp = if (firstMsg.timestampMillis > 0) firstMsg.timestampMillis else System.currentTimeMillis()

            val body = buildString {
                for (msg in pdus) {
                    val chunk = msg.messageBody
                    if (!chunk.isNullOrEmpty()) append(chunk)
                }
            }
            if (body.isBlank()) return

            Log.d("SMS_RECEIVER", "Incoming SMS from $sender: $body")

            val incomingSms = Sms(
                id = System.currentTimeMillis(),
                threadId = 0L,
                sender = sender,
                message = body,
                date = timestamp,
                unread = true,
                type = 1
            )

            // Emit immediately for optimistic UI — SmsContentObserver will do the authoritative reload
            SmsEventBus.emitSms(incomingSms)

            // Notify unless user is actively viewing this conversation
            val isForeground = SmsEventBus.isAppInForeground
            val activePhone = SmsEventBus.activeConversationPhone
            val normalizedSender = ContactRepository.normalizePhone(sender)
            val normalizedActive = ContactRepository.normalizePhone(activePhone)

            val isViewingThis = isForeground && activePhone.isNotBlank() &&
                    (normalizedSender == normalizedActive ||
                            normalizedSender.endsWith(normalizedActive) ||
                            normalizedActive.endsWith(normalizedSender))

            if (!isViewingThis) {
                NotificationHelper.showSmsNotification(context, incomingSms)
            }

        } catch (e: Exception) {
            Log.e("SMS_RECEIVER", "Error processing incoming SMS", e)
        }
    }
}
