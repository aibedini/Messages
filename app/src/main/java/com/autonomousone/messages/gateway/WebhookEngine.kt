package com.autonomousone.messages.gateway

import android.content.Context
import android.util.Log
import com.autonomousone.messages.model.Sms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object WebhookEngine {

    private const val TAG = "WEBHOOK_ENGINE"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun sendIncomingSmsWebhook(context: Context, sms: Sms) {
        val prefs = GatewayPreferences(context)
        val webhookUrl = prefs.webhookUrl.trim()
        if (webhookUrl.isBlank()) return

        scope.launch {
            try {
                val json = JSONObject().apply {
                    put("event", "sms_received")
                    put("sender", sms.sender)
                    put("message", sms.message)
                    put("timestamp", sms.date)
                    put("threadId", sms.threadId)
                }

                val url = URL(webhookUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("User-Agent", "Android-SMS-Gateway/1.0")
                    connectTimeout = 8000
                    readTimeout = 8000
                    doOutput = true
                }

                conn.outputStream.use { os ->
                    os.write(json.toString().toByteArray(Charsets.UTF_8))
                }

                val responseCode = conn.responseCode
                Log.d(TAG, "Webhook dispatched to $webhookUrl, responseCode=$responseCode")
                conn.disconnect()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to dispatch webhook to $webhookUrl", e)
            }
        }
    }
}
