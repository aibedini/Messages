package com.autonomousone.messages.receiver

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.autonomousone.messages.sms.SmsSender
import com.autonomousone.messages.repository.SmsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles notification actions (mark-read, quick reply, copy OTP).
 *
 * v2.6.10 safety pass: this receiver used to run `SmsRepository` provider
 * writes and `SmsSender.send()` — which can block on the send rate limiter
 * (`Thread.sleep`) — directly on the MAIN thread inside onReceive(): a
 * guaranteed ANR path. Work now runs on Dispatchers.IO under goAsync(), so
 * the system keeps the process alive until the action completes.
 *
 * The duplicate optimistic event is also gone: SmsSender already persists
 * the message and emits its own OutgoingSent event; the receiver emitting a
 * SECOND Sms event (labelled as incoming flow) forced downstream 5-second
 * text-window dedupe heuristics. One send, one event.
 *
 * Still not a durable queue — a process death mid-send loses the reply.
 * The Room-backed OutgoingMessageQueue that replaces direct sends is the
 * Pass-2 durability refactor (release notes v2.6.10).
 */
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotifActionReceiver"

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
        val appContext = context.applicationContext ?: return

        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val threadId = intent.getLongExtra(EXTRA_THREAD_ID, 0L)
        val phone = intent.getStringExtra(EXTRA_PHONE) ?: ""

        when (intent.action) {
            ACTION_MARK_READ, ACTION_REPLY -> {
                // goAsync + IO: provider writes and rate-limited sends must
                // never run on the main thread of a BroadcastReceiver.
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        when (intent.action) {
                            ACTION_MARK_READ -> {
                                SmsRepository(appContext).markThreadAsRead(threadId, phone)
                                if (notificationId != 0) {
                                    NotificationManagerCompat.from(appContext).cancel(notificationId)
                                }
                            }
                            ACTION_REPLY -> {
                                val remoteInput = RemoteInput.getResultsFromIntent(intent)
                                val replyText = remoteInput
                                    ?.getCharSequence(KEY_TEXT_REPLY)?.toString()?.trim() ?: ""

                                if (replyText.isNotBlank() && phone.isNotBlank()) {
                                    // SmsSender persists + emits OutgoingSent itself.
                                    SmsSender(appContext).send(phone, replyText)

                                    if (notificationId != 0) {
                                        NotificationManagerCompat.from(appContext).cancel(notificationId)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Notification action failed", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }

            ACTION_COPY_OTP -> {
                val otpCode = intent.getStringExtra(EXTRA_OTP_CODE) ?: ""
                if (otpCode.isNotBlank()) {
                    try {
                        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("OTP Code", otpCode)
                        clipboard.setPrimaryClip(clip)

                        Toast.makeText(appContext, "OTP $otpCode copied to clipboard", Toast.LENGTH_SHORT).show()

                        if (notificationId != 0) {
                            NotificationManagerCompat.from(appContext).cancel(notificationId)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "OTP copy failed", e)
                    }
                }
            }
        }
    }
}
