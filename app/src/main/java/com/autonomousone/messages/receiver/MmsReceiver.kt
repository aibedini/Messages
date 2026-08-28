package com.autonomousone.messages.receiver

import android.content.Context
import android.net.Uri
import android.util.Log
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.repository.BlocklistRepository
import com.klinker.android.send_message.MmsReceivedReceiver

/**
 * Real MMS receive path.
 *
 * The mmslib PushReceiver (manifest-declared) takes WAP_PUSH_DELIVER, persists
 * the notification-indication and drives TransactionService to download the
 * actual MMS payload from the carrier MMSC over the MMS APN. When the download
 * finishes it broadcasts MMS_RECEIVED to THIS receiver (matched by taskAffinity),
 * which then:
 *
 *   1. reads the persisted Telephony.Mms row back from the provider (SSOT —
 *      the row was written by DownloadRequest.persist inside mmslib), and
 *   2. fans out through IncomingMessageDispatcher (bus + webhook + notify).
 *
 * Blocked senders are dropped here BEFORE dispatch; the NotificationInd row is
 * left to the library's own screening path (isAddressBlocked below).
 */
class MmsReceiver : MmsReceivedReceiver() {

    override fun isAddressBlocked(context: Context, address: String): Boolean =
        BlocklistRepository.isBlocked(context, address)

    override fun onMessageReceived(context: Context, messageUri: Uri?) {
        val uri = messageUri ?: run {
            Log.w(TAG, "MMS received with null message uri")
            return
        }

        try {
            val sms = readMmsFromProvider(context, uri)
            if (sms == null) {
                Log.w(TAG, "Could not read persisted MMS row $uri")
                return
            }
            Log.d(TAG, "MMS received id=${sms.id} threadId=${sms.threadId} from ${sms.sender}")
            IncomingMessageDispatcher.dispatch(
                context, sms, source = com.autonomousone.messages.data.MessageEntity.SOURCE_MMS
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error processing incoming MMS", e)
        }
    }

    override fun onError(context: Context, error: String) {
        Log.e(TAG, "MMS receive error: $error")
    }

    /**
     * Reads one MMS row (+ its FROM address + text part) straight from the
     * provider into our Sms model. Mirrors SmsRepository.queryMms semantics
     * (dates in seconds → ms, negated ids are NOT used here because we keep
     * the positive provider id for dedupe/read-state handling).
     */
    private fun readMmsFromProvider(context: Context, messageUri: Uri): Sms? {
        val cr = context.contentResolver

        val projection = arrayOf(
            android.provider.Telephony.Mms._ID,
            android.provider.Telephony.Mms.THREAD_ID,
            android.provider.Telephony.Mms.DATE,
            android.provider.Telephony.Mms.MESSAGE_BOX,
            android.provider.Telephony.Mms.READ,
            android.provider.Telephony.Mms.SUBJECT
        )
        val base = cr.query(messageUri, projection, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val id = c.getLong(c.getColumnIndexOrThrow(android.provider.Telephony.Mms._ID))
            MmsRow(
                id = id,
                threadId = c.getLong(c.getColumnIndexOrThrow(android.provider.Telephony.Mms.THREAD_ID)),
                dateMs = c.getLong(c.getColumnIndexOrThrow(android.provider.Telephony.Mms.DATE)) * 1000L,
                box = c.getInt(c.getColumnIndexOrThrow(android.provider.Telephony.Mms.MESSAGE_BOX)),
                unread = c.getInt(c.getColumnIndexOrThrow(android.provider.Telephony.Mms.READ)) == 0,
                subject = c.getString(c.getColumnIndexOrThrow(android.provider.Telephony.Mms.SUBJECT))
            )
        } ?: return null

        val sender = queryAddress(cr, base.id)
        val bodyPart = queryTextBody(cr, base.id)

        return Sms(
            id = -base.id, // keep the repo-wide convention: MMS ids are negative
            threadId = base.threadId,
            sender = sender,
            message = bodyPart
                ?: base.subject?.takeIf { it.isNotBlank() }
                ?: "[MMS]",
            date = base.dateMs,
            unread = base.unread,
            type = if (base.box == android.provider.Telephony.Mms.MESSAGE_BOX_INBOX)
                android.provider.Telephony.Sms.MESSAGE_TYPE_INBOX
            else
                android.provider.Telephony.Sms.MESSAGE_TYPE_SENT
        )
    }

    private data class MmsRow(
        val id: Long,
        val threadId: Long,
        val dateMs: Long,
        val box: Int,
        val unread: Boolean,
        val subject: String?
    )

    /** FROM (type 137) first, then any other non-placeholder address. */
    private fun queryAddress(cr: android.content.ContentResolver, msgId: Long): String {
        var fallback = ""
        try {
            cr.query(
                Uri.parse("content://mms/addr"),
                arrayOf(
                    android.provider.Telephony.Mms.Addr.ADDRESS,
                    android.provider.Telephony.Mms.Addr.TYPE
                ),
                "${android.provider.Telephony.Mms.Addr.MSG_ID} = ?",
                arrayOf(msgId.toString()),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val raw = c.getString(0)?.trim().orEmpty()
                    if (raw.isEmpty() || raw.equals("insert-address-token", true)) continue
                    val type = c.getInt(1)
                    val normalized = com.autonomousone.messages.repository.ContactRepository.normalizePhone(raw)
                    if (type == 137 && normalized.isNotBlank()) return normalized
                    if (fallback.isBlank()) fallback = normalized
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "addr lookup failed for mms $msgId", e)
        }
        return fallback
    }

    /** text/plain part body, or null when the MMS carries no readable text. */
    private fun queryTextBody(cr: android.content.ContentResolver, msgId: Long): String? {
        return try {
            cr.query(
                android.provider.Telephony.Mms.Part.CONTENT_URI,
                arrayOf(android.provider.Telephony.Mms.Part.TEXT),
                "${android.provider.Telephony.Mms.Part.MSG_ID} = ? AND ${android.provider.Telephony.Mms.Part.CONTENT_TYPE} = ?",
                arrayOf(msgId.toString(), "text/plain"),
                null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "part lookup failed for mms $msgId", e)
            null
        }
    }

    private companion object {
        const val TAG = "MMS_RECEIVER"
    }
}
