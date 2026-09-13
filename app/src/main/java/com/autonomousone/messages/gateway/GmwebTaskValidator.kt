package com.autonomousone.messages.gateway

import android.util.Log
import com.autonomousone.messages.eve.EveSmsQueue
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * GMweb task validator — the server side of the mandatory final pre-send gate.
 *
 *   POST {gmwebUrl}/gateway/validate   X-API-Key: <gateway api key>
 *   {"requestId":"<GMweb gateway requestId>"}
 *
 * Valid:     {"valid":true,  "status":"valid",      "reason":null}
 * Superseded:{"valid":false, "status":"superseded", "reason":"renewed"}
 *
 * Fail-closed policy: any transport/behavioural failure (timeout, offline,
 * 5xx, 429, unparsable body) returns [EveSmsQueue.ValidationDecision.Unavailable]
 * so a depletion notification is deferred rather than sent stale. It is NEVER
 * interpreted as "valid".
 *
 * The class is stateless and blocking; it is called from the EveSmsQueue worker
 * thread (never the main thread) and from the OutboxPoller's IO coroutine.
 */
class GmwebTaskValidator(
    private val config: Config,
    private val transport: Transport = HttpUrlConnectionTransport()
) : EveSmsQueue.FinalValidator {

    /**
     * Indirection over the app's configuration/naming so the validator is
     * JVM-unit-testable without an Android Context.
     */
    data class Config(
        val gmwebUrl: () -> String,
        val apiKey: () -> String,
        val canTransmit: () -> Boolean,
        val isOnline: () -> Boolean
    )

    /** Injectable HTTP hop so tests can drive every failure mode deterministically. */
    interface Transport {
        fun postJson(
            url: String,
            apiKey: String,
            body: String,
            connectTimeoutMs: Int,
            readTimeoutMs: Int
        ): Result
    }

    data class Result(val code: Int, val body: String?)

    companion object {
        private const val TAG = "GMWEB_VALIDATE"
        const val PATH = "/gateway/validate"

        /**
         * Bounded request timeouts: the gate sits between a dequeued job and the
         * radio, so it must fail fast and let the backoff ladder retry.
         */
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 8_000

        fun from(prefs: GatewayPreferences, networkMonitor: NetworkMonitor): GmwebTaskValidator =
            GmwebTaskValidator(
                Config(
                    gmwebUrl = { prefs.gmwebUrl },
                    apiKey = { prefs.apiKey },
                    canTransmit = {
                        GatewayAccessPolicy.canTransmit(prefs.hasGatewayConsent, prefs.isEnabled)
                    },
                    isOnline = { networkMonitor.isOnline() }
                )
            )
    }

    override fun validate(record: EveSmsQueue.Record): EveSmsQueue.ValidationDecision {
        val id = record.gatewayRequestId
        if (id.isNullOrBlank()) {
            // Fail closed: a metadata-aware record with no server identity can
            // never be validated, so it must never be sent.
            return EveSmsQueue.ValidationDecision.Unavailable("missing_gateway_request_id")
        }
        return validateRequestId(id)
    }

    /** Validates a raw GMweb gateway requestId (used for the pull-time check too). */
    fun validateRequestId(requestId: String): EveSmsQueue.ValidationDecision {
        if (requestId.isBlank()) {
            return EveSmsQueue.ValidationDecision.Unavailable("missing_gateway_request_id")
        }
        if (!config.canTransmit()) return unavailable(requestId, "gateway_inactive")
        val base = config.gmwebUrl().trim().trimEnd('/')
        if (base.isBlank()) return unavailable(requestId, "gmweb_not_configured")
        // Respect the app's existing network monitor instead of dialling blindly.
        if (!config.isOnline()) return unavailable(requestId, "offline")

        val body = JSONObject().put("requestId", requestId).toString()
        val result = try {
            transport.postJson(base + PATH, config.apiKey(), body, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)
        } catch (e: SocketTimeoutException) {
            return unavailable(requestId, "timeout")
        } catch (e: Exception) {
            return unavailable(requestId, "network_error")
        }

        if (result.code == 408 || result.code == 504) return unavailable(requestId, "timeout")
        if (result.code == 429) return unavailable(requestId, "rate_limited")
        if (result.code !in 200..299) return unavailable(requestId, "http_" + result.code)

        val raw = result.body
        if (raw.isNullOrBlank()) return unavailable(requestId, "empty_response")

        return try {
            val json = JSONObject(raw)
            if (json.optBoolean("valid", false)) {
                EveSmsQueue.ValidationDecision.Valid
            } else {
                val reason = json.optString("reason", "").trim().ifBlank { "superseded" }
                EveSmsQueue.ValidationDecision.Superseded(reason)
            }
        } catch (e: Exception) {
            unavailable(requestId, "invalid_response")
        }
    }

    private fun unavailable(
        requestId: String,
        reason: String
    ): EveSmsQueue.ValidationDecision {
        // Never log the message body or the API key — only the opaque request id.
        Log.w(TAG, "validation unavailable for " + requestId + ": " + reason)
        return EveSmsQueue.ValidationDecision.Unavailable(reason)
    }
}

/**
 * Default transport: a single HTTPS POST through HttpURLConnection, mirroring
 * the OutboxPoller's existing auth/TLS handling (X-API-Key header, no
 * plaintext downgrade, bounded timeouts).
 */
class HttpUrlConnectionTransport : GmwebTaskValidator.Transport {

    override fun postJson(
        url: String,
        apiKey: String,
        body: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): GmwebTaskValidator.Result {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("X-API-Key", apiKey)
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = try {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.use { it.bufferedReader().readText() }
            } catch (_: Exception) {
                null
            }
            return GmwebTaskValidator.Result(code, text)
        } finally {
            conn.disconnect()
        }
    }
}
