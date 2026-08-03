package com.autonomousone.messages.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Stub MMS/WAP Push receiver — required to be selectable as the default SMS app.
 * MMS processing can be expanded here in future.
 */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d("MMS_RECEIVER", "WAP Push received: ${intent?.action}")
        // MMS support placeholder
    }
}
