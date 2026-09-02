package com.autonomousone.messages.security

import android.content.Context
import android.util.Log
import com.autonomousone.messages.gateway.AgentAuth
import com.autonomousone.messages.gateway.BackendClient
import com.autonomousone.messages.gateway.GatewayPreferences
import com.autonomousone.messages.gateway.RegistrationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Android is the primary trust device; GMweb only relays its approval. */
object PairingClient {
    private const val TAG = "PAIRING_CLIENT"

    private val nonRetryableAuthReasons = setOf(
        "signature_mismatch", "timestamp_out_of_window", "replayed_timestamp",
        "missing_agent_auth_header", "malformed_agent_auth_header"
    )

    internal fun shouldRetryMetadata(status: Int, reason: String, alreadyRetried: Boolean): Boolean =
        status == 401 && reason == "unknown_device" && !alreadyRetried

    internal fun approveMakesLinked(status: Int): Boolean = status == 200

    internal class ExactBody private constructor(val bytes: ByteArray) {
        val signingBytes: ByteArray get() = bytes
        val wireBytes: ByteArray get() = bytes
        companion object {
            fun utf8(json: JSONObject): ExactBody = ExactBody(json.toString().toByteArray(Charsets.UTF_8))
        }
    }

    private fun short(value: String): String = value.take(8)

    data class SessionInfo(
        val pairingSessionId: String,
        val webDeviceId: String,
        val origin: String,
        val expiresAt: Long,
        val transcriptHash: String,
        val rawMetadata: JSONObject? = null
    )

    fun parseQrPayload(raw: String): SessionInfo? = try {
        val o = JSONObject(raw)
        if (o.optString("pairingSessionId").isBlank() ||
            o.optString("webDeviceId").isBlank() || o.optString("origin").isBlank()
        ) null else SessionInfo(
            o.getString("pairingSessionId"), o.getString("webDeviceId"),
            o.getString("origin"), o.optLong("expiresAt", 0L),
            o.optString("transcriptHash", "")
        )
    } catch (e: Exception) {
        Log.w(TAG, "QR payload parse failed", e)
        null
    }

    fun originMatches(serverOrigin: String, scanned: SessionInfo): Boolean {
        val a = serverOrigin.trimEnd('/')
        val b = scanned.origin.trimEnd('/')
        return a.isNotEmpty() && a.equals(b, ignoreCase = true)
    }

