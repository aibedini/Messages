package com.autonomousone.messages.security

import android.content.Context
import android.util.Log
import com.autonomousone.messages.BuildConfig
import com.autonomousone.messages.gateway.AgentAuth
import com.autonomousone.messages.gateway.DeviceIdentity
import com.autonomousone.messages.gateway.GatewayPreferences
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * ADR-007 — Android-side pairing client (the PRIMARY TRUST DEVICE flow).
 *
 * The Android agent is the ONLY device that can approve a web browser:
 *   1. fetch pairing session metadata (what am I approving?)
 *   2. verify the origin matches the configured GMweb origin (P0-5)
 *   3. after explicit user confirmation, sign a DeviceCertificate with the
 *      Trust Root operational key (AgentAuth/DeviceIdentity, PR-05/08b)
 *   4. POST the signed certificate to GMweb (X-Agent-Auth signed request)
 *
 * GMweb merely relays; trust originates HERE (§4).
 */
object PairingClient {

    private const val TAG = "PAIRING_CLIENT"

    /** Metadata about the web device asking to be linked. */
    data class SessionInfo(
        val pairingSessionId: String,
        val webDeviceId: String,
        val origin: String,
        val expiresAt: Long,
        val transcriptHash: String,
        /** Full server transcript (raw JSON) — carried to approve() so the
         *  certificate binds the server-verified public keys. */
        val rawMetadata: JSONObject? = null
    )

