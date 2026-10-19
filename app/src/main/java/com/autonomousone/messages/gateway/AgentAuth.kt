package com.autonomousone.messages.gateway

import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

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
 * at registration (publicKeys.signing). Both sides must stay byte-identical, and **nothing pinned that
 * here**: the KDoc used to claim `DeviceIdentityFormatTest` did, and that test pins the public-point
 * encoding and an ES256 round-trip — not this string. `AgentAuthTest` now pins the canonical form
 * literally, because a silent change to it would make every agent request 401, and a 401 makes this
 * device wipe its credentials and re-enroll (see `ControlPlaneAuthPolicy`) against a server that
 * revoked nothing.
 *
 * Fail-closed (§83): if the Keystore cannot sign, the request is aborted —
 * the server would reject anything unsigned anyway.
 */
object AgentAuth {

    /** The two headers every signed call carries, produced independently of the client. */
    internal data class AgentAuthHeaders(val agentAuth: String, val timestamp: Long)

    private const val TAG = "AGENT_AUTH"
    private const val REPLAY_WINDOW_MS = 90_000L
    private val lastTimestamp = AtomicLong(0L)

    internal fun freshTimestamp(now: Long = System.currentTimeMillis()): Long {
        while (true) {
            val previous = lastTimestamp.get()
            val next = maxOf(now, previous + 1L)
            if (lastTimestamp.compareAndSet(previous, next)) return next
        }
    }

    /**
     * The exact bytes that are signed, and the whole cross-repo contract.
     *
     * Split out of [sign] so it can be pinned by a test without an Android `HttpURLConnection`: this
     * string has a counterpart in GMweb's `agentAuth.js`, and the two must agree byte for byte.
     *
     * Properties that a refactor could break silently:
     *
     *  - the field order is method, path, body hash, timestamp;
     *  - the separator is a single `\n` and the string ENDS with one;
     *  - the body is covered by its lowercase-hex SHA-256, so any body change invalidates the
     *    signature without the whole body being in the canonical string;
     *  - the timestamp is INSIDE the signed material, which is what makes the replay window meaningful
     *    — a timestamp that travelled outside the signature could be rewritten by anyone;
     *  - the timestamp header name is spelled `X-AGENT-TS` exactly.
     */
    internal fun canonicalString(
        method: String,
        path: String,
        bodyBytes: ByteArray?,
        timestamp: Long,
    ): String = "$method\n$path\n${sha256Hex(bodyBytes ?: ByteArray(0))}\nX-AGENT-TS:$timestamp\n"

    /**
     * The signed headers for one request, independent of the HTTP client.
     *
     * Stage 4's WebSocket upgrade uses OkHttp, which cannot hand this object an `HttpURLConnection`.
     * The signature path is therefore split out rather than reimplemented: the canonical string,
     * the Keystore key and the fail-closed rule stay in one place. Returns null when signing is
     * impossible; the caller MUST abort the request rather than send it unsigned.
     */
    internal fun signedHeaders(
        deviceId: String,
        path: String,
        method: String,
        bodyBytes: ByteArray?,
    ): AgentAuthHeaders? {
        val ts = freshTimestamp()
        val canonical = canonicalString(method, path, bodyBytes, ts)
        val signature = try {
            DeviceIdentity.signWithOperationalKey(canonical.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            android.util.Log.e(TAG, "signing failed — aborting request (fail closed)", e)
            return null
        }
        val b64 = android.util.Base64.encodeToString(signature, android.util.Base64.NO_WRAP)
        return AgentAuthHeaders(agentAuth = "$deviceId:$b64", timestamp = ts)
    }

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
        val headers = signedHeaders(deviceId, path, method, bodyBytes) ?: return false
        conn.setRequestProperty("X-Agent-Auth", headers.agentAuth)
        conn.setRequestProperty("X-Agent-TS", headers.timestamp.toString())
        return true
    }

    /** Server-side replay window, exposed for tests (§57). */
    const val SERVER_REPLAY_WINDOW_MS = REPLAY_WINDOW_MS

    fun nowMs(): Long = System.currentTimeMillis()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
