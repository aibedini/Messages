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

/**
 * PR-02 (TechSpec §86): the fire-and-forget CLOUD path is DELETED — cloud
 * events are committed to `gateway_event_outbox` inside the Room transaction
 * that lands the message (see TelephonySyncCoordinator + GatewayEventFactory)
 * and transmitted by EventUploader with retry/ACK. What remains here is the
 * user-configured LOCAL webhook only: best-effort by design, consent-gated,
 * unchanged behaviour.
 */
object WebhookEngine {

    private const val TAG = "WEBHOOK_ENGINE"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Local webhook dispatch for incoming SMS (best-effort, never critical). */
    fun sendLocalWebhook(context: Context, sms: Sms) {
        val prefs = GatewayPreferences(context)
        if (!GatewayAccessPolicy.canTransmit(prefs.hasGatewayConsent, prefs.isEnabled)) {
            Log.d(TAG, "Gateway consent is absent or gateway is disabled; skipping webhook dispatch")
            return
        }
        val webhookUrl = prefs.webhookUrl.trim()
        if (webhookUrl.isBlank()) return
        if (!webhookUrl.startsWith("https://")) {
            Log.w(TAG, "Webhook URL rejected — HTTPS required")
            return
        }
        scope.launch { post(webhookUrl, sms, prefs.webhookSecret) }
    }

    private fun post(webhookUrl: String, sms: Sms, signingSecret: String) {
        try {
            val json = JSONObject().apply {
                put("event", "sms_received")
                put("sender", sms.sender)
                put("message", sms.message)
                put("timestamp", sms.date)
                put("threadId", sms.threadId)
            }
            val conn = (URL(webhookUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty(
                    "User-Agent",
                    "Android-SMS-Gateway/${com.autonomousone.messages.BuildConfig.APP_VERSION}"
                )
                connectTimeout = 8_000
                readTimeout = 8_000
                doOutput = true
            }
            val body = json.toString()
            if (signingSecret.isNotBlank()) {
                // HMAC-SHA256 over "<timestamp>.<body>" so receivers can verify
                // authenticity and reject replayed payloads.
                val timestamp = System.currentTimeMillis().toString()
                conn.setRequestProperty("X-Timestamp", timestamp)
                conn.setRequestProperty("X-Signature", hmacSha256(signingSecret, "$timestamp.$body"))
            }
            conn.outputStream.use { os -> os.write(body.toByteArray(Charsets.UTF_8)) }
            Log.d(TAG, "Local webhook dispatched → $webhookUrl, HTTP ${conn.responseCode}")
            conn.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch local webhook to $webhookUrl", e)
        }
    }

    /** HMAC-SHA256 of [data] keyed with [secret], hex-encoded. */
    private fun hmacSha256(secret: String, data: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
