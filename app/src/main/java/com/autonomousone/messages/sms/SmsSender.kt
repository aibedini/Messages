package com.autonomousone.messages.sms

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast

class SmsSender(
    private val context: Context
) {

    /**
     * Sends an SMS and persists it to Telephony.Sms.Sent immediately.
     * Returns the persisted row ID (or a timestamp fallback).
     *
     * On Android, SmsManager.sendTextMessage() does NOT automatically save sent
     * messages for non-default SMS apps. We must write to Sent manually so the
     * ConversationViewModel's DB-reload (triggered by SmsContentObserver) finds it.
     */
    fun send(phone: String, text: String): Long {
        val sentId = persistToSent(phone, text)

        try {
            val manager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            // Split long messages into multi-part SMS if needed
            val parts = manager.divideMessage(text)
            if (parts.size > 1) {
                manager.sendMultipartTextMessage(phone, null, parts, null, null)
            } else {
                manager.sendTextMessage(phone, null, text, null, null)
            }

            Log.d("SMS_SENDER", "SMS sent to $phone (id=$sentId)")

        } catch (e: Exception) {
            Log.e("SMS_SENDER", "Error sending SMS to $phone", e)
            Toast.makeText(context, e.message ?: "Failed to send SMS", Toast.LENGTH_LONG).show()
        }

        return sentId
    }

    /**
     * Persist the sent SMS to Telephony.Sms.Sent immediately BEFORE sending,
     * so ContentObserver reload always finds it in the DB.
     */
    private fun persistToSent(phone: String, text: String): Long {
        return try {
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, phone)
                put(Telephony.Sms.BODY, text)
                put(Telephony.Sms.DATE, now)
                put(Telephony.Sms.DATE_SENT, now)
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
            }
            val uri = context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
            val id = uri?.lastPathSegment?.toLongOrNull() ?: now
            Log.d("SMS_SENDER", "Persisted to Sent: id=$id phone=$phone")
            id
        } catch (e: Exception) {
            Log.e("SMS_SENDER", "Error persisting sent SMS to DB", e)
            System.currentTimeMillis()
        }
    }
}