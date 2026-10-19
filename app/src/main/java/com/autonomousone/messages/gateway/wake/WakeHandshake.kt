package com.autonomousone.messages.gateway.wake

/**
 * The signed upgrade headers, independent of the HTTP client (STAGE-4 §1).
 *
 * Production uses [AgentAuthWakeSigner], which delegates to `AgentAuth.signedHeaders`; tests inject
 * a fake so the socket can be exercised on the JVM without the Android Keystore.
 */
data class WakeHandshakeHeaders(
    val agentAuth: String,
    val timestamp: Long,
)

fun interface WakeHandshakeSigner {
    /** Returns null when signing is impossible. The caller must then abort: no unsigned upgrade. */
    fun sign(deviceId: String, path: String, method: String, body: ByteArray?): WakeHandshakeHeaders?
}