    suspend fun registerIdentity(context: Context): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        val prefs = GatewayPreferences(context)
        val base = PairingEndpointResolver.trustedServerUrl(context).trimEnd('/')
        val deviceId = prefs.stableDeviceId(context)
        Log.i(TAG, "stage=REGISTERING_IDENTITY endpoint=/api/v1/agent/identity device=${short(deviceId)}")
        val ok = RegistrationManager(context, prefs, BackendClient(prefs)).registerForPairing(base)
        Log.i(TAG, "identity registration status=${if (ok) "success" else "failed"} device=${short(deviceId)}")
        ok to if (ok) null else "identity registration failed"
    }

    suspend fun fetchSessionMetadata(
        context: Context,
        scanned: SessionInfo
    ): Pair<SessionInfo?, String?> = withContext(Dispatchers.IO) {
        val prefs = GatewayPreferences(context)
        val base = PairingEndpointResolver.trustedServerUrl(context).trimEnd('/')
        val path = "/api/v1/pairing/session/${scanned.pairingSessionId}"

        fun request(): Triple<Int, String, String> {
            val conn = URL("$base$path").openConnection() as HttpURLConnection
            return try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                if (!AgentAuth.sign(conn, prefs.agentDeviceId(context), path, "GET", ByteArray(0))) {
                    return Triple(0, "signing_failed", "")
                }
                val status = conn.responseCode
                val body = if (status == 200) {
                    conn.inputStream.use { it.bufferedReader().readText() }
                } else {
                    conn.errorStream?.use { it.bufferedReader().readText() }?.take(300) ?: ""
                }
                val reason = runCatching { JSONObject(body).optString("reason", "") }.getOrDefault("")
                Log.i(TAG, "stage=FETCHING_METADATA endpoint=$path status=$status reason=${reason.ifBlank { "none" }} session=${short(scanned.pairingSessionId)} device=${short(prefs.agentDeviceId(context))}")
                Triple(status, reason, body)
            } finally {
                conn.disconnect()
            }
        }

        try {
            val registration = registerIdentity(context)
            if (!registration.first) return@withContext null to registration.second
            var response = request()
            if (shouldRetryMetadata(response.first, response.second, false)) {
                val refreshed = registerIdentity(context)
                if (!refreshed.first) return@withContext null to "identity re-registration failed after unknown_device"
                response = request()
            }
            val (status, reason, body) = response
            if (status != 200) {
                val safeReason = reason.takeIf {
                    it in nonRetryableAuthReasons || it == "unknown_device" || it == "signing_failed"
                } ?: "request_failed"
                return@withContext null to "pairing session lookup failed: HTTP $status reason=$safeReason"
            }
            val o = JSONObject(body)
            val info = SessionInfo(
                o.getString("pairingSessionId"), o.getString("webDeviceId"),
                o.getString("origin"), o.optLong("expiresAt", 0L),
                o.optString("transcriptHash", ""), o
            )
            if (scanned.transcriptHash.isNotBlank() && info.transcriptHash.isNotBlank() &&
                info.transcriptHash != scanned.transcriptHash
            ) return@withContext null to "transcript mismatch between QR and server"
            info to null
        } catch (e: Exception) {
            Log.e(TAG, "session metadata fetch failed", e)
            null to (e.message ?: "network error")
        }
    }

    suspend fun approve(
        context: Context,
        info: SessionInfo,
        capabilities: List<String>,
        historyGrant: String,
        trustSequence: Int
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            val prefs = GatewayPreferences(context)
            val base = PairingEndpointResolver.trustedServerUrl(context).trimEnd('/')
            val deviceId = prefs.agentDeviceId(context)
            val meta = info.rawMetadata ?: error("server transcript metadata missing")
            val certificate = JSONObject().apply {
                put("accountId", "default")
                put("deviceId", info.webDeviceId)
                put("deviceType", "WEB_PWA")
                put("signingPublicKey", meta.optString("webSigningPublicKey", ""))
                put("encryptionPublicKey", meta.optString("webEncryptionPublicKey", ""))
                put("capabilities", JSONArray(capabilities))
                put("historyGrant", historyGrant)
                put("trustSequence", trustSequence.toLong())
                put("issuedAt", System.currentTimeMillis())
                put("expiresAt", System.currentTimeMillis() + 180L * 24 * 3600 * 1000)
                put("pairingTranscriptHash", info.transcriptHash)
                put("origin", info.origin)
            }
            certificate.put("rootSignature", PrimaryTrustRoot.sign(certificate))
            val path = "/api/v1/pairing/approve"
            val exactBody = ExactBody.utf8(JSONObject()
                .put("pairingSessionId", info.pairingSessionId)
                .put("certificate", certificate.toString())
                .put("deviceId", info.webDeviceId)
                .put("transcriptHash", info.transcriptHash)
                .put("trustRootPublicKey", PrimaryTrustRoot.publicKeyBase64()))
            val conn = URL("$base$path").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.doOutput = true
                if (!AgentAuth.sign(conn, deviceId, path, "POST", exactBody.signingBytes)) {
                    error("agent signing failed (keystore unavailable)")
                }
                conn.outputStream.use { it.write(exactBody.wireBytes) }
                val status = conn.responseCode
                if (!approveMakesLinked(status)) {
                    val err = conn.errorStream?.use { it.bufferedReader().readText() }?.take(300) ?: ""
                    val reason = runCatching { JSONObject(err).optString("reason", "") }.getOrDefault("")
                    Log.w(TAG, "stage=APPROVING_ON_SERVER endpoint=$path status=$status reason=${reason.ifBlank { "none" }} session=${short(info.pairingSessionId)} device=${short(deviceId)}")
                    error("approve failed: HTTP $status reason=${reason.ifBlank { "request_failed" }}")
                }
                Log.i(TAG, "stage=LINKED endpoint=$path status=$status session=${short(info.pairingSessionId)} device=${short(deviceId)}")
                certificate
            } finally {
                conn.disconnect()
            }
        }
    }
}
