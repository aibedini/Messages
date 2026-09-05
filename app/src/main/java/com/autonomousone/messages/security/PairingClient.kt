package com.autonomousone.messages.security

import android.content.Context
import android.util.Log
import com.autonomousone.messages.gateway.AgentAuth
import com.autonomousone.messages.gateway.GatewayPreferences
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
        val apiOrigin: String = "",
        val rawMetadata: JSONObject? = null
    )

    fun parseQrPayload(raw: String): SessionInfo? = runCatching {
        val o = JSONObject(raw)
        require(o.getString("protocol") == PairingProtocol.PROTOCOL)
        require(o.getLong("expiresAt") > System.currentTimeMillis())
        require(PairingProtocol.transcriptHash(o) == o.getString("transcriptHash"))
        SessionInfo(o.getString("pairingSessionId"), o.getString("webDeviceId"),
            o.getString("webOrigin"), o.getLong("expiresAt"), o.getString("transcriptHash"),
            o.getString("apiOrigin"), o)
    }.getOrNull()

    fun originMatches(serverOrigin: String, scanned: SessionInfo): Boolean =
        serverOrigin.trimEnd('/') == scanned.apiOrigin

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
            val response = request()
            val (status, reason, body) = response
            if (status != 200) {
                if (reason == "unknown_device" || reason == "primary_enrollment_required")
                    return@withContext null to "First use Enroll this phone as Primary with a phone setup QR from the dashboard"
                val safeReason = reason.takeIf {
                    it in nonRetryableAuthReasons || it == "unknown_device" || it == "signing_failed"
                } ?: "request_failed"
                return@withContext null to "pairing session lookup failed: HTTP $status reason=$safeReason"
            }
            val o = JSONObject(body)
            val info = SessionInfo(
                o.getString("pairingSessionId"), o.getString("webDeviceId"),
                o.getString("webOrigin"), o.optLong("expiresAt", 0L),
                o.optString("transcriptHash", ""), o.getString("apiOrigin"), o
            )
            if (info.transcriptHash != scanned.transcriptHash ||
                PairingProtocol.transcriptHash(o) != scanned.transcriptHash ||
                info.apiOrigin != scanned.apiOrigin || info.origin != scanned.origin ||
                info.webDeviceId != scanned.webDeviceId || info.pairingSessionId != scanned.pairingSessionId ||
                info.expiresAt <= System.currentTimeMillis())
                return@withContext null to "transcript mismatch or expired QR"
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
        trustSequence: Int,
        persistApproval: suspend (JSONObject) -> Unit
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            val prefs = GatewayPreferences(context)
            val base = PairingEndpointResolver.trustedServerUrl(context).trimEnd('/')
            val deviceId = prefs.agentDeviceId(context)
            val meta = info.rawMetadata ?: error("server transcript metadata missing")
            val issuedAt = System.currentTimeMillis()
            val certificate = JSONObject().apply {
                put("protocol", PairingProtocol.PROTOCOL)
                put("pairingSessionId", info.pairingSessionId)
                put("apiOrigin", info.apiOrigin)
                put("webOrigin", info.origin)
                put("accountId", "default")
                put("deviceId", info.webDeviceId)
                put("deviceType", "WEB_PWA")
                put("signingPublicKey", meta.optString("webSigningPublicKey", ""))
                put("encryptionPublicKey", meta.optString("webEncryptionPublicKey", ""))
                put("capabilities", JSONArray(capabilities))
                put("historyGrant", historyGrant)
                put("trustSequence", trustSequence.toLong())
                put("issuedAt", issuedAt)
                put("expiresAt", issuedAt + 180L * 24 * 3600 * 1000)
                put("pairingTranscriptHash", info.transcriptHash)
            }
            certificate.put("rootSignature", PrimaryTrustRoot.sign(certificate))
            require(info.expiresAt > System.currentTimeMillis()) { "stage=PREPARING_APPROVAL reason=qr_expired" }
            // The certificate, device and signed publication outbox must be
            // durable before the browser can observe a successful approval.
            persistApproval(certificate)
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
