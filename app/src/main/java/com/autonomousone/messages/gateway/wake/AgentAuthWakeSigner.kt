package com.autonomousone.messages.gateway.wake

import com.autonomousone.messages.gateway.AgentAuth

/**
 * The production handshake signer: the SAME ES256 operational key and canonical string every other
 * agent call uses, with the body hash of the empty body (STAGE-4 §1).
 *
 * Fail closed: if `AgentAuth` cannot sign, this returns null and `SocketWakeSource` aborts.
 */
object AgentAuthWakeSigner : WakeHandshakeSigner {
    override fun sign(
        deviceId: String,
        path: String,
        method: String,
        body: ByteArray?,
    ): WakeHandshakeHeaders? =
        AgentAuth.signedHeaders(deviceId, path, method, body)?.let {
            WakeHandshakeHeaders(agentAuth = it.agentAuth, timestamp = it.timestamp)
        }
}
