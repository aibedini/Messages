package com.autonomousone.messages.gateway

import android.util.Log
import com.autonomousone.messages.gateway.GatewayPreferences
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * SSOT for the GMweb CONTROL PLANE (ADR-001/007): identity enrollment, event
 * batch upload, command claim/ack, trust statements, pairing metadata.
 *
 * Base URL is ALWAYS prefs.gmwebUrl — never the legacy cloud backendUrl.
 * Wire format mirrors BackendClient (signer callback for X-Agent-Auth over
 * the exact body bytes) so EventUploader/SecureCommandPoller/trust publish
 * can share one transport without a silent wrong-host possibility.
 */
class ControlPlaneClient(private val prefs: GatewayPreferences) {

    companion object {
        private const val TAG = "CONTROL_PLANE_CLIENT"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000

        /** An hour. Long enough to respect a real rate limit, bounded enough to stay sane. */
        internal const val MAX_RETRY_AFTER_MS = 60 * 60_000L

        /**
         * `Retry-After` in milliseconds, or null when absent or unusable.
         *
         * The header is either delta-seconds (`Retry-After: 120`) or an HTTP date. Only the
         * delta-seconds form is honoured: the date form would need the device clock to agree with
         * the server's, and a wrong clock turning a short wait into a negative or enormous one is
         * worse than ignoring the hint. An absurd delay is capped so a hostile or broken value
         * cannot park the outbox for a day.
         *
         * Stateless, so it lives here and can be tested directly without Android or a network.
         */
        internal fun parseRetryAfter(header: String?): Long? {
            val seconds = header?.trim()?.toLongOrNull() ?: return null
            if (seconds <= 0) return null
            return (seconds * 1000L).coerceAtMost(MAX_RETRY_AFTER_MS)
        }
    }

    sealed class Result<out T> {
        data class Success<T>(val data: T, val httpStatus: Int) : Result<T>()
        data class Failure(
            val error: String,
            val httpStatus: Int? = null,
            val isAuthError: Boolean = false,
            /**
             * `Retry-After` from the response, in milliseconds (mission §17).
             *
             * Null when the server did not send one. The header is deliberately NOT read for
             * 2xx: it only means anything alongside a refusal.
             */
            val retryAfterMs: Long? = null,
        ) : Result<Nothing>()
    }

    fun post(
        path: String,
        body: JSONObject,
        extraHeaders: Map<String, String> = emptyMap(),
        signer: ((java.net.HttpURLConnection, ByteArray) -> Boolean)? = null,
    ): Result<String> {
        return try {
            // SSOT: control plane is gmwebUrl — /api/v1/agent/* lives there.
            val baseUrl = prefs.gmwebUrl.trimEnd('/')
            if (!baseUrl.startsWith("https://")) {
                return Result.Failure("Insecure control-plane URL rejected — HTTPS required")
            }
            val bodyBytes = body.toString().toByteArray(Charsets.UTF_8)
            val conn = URL(baseUrl + path).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doOutput = true
            for ((k, v) in extraHeaders) conn.setRequestProperty(k, v)
            if (signer != null && !signer(conn, bodyBytes)) {
                return Result.Failure("signing failed — request aborted (fail closed)")
            }
            conn.outputStream.use { it.write(bodyBytes) }
            val code = conn.responseCode
            if (code in 200..299) {
                Result.Success(conn.inputStream.use { it.bufferedReader().readText() }, code)
            } else {
                val err = conn.errorStream?.use { it.bufferedReader().readText() } ?: ""
                val retryAfter = parseRetryAfter(conn.getHeaderField("Retry-After"))
                Log.w(TAG, "POST $path → HTTP $code" + (retryAfter?.let { " retry-after=${it}ms" } ?: ""))
                Result.Failure(
                    "HTTP $code ${err.take(200)}",
                    httpStatus = code,
                    isAuthError = code == 401 || code == 403,
                    retryAfterMs = retryAfter,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "POST $path failed: ${e.message}")
            Result.Failure(e.message ?: "network error")
        }
    }
}
