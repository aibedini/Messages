package com.autonomousone.messages.repository

import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.provider.Telephony
import android.util.Log
import com.autonomousone.messages.model.Sms

class SmsRepository(
    private val context: Context
) {

    fun getAllSms(): List<Sms> {
        return getSmsWithFilters()
    }

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
                Telephony.Sms.READ,
                Telephony.Sms.TYPE
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
                val readIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
                val typeIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)

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
                            type = cursor.getInt(typeIndex)
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
     * Group conversations by normalized phone address to eliminate duplicates.
     * Merges SMS + MMS so MMS-only threads appear and the latest message per
     * conversation (whether SMS or MMS) is shown.
     */
    fun getConversations(): List<Sms> {
        val conversationMap = mutableMapOf<String, Sms>()

        try {
            // Both queries return most-recent-first. We keep the row with the
            // highest date per normalized phone key regardless of source.
            val allMessages = mutableListOf<Sms>()
            allMessages.addAll(querySms(null, null, "${Telephony.Sms.DATE} DESC"))
            allMessages.addAll(queryMms(null, null, "${Telephony.Mms.DATE} DESC"))

            for (sms in allMessages) {
                val norm = ContactRepository.normalizePhone(sms.sender)
                val key = if (norm.isNotBlank()) norm else sms.sender
                val existing = conversationMap[key]
                if (existing == null || sms.date > existing.date) {
                    conversationMap[key] = sms
                }
            }

            Log.d("SMS_DEBUG", "Total Conversations = ${conversationMap.size}")

        } catch (e: Exception) {
            Log.e("SMS_DEBUG", "Error reading conversations", e)
        }

        return conversationMap.values.sortedByDescending { it.date }
    }

    fun getMessagesByThread(threadId: Long): List<Sms> {
        if (threadId <= 0) return emptyList()
        val sms = querySms(
            selection = "${Telephony.Sms.THREAD_ID} = ?",
            selectionArgs = arrayOf(threadId.toString()),
            sortOrder = "${Telephony.Sms.DATE} ASC"
        )
        // Merge MMS rows belonging to the same thread
        val mms = queryMms(
            selection = "${Telephony.Mms.THREAD_ID} = ?",
            selectionArgs = arrayOf(threadId.toString()),
            sortOrder = "${Telephony.Mms.DATE} ASC"
        )
        return (sms + mms).sortedBy { it.date }
    }

    fun getMessagesByPhone(phone: String): List<Sms> {
        if (phone.isBlank()) return emptyList()
        val normalized = ContactRepository.normalizePhone(phone)
        val lastDigits = if (normalized.length >= 7) normalized.takeLast(7) else normalized

        val sms = querySms(
            selection = "(${Telephony.Sms.ADDRESS} LIKE ? OR ${Telephony.Sms.ADDRESS} = ?)",
            selectionArgs = arrayOf("%$lastDigits%", phone),
            sortOrder = "${Telephony.Sms.DATE} ASC"
        )
        // MMS addresses live in a separate Addr table, so match on the resolved address in memory
        val mms = queryMms(
            selection = null,
            selectionArgs = null,
            sortOrder = "${Telephony.Mms.DATE} ASC"
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
        sortOrder: String
    ): List<Sms> {
        val smsList = mutableListOf<Sms>()
        try {
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
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                val threadIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
                val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val readIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
                val typeIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)

                while (cursor.moveToNext()) {
                    smsList.add(
                        Sms(
                            id = cursor.getLong(idIndex),
                            threadId = cursor.getLong(threadIndex),
                            sender = cursor.getString(addressIndex) ?: "Unknown",
                            message = cursor.getString(bodyIndex) ?: "",
                            date = cursor.getLong(dateIndex),
                            unread = cursor.getInt(readIndex) == 0,
                            type = cursor.getInt(typeIndex)
                        )
                    )
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
        sortOrder: String = "${Telephony.Mms.DATE} ASC"
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

                while (cursor.moveToNext()) {
                    val msgId = cursor.getLong(idIndex)
                    val box = cursor.getInt(boxIndex)
                    mmsList.add(
                        Sms(
                            id = -msgId,
                            threadId = cursor.getLong(threadIndex),
                            sender = resolveMmsAddress(msgId, box).ifBlank { "Unknown" },
                            message = resolveMmsBody(msgId, cursor.getString(subIndex)),
                            date = cursor.getLong(dateIndex),
                            unread = cursor.getInt(readIndex) == 0,
                            type = if (box == Telephony.Mms.MESSAGE_BOX_INBOX) 1 else 2
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
     * Resolve the counterpart phone number for an MMS row. MMS stores parties in
     * the separate Addr table, using TYPE 137 (FROM) for the sender of a received
     * message and TYPE 151 (TO) for the recipient of a sent message.
     */
    private fun resolveMmsAddress(msgId: Long, msgBox: Int): String {
        var address = ""
        var preferred: String? = null
        try {
            val projection = arrayOf(
                Telephony.Mms.Addr.ADDRESS,
                Telephony.Mms.Addr.TYPE
            )
            context.contentResolver.query(
                Telephony.Mms.Addr.CONTENT_URI,
                projection,
                "${Telephony.Mms.Addr.MSG_ID} = ?",
                arrayOf(msgId.toString()),
                null
            )?.use { cursor ->
                val addrIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.Addr.ADDRESS)
                val typeIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.Addr.TYPE)
                while (cursor.moveToNext()) {
                    val addr = cursor.getString(addrIndex) ?: ""
                    if (addr.isBlank()) continue
                    val preferredType = if (msgBox == Telephony.Mms.MESSAGE_BOX_INBOX) 137 else 151
                    if (cursor.getInt(typeIndex) == preferredType) preferred = addr
                    if (address.isBlank()) address = addr
                }
            }
        } catch (e: Exception) {
            Log.e("SMS_DEBUG", "Error resolving MMS address for $msgId", e)
        }
        return preferred ?: address
    }

    /**
     * Resolve the display body for an MMS row: prefer the text/plain part, then an
     * attachment placeholder, then the subject.
     */
    private fun resolveMmsBody(msgId: Long, subject: String?): String {
        var text: String? = null
        var hasAttachment = false
        try {
            val projection = arrayOf(
                Telephony.Mms.Part.MSG_ID,
                Telephony.Mms.Part.CT,
                Telephony.Mms.Part.TEXT
            )
            context.contentResolver.query(
                Telephony.Mms.Part.CONTENT_URI,
                projection,
                "${Telephony.Mms.Part.MSG_ID} = ?",
                arrayOf(msgId.toString()),
                null
            )?.use { cursor ->
                val ctIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.Part.CT)
                val textIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.Part.TEXT)
                while (cursor.moveToNext()) {
                    val ct = cursor.getString(ctIndex)?.lowercase() ?: ""
                    when {
                        ct == "text/plain" -> {
                            val body = cursor.getString(textIndex)
                            if (!body.isNullOrBlank()) text = body
                        }
                        ct.startsWith("image/") || ct.startsWith("audio/") || ct.startsWith("video/") -> {
                            hasAttachment = true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SMS_DEBUG", "Error resolving MMS body for $msgId", e)
        }
        return when {
            !text.isNullOrBlank() -> text
            hasAttachment -> "[MMS]"
            !subject.isNullOrBlank() -> subject
            else -> "[MMS]"
        }
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
    fun registerObserver(
        observer: ContentObserver
    ) {
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
        context.contentResolver.unregisterContentObserver(
            observer
        )
    }
}