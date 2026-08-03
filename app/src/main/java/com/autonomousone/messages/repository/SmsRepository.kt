package com.autonomousone.messages.repository

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

    fun getConversations(): List<Sms> {
        val conversations = getAllSms()
            .groupBy { it.threadId }
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
        val normalized = ContactRepository.normalizePhone(phone)
        return getAllSms()
            .filter { ContactRepository.normalizePhone(it.sender) == normalized || it.sender == phone }
            .sortedBy { it.date }
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