package com.autonomousone.messages.mms

import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import com.autonomousone.messages.utils.DiagnosticLog
import java.io.ByteArrayOutputStream

/**
 * Handles MMS sending (images, audio) using Android's MMS content provider
 * and SmsManager.sendMultimediaMessage().
 *
 * Requires this app to be the default SMS app to write to content://mms/.
 */
class MmsSender(private val context: Context) {

    companion object {
        private const val TAG = "MMS_SENDER"
        /** Max MMS image size in bytes — most carriers cap at 1MB */
        private const val MAX_IMAGE_BYTES = 900_000
        private const val ADDR_FROM = 137 // PduHeaders.FROM
        private const val ADDR_TO = 151   // PduHeaders.TO
    }

    /**
     * Send an image URI as an MMS to the given phone number.
     *
     * @return [MmsSendResult.Queued] when the request row was created and handed to the platform — NOT
     *   that it was sent; the outcome arrives at [MmsStatusReceiver]. [MmsSendResult.Rejected] when it
     *   provably will not be sent, with a reason. The two used to be a bare Boolean, and every caller
     *   discarded it.
     */
    fun sendImage(phone: String, imageUri: Uri): MmsSendResult {
        return try {
            // Build and validate the payload BEFORE creating any provider row.
            //
            // Order matters twice over. A rejected send leaves no half-built MMS in the provider for
            // the mirror to replicate as a message that never existed, and the check cannot be
            // forgotten at the end of a long insert sequence.
            //
            // The image is still COMPRESSED first — that capability is unchanged — and only the result
            // is judged. A photo that compresses under the cap is sent as before; one that cannot
            // (or that will not decode at all) is refused with a reason instead of being written as an
            // oversize or empty part for the network to fail later.
            val mimeType = context.contentResolver.getType(imageUri) ?: "image/jpeg"
            val bytes = compressImage(imageUri, mimeType)
            val verdict = MmsPayloadPolicy.classify(bytes)
            if (verdict !is MmsPayloadVerdict.Usable) {
                return MmsSendResult.Rejected(
                    code = MmsPayloadPolicy.code(verdict)!!,
                    reason = MmsPayloadPolicy.reason(verdict)
                )
            }
            val mmsId = insertImageMms(phone, imageUri, bytes!!)
            if (mmsId > 0L) {
                triggerSend(mmsId)
                Log.d(TAG, "MMS image queued, id=$mmsId bytes=${bytes.size}")
                MmsSendResult.Queued(mmsId)
            } else {
                Log.e(TAG, "Failed to insert MMS into content provider")
                MmsSendResult.Rejected("mms_insert_failed", "the message could not be created")
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendImage error", e)
            MmsSendResult.Rejected("mms_insert_failed", e.message ?: "insert failed")
        }
    }

    /**
     * Send an audio file URI as an MMS to the given phone number.
     *
     * @return as [sendImage]: queued is not sent, and a rejection carries a reason.
     */
    fun sendAudio(phone: String, audioUri: Uri): MmsSendResult {
        return try {
            // Read the source before inserting, so an unreadable or oversize recording is refused
            // instead of producing a part with no bytes in it.
            val bytes = loadBounded(audioUri)
            val verdict = MmsPayloadPolicy.classify(bytes)
            if (verdict !is MmsPayloadVerdict.Usable) {
                return MmsSendResult.Rejected(
                    code = MmsPayloadPolicy.code(verdict)!!,
                    reason = MmsPayloadPolicy.reason(verdict)
                )
            }
            val mmsId = insertAudioMms(phone, audioUri, bytes!!)
            if (mmsId > 0L) {
                triggerSend(mmsId)
                Log.d(TAG, "MMS audio queued, id=$mmsId bytes=${bytes.size}")
                MmsSendResult.Queued(mmsId)
            } else {
                Log.e(TAG, "Failed to insert audio MMS")
                MmsSendResult.Rejected("mms_insert_failed", "the message could not be created")
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendAudio error", e)
            MmsSendResult.Rejected("mms_insert_failed", e.message ?: "insert failed")
        }
    }

    /**
     * Send one text message to MULTIPLE recipients as a single group MMS
     * (Google Messages-style group conversation) instead of N separate SMS.
     *
     * Creates a proper group thread via Telephony.Threads so the conversation
     * shows up as one thread with every recipient attached.
     *
     * @return as [sendImage]: queued is not sent, and a rejection carries a reason.
     */
    fun sendGroupText(recipients: List<String>, text: String): MmsSendResult {
        val cleaned = recipients.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (cleaned.isEmpty() || text.isBlank()) {
            return MmsSendResult.Rejected("mms_empty_payload", "no recipient or no text to send")
        }
        return try {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val verdict = MmsPayloadPolicy.classify(bytes)
            if (verdict !is MmsPayloadVerdict.Usable) {
                return MmsSendResult.Rejected(
                    code = MmsPayloadPolicy.code(verdict)!!,
                    reason = MmsPayloadPolicy.reason(verdict)
                )
            }
            val mmsId = insertTextMms(cleaned, text, bytes)
            if (mmsId > 0L) {
                triggerSend(mmsId)
                Log.d(TAG, "Group MMS queued to ${cleaned.size} recipients, id=$mmsId")
                MmsSendResult.Queued(mmsId)
            } else {
                Log.e(TAG, "Failed to insert group text MMS")
                MmsSendResult.Rejected("mms_insert_failed", "the message could not be created")
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendGroupText error", e)
            MmsSendResult.Rejected("mms_insert_failed", e.message ?: "insert failed")
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /** Text-only group MMS row + one TO address per recipient + a text part. */
    private fun insertTextMms(recipients: List<String>, text: String, bytes: ByteArray): Long {
        val cr = context.contentResolver
        // Set overload builds the GROUP thread id (same for all recipients).
        val threadId = Telephony.Threads.getOrCreateThreadId(context, recipients.toSet())

        val values = mmsBaseValues(threadId).apply {
            // multipart.mixed = generic attachments container; correct for plain text.
            put(Telephony.Mms.CONTENT_TYPE, "application/vnd.wap.multipart.mixed")
        }
        val mmsUri = cr.insert(Telephony.Mms.CONTENT_URI, values) ?: return -1
        val mmsId = ContentUris.parseId(mmsUri)

        insertAddr(mmsId, "insert-address-token", ADDR_FROM)
        recipients.forEach { insertAddr(mmsId, it, ADDR_TO) }

        val partValues = ContentValues().apply {
            put(Telephony.Mms.Part.MSG_ID, mmsId)
            put(Telephony.Mms.Part.CONTENT_TYPE, "text/plain")
            put(Telephony.Mms.Part.CHARSET, 106) // UTF-8
            put(Telephony.Mms.Part.NAME, "text_0.txt")
        }
        if (!writePart(mmsId, partValues, bytes)) return rollback(cr, mmsId)

        return mmsId
    }

    private fun insertImageMms(phone: String, imageUri: Uri, imageBytes: ByteArray): Long {
        val cr = context.contentResolver
        val threadId = Telephony.Threads.getOrCreateThreadId(context, phone)

        // 1. Create MMS send request row
        val mmsUri = cr.insert(Telephony.Mms.CONTENT_URI, mmsBaseValues(threadId)) ?: return -1
        val mmsId = ContentUris.parseId(mmsUri)

        // 2. Addresses
        insertAddr(mmsId, "insert-address-token", ADDR_FROM)
        insertAddr(mmsId, phone, ADDR_TO)

        // 3. Image part — the bytes are already validated, so a failure here is a provider fault
        val partValues = ContentValues().apply {
            put(Telephony.Mms.Part.MSG_ID, mmsId)
            put(Telephony.Mms.Part.CONTENT_TYPE, "image/jpeg")
            put(Telephony.Mms.Part.FILENAME, "image.jpg")
            put(Telephony.Mms.Part.NAME, "image.jpg")
        }
        if (!writePart(mmsId, partValues, imageBytes)) return rollback(cr, mmsId)

        return mmsId
    }

    private fun insertAudioMms(phone: String, audioUri: Uri, audioBytes: ByteArray): Long {
        val cr = context.contentResolver
        val threadId = Telephony.Threads.getOrCreateThreadId(context, phone)

        val mmsUri = cr.insert(Telephony.Mms.CONTENT_URI, mmsBaseValues(threadId)) ?: return -1
        val mmsId = ContentUris.parseId(mmsUri)

        insertAddr(mmsId, "insert-address-token", ADDR_FROM)
        insertAddr(mmsId, phone, ADDR_TO)

        val mimeType = cr.getType(audioUri) ?: "audio/mpeg"
        val fileName = getFileName(audioUri) ?: "audio.mp3"

        val partValues = ContentValues().apply {
            put(Telephony.Mms.Part.MSG_ID, mmsId)
            put(Telephony.Mms.Part.CONTENT_TYPE, mimeType)
            put(Telephony.Mms.Part.FILENAME, fileName)
            put(Telephony.Mms.Part.NAME, fileName)
        }
        if (!writePart(mmsId, partValues, audioBytes)) return rollback(cr, mmsId)

        return mmsId
    }

    /**
     * Create a part and write its payload, reporting whether it actually landed.
     *
     * This replaces `cr.openOutputStream(partUri)?.use { out -> out.write(bytes) }`, whose `?.` made a
     * null stream indistinguishable from a successful write: the part stayed empty, the function
     * returned a valid MMS id, and the caller reported success for a message with no content.
     */
    private fun writePart(mmsId: Long, partValues: ContentValues, bytes: ByteArray): Boolean {
        val cr = context.contentResolver
        val partUri = cr.insert(Uri.parse("content://mms/$mmsId/part"), partValues) ?: run {
            Log.e(TAG, "MMS $mmsId: the part row could not be created")
            return false
        }
        val stream = cr.openOutputStream(partUri) ?: run {
            Log.e(TAG, "MMS $mmsId: the part stream could not be opened")
            return false
        }
        return try {
            stream.use { it.write(bytes) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "MMS $mmsId: writing the part failed", e)
            false
        }
    }

    /**
     * Remove an MMS row THIS APP just created and could not finish.
     *
     * A rollback of our own incomplete insert, not a delete of anything the user owns: the row was
     * created moments ago in this call, has no payload, and was never handed to the platform. Leaving
     * it would be worse than removing it — an empty OUTBOX row is a real message in the provider, so
     * the mirror would replicate it and GMweb would show a message that never existed.
     *
     * @return -1, so the caller's `mmsId > 0` contract is unchanged.
     */
    private fun rollback(cr: android.content.ContentResolver, mmsId: Long): Long {
        val removed = runCatching {
            cr.delete(ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, mmsId), null, null)
        }.getOrDefault(0)
        Log.e(TAG, "MMS $mmsId rolled back after a failed part write (rows removed=$removed)")
        DiagnosticLog.event("MMS_SEND", "insert-rolled-back mms=$mmsId")
        return -1
    }

    /**
     * Read a source, refusing anything larger than a part may be.
     *
     * Reads at most [MmsPayloadPolicy.READ_LIMIT_BYTES] — enough to tell "too large" from "fine" —
     * so a tens-of-megabytes recording is never buffered just to be rejected.
     *
     * @return null when the source cannot be opened at all.
     */
    private fun loadBounded(uri: Uri): ByteArray? {
        val stream = context.contentResolver.openInputStream(uri) ?: return null
        return stream.use { input ->
            val buffer = ByteArray(MmsPayloadPolicy.READ_LIMIT_BYTES)
            var total = 0
            while (total < buffer.size) {
                val read = input.read(buffer, total, buffer.size - total)
                if (read <= 0) break
                total += read
            }
            buffer.copyOf(total)
        }
    }

    private fun mmsBaseValues(threadId: Long) = ContentValues().apply {
        put(Telephony.Mms.THREAD_ID, threadId)
        put(Telephony.Mms.MESSAGE_TYPE, 0x80)  // MESSAGE_TYPE_SEND_REQ
        put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_OUTBOX)
        put(Telephony.Mms.CONTENT_TYPE, "application/vnd.wap.multipart.related")
        put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000L)
        put(Telephony.Mms.READ, 1)
        put(Telephony.Mms.SEEN, 1)
        put(Telephony.Mms.MESSAGE_CLASS, "personal")
        put(Telephony.Mms.PRIORITY, 129)  // PRIORITY_NORMAL
    }

    private fun insertAddr(mmsId: Long, address: String, type: Int) {
        val values = ContentValues().apply {
            put(Telephony.Mms.Addr.MSG_ID, mmsId)
            put(Telephony.Mms.Addr.ADDRESS, address)
            put(Telephony.Mms.Addr.TYPE, type)
            put(Telephony.Mms.Addr.CHARSET, 106) // UTF-8
        }
        context.contentResolver.insert(Uri.parse("content://mms/$mmsId/addr"), values)
    }

    /**
     * Hand the request to the platform's MMS stack, and collect the RESULT.
     *
     * The `PendingIntent` used to be `null`, which is not "no callback needed" — it is silence. The
     * platform had nowhere to deliver the outcome, so a failed MMS stayed in the OUTBOX looking like a
     * message that was still being sent, and nothing in the app or the gateway could tell a picture
     * that left the device from one that never did.
     *
     * The intent is explicit (this receiver, by component) so the result still arrives after the
     * sending process is gone, and `FLAG_IMMUTABLE` because nothing needs to fill it in — the same
     * shape the SMS status intents already use.
     */
    private fun triggerSend(mmsId: Long) {
        val mmsUri = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, mmsId)
        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION") SmsManager.getDefault()
        }
        val resultIntent = PendingIntent.getBroadcast(
            context,
            // Unique per MMS row: two sends must not share a PendingIntent, or the second replaces
            // the first's result and one outcome is lost.
            (mmsId and 0x7FFFFFFF).toInt(),
            Intent(context, MmsStatusReceiver::class.java)
                .setAction(MmsStatusReceiver.ACTION_MMS_SENT)
                .putExtra(MmsStatusReceiver.EXTRA_MMS_ID, mmsId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // null locationUrl → system uses carrier MMSC settings automatically
        smsManager.sendMultimediaMessage(context, mmsUri, null, null, resultIntent)
    }

    /**
     * Compress an image to fit within MAX_IMAGE_BYTES, reducing quality progressively.
     */
    private fun compressImage(imageUri: Uri, originalMime: String): ByteArray {
        val cr = context.contentResolver

        // Decode with sub-sampling if very large
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(imageUri)?.use { BitmapFactory.decodeStream(it, null, opts) }

        var sampleSize = 1
        val maxDim = 1600
        while (opts.outWidth / sampleSize > maxDim || opts.outHeight / sampleSize > maxDim) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap: Bitmap = cr.openInputStream(imageUri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: return ByteArray(0)

        // Compress to JPEG, reducing quality until under MAX_IMAGE_BYTES
        var quality = 90
        var bytes: ByteArray
        do {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            bytes = baos.toByteArray()
            quality -= 10
        } while (bytes.size > MAX_IMAGE_BYTES && quality > 20)

        bitmap.recycle()
        return bytes
    }

    private fun getFileName(uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex("_display_name")
            if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
        }
    }
}
