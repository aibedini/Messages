package com.autonomousone.messages.receiver

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.autonomousone.messages.event.SmsEventBus
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.repository.SmsRepository
import com.autonomousone.messages.sms.SmsSender

class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_MARK_READ = "com.autonomousone.messages.ACTION_MARK_READ"
        const val ACTION_REPLY = "com.autonomousone.messages.ACTION_REPLY"
        const val ACTION_COPY_OTP = "com.autonomousone.messages.ACTION_COPY_OTP"

        const val EXTRA_THREAD_ID = "extra_thread_id"
        const val EXTRA_PHONE = "extra_phone"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        const val EXTRA_OTP_CODE = "extra_otp_code"

        const val KEY_TEXT_REPLY = "key_text_reply"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val threadId = intent.getLongExtra(EXTRA_THREAD_ID, 0L)
        val phone = intent.getStringExtra(EXTRA_PHONE) ?: ""

        when (intent.action) {
            ACTION_MARK_READ -> {
                try {
                    SmsRepository(context).markThreadAsRead(threadId, phone)
                    if (notificationId != 0) {
                        NotificationManagerCompat.from(context).cancel(notificationId)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            ACTION_REPLY -> {
                val remoteInput = RemoteInput.getResultsFromIntent(intent)
                val replyText = remoteInput?.getCharSequence(KEY_TEXT_REPLY)?.toString()?.trim() ?: ""

                if (replyText.isNotBlank() && phone.isNotBlank()) {
                    try {
                        SmsSender(context).send(phone, replyText)

                        // Emit optimistic outgoing message to update active UI
                        val optimisticSms = Sms(
                            id = System.currentTimeMillis(),
                            threadId = threadId,
                            sender = phone,
                            message = replyText,
                            date = System.currentTimeMillis(),
                            unread = false,
                            type = 2
                        )
                        SmsEventBus.emitSms(optimisticSms)

                        if (notificationId != 0) {
                            NotificationManagerCompat.from(context).cancel(notificationId)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            ACTION_COPY_OTP -> {
                val otpCode = intent.getStringExtra(EXTRA_OTP_CODE) ?: ""
                if (otpCode.isNotBlank()) {
                    try {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("OTP Code", otpCode)
                        clipboard.setPrimaryClip(clip)

                        Toast.makeText(context, "OTP $otpCode copied to clipboard", Toast.LENGTH_SHORT).show()

                        if (notificationId != 0) {
                            NotificationManagerCompat.from(context).cancel(notificationId)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }
}
