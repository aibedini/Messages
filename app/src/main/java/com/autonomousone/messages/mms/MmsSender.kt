package com.autonomousone.messages.mms

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
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
     * @return true if dispatch succeeded, false on error.
     */
    fun sendImage(phone: String, imageUri: Uri): Boolean {
        return try {
            val mmsId = insertImageMms(phone, imageUri)
            if (mmsId > 0L) {
                triggerSend(mmsId)
                Log.d(TAG, "MMS image queued, id=$mmsId")
                true
            } else {
                Log.e(TAG, "Failed to insert MMS into content provider")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendImage error", e)
            false
        }
    }

    /**
     * Send an audio file URI as an MMS to the given phone number.
     */
    fun sendAudio(phone: String, audioUri: Uri): Boolean {
        return try {
            val mmsId = insertAudioMms(phone, audioUri)
            if (mmsId > 0L) {
                triggerSend(mmsId)
                Log.d(TAG, "MMS audio queued, id=$mmsId")
                true
            } else {
                Log.e(TAG, "Failed to insert audio MMS")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendAudio error", e)
            false
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun insertImageMms(phone: String, imageUri: Uri): Long {
        val cr = context.contentResolver
        val threadId = Telephony.Threads.getOrCreateThreadId(context, phone)

        // 1. Create MMS send request row
        val mmsUri = cr.insert(Telephony.Mms.CONTENT_URI, mmsBaseValues(threadId)) ?: return -1
        val mmsId = ContentUris.parseId(mmsUri)

        // 2. Addresses
        insertAddr(mmsId, "insert-address-token", ADDR_FROM)
        insertAddr(mmsId, phone, ADDR_TO)

        // 3. Image part — compress to fit carrier limits
        val mimeType = cr.getType(imageUri) ?: "image/jpeg"
        val imageBytes = compressImage(imageUri, mimeType)

        val partValues = ContentValues().apply {
            put(Telephony.Mms.Part.MSG_ID, mmsId)
            put(Telephony.Mms.Part.CONTENT_TYPE, "image/jpeg")
            put(Telephony.Mms.Part.FILENAME, "image.jpg")
            put(Telephony.Mms.Part.NAME, "image.jpg")
        }
        val partUri = cr.insert(Uri.parse("content://mms/$mmsId/part"), partValues) ?: return -1
        cr.openOutputStream(partUri)?.use { out -> out.write(imageBytes) }

        return mmsId
    }

    private fun insertAudioMms(phone: String, audioUri: Uri): Long {
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
        val partUri = cr.insert(Uri.parse("content://mms/$mmsId/part"), partValues) ?: return -1
        cr.openOutputStream(partUri)?.use { out ->
            cr.openInputStream(audioUri)?.use { inp -> inp.copyTo(out) }
        }

        return mmsId
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

    private fun triggerSend(mmsId: Long) {
        val mmsUri = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, mmsId)
        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION") SmsManager.getDefault()
        }
        // null locationUrl → system uses carrier MMSC settings automatically
        smsManager.sendMultimediaMessage(context, mmsUri, null, null, null)
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
