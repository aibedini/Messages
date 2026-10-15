package com.autonomousone.messages.service

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.telephony.TelephonyManager
import android.util.Log
import com.autonomousone.messages.sms.SmsSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Default SMS app contract: RESPOND_VIA_MESSAGE from the dialer.
 * Sends the SMS on a background thread and stops itself.
 */
class HeadlessSmsSendService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uri: Uri? = intent?.data
        val message = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: intent?.getStringExtra("sms_body")
        val phone = uri?.schemeSpecificPart
            ?: intent?.getStringExtra("address")
            ?: intent?.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        if (phone.isNullOrBlank() || message.isNullOrBlank()) {
            Log.w(TAG, "Missing phone or message — cannot send")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        scope.launch {
            try {
                SmsSender(applicationContext).send(phone, message)
            } catch (e: Exception) {
                Log.e(TAG, "HeadlessSmsSend failed", e)
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        private const val TAG = "HeadlessSmsSend"
    }
}
