package com.autonomousone.messages.repository

import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.repository.ContactRepository

/**
 * Progress emitted while a bulk SMS/MMS read is in flight.
 *
 * @param phase one of "sms", "mms" or "contacts" so the UI can show a friendly label.
 * @param loaded how many rows have been read so far.
 * @param total  total rows the provider reported for this phase (0 when unknown).
 */
data class LoadProgress(
    val phase: String,
    val loaded: Int,
    val total: Int
)

/** Receives [LoadProgress] updates while the repository iterates a content cursor. */
fun interface ProgressListener {
    fun onProgress(progress: LoadProgress)
}

class SmsRepository(
    private val context: Context
) {

    /** Fast path: one row per SMS/MMS thread instead of materializing every message. */
    fun getConversationsFast(
        progress: ProgressListener? = null,
        onPartial: ((List<Sms>) -> Unit)? = null
    ): List<Sms> {
        val result = mutableListOf<Sms>()
        try {
            val canonicalAddresses = loadCanonicalAddresses()
            var providerResponded = false
            val projection = arrayOf(
                Telephony.Threads._ID,
                Telephony.Threads.DATE,
                Telephony.Threads.RECIPIENT_IDS,
                Telephony.Threads.SNIPPET,
                Telephony.Threads.READ
            )
            context.contentResolver.query(
                Telephony.Threads.CONTENT_URI,
                projection,
                null,
                null,
                "${Telephony.Threads.DATE} DESC"
            )?.use { cursor ->
                providerResponded = true
                val idIndex = cursor.getColumnIndexOrThrow(Telephony.Threads._ID)
                val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Threads.DATE)
                val recipientsIndex = cursor.getColumnIndexOrThrow(Telephony.Threads.RECIPIENT_IDS)
                val snippetIndex = cursor.getColumnIndexOrThrow(Telephony.Threads.SNIPPET)
                val readIndex = cursor.getColumnIndexOrThrow(Telephony.Threads.READ)
                val total = cursor.count
                var loaded = 0
                while (cursor.moveToNext()) {
                    loaded++
                    val threadId = cursor.getLong(idIndex)
                    val recipients = cursor.getString(recipientsIndex).orEmpty()
                        .split(' ')
                        .mapNotNull { it.toLongOrNull()?.let(canonicalAddresses::get) }
                        .filter { it.isNotBlank() }
                    val sender = recipients.joinToString(", ").ifBlank {
                        resolveSmsAddressForThread(threadId)
                    }
                    result += Sms(
                        id = threadId,
                        threadId = threadId,
                        sender = sender.ifBlank { "Unknown" },
                        message = cursor.getString(snippetIndex).orEmpty(),
                        date = cursor.getLong(dateIndex),
                        unread = cursor.getInt(readIndex) == 0,
                        type = 1
                    )
                    if (loaded == total || loaded % 25 == 0) {
                        progress?.onProgress(LoadProgress("threads", loaded, total))
                        onPartial?.invoke(result.toList())
                    }
                }
                if (total == 0) progress?.onProgress(LoadProgress("threads", 0, 0))
            }
            if (!providerResponded) return getConversations(progress, onPartial)
            return result
        } catch (error: Exception) {
            Log.w("SMS_DEBUG", "Thread provider unavailable; using compatibility scan", error)
            return getConversations(progress, onPartial)
        }
    }

    private fun loadCanonicalAddresses(): Map<Long, String> {
        val result = mutableMapOf<Long, String>()
        try {
            context.contentResolver.query(
                Uri.parse("content://mms-sms/canonical-addresses"),
                arrayOf("_id", "address"),
                null,
                null,
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow("_id")
                val addressIndex = cursor.getColumnIndexOrThrow("address")
                while (cursor.moveToNext()) {
                    result[cursor.getLong(idIndex)] = cursor.getString(addressIndex).orEmpty()
                }
            }
        } catch (error: Exception) {
            Log.w("SMS_DEBUG", "Canonical addresses unavailable", error)
        }
        return result
    }

    private fun resolveSmsAddressForThread(threadId: Long): String {
        return try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS),
                "${Telephony.Sms.THREAD_ID} = ?",
                arrayOf(threadId.toString()),
                "${Telephony.Sms.DATE} DESC LIMIT 1"
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
            }.orEmpty()
        } catch (error: Exception) {
            ""
        }
    }

    fun getAllSms(): List<Sms> {
        return getSmsWithFilters()
    }

    /**
     * Raw paged SMS query used by [ThreadPager]: supports LIMIT/OFFSET so a
     * conversation can be windowed instead of fully loaded.
     */
    fun querySmsRaw(
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String,
        limit: Int = Int.MAX_VALUE,
        offset: Int = 0
    ): List<Sms> {
        val out = mutableListOf<Sms>()
        try {
            val projection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.THREAD_ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.DATE_SENT,
                Telephony.Sms.READ,
                Telephony.Sms.TYPE,
                Telephony.Sms.STATUS
            )
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI.buildUpon()
                    .appendQueryParameter("limit", "$offset,$limit")
                    .build(),
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    out += smsFromCursor(cursor)
                }
            }
        } catch (e: Exception) {
            Log.e("SMS_DEBUG", "querySmsRaw failed", e)
        }
        return out
    }

    /** Paged MMS twin of [querySmsRaw]. */
    fun queryMmsRaw(
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String,
        limit: Int = Int.MAX_VALUE,
        offset: Int = 0
    ): List<Sms> {
        return try {
            val projection = arrayOf(
                Telephony.Mms._ID,
                Telephony.Mms.THREAD_ID,
                Telephony.Mms.DATE,
                Telephony.Mms.MESSAGE_BOX,
                Telephony.Mms.READ,
                Telephony.Mms.SUBJECT
            )
            data class Row(val id: Long, val threadId: Long, val date: Long, val box: Int, val read: Int, val subject: String?)
            val rows = mutableListOf<Row>()
            context.contentResolver.query(
                Uri.parse("content://mms").buildUpon()
                    .appendQueryParameter("limit", "$offset,$limit")
                    .build(),
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idI = cursor.getColumnIndexOrThrow(Telephony.Mms._ID)
                val thI = cursor.getColumnIndexOrThrow(Telephony.Mms.THREAD_ID)
                val dtI = cursor.getColumnIndexOrThrow(Telephony.Mms.DATE)
                val bxI = cursor.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX)
                val rdI = cursor.getColumnIndexOrThrow(Telephony.Mms.READ)
                val sbI = cursor.getColumnIndexOrThrow(Telephony.Mms.SUBJECT)
                while (cursor.moveToNext()) {
                    rows += Row(
                        cursor.getLong(idI), cursor.getLong(thI),
                        cursor.getLong(dtI) * 1000L, cursor.getInt(bxI), cursor.getInt(rdI), cursor.getString(sbI)
                    )
                }
            }
            if (rows.isEmpty()) return emptyList()
            val addressMap = loadMmsAddresses(rows.map { it.id })
            val bodyMap = loadMmsBodies(rows.map { it.id })
            rows.map { r ->
                Sms(
                    id = -r.id,
                    threadId = r.threadId,
                    sender = (addressMap[r.id] ?: "").let {
                        if (it.isBlank() || it.equals("insert-address-token", true)) "" else it
                    }.ifBlank { phoneFallbackForThread(r.threadId) },
                    message = bodyMap[r.id] ?: r.subject?.takeIf { s -> s.isNotBlank() } ?: "[MMS]",
                    date = r.date,
                    unread = r.read == 0,
                    type = if (r.box == Telephony.Mms.MESSAGE_BOX_INBOX) 1 else 2
                )
            }
        } catch (e: Exception) {
            Log.e("SMS_DEBUG", "queryMmsRaw failed", e)
            emptyList()
        }
    }

    private fun phoneFallbackForThread(threadId: Long): String =
        resolveSmsAddressForThread(threadId).ifBlank { "Unknown" }

    private fun smsFromCursor(cursor: android.database.Cursor): Sms =
        Sms(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms._ID)),
            threadId = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)),
            sender = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: "Unknown",
            message = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: "",
            date = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)),
            unread = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.READ)) == 0,
            type = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)),
            status = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.STATUS)),
            dateSent = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE_SENT))
        )

    fun getSmsWithFilters(
        limit: Int? = null,
        offset: Int? = null,
        type: String? = null,
        phone: String? = null,
        fromDate: Long? = null,
        toDate: Long? = null
    ): List<Sms> {
        val smsList = mutableListOf<Sms>()

        try {
            val projection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.THREAD_ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.DATE_SENT,
                Telephony.Sms.READ,
                Telephony.Sms.TYPE,
                Telephony.Sms.STATUS
            )

            val selectionParts = mutableListOf<String>()
            val selectionArgs = mutableListOf<String>()

            if (type != null) {
                when (type.lowercase()) {
                    "received" -> {
                        selectionParts.add("${Telephony.Sms.TYPE} = ?")
                        selectionArgs.add("1")
                    }
                    "sent" -> {
                        selectionParts.add("${Telephony.Sms.TYPE} = ?")
                        selectionArgs.add("2")
                    }
                }
            }

            if (phone != null && phone.isNotBlank()) {
                selectionParts.add("${Telephony.Sms.ADDRESS} LIKE ?")
                selectionArgs.add("%$phone%")
            }

            if (fromDate != null) {
                selectionParts.add("${Telephony.Sms.DATE} >= ?")
                selectionArgs.add(fromDate.toString())
            }

            if (toDate != null) {
                selectionParts.add("${Telephony.Sms.DATE} <= ?")
                selectionArgs.add(toDate.toString())
            }

            val selection = if (selectionParts.isNotEmpty()) {
                selectionParts.joinToString(" AND ")
            } else null

            val selectionArgsArray = if (selectionArgs.isNotEmpty()) {
                selectionArgs.toTypedArray()
            } else null

            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                selection,
                selectionArgsArray,
                "${Telephony.Sms.DATE} DESC"
            )?.use { cursor ->

                val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                val threadIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
                val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val dateSentIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE_SENT)
                val readIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
                val typeIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                val statusIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.STATUS)

                var count = 0
                val skipCount = offset ?: 0
                val takeCount = limit

                while (cursor.moveToNext()) {
                    if (count < skipCount) {
                        count++
                        continue
                    }

                    if (takeCount != null && count >= skipCount + takeCount) {
                        break
                    }

                    smsList.add(
                        Sms(
                            id = cursor.getLong(idIndex),
                            threadId = cursor.getLong(threadIndex),
                            sender = cursor.getString(addressIndex) ?: "Unknown",
                            message = cursor.getString(bodyIndex) ?: "",
                            date = cursor.getLong(dateIndex),
                            unread = cursor.getInt(readIndex) == 0,
                            type = cursor.getInt(typeIndex),
                            status = cursor.getInt(statusIndex),
                            dateSent = cursor.getLong(dateSentIndex)
                        )
                    )
                    count++
                }
            }

            Log.d("SMS_DEBUG", "SMS Read with filters = ${smsList.size}")

        } catch (e: Exception) {
            Log.e("SMS_DEBUG", "Error reading SMS with filters", e)
        }

        return smsList
    }

    /**
     * Deletes SMS messages sent/received BEFORE (true) or AFTER (false) [cutoffMillis].
     * Requires default-SMS-app status. Returns the number of deleted rows.
     */
    fun deleteSmsByRange(cutoffMillis: Long, before: Boolean): Int {
        return try {
            val selection =
                if (before) "${Telephony.Sms.DATE} <= ?" else "${Telephony.Sms.DATE} >= ?"
            context.contentResolver.delete(
                Telephony.Sms.CONTENT_URI,
                selection,
                arrayOf(cutoffMillis.toString())
            )
        } catch (e: Exception) {
            Log.e("SMS_DEBUG", "deleteSmsByRange failed", e)
            -1
        }
    }

    /**
     * MMS counterpart of [deleteSmsByRange]. Note: Telephony.Mms.DATE is in SECONDS.
     */
    fun deleteMmsByRange(cutoffMillis: Long, before: Boolean): Int {
        return try {
            val cutoffSeconds = cutoffMillis / 1000L
            val selection =
                if (before) "${Telephony.Mms.DATE} <= ?" else "${Telephony.Mms.DATE} >= ?"
            context.contentResolver.delete(
                Uri.parse("content://mms"),
                selection,
                arrayOf(cutoffSeconds.toString())
            )
        } catch (e: Exception) {
            Log.e("SMS_DEBUG", "deleteMmsByRange failed", e)
            -1
        }
    }

    /**
     * Group conversations by normalized phone address to eliminate duplicates.
     * Merges SMS + MMS so MMS-only threads appear and the latest message per
     * conversation (whether SMS or MMS) is shown.
     */
    fun getConversations(
        progress: ProgressListener? = null,
        onPartial: ((List<Sms>) -> Unit)? = null
    ): List<Sms> {
        val conversationMap = mutableMapOf<String, Sms>()

        try {
            scanSmsConversations(conversationMap, progress, onPartial)
            for (sms in queryMms(null, null, "${Telephony.Mms.DATE} DESC", progress)) {
                val norm = ContactRepository.normalizePhone(sms.sender)
                val key = if (sms.threadId > 0) "thread:${sms.threadId}"
                else if (norm.isNotBlank()) "address:$norm" else "address:${sms.sender}"
                val existing = conversationMap[key]
                if (existing == null || sms.date > existing.date) {
                    conversationMap[key] = sms
                }
            }
            onPartial?.invoke(conversationMap.values.sortedByDescending { it.date })

            Log.d("SMS_DEBUG", "Total Conversations = ${conversationMap.size}")

        } catch (e: Exception) {
            Log.e("SMS_DEBUG", "Error reading conversations", e)
        }

        return conversationMap.values.sortedByDescending { it.date }
    }

    private fun scanSmsConversations(
        conversationMap: MutableMap<String, Sms>,
        progress: ProgressListener?,
        onPartial: ((List<Sms>) -> Unit)?
    ) {
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.READ,
            Telephony.Sms.TYPE
        )
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            null,
            null,
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val threadIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val readIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
            val typeIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            val total = cursor.count
            var loaded = 0
            while (cursor.moveToNext()) {
                loaded++
                val sms = Sms(
                    id = cursor.getLong(idIndex),
                    threadId = cursor.getLong(threadIndex),
                    sender = cursor.getString(addressIndex) ?: "Unknown",
                    message = cursor.getString(bodyIndex).orEmpty(),
                    date = cursor.getLong(dateIndex),
                    unread = cursor.getInt(readIndex) == 0,
                    type = cursor.getInt(typeIndex)
                )
                val normalized = ContactRepository.normalizePhone(sms.sender)
                val key = if (sms.threadId > 0) "thread:${sms.threadId}" else "address:$normalized"
                if (!conversationMap.containsKey(key)) conversationMap[key] = sms

                val shouldEmit = loaded == 250 || loaded == total || loaded % 20_000 == 0
                if (shouldEmit) {
                    progress?.onProgress(LoadProgress("sms", loaded, total))
                    onPartial?.invoke(conversationMap.values.sortedByDescending { it.date })
                }
            }
            if (total == 0) progress?.onProgress(LoadProgress("sms", 0, 0))
        }
    }

    fun getMessagesByThread(threadId: Long, progress: ProgressListener? = null): List<Sms> {
        if (threadId <= 0) return emptyList()
        val sms = querySms(
            selection = "${Telephony.Sms.THREAD_ID} = ?",
            selectionArgs = arrayOf(threadId.toString()),
            sortOrder = "${Telephony.Sms.DATE} ASC",
            progress = progress
        )
        // Merge MMS rows belonging to the same thread
        val mms = queryMms(
            selection = "${Telephony.Mms.THREAD_ID} = ?",
            selectionArgs = arrayOf(threadId.toString()),
            sortOrder = "${Telephony.Mms.DATE} ASC",
            progress = progress
        )
        return (sms + mms).sortedBy { it.date }
    }

    fun getMessagesByPhone(phone: String, progress: ProgressListener? = null, threadIdHint: Long = 0L): List<Sms> {
        if (phone.isBlank()) return emptyList()
        val normalized = ContactRepository.normalizePhone(phone)
        val lastDigits = if (normalized.length >= 7) normalized.takeLast(7) else normalized

        val sms = querySms(
            selection = "(${Telephony.Sms.ADDRESS} LIKE ? OR ${Telephony.Sms.ADDRESS} = ?)",
            selectionArgs = arrayOf("%$lastDigits%", phone),
            sortOrder = "${Telephony.Sms.DATE} ASC",
            progress = progress
        )
        // When we know the thread, filter MMS at the provider level (fast).
        // Otherwise fall back to a full MMS scan filtered in memory — but only
        // report MMS progress when the device actually has MMS rows.
        val mms = queryMms(
            selection = if (threadIdHint > 0) "${Telephony.Mms.THREAD_ID} = ?" else null,
            selectionArgs = if (threadIdHint > 0) arrayOf(threadIdHint.toString()) else null,
            sortOrder = "${Telephony.Mms.DATE} ASC",
            progress = null // silent scan; SMS progress is what the user sees
        ).filter { mmsMsg ->
            val n = ContactRepository.normalizePhone(mmsMsg.sender)
            n.isNotBlank() && (n == normalized || n.endsWith(lastDigits) || lastDigits.endsWith(n))
        }
        return (sms + mms).sortedBy { it.date }
    }

    /**
     * Shared low-level query: read SMS rows matching [selection] into [Sms] models.
     */
    private fun querySms(
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String,
        progress: ProgressListener? = null
    ): List<Sms> {
        val smsList = mutableListOf<Sms>()
        try {
            val projection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.THREAD_ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.DATE_SENT,
                Telephony.Sms.READ,
                Telephony.Sms.TYPE,
                Telephony.Sms.STATUS
            )

            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                val threadIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
                val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val dateSentIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE_SENT)
                val readIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
                val typeIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                val statusIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.STATUS)

                val total = cursor.count
                var lastEmitted = -1
                while (cursor.moveToNext()) {
                    smsList.add(
                        Sms(
                            id = cursor.getLong(idIndex),
                            threadId = cursor.getLong(threadIndex),
                            sender = cursor.getString(addressIndex) ?: "Unknown",
                            message = cursor.getString(bodyIndex) ?: "",
                            date = cursor.getLong(dateIndex),
                            unread = cursor.getInt(readIndex) == 0,
                            type = cursor.getInt(typeIndex),
                            status = cursor.getInt(statusIndex),
                            dateSent = cursor.getLong(dateSentIndex)
                        )
                    )
                    if (progress != null && (smsList.size == total || smsList.size - lastEmitted >= 50)) {
                        lastEmitted = smsList.size
                        progress.onProgress(LoadProgress("sms", smsList.size, total))
                    }
                }
                if (progress != null && total == 0) {
                    progress.onProgress(LoadProgress("sms", 0, 0))
                }
            }
        } catch (e: Exception) {
            Log.e("SMS_DEBUG", "Error querying SMS", e)
        }
        return smsList
    }

    /**
     * Shared low-level query for MMS: reads Telephony.Mms rows and resolves each
     * message's counterpart address (from Telephony.Mms.Addr) and body/subject
     * (from Telephony.Mms.Part) into an [Sms] model.
     *
     * MMS row ids are negated so they never collide with real SMS ids when both
     * sources are merged. Reading MMS requires READ_SMS (or default-SMS status);
     * failures degrade gracefully to an empty list.
     */
    private fun queryMms(
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        sortOrder: String = "${Telephony.Mms.DATE} ASC",
        progress: ProgressListener? = null
    ): List<Sms> {
        val mmsList = mutableListOf<Sms>()
        try {
            val projection = arrayOf(
                Telephony.Mms._ID,
                Telephony.Mms.THREAD_ID,
                Telephony.Mms.DATE,
                Telephony.Mms.MESSAGE_BOX,
                Telephony.Mms.READ,
                Telephony.Mms.SUBJECT
            )

            data class Row(
                val id: Long,
                val threadId: Long,
                val date: Long,
                val box: Int,
                val read: Int,
                val subject: String?
            )

            val rows = mutableListOf<Row>()
            context.contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(Telephony.Mms._ID)
                val threadIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.THREAD_ID)
                val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.DATE)
                val boxIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX)
                val readIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.READ)
                val subIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.SUBJECT)
                val total = cursor.count
                var lastEmitted = -1
                while (cursor.moveToNext()) {
                    rows.add(
                        Row(
                            id = cursor.getLong(idIndex),
                            threadId = cursor.getLong(threadIndex),
                            date = cursor.getLong(dateIndex),
                            box = cursor.getInt(boxIndex),
                            read = cursor.getInt(readIndex),
                            subject = cursor.getString(subIndex)
                        )
                    )
                    if (progress != null && (rows.size == total || rows.size - lastEmitted >= 50)) {
                        lastEmitted = rows.size
                        progress.onProgress(LoadProgress("mms", rows.size, total))
                    }
                }
                if (progress != null && total == 0) {
                    progress.onProgress(LoadProgress("mms", 0, 0))
                }
            }

            // The MMS cursor is now closed. Resolve addresses and bodies in bulk with
            // one query each — per-message nested queries inside the cursor loop can
            // deadlock the MMS provider and hang the conversation load.
            if (rows.isNotEmpty()) {
                val ids = rows.map { it.id }
                val addressMap = loadMmsAddresses(ids)
                val bodyMap = loadMmsBodies(ids)
                for (row in rows) {
                    mmsList.add(
                        Sms(
                            id = -row.id,
                            threadId = row.threadId,
                            sender = (addressMap[row.id] ?: "").ifBlank { "Unknown" },
                            message = bodyMap[row.id]
                                ?: row.subject?.takeIf { it.isNotBlank() }
                                ?: "[MMS]",
                            date = row.date * 1_000L,
                            unread = row.read == 0,
                            type = if (row.box == Telephony.Mms.MESSAGE_BOX_INBOX) 1 else 2
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("SMS_DEBUG", "Error querying MMS", e)
        }
        return mmsList
    }

    /**
     * Bulk-resolve counterpart phone numbers for a set of MMS ids from the Addr
     * table. Prefers the FROM address (TYPE 137) and falls back to TO (151).
     */
    private fun loadMmsAddresses(msgIds: List<Long>): Map<Long, String> {
        val result = mutableMapOf<Long, String>()
        if (msgIds.isEmpty()) return result
        try {
            val projection = arrayOf(
                Telephony.Mms.Addr.MSG_ID,
                Telephony.Mms.Addr.ADDRESS,
                Telephony.Mms.Addr.TYPE
            )
            context.contentResolver.query(
                Uri.parse("content://mms/addr"),
                projection,
                "${Telephony.Mms.Addr.MSG_ID} IN (${msgIds.joinToString(",")})",
                null,
                null
            )?.use { cursor ->
                val msgIdIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.Addr.MSG_ID)
                val addrIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.Addr.ADDRESS)
                val typeIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.Addr.TYPE)
                val from = mutableMapOf<Long, String>()
                val to = mutableMapOf<Long, String>()
                val other = mutableMapOf<Long, String>()
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(msgIdIndex)
                    var addr = cursor.getString(addrIndex)?.trim() ?: ""
                    if (addr.isBlank()) continue
                    // Skip the placeholder token some stacks store for the FROM
                    // address, and strip stray '+' separators that make headers
                    // render like "+98+991+716+6…".
                    if (addr.equals("insert-address-token", ignoreCase = true)) continue
                    addr = ContactRepository.normalizePhone(addr)
                    when (cursor.getInt(typeIndex)) {
                        137 -> if (!from.containsKey(id)) from[id] = addr
                        151 -> if (!to.containsKey(id)) to[id] = addr
                        else -> if (!other.containsKey(id)) other[id] = addr
                    }
                }
                val allIds = (from.keys + to.keys + other.keys).distinct()
                for (id in allIds) {
                    val addr = from[id] ?: to[id] ?: other[id] ?: ""
                    if (addr.isNotBlank()) result[id] = addr
                }
            }
        } catch (e: Exception) {
            Log.e("SMS_DEBUG", "Error loading MMS addresses", e)
        }
        return result
    }

    /**
     * Bulk-resolve the display body for a set of MMS ids from the Part table.
     * Prefers the text/plain part, then an attachment placeholder.
     */
    private fun loadMmsBodies(msgIds: List<Long>): Map<Long, String> {
        val result = mutableMapOf<Long, String>()
        if (msgIds.isEmpty()) return result
        try {
            val projection = arrayOf(
                Telephony.Mms.Part.MSG_ID,
                Telephony.Mms.Part.CONTENT_TYPE,
                Telephony.Mms.Part.TEXT
            )
            context.contentResolver.query(
                Telephony.Mms.Part.CONTENT_URI,
                projection,
                "${Telephony.Mms.Part.MSG_ID} IN (${msgIds.joinToString(",")})",
                null,
                null
            )?.use { cursor ->
                val msgIdIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.Part.MSG_ID)
                val ctIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.Part.CONTENT_TYPE)
                val textIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.Part.TEXT)
                val text = mutableMapOf<Long, String>()
                val attachments = mutableSetOf<Long>()
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(msgIdIndex)
                    val ct = cursor.getString(ctIndex)?.lowercase() ?: ""
                    when {
                        ct == "text/plain" -> {
                            val body = cursor.getString(textIndex)
                            if (!body.isNullOrBlank() && !text.containsKey(id)) text[id] = body
                        }
                        ct.startsWith("image/") || ct.startsWith("audio/") || ct.startsWith("video/") -> {
                            attachments.add(id)
                        }
                    }
                }
                val allIds = (text.keys + attachments).distinct()
                for (id in allIds) {
                    val body = text[id]
                    when {
                        body != null -> result[id] = body
                        id in attachments -> result[id] = "[MMS]"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SMS_DEBUG", "Error loading MMS bodies", e)
        }
        return result
    }

    fun markThreadAsRead(threadId: Long, phone: String = "") {
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.READ, 1)
            }
            if (threadId > 0) {
                context.contentResolver.update(
                    Telephony.Sms.CONTENT_URI,
                    values,
                    "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
                    arrayOf(threadId.toString())
                )
            }
            if (phone.isNotBlank()) {
                val normalized = ContactRepository.normalizePhone(phone)
                val lastDigits = if (normalized.length >= 7) normalized.takeLast(7) else normalized
                context.contentResolver.update(
                    Telephony.Sms.CONTENT_URI,
                    values,
                    "(${Telephony.Sms.ADDRESS} LIKE ? OR ${Telephony.Sms.ADDRESS} = ?) AND ${Telephony.Sms.READ} = 0",
                    arrayOf("%$lastDigits%", phone)
                )
            }
            // Mark matching MMS rows (thread-based) as read too
            if (threadId > 0) {
                val mmsValues = ContentValues().apply {
                    put(Telephony.Mms.READ, 1)
                }
                context.contentResolver.update(
                    Telephony.Mms.CONTENT_URI,
                    mmsValues,
                    "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.READ} = 0",
                    arrayOf(threadId.toString())
                )
            }
        } catch (e: Exception) {
            Log.e("SMS_DEBUG", "Error marking thread as read", e)
        }
    }

    fun markAllAsRead() {
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.READ, 1)
            }
            context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                "${Telephony.Sms.READ} = 0",
                null
            )
            val mmsValues = ContentValues().apply {
                put(Telephony.Mms.READ, 1)
            }
            context.contentResolver.update(
                Telephony.Mms.CONTENT_URI,
                mmsValues,
                "${Telephony.Mms.READ} = 0",
                null
            )
        } catch (e: Exception) {
            Log.e("SMS_DEBUG", "Error marking all as read", e)
        }
    }

    /**
     * Permanently delete all messages belonging to [threadId] from the system SMS ContentProvider.
     * If [threadId] is 0 (unknown), falls back to deleting by [phone] address.
     */
    fun deleteThread(threadId: Long, phone: String = "") {
        try {
            if (threadId > 0) {
                context.contentResolver.delete(
                    Telephony.Sms.CONTENT_URI,
                    "${Telephony.Sms.THREAD_ID} = ?",
                    arrayOf(threadId.toString())
                )
                context.contentResolver.delete(
                    Telephony.Mms.CONTENT_URI,
                    "${Telephony.Mms.THREAD_ID} = ?",
                    arrayOf(threadId.toString())
                )
                Log.d("SMS_DEBUG", "Deleted thread $threadId")
            } else if (phone.isNotBlank()) {
                val normalized = ContactRepository.normalizePhone(phone)
                val lastDigits = if (normalized.length >= 7) normalized.takeLast(7) else normalized
                context.contentResolver.delete(
                    Telephony.Sms.CONTENT_URI,
                    "${Telephony.Sms.ADDRESS} LIKE ? OR ${Telephony.Sms.ADDRESS} = ?",
                    arrayOf("%$lastDigits%", phone)
                )
                Log.d("SMS_DEBUG", "Deleted messages for phone $phone")
            }
        } catch (e: Exception) {
            Log.e("SMS_DEBUG", "Error deleting thread $threadId", e)
        }
    }

    /**
     * Observe SMS database changes
     */
    private var registeredObserver: ContentObserver? = null

    /**
     * Cheap change-detection: does any SMS/MMS row exist with a date newer
     * than [newestKnownDateMillis]? One indexed single-row query instead of a
     * full conversation scan — used to decide whether a resume needs a sync.
     */
    fun hasProviderChangedSince(newestKnownDateMillis: Long): Boolean {
        if (newestKnownDateMillis <= 0L) return true
        return try {
            // Any SMS newer than what we know?
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID),
                "${Telephony.Sms.DATE} > ?",
                arrayOf(newestKnownDateMillis.toString()),
                "LIMIT 1"
            )?.use { if (it.moveToFirst()) return true }
            // Any MMS newer? (MMS dates are seconds)
            context.contentResolver.query(
                Uri.parse("content://mms"),
                arrayOf(Telephony.Mms._ID),
                "${Telephony.Mms.DATE} > ?",
                arrayOf((newestKnownDateMillis / 1000L).toString()),
                "LIMIT 1"
            )?.use { if (it.moveToFirst()) return true }
            false
        } catch (_: Exception) {
            true // be safe: assume changed when the check itself fails
        }
    }

    /** Nudges the ContentObserver after bulk external writes (e.g. restore). */
    fun notifyExternalChange() {
        registeredObserver?.dispatchChange(false, null)
    }

    fun registerObserver(
        observer: ContentObserver
    ) {
        registeredObserver = observer
        context.contentResolver.registerContentObserver(
            Telephony.Sms.CONTENT_URI,
            true,
            observer
        )
        // Also react to MMS database changes so MMS appear live in the UI
        try {
            context.contentResolver.registerContentObserver(
                Telephony.Mms.CONTENT_URI,
                true,
                observer
            )
        } catch (e: Exception) {
            Log.e("SMS_DEBUG", "Error registering MMS observer", e)
        }
    }

    /**
     * Stop observing SMS database
     */
    fun unregisterObserver(
        observer: ContentObserver
    ) {
        if (registeredObserver === observer) registeredObserver = null
        context.contentResolver.unregisterContentObserver(
            observer
        )
    }
}
