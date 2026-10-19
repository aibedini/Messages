package com.autonomousone.messages.gateway.wake

import com.autonomousone.messages.gateway.AgentAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the cross-repo handshake contract (STAGE-4 §1/§6).
 *
 * The canonical string is the ONE thing that must match GMweb's `agentAuth.js` byte for byte. The
 * upgrade must carry the same two headers as every other signed call.
 */
class WakeHandshakeTest : WakeSocketTestBase() {

    @Test
    fun `canonical string is exactly the documented bytes`() {
        val timestamp = 1_700_000_000_000L
        val expected = "GET\n/gateway/wake\n" +
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\n" +
            "X-AGENT-TS:$timestamp\n"

        assertEquals(
            expected,
            AgentAuth.canonicalString(
                method = "GET",
                path = "/gateway/wake",
                bodyBytes = ByteArray(0),
                timestamp = timestamp,
            )
        )
    }

    @Test
    fun `the signed path carries no query string`() {
        val canonical = AgentAuth.canonicalString(
            method = "GET",
            path = WakeSocketEndpoint.PATH,
            bodyBytes = null,
            timestamp = 42L,
        )

        assertEquals("GET\n/gateway/wake\n", canonical.substringBefore("e3b0"))
        assertFalse("the signed path must not grow a query string", canonical.contains("?"))
    }

    @Test
    fun `the upgrade carries the signed headers on the wire`() {
        val server = newServer()
        newRuntime(server)

        WakeTestHarness.awaitUntil(description = "the websocket upgrade") {
            server.upgradeCount.get() == 1
        }

        assertEquals("GET /gateway/wake HTTP/1.1", server.lastRequestLine)
        assertEquals("device-test:c2ln", server.lastRequestHeaders["x-agent-auth"])
        assertEquals("123456789", server.lastRequestHeaders["x-agent-ts"])
        // The wake channel has no payload: the upgrade is a plain GET with no body.
        assertNull(server.lastRequestHeaders["content-type"])
        assertNull(server.lastRequestHeaders["x-wake-payload"])
    }
}
