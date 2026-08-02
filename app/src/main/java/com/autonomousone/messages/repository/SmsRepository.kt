package com.autonomousone.messages.repository

import android.content.Context
import android.provider.Telephony
import com.autonomousone.messages.model.Sms

class SmsRepository(
    private val context: Context
) {

    /**
     * Read all SMS from device
     */
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

            val cursor = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                null,
                null,
                "${Telephony.Sms.DATE} DESC"
            )

            cursor?.use {

                val idIndex = it.getColumnIndexOrThrow(Telephony.Sms._ID)
                val threadIndex = it.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
                val addressIndex = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIndex = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIndex = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val readIndex = it.getColumnIndexOrThrow(Telephony.Sms.READ)
                val typeIndex = it.getColumnIndexOrThrow(Telephony.Sms.TYPE)

                while (it.moveToNext()) {

                    smsList.add(

                        Sms(
                            id = it.getLong(idIndex),
                            threadId = it.getLong(threadIndex),
                            sender = it.getString(addressIndex) ?: "Unknown",
                            message = it.getString(bodyIndex) ?: "",
                            date = it.getLong(dateIndex),
                            unread = it.getInt(readIndex) == 0,
                            type = it.getInt(typeIndex)
                        )

                    )

                }

            }

        } catch (e: Exception) {

            e.printStackTrace()

        }

        return smsList
    }

    /**
     * Returns one item per conversation.
     * The latest message of every thread is returned.
     */
    fun getConversations(): List<Sms> {

        return getAllSms()
            .groupBy { it.threadId }
            .map { (_, messages) ->
                messages.maxByOrNull { it.date }!!
            }
            .sortedByDescending { it.date }
    }

    /**
     * Returns complete conversation by thread id
     */
    fun getMessagesByThread(threadId: Long): List<Sms> {
        return getAllSms()
            .filter {
                it.threadId == threadId
            }
            .sortedBy {
                it.date
            }
    }
}