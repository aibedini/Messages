package com.autonomousone.messages.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import com.autonomousone.messages.event.SmsEventBus
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.repository.ContactRepository
import com.autonomousone.messages.utils.NotificationHelper

/**
 * Receives both SMS_RECEIVED (all apps) and SMS_DELIVER (default SMS app only).
 *
 * When this app IS the default SMS app:
 *   - SMS_DELIVER fires and we write to Telephony.Sms.Inbox ourselves (full control).
 *   - SMS_RECEIVED may also fire but we skip duplicate processing.
 *
 * When this app is NOT the default SMS app:
 *   - Only SMS_RECEIVED fires. We emit to SmsEventBus for optimistic UI.
 *   - The system default app writes to DB; SmsContentObserver catches it.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action ?: return
        val isSmsDeliver = action == Telephony.Sms.Intents.SMS_DELIVER_ACTION
        val isSmsReceived = action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION

        if (!isSmsDeliver && !isSmsReceived) return

        // Avoid double-processing: if we get SMS_DELIVER we are the default app and handle everything.
        // If we get SMS_RECEIVED and we ARE the default app, skip (SMS_DELIVER already handled it).
        if (isSmsReceived && isDefaultSmsApp(context)) {
            Log.d("SMS_RECEIVER", "Skipping SMS_RECEIVED - we are the default app, SMS_DELIVER will handle it")
            return
        }

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

            Log.d("SMS_RECEIVER", "Incoming SMS [action=$action] from $sender: $body")

            var persistedId = System.currentTimeMillis()

            // If we are the default SMS app, write to Inbox ourselves
            if (isSmsDeliver) {
                try {
                    val values = ContentValues().apply {
                        put(Telephony.Sms.ADDRESS, sender)
                        put(Telephony.Sms.BODY, body)
                        put(Telephony.Sms.DATE, timestamp)
                        put(Telephony.Sms.DATE_SENT, timestamp)
                        put(Telephony.Sms.READ, 0)
                        put(Telephony.Sms.SEEN, 0)
                        put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
                    }
                    val uri = context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
                    val insertedId = uri?.lastPathSegment?.toLongOrNull()
                    if (insertedId != null) {
                        persistedId = insertedId
                        Log.d("SMS_RECEIVER", "Persisted SMS to Inbox with id=$insertedId")
                    } else {
                        Log.w("SMS_RECEIVER", "Inbox insert returned null URI")
                    }
                } catch (e: Exception) {
                    Log.e("SMS_RECEIVER", "Error writing SMS to Inbox", e)
                }
            }

            val incomingSms = Sms(
                id = persistedId,
                threadId = 0L,
                sender = sender,
                message = body,
                date = timestamp,
                unread = true,
                type = 1
            )

            // Emit immediately for optimistic UI update
            SmsEventBus.emitSms(incomingSms)

            // Show notification unless user is actively viewing this conversation
            val activePhone = SmsEventBus.activeConversationPhone
            val normalizedSender = ContactRepository.normalizePhone(sender)
            val normalizedActive = ContactRepository.normalizePhone(activePhone)
            val isViewingThis = SmsEventBus.isAppInForeground && activePhone.isNotBlank() &&
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

    private fun isDefaultSmsApp(context: Context): Boolean {
        return try {
            val defaultPkg = Telephony.Sms.getDefaultSmsPackage(context)
            defaultPkg == context.packageName
        } catch (e: Exception) {
            false
        }
    }
}
