package com.autonomousone.messages.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class IdentityBootstrapErrorTest {
    @Test
    fun `device key mismatch is actionable without exposing full key`() {
        val body = """{"error":"unauthorized","reason":"device_key_mismatch","expectedKeyPreview":"devk_…wxyz"}"""
        val message = BackendClient.safeAuthError(body)
        assertEquals("device_key_mismatch (server key devk_…wxyz)", message)
        assertFalse(message.contains("abcdefghijklmnopqrstuvwxyz"))
    }

    @Test
    fun `malformed auth response stays safe`() {
        assertEquals("unauthorized", BackendClient.safeAuthError("not-json"))
    }
}
