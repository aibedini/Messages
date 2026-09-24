package com.autonomousone.messages.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.utils.DiagnosticLog
import java.security.MessageDigest

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
        if (pdus.isNullOrEmpty()) {
            Log.w(TAG, "incoming_sms_received from=unknown length=0 — no PDUs in ${intent.action}")
            return
        }

        val sender = pdus.first().originatingAddress ?: return
        val pduFingerprint = fingerprint(intent)
        val timestamp = pdus.first().timestampMillis.takeIf { it > 0 }
            ?: System.currentTimeMillis()

        // Merge multipart PDUs into one message body (concatenated SMS).
        val body = buildString {
            for (msg in pdus) msg.messageBody?.let { append(it) }
        }
        if (body.isBlank()) {
            Log.w(TAG, "incoming_sms_received from=$sender length=0 — blank multipart body, ignoring")
            return
        }

        Log.i(TAG, "incoming_sms_received from=$sender length=${body.length} action=${intent.action}")
        DiagnosticLog.event(
            "INCOMING_DEDUP",
            "broadcast action=${intent.action} pdu=$pduFingerprint " +
                "phone=${DiagnosticLog.phoneToken(sender)}"
        )

        // A non-default SMS app does not own provider persistence. The system
        // default app will insert the row and our ContentObserver will ingest
        // that real provider identity. Publishing the broadcast payload here
        // used to create a timestamp-id Room row followed by the real row: one
        // physical SMS, two bubbles (CASE C).
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            DiagnosticLog.event(
                "INCOMING_DEDUP",
                "pdu=$pduFingerprint path=broadcast decision=provider-observer-only"
            )
            return
        }

        // ── 1. Persist FIRST (default-app path). Non-default apps never get
        //       SMS_DELIVER; the system default app writes the row instead and
        //       our ContentObserver syncs from it. ────────────────────────────
        var persistedId = -1L
        var threadId = IncomingMessageDispatcher.resolveThreadId(context, sender)
        if (intent.action == Telephony.Sms.Intents.SMS_DELIVER_ACTION) {
            // A bounded retry: the likely cause of a failed insert is a provider that is momentarily
            // unavailable, and this receiver already holds a broadcast window to spend on it.
            // Named argument, not a trailing lambda: the trailing position is `maxAttempts`, and a
            // trailing lambda would bind there.
            val outcome = InboxWriteRetryPolicy.persist(
                write = { insertIntoInbox(context, sender, body, timestamp, threadId) }
            )
            when (outcome) {
                is InboxPersistOutcome.Persisted -> persistedId = outcome.rowId
                is InboxPersistOutcome.Failed -> {
                    // NOT the same fact as "written but not visible yet".
                    //
                    // This app is the default SMS app, so nothing else wrote the row: as far as anyone
                    // can tell, the message exists nowhere. Reporting that as
                    // `defer-to-provider-observer` — which is what the collapsed `-1` used to produce —
                    // told a reader that a ContentObserver would pick it up from a provider that has no
                    // such row, so an inbound message could be lost with no record that it arrived.
                    Log.e(
                        TAG,
                        "incoming sms could not be persisted after ${outcome.attempts} attempt(s) " +
                            "from=${DiagnosticLog.phoneToken(sender)} reason=${outcome.reason ?: "unknown"}"
                    )
                    DiagnosticLog.event(
                        "INCOMING_PERSIST_FAILED",
                        "pdu=$pduFingerprint attempts=${outcome.attempts} " +
                            "reason=${outcome.reason ?: "unknown"} " +
                            "phone=${DiagnosticLog.phoneToken(sender)} length=${body.length} " +
                            "decision=message-not-persisted"
                    )
                    // HELD, not dropped. This app is the default SMS app, so nothing else will ever write
                    // this message: without a durable copy it is gone, and a log line is not a record that
                    // a message arrived. The row is idempotent on the PDU fingerprint, so a redelivered
                    // broadcast cannot hold it twice.
                    holdForLaterDelivery(context, pduFingerprint, sender, body, timestamp, threadId, outcome.reason)
                    return
                }
            }
        }

        // ── 2. Read back what the provider actually holds (SSOT), falling back
        //       to broadcast data when the row is not visible to us. ──────────
        val sms = readBackFromProvider(context, persistedId)
        if (sms == null || sms.id <= 0L) {
            DiagnosticLog.event(
                "INCOMING_DEDUP",
                "pdu=$pduFingerprint providerId=$persistedId threadId=$threadId " +
                    "decision=defer-to-provider-observer"
            )
            return
        }
        DiagnosticLog.event(
            "INCOMING_DEDUP",
            "pdu=$pduFingerprint providerId=${sms.id} threadId=${sms.threadId} " +
                "source=sms path=eventbus decision=append-or-replace"
        )

        // ── 3. One shared fan-out: bus + webhook + notification. ─────────────
        IncomingMessageDispatcher.dispatch(context, sms)
    }

    /**
     * Holds an inbound message that could not be written, so a retry pass can try again.
     *
     * The write is synchronous and bounded on purpose: the receiver is inside a broadcast, and a message
     * that could not be stored must not also be lost because the broadcast window closed while a
     * coroutine was being scheduled. `runBlocking` runs on the receiver's own background thread
     * (`goAsync`), never the main thread.
     *
     * If even holding it fails, that is reported separately and loudly: at that point the message is
     * genuinely gone, and the one thing that must not happen is for that to look like the ordinary path.
     */
    private fun holdForLaterDelivery(
        context: Context,
        pduFingerprint: String,
        sender: String,
        body: String,
        timestamp: Long,
        threadId: Long,
        reason: String?
    ) {
        val row = com.autonomousone.messages.data.PendingInboundSmsEntity(
            pduFingerprint = pduFingerprint,
            address = sender,
            body = body,
            dateMs = timestamp,
            threadId = threadId,
            createdAt = System.currentTimeMillis(),
            lastError = reason
        )
        val held = runCatching {
            kotlinx.coroutines.runBlocking {
                com.autonomousone.messages.data.MessagesDatabase.get(context.applicationContext)
                    .pendingInboundSmsDao()
                    .insertOrIgnore(row)
            }
        }
        held.onSuccess { rowId ->
            DiagnosticLog.event(
                "INCOMING_PERSIST_HELD",
                "pdu=$pduFingerprint held=$rowId phone=${DiagnosticLog.phoneToken(sender)} " +
                    "decision=retry-later"
            )
            // Ask for the recovery pass now rather than waiting for the periodic sweep: a provider that
            // was momentarily unavailable is very likely to answer a minute from now.
            PendingInboundWorker.scheduleRetry(context)
        }.onFailure { error ->
            Log.e(TAG, "incoming sms could not be held for retry either", error)
            DiagnosticLog.event(
                "INCOMING_PERSIST_LOST",
                "pdu=$pduFingerprint reason=${error.message ?: "unknown"} " +
                    "phone=${DiagnosticLog.phoneToken(sender)} length=${body.length} " +
                    "decision=message-lost",
                error
            )
        }
    }

    /**
     * One attempt to insert the inbox row with THREAD_ID set so Threads stays consistent.
     *
     * Returns [InboxWriteAttempt] rather than a bare id, so the caller can tell a write that FAILED
     * from one that succeeded — the two used to collapse into `-1` and be reported identically.
     */
    private fun insertIntoInbox(
        context: Context,
        sender: String,
        body: String,
        timestamp: Long,
        threadId: Long
    ): InboxWriteAttempt {
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
            if (id <= 0L) {
                // The provider answered but gave no row id. Treated as a failure rather than a success
                // with an unknown id: there is nothing to read back, so the caller must not proceed as
                // though a row exists.
                Log.e(TAG, "Persist SMS to Inbox returned no row id")
                InboxWriteAttempt.Threw("no-row-id")
            } else {
                Log.d(TAG, "Persisted SMS to Inbox id=$id threadId=$threadId")
                InboxWriteAttempt.Wrote(id)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing SMS to Inbox", e)
            InboxWriteAttempt.Threw(e.message)
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

    /** Privacy-safe physical broadcast token; raw PDU bytes never leave memory. */
    private fun fingerprint(intent: Intent): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        @Suppress("DEPRECATION")
        val rawPdus = intent.extras?.get("pdus") as? Array<*>
        rawPdus.orEmpty().forEach { (it as? ByteArray)?.let(digest::update) }
        digest.update(intent.getStringExtra("format").orEmpty().toByteArray())
        digest.digest().take(6).joinToString("") { "%02x".format(it) }
    }.getOrDefault("unknown")

    private companion object {
        // PascalCase to match the receiver class name for case-sensitive logcat
        // filters (`adb logcat -s SmsReceiver:I`) and source greps.
        const val TAG = "SmsReceiver"
    }
}
