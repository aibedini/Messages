package com.autonomousone.messages.sms

import android.app.PendingIntent
import android.content.Context
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

            val manager =
                SmsManager.getDefault()

            manager.sendTextMessage(
                phone,
                null,
                text,
                null,
                null
            )

            Toast
                .makeText(
                    context,
                    "SMS Sent",
                    Toast.LENGTH_SHORT
                )
                .show()

        } catch (e: Exception) {

            Toast
                .makeText(
                    context,
                    e.message,
                    Toast.LENGTH_LONG
                )
                .show()

        }

    }

}