    /** Parse the scanned QR payload (canonical JSON written by the web app). */
    fun parseQrPayload(raw: String): SessionInfo? = try {
        val o = JSONObject(raw)
        if (o.optString("pairingSessionId").isBlank() ||
            o.optString("webDeviceId").isBlank() ||
            o.optString("origin").isBlank()
        ) {
            null
        } else {
            SessionInfo(
                pairingSessionId = o.getString("pairingSessionId"),
                webDeviceId = o.getString("webDeviceId"),
                origin = o.getString("origin"),
                expiresAt = o.optLong("expiresAt", 0L),
                transcriptHash = o.optString("transcriptHash", "")
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "QR payload parse failed", e)
        null
    }

    /**
     * P0-5/§3: the scanned origin MUST match the configured GMweb origin —
     * a QR from a different site is a phishing attempt and is rejected
     * before any confirmation screen is shown.
     */
    fun originMatches(serverOrigin: String, scanned: SessionInfo): Boolean {
        val a = serverOrigin.trimEnd('/')
        val b = scanned.origin.trimEnd('/')
        return a.isNotEmpty() && a.equals(b, ignoreCase = true)
    }

    /**
     * Fetch live session metadata from GMweb (agent-signed GET). Confirms
     * the session still exists and its transcriptHash matches the QR.
     */
    suspend fun fetchSessionMetadata(
        context: Context,
        scanned: SessionInfo
    ): Pair<SessionInfo?, String?> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val prefs = GatewayPreferences(context)
        val base = prefs.gmwebUrl.trimEnd('/')
        if (base.isBlank()) {
            return@withContext null to "GMweb URL is not configured"
        }
        val path = "/api/v1/pairing/session/${scanned.pairingSessionId}"
        val result: Pair<SessionInfo?, String?> = try {
            val conn = URL("$base$path").openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
            }
            AgentAuth.sign(conn, prefs.agentDeviceId(context), path, "GET", null)
            conn.setRequestProperty("X-API-Key", prefs.apiKey)
            val code = conn.responseCode
            if (code != 200) {
                // Diagnostic: surface the server's safe reason (unknown_device,
                // signature_mismatch, timestamp_out_of_window) — never echo
                // keys/signatures. Read at most 300 chars.
                val errBody = try {
                    conn.errorStream?.use { it.bufferedReader().readText() }?.take(300) ?: ""
                } catch (_: Exception) { "" }
                conn.disconnect()
                val reason = try {
                    org.json.JSONObject(errBody).optString("reason", "")
                } catch (_: Exception) { "" }
                val detail = if (reason.isNotBlank()) "reason=$reason" else ""
                return@withContext null to "pairing session lookup failed: HTTP $code $detail"
            }
            val body = conn.inputStream.use { it.bufferedReader().readText() }
            conn.disconnect()
            val o = JSONObject(body)
            val info = SessionInfo(
                pairingSessionId = o.getString("pairingSessionId"),
                webDeviceId = o.getString("webDeviceId"),
                origin = o.getString("origin"),
                expiresAt = o.optLong("expiresAt", 0L),
                transcriptHash = o.optString("transcriptHash", ""),
                rawMetadata = o
            )
            // P0-8: metadata hash must equal the QR hash — substitution check.
            if (scanned.transcriptHash.isNotBlank() &&
                info.transcriptHash.isNotBlank() &&
                info.transcriptHash != scanned.transcriptHash
            ) {
                return@withContext null to "transcript mismatch between QR and server"
            }
            info to null
        } catch (e: Exception) {
            Log.e(TAG, "session metadata fetch failed", e)
            null to (e.message ?: "network error")
        }
        result
    }

    /**
     * Approve: build the DeviceCertificate, sign it with the Trust Root
     * operational key, and POST it (X-Agent-Auth over the exact body).
     * Returns the signed certificate on success.
     */
    suspend fun approve(
        context: Context,
        info: SessionInfo,
        capabilities: List<String>,
        historyGrant: String
    ): Result<JSONObject> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
        val prefs = GatewayPreferences(context)
        val base = prefs.gmwebUrl.trimEnd('/')
        require(base.isNotBlank()) { "GMweb URL is not configured" }

        val deviceId = prefs.agentDeviceId(context)
        // The server transcript (fetched, hash-verified) carries the web's
        // REAL public keys — used here, never the opaque deviceId strings.
        val meta = info.rawMetadata ?: throw IllegalStateException("server transcript metadata missing")
        val certificate = JSONObject().apply {
            put("accountId", "default")
            put("deviceId", info.webDeviceId)
            put("deviceType", "WEB_PWA")
            put("signingPublicKey", meta.optString("webSigningPublicKey", ""))
            put("encryptionPublicKey", meta.optString("webEncryptionPublicKey", ""))
            put("capabilities", org.json.JSONArray(capabilities))
            put("historyGrant", historyGrant)
            put("trustSequence", System.currentTimeMillis() / 1000)
            put("issuedAt", System.currentTimeMillis())
            put("expiresAt", System.currentTimeMillis() + 180L * 24 * 3600 * 1000)
            put("pairingTranscriptHash", info.transcriptHash)
            put("origin", info.origin)
        }

        // BLOCKER 2: the PrimaryTrustRoot (Keystore, non-exportable, separate
        // from the AgentAuth HTTP key) signs the canonical certificate bytes.
        val rootSignature = PrimaryTrustRoot.sign(certificate)
        certificate.put("rootSignature", rootSignature)

        val path = "/api/v1/pairing/approve"
        val body = JSONObject()
            .put("pairingSessionId", info.pairingSessionId)
            .put("certificate", certificate.toString())
            .put("deviceId", info.webDeviceId)
            .put("transcriptHash", info.transcriptHash)
            .put("trustRootPublicKey", PrimaryTrustRoot.publicKeyBase64())

        val conn = URL("$base$path").openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = true
        }
        val bodyBytes = body.toString().toByteArray(Charsets.UTF_8)
        conn.setRequestProperty("X-API-Key", prefs.apiKey)
        if (!AgentAuth.sign(conn, deviceId, path, "POST", bodyBytes)) {
            throw IllegalStateException("agent signing failed (keystore unavailable)")
        }
        conn.outputStream.use { it.write(bodyBytes) }
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.use { it.bufferedReader().readText() } ?: ""
            throw IllegalStateException("approve failed: HTTP $code $err")
        }
        conn.disconnect()
        certificate
        }
    }
}
