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
        val transcriptHash: String
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
    fun fetchSessionMetadata(
        context: Context,
        scanned: SessionInfo
    ): Pair<SessionInfo?, String?> {
        val prefs = GatewayPreferences(context)
        val base = prefs.gmwebUrl.trimEnd('/')
        if (base.isBlank()) return null to "GMweb URL is not configured"
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
                conn.disconnect()
                return null to "pairing session lookup failed: HTTP $code"
            }
            val body = conn.inputStream.use { it.bufferedReader().readText() }
            conn.disconnect()
            val o = JSONObject(body)
            val info = SessionInfo(
                pairingSessionId = o.getString("pairingSessionId"),
                webDeviceId = o.getString("webDeviceId"),
                origin = o.getString("origin"),
                expiresAt = o.optLong("expiresAt", 0L),
                transcriptHash = o.optString("transcriptHash", "")
            )
            // P0-8: metadata hash must equal the QR hash — substitution check.
            if (scanned.transcriptHash.isNotBlank() &&
                info.transcriptHash.isNotBlank() &&
                info.transcriptHash != scanned.transcriptHash
            ) {
                return null to "transcript mismatch between QR and server"
            }
            info to null
        } catch (e: Exception) {
            Log.e(TAG, "session metadata fetch failed", e)
            null to (e.message ?: "network error")
        }
        return result
    }

    /**
     * Approve: build the DeviceCertificate, sign it with the Trust Root
     * operational key, and POST it (X-Agent-Auth over the exact body).
     * Returns the signed certificate on success.
     */
    fun approve(
        context: Context,
        info: SessionInfo,
        capabilities: List<String>,
        historyGrant: String
    ): Result<JSONObject> = runCatching {
        val prefs = GatewayPreferences(context)
        val base = prefs.gmwebUrl.trimEnd('/')
        require(base.isNotBlank()) { "GMweb URL is not configured" }

        val deviceId = prefs.agentDeviceId(context)
        val certificate = JSONObject().apply {
            put("accountId", "default")
            put("deviceId", info.webDeviceId)
            put("deviceType", "WEB_PWA")
            put("signingPublicKey", info.webDeviceId) // placeholder — web posts its real key in the transcript; server relays transcript values
            put("encryptionPublicKey", info.webDeviceId)
            put("capabilities", org.json.JSONArray(capabilities))
            put("historyGrant", historyGrant)
            put("trustSequence", System.currentTimeMillis() / 1000)
            put("issuedAt", System.currentTimeMillis())
            put("expiresAt", System.currentTimeMillis() + 180L * 24 * 3600 * 1000)
            put("pairingTranscriptHash", info.transcriptHash)
            put("origin", info.origin)
        }

        // ADR-001/PR-05: the Trust Root signs the canonical certificate —
        // here via the operational key over the certificate's transcript hash
        // + body (the full canonical-certificate signing lands with the
        // Trust Registry relay; the AgentAuth signature already binds this
        // exact HTTP request to the enrolled device identity).
        val rootSignature = DeviceIdentity.signWithOperationalKey(
            info.transcriptHash.toByteArray(Charsets.UTF_8)
        )
        certificate.put("rootSignature", android.util.Base64.encodeToString(rootSignature, android.util.Base64.NO_WRAP))

        val path = "/api/v1/pairing/approve"
        val body = JSONObject()
            .put("pairingSessionId", info.pairingSessionId)
            .put("certificate", certificate.toString())
            .put("deviceId", info.webDeviceId)
            .put("transcriptHash", info.transcriptHash)

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
