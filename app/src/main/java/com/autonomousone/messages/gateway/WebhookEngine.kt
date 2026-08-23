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
import java.util.UUID

object WebhookEngine {

    private const val TAG = "WEBHOOK_ENGINE"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Called by SmsReceiver on every incoming SMS.
     *
     * 1. Fires to the user-configured local webhook URL (existing behaviour — unchanged).
     * 2. Sends event to the cloud backend via BackendClient (new — cloud gateway).
     *
     * Both dispatches are fire-and-forget; failures are logged but don't block the receiver.
     */
    fun sendIncomingSmsWebhook(context: Context, sms: Sms) {
        val prefs = GatewayPreferences(context)
        if (!GatewayAccessPolicy.canTransmit(prefs.hasGatewayConsent, prefs.isEnabled)) {
            Log.d(TAG, "Gateway consent is absent or gateway is disabled; skipping all SMS dispatch")
            return
        }

        // ── 1. Existing local webhook ──────────────────────────────────────
        val webhookUrl = prefs.webhookUrl.trim()
        if (webhookUrl.isNotBlank()) {
            if (!webhookUrl.startsWith("https://")) {
                Log.w(TAG, "Webhook URL rejected — HTTPS required (got $webhookUrl)")
            } else {
                scope.launch { sendLocalWebhook(webhookUrl, sms, prefs.webhookSecret) }
            }
        }

        // ── 2. Cloud backend event upload ──────────────────────────────────
        scope.launch { sendCloudEvent(prefs, sms) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Local webhook (existing — unchanged behaviour)
    // ─────────────────────────────────────────────────────────────────────────

    private fun sendLocalWebhook(webhookUrl: String, sms: Sms, signingSecret: String) {
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
                setRequestProperty("User-Agent", "Android-SMS-Gateway/${com.autonomousone.messages.BuildConfig.APP_VERSION}")
                connectTimeout = 8_000
                readTimeout = 8_000
                doOutput = true
            }

            val body = json.toString()
            if (signingSecret.isNotBlank()) {
                // HMAC-SHA256 signature over "<timestamp>.<body>" so receivers can
                // verify authenticity and reject replayed payloads.
                val timestamp = System.currentTimeMillis().toString()
                conn.setRequestProperty("X-Timestamp", timestamp)
                conn.setRequestProperty("X-Signature", hmacSha256(signingSecret, "$timestamp.$body"))
            }

            conn.outputStream.use { os -> os.write(body.toByteArray(Charsets.UTF_8)) }
            val responseCode = conn.responseCode
            Log.d(TAG, "Local webhook dispatched → $webhookUrl, HTTP $responseCode")
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

    // ─────────────────────────────────────────────────────────────────────────
    // Cloud backend event upload (new)
    // ─────────────────────────────────────────────────────────────────────────

    private fun sendCloudEvent(prefs: GatewayPreferences, sms: Sms) {
        if (!prefs.isRegistered || prefs.gatewayToken.isBlank()) {
            Log.d(TAG, "Cloud backend not registered — skipping cloud event upload")
            return
        }

        // Generate a stable UUID for this SMS event — used for idempotency
        // We derive it from the SMS content + timestamp so we generate the same UUID
        // even if the process restarts before acknowledging the backend response.
        val eventId = generateEventId(sms)

        // Check local idempotency cache — avoid re-sending if already acknowledged
        if (prefs.hasEventBeenSent(eventId)) {
            Log.d(TAG, "Event $eventId already sent — skipping")
            return
        }

        val payload = JSONObject().apply {
            put("eventId", eventId)
            put("type", "sms.received")
            put("sender", sms.sender)
            put("message", sms.message)
            put("timestamp", sms.date)
        }

        val client = BackendClient(prefs)
        val result = client.post("/api/gateways/events/sms", payload)

        when (result) {
            is BackendClient.Result.Success -> {
                prefs.markEventSent(eventId)
                Log.i(TAG, "Cloud event uploaded: $eventId")
            }
            is BackendClient.Result.Failure -> {
                // Don't mark as sent — will be retried next time this SMS is re-processed
                // In practice, SMS_DELIVER fires once so this just means the event is lost
                // if the backend is permanently down. The backend-side retry handles
                // downstream webhook delivery failures independently.
                Log.w(TAG, "Cloud event upload failed: ${result.error}")
            }
        }
    }

    /**
     * Derive a deterministic UUID from SMS data.
     * If two processes both try to upload the same SMS, they'll generate the same ID
     * and the backend will safely deduplicate via the eventId field.
     */
    private fun generateEventId(sms: Sms): String {
        val seed = "${sms.sender}|${sms.date}|${sms.message.take(100)}"
        return UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8)).toString()
    }
}
