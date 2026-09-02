package com.autonomousone.messages.gateway

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test

class SecureCommandPollerContractTest {
    @Test
    fun `claim body uses real device identity`() {
        val body = JSONObject(String(SecureCommandPoller.buildClaimBody("real-device-id")))

        assertEquals("real-device-id", body.getString("agentId"))
        assertEquals(25, body.getInt("limit"))
    }

    @Test
    fun `ciphertext field decodes exact bytes`() {
        val expected = "command-body".toByteArray()
        val json = JSONObject().put(
            "ciphertext",
            java.util.Base64.getEncoder().encodeToString(expected)
        )

        assertArrayEquals(expected, SecureCommandPoller.decodeCiphertext(json))
    }

    @Test
    fun `legacy payload field is rejected`() {
        val json = JSONObject().put("payload", "Y29tbWFuZA==")

        assertNull(SecureCommandPoller.decodeCiphertext(json))
    }

    @Test
    fun `missing or invalid ciphertext fails closed`() {
        assertNull(SecureCommandPoller.decodeCiphertext(JSONObject()))
        assertNull(SecureCommandPoller.decodeCiphertext(JSONObject().put("ciphertext", "%%%")))
    }

    @Test
    fun `intake default is legacy pull ownership`() {
        // No-dual-execution P0: SEND_SMS intake is owned by the legacy pull
        // bridge by default; strategic control-plane sends are OFF until
        // real-device E2E proves them.
        org.junit.Assert.assertFalse(GatewayPreferences.DEFAULT_CONTROL_PLANE_SENDS)
    }
}
