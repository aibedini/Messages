package com.autonomousone.messages.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.autonomousone.messages.model.Sms

/**
 * Receives both SMS_RECEIVED (all apps) and SMS_DELIVER (default SMS app only).
 *
 * When this app IS the default SMS app:
 *   - SMS_DELIVER fires and we write to Telephony.Sms.Inbox ourselves.
 *   - SMS_RECEIVED may also fire but we skip duplicate processing.
 *
 * When this app is NOT the default SMS app:
 *   - Only SMS_RECEIVED fires; the system default app writes the row and our
 *     SmsContentObserver picks the change up.
 *
 * Single-source-of-truth flow: broadcast → INSERT into the provider → read the
 * persisted row BACK from the provider → dispatch UI/webhook/notification from
 * that confirmed state. The receiver does heavy work inside goAsync() on a
 * background thread so the system does not kill the process mid-INSERT, and
 * webhook/network work happens only after persistence succeeded.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action ?: return
        val isSmsDeliver = action == Telephony.Sms.Intents.SMS_DELIVER_ACTION
        val isSmsReceived = action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION
        if (!isSmsDeliver && !isSmsReceived) return

        // Avoid double-processing: when we are default, SMS_DELIVER owns the row;
        // a follow-up SMS_RECEIVED for the same PDU must be ignored.
        if (isSmsReceived && isDefaultSmsApp(context)) {
            Log.d(TAG, "Skipping SMS_RECEIVED - we are the default app, SMS_DELIVER will handle it")
            return
        }

        val pending = goAsync()
        val appContext = context.applicationContext

        Thread {
            try {
                processIntent(appContext, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing incoming SMS", e)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun processIntent(context: Context, intent: Intent) {
        val pdus = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (pdus.isNullOrEmpty()) return

        val sender = pdus.first().originatingAddress ?: return
        val timestamp = pdus.first().timestampMillis.takeIf { it > 0 }
            ?: System.currentTimeMillis()

        // Merge multipart PDUs into one message body (concatenated SMS).
        val body = buildString {
            for (msg in pdus) msg.messageBody?.let { append(it) }
        }
        if (body.isBlank()) return

        Log.d(TAG, "Incoming SMS [action=${intent.action}] from $sender (${body.length} chars)")

        // ── 1. Persist FIRST (default-app path). Non-default apps never get
        //       SMS_DELIVER; the system default app writes the row instead and
        //       our ContentObserver syncs from it. ────────────────────────────
        var persistedId = -1L
        var threadId = IncomingMessageDispatcher.resolveThreadId(context, sender)
        if (intent.action == Telephony.Sms.Intents.SMS_DELIVER_ACTION) {
            val inserted = insertIntoInbox(context, sender, body, timestamp, threadId)
            persistedId = inserted.first
            inserted.second?.let { threadId = it }
        }

        // ── 2. Read back what the provider actually holds (SSOT), falling back
        //       to broadcast data when the row is not visible to us. ──────────
        val sms = readBackFromProvider(context, persistedId)
            ?: Sms(
                id = if (persistedId > 0) persistedId else timestamp,
                threadId = threadId,
                sender = sender,
                message = body,
                date = timestamp,
                unread = true,
                type = Telephony.Sms.MESSAGE_TYPE_INBOX
            )

        // ── 3. One shared fan-out: bus + webhook + notification. ─────────────
        IncomingMessageDispatcher.dispatch(context, sms)
    }

    /** Inserts the inbox row with THREAD_ID set so Threads stays consistent. */
    private fun insertIntoInbox(
        context: Context,
        sender: String,
        body: String,
        timestamp: Long,
        threadId: Long
    ): Pair<Long, Long?> {
        return try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, sender)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, timestamp)
                put(Telephony.Sms.DATE_SENT, timestamp)
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.SEEN, 0)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
                if (threadId > 0L) put(Telephony.Sms.THREAD_ID, threadId)
            }
            val uri = context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
            val id = uri?.lastPathSegment?.toLongOrNull() ?: -1L
            Log.d(TAG, "Persisted SMS to Inbox id=$id threadId=$threadId")
            Pair(id, null) // provider fills/normalizes THREAD_ID on insert
        } catch (e: Exception) {
            Log.e(TAG, "Error writing SMS to Inbox", e)
            Pair(-1L, null)
        }
    }

    /**
     * Reads the freshly persisted row back from Telephony.Sms so every consumer
     * sees exactly what the provider holds (real id, THREAD_ID, timestamps).
     */
    private fun readBackFromProvider(context: Context, rowId: Long): Sms? {
        if (rowId <= 0L) return null
        return try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.THREAD_ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.READ,
                    Telephony.Sms.TYPE
                ),
                "${Telephony.Sms._ID} = ?",
                arrayOf(rowId.toString()),
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                Sms(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms._ID)),
                    threadId = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)),
                    sender = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: "",
                    message = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: "",
                    date = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)),
                    unread = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.READ)) == 0,
                    type = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE))
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Provider read-back failed for id=$rowId", e)
            null
        }
    }

    private fun isDefaultSmsApp(context: Context): Boolean = try {
        Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
    } catch (e: Exception) {
        false
    }

    private companion object {
        const val TAG = "SMS_RECEIVER"
    }
}
