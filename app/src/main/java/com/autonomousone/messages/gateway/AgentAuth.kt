package com.autonomousone.messages.gateway

import java.net.HttpURLConnection
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * PR-08b (TechSpec §57, ADR-001 LOCK 4) — per-device agent authentication.
 *
 * Every agent API call carries:
 *   X-Agent-Auth: <deviceId>:<base64(derSignature)>
 *   X-Agent-TS:   <epochMs>
 *
 * Signature: ES256 (SHA256withECDSA) with the OPERATIONAL_SIGNING key
 * (DeviceIdentity, PR-05) over the canonical string
 *
 *   METHOD\n<path-without-query>\n<sha256hex(body)>\nX-AGENT-TS:<ts>\n
 *
 * The GMweb side (src/agentAuth.js) verifies against the public key stored
 * at registration (publicKeys.signing). Both sides must stay byte-identical —
 * pinned by DeviceIdentityFormatTest here and agentAuth.test.js on GMweb.
 *
 * Fail-closed (§83): if the Keystore cannot sign, the request is aborted —
 * the server would reject anything unsigned anyway.
 */
object AgentAuth {

    private const val TAG = "AGENT_AUTH"
    private const val REPLAY_WINDOW_MS = 90_000L
    private val random = SecureRandom()

    /**
     * Adds X-Agent-Auth + X-Agent-TS headers to [conn]. Returns false (and
     * leaves the request unsigned) when the Keystore is unavailable — the
     * caller must then abort the request instead of sending it in the clear.
     */
    fun sign(
        conn: HttpURLConnection,
        deviceId: String,
        path: String,
        method: String,
        bodyBytes: ByteArray?
    ): Boolean {
        val ts = System.currentTimeMillis()
        val bodyHash = sha256Hex(bodyBytes ?: ByteArray(0))
        val canonical = "$method\n$path\n$bodyHash\nX-AGENT-TS:$ts\n"
        val signature = try {
            DeviceIdentity.signWithOperationalKey(canonical.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            android.util.Log.e(TAG, "signing failed — aborting request (fail closed)", e)
            return false
        }
        val b64 = android.util.Base64.encodeToString(signature, android.util.Base64.NO_WRAP)
        conn.setRequestProperty("X-Agent-Auth", "$deviceId:$b64")
        conn.setRequestProperty("X-Agent-TS", ts.toString())
        return true
    }

    /** Server-side replay window, exposed for tests (§57). */
    const val SERVER_REPLAY_WINDOW_MS = REPLAY_WINDOW_MS

    fun nowMs(): Long = System.currentTimeMillis()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
