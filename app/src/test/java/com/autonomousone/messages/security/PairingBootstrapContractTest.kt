package com.autonomousone.messages.security

import com.autonomousone.messages.gateway.AgentAuth
import com.autonomousone.messages.gateway.RegistrationManager
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class PairingBootstrapContractTest {
    @Test
    fun `QR parser retains the session-bound identity bootstrap token`() {
        val session = PairingClient.parseQrPayload(
            JSONObject()
                .put("pairingSessionId", "session-1")
                .put("webDeviceId", "web-1")
                .put("origin", "https://gmweb.example")
                .put("expiresAt", 123L)
                .put("transcriptHash", "hash")
                .put("identityBootstrapToken", "one-time-token")
                .toString()
        )
        assertEquals("one-time-token", session?.identityBootstrapToken)
    }

    @Test
    fun `pairing bootstrap replaces local gateway key headers`() {
        val headers = RegistrationManager.registrationHeaders(
            identityRegistered = false,
            apiKey = "gw_local_only",
            registrationSecret = "",
            pairingSessionId = "session-1",
            pairingBootstrapToken = "one-time-token",
        )
        assertEquals("session-1", headers["X-Pairing-Session"])
        assertEquals("one-time-token", headers["X-Pairing-Bootstrap"])
        assertFalse(headers.containsKey("X-API-Key"))
    }

    @Test
    fun `known identity refresh does not fall back to shared gateway key`() {
        val headers = RegistrationManager.registrationHeaders(
            identityRegistered = true,
            apiKey = "gw_local_only",
            registrationSecret = "",
            pairingSessionId = null,
            pairingBootstrapToken = null,
        )
        assertFalse(headers.containsKey("X-API-Key"))
    }

    @Test
    fun `fresh install registration success proceeds without metadata retry`() {
        assertFalse(PairingClient.shouldRetryMetadata(200, "", alreadyRetried = false))
    }

    @Test
    fun `unknown device retries exactly once with fresh signature`() {
        assertTrue(PairingClient.shouldRetryMetadata(401, "unknown_device", alreadyRetried = false))
        assertFalse(PairingClient.shouldRetryMetadata(401, "unknown_device", alreadyRetried = true))
        val first = AgentAuth.freshTimestamp(100L)
        val retry = AgentAuth.freshTimestamp(100L)
        assertTrue(retry > first)
    }

    @Test
    fun `signature and replay failures never enter retry loop`() {
        assertFalse(PairingClient.shouldRetryMetadata(401, "signature_mismatch", false))
        assertFalse(PairingClient.shouldRetryMetadata(401, "replayed_timestamp", false))
        assertFalse(PairingClient.shouldRetryMetadata(401, "timestamp_out_of_window", false))
    }

    @Test
    fun `approve signs and sends the identical byte array`() {
        val body = PairingClient.ExactBody.utf8(JSONObject().put("pairingSessionId", "session"))
        assertSame(body.signingBytes, body.wireBytes)
    }

    @Test
    fun `approve failure cannot produce linked state`() {
        assertFalse(PairingClient.approveMakesLinked(401))
        assertFalse(PairingClient.approveMakesLinked(500))
    }

    @Test
    fun `approve 200 produces linked state and browser approved contract`() {
        assertTrue(PairingClient.approveMakesLinked(200))
        assertFalse(PairingClient.approveMakesLinked(201))
    }
}
