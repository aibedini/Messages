package com.autonomousone.messages.sms

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.widget.Toast

class SmsSender(
    private val context: Context
) {

    fun send(
        phone: String,
        text: String
    ) {
        try {
            val manager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            manager.sendTextMessage(
                phone,
                null,
                text,
                null,
                null
            )
            Toast.makeText(
                context,
                "SMS Sent",
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Toast.makeText(
                context,
                e.message ?: "Failed to send SMS",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}