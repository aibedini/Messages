package com.autonomousone.messages.gateway

import android.content.Context
import android.util.Log
import com.autonomousone.messages.BuildConfig
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Central HTTP client for all communication with the cloud backend.
 *
 * - Reads backendUrl and gatewayToken from GatewayPreferences.
 * - All authenticated requests include: Authorization: Bearer <token>
 * - 15s connect / 30s read timeout.
 * - Returns sealed Result<T> — no exceptions leak to callers.
 */
class BackendClient(private val prefs: GatewayPreferences) {

    companion object {
        private const val TAG = "BACKEND_CLIENT"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000

        internal fun safeAuthError(responseBody: String): String = runCatching {
            val json = JSONObject(responseBody)
            val reason = json.optString("reason", "unauthorized")
            val preview = json.optString("expectedKeyPreview", "")
            if (preview.isBlank()) reason else "$reason (server key $preview)"
        }.getOrDefault("unauthorized")
    }

    sealed class Result<out T> {
        data class Success<T>(val data: T, val httpStatus: Int) : Result<T>()
        data class Failure(
            val error: String,
            val httpStatus: Int? = null,
            val isAuthError: Boolean = false,
        ) : Result<Nothing>()
    }

    /**
     * POST JSON to the backend. Returns the raw response body on success.
     *
     * [signer] (PR-11): optional per-device request signing callback — invoked
     * with the open connection and the EXACT body bytes about to be sent, so
     * X-Agent-Auth signatures cover the real payload (ADR-001). Returning
     * false aborts the request (fail closed) instead of sending it unsigned.
     */
    fun post(
        path: String,
        body: JSONObject,
        authenticated: Boolean = true,
        extraHeaders: Map<String, String> = emptyMap(),
        signer: ((java.net.HttpURLConnection, ByteArray) -> Boolean)? = null,
        baseUrlOverride: String? = null,
    ): Result<String> {
        return try {
            // Never send the bearer token (or register payloads) over plaintext HTTP.
            val baseUrl = baseUrlOverride?.trimEnd('/') ?: prefs.backendUrl
            if (!baseUrl.startsWith("https://")) {
                return Result.Failure("Insecure backend URL rejected — HTTPS required")
            }
            val url = URL("$baseUrl$path")
            val conn = url.openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("User-Agent", "AndroidGateway/${BuildConfig.APP_VERSION}")
                if (authenticated && prefs.gatewayToken.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${prefs.gatewayToken}")
                }
                extraHeaders.forEach { (name, value) ->
                    if (value.isNotBlank()) setRequestProperty(name, value)
                }
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
            }

            val bodyBytes = body.toString().toByteArray(Charsets.UTF_8)
            // PR-11: per-device signature over the canonical request (ADR-001).
            // GMweb REQUIRES X-Agent-Auth once the deviceId has enrolled; a
            // signer returning false aborts here (fail closed) — the request
            // is never sent unsigned.
            if (signer != null && !signer(conn, bodyBytes)) {
                conn.disconnect()
                return Result.Failure("Request aborted: agent signing failed (keystore unavailable)")
            }
            conn.outputStream.use { it.write(bodyBytes) }

            val status = conn.responseCode
            val responseBody = if (status >= 400) {
                conn.errorStream?.use { BufferedReader(InputStreamReader(it)).readText() } ?: ""
            } else {
                conn.inputStream.use { BufferedReader(InputStreamReader(it)).readText() }
            }
            conn.disconnect()

            Log.d(TAG, "POST $path → HTTP $status")

            when {
                status in 200..299 -> Result.Success(responseBody, status)
                status == 401 || status == 403 -> {
                    val safe = safeAuthError(responseBody)
                    Result.Failure("Auth error: HTTP $status reason=$safe", status, isAuthError = true)
                }
                else -> Result.Failure("HTTP $status: $responseBody", status)
            }
        } catch (e: Exception) {
            Log.e(TAG, "POST $path failed: ${e.message}")
            Result.Failure(e.message ?: "Unknown network error")
        }
    }
}
