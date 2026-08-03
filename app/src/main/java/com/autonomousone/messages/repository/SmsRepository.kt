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

            Log.d("SMS_DEBUG", "Total SMS Read = ${smsList.size}")

        } catch (e: Exception) {
            Log.e("SMS_DEBUG", "Error reading SMS", e)
        }

        return smsList
    }

    /**
     * Group conversations by normalized phone address to eliminate duplicates.
     */
    fun getConversations(): List<Sms> {
        val conversations = getAllSms()
            .groupBy {
                val norm = ContactRepository.normalizePhone(it.sender)
                if (norm.isNotBlank()) norm else it.sender
            }
            .mapNotNull { (_, messages) ->
                messages.maxByOrNull { it.date }
            }
            .sortedByDescending { it.date }

        Log.d("SMS_DEBUG", "Total Conversations = ${conversations.size}")
        return conversations
    }

    fun getMessagesByThread(threadId: Long): List<Sms> {
        return getAllSms()
            .filter { it.threadId == threadId }
            .sortedBy { it.date }
    }

    fun getMessagesByPhone(phone: String): List<Sms> {
        if (phone.isBlank()) return emptyList()
        val targetNorm = ContactRepository.normalizePhone(phone)
        return getAllSms()
            .filter {
                val norm = ContactRepository.normalizePhone(it.sender)
                (norm.isNotBlank() && targetNorm.isNotBlank() &&
                        (norm == targetNorm || norm.endsWith(targetNorm) || targetNorm.endsWith(norm))) ||
                        it.sender == phone
            }
            .sortedBy { it.date }
    }

    fun markThreadAsRead(threadId: Long, phone: String) {
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.READ, 1)
            }
            if (phone.isNotBlank()) {
                val normalized = ContactRepository.normalizePhone(phone)
                context.contentResolver.update(
                    Telephony.Sms.CONTENT_URI,
                    values,
                    "${Telephony.Sms.ADDRESS} LIKE ? AND ${Telephony.Sms.READ} = 0",
                    arrayOf("%$normalized%")
                )
            } else if (threadId > 0) {
                context.contentResolver.update(
                    Telephony.Sms.CONTENT_URI,
                    values,
                    "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
                    arrayOf(threadId.toString())
                )
            }
        } catch (e: Exception) {
            Log.e("SMS_DEBUG", "Error marking thread as read", e)
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