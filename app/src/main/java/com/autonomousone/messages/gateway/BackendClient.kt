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
    }

    sealed class Result<out T> {
        data class Success<T>(val data: T, val httpStatus: Int) : Result<T>()
        data class Failure(
            val error: String,
            val httpStatus: Int? = null,
            val isAuthError: Boolean = false,
        ) : Result<Nothing>()
    }

    /** POST JSON to the backend. Returns the raw response body on success. */
    fun post(
        path: String,
        body: JSONObject,
        authenticated: Boolean = true,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Result<String> {
        return try {
            // Never send the bearer token (or register payloads) over plaintext HTTP.
            val baseUrl = prefs.backendUrl
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
                status == 401 || status == 403 -> Result.Failure(
                    "Auth error: HTTP $status",
                    status,
                    isAuthError = true,
                )
                else -> Result.Failure("HTTP $status: $responseBody", status)
            }
        } catch (e: Exception) {
            Log.e(TAG, "POST $path failed: ${e.message}")
            Result.Failure(e.message ?: "Unknown network error")
        }
    }
}
