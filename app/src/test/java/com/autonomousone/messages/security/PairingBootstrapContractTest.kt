package com.autonomousone.messages.security

import com.autonomousone.messages.gateway.RegistrationManager
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class PairingBootstrapContractTest {
    private fun validQr(): JSONObject {
        val raw = checkNotNull(javaClass.classLoader?.getResourceAsStream("pairing-protocol-v1.json"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val qr = JSONObject(raw).getJSONArray("vectors").getJSONObject(1).getJSONObject("input")
        qr.put("expiresAt", System.currentTimeMillis() + 120000)
        qr.put("transcriptHash", PairingProtocol.transcriptHash(qr))
        return qr
    }

    @Test fun `QR binds separate web and API origins`() {
        val qr = validQr()
        val session = checkNotNull(PairingClient.parseQrPayload(qr.toString()))
        assertEquals("https://web.example.com", session.origin)
        assertTrue(PairingClient.originMatches("https://api.example.com", session))
        assertFalse(PairingClient.originMatches("https://web.example.com", session))
    }

    @Test fun `expired or substituted QR is rejected before metadata`() {
        val changed = validQr().put("nonce", "substituted")
        assertEquals(null, PairingClient.parseQrPayload(changed.toString()))
        val expired = validQr().put("expiresAt", System.currentTimeMillis() - 1)
        expired.put("transcriptHash", PairingProtocol.transcriptHash(expired))
        assertEquals(null, PairingClient.parseQrPayload(expired.toString()))
    }

    @Test
    fun `old bootstrap QR is rejected`() {
        val old = JSONObject().put("pairingSessionId", "session").put("webDeviceId", "web")
            .put("origin", "https://gmweb.example").put("identityBootstrapToken", "secret")
        assertEquals(null, PairingClient.parseQrPayload(old.toString()))
    }

    @Test
    fun `known identity refresh does not fall back to shared gateway key`() {
        val headers = RegistrationManager.registrationHeaders(
            identityRegistered = true,
            apiKey = "gw_local_only",
            registrationSecret = "",
        )
        assertFalse(headers.containsKey("X-API-Key"))
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
