package com.autonomousone.messages.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Required stub service for the default SMS app contract.
 * Android requires every default SMS app to implement RESPOND_VIA_MESSAGE
 * so users can respond to calls with an SMS from the dialer.
 */
class HeadlessSmsSendService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("HeadlessSmsSend", "HeadlessSmsSendService started")
        // Future: send SMS to intent.getStringExtra("address") here
        stopSelf()
        return START_NOT_STICKY
    }
}
