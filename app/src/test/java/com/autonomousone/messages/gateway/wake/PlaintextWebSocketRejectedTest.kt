package com.autonomousone.messages.gateway.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** STAGE-4 §2: WSS only. `ws://` is refused unconditionally, not a setting. */
class PlaintextWebSocketRejectedTest {

    @Test
    fun `https origin becomes wss and keeps host and port`() {
        val endpoint = WakeSocketEndpoint.fromServerOrigin("https://gmweb.example.com")
        assertEquals("wss://gmweb.example.com/gateway/wake", endpoint?.url)
        assertEquals("gmweb.example.com", endpoint?.host)

        val withPort = WakeSocketEndpoint.fromServerOrigin("https://gmweb.example.com:8443")
        assertEquals("wss://gmweb.example.com:8443/gateway/wake", withPort?.url)
    }

    @Test
    fun `http and ws origins are rejected`() {
        assertNull(WakeSocketEndpoint.fromServerOrigin("http://gmweb.example.com"))
        assertNull(WakeSocketEndpoint.fromServerOrigin("ws://gmweb.example.com"))
        assertNull(WakeSocketEndpoint.fromServerOrigin("wss://gmweb.example.com"))
        assertNull(WakeSocketEndpoint.fromServerOrigin(""))
        assertNull(WakeSocketEndpoint.fromServerOrigin("gmweb.example.com"))
    }

    @Test
    fun `an insecure endpoint cannot even be constructed`() {
        val refused = runCatching {
            WakeSocketEndpoint(url = "ws://gmweb.example.com/gateway/wake", host = "gmweb.example.com")
        }
        assertTrue("ws:// must not construct", refused.isFailure)
    }

    @Test
    fun `the source refuses an unusable endpoint before opening a socket`() {
        var fallbacks = 0
        val source = SocketWakeSource(
            config = SocketWakeConfig(
                endpoint = WakeSocketEndpoint(
                    url = "wss://127.0.0.1:1/gateway/wake",
                    host = "127.0.0.1"
                ),
                deviceId = "  ",
            ),
            signer = WakeHandshakeSigner { _, _, _, _ -> null },
            startPollingFallback = { fallbacks++ },
        )
        source.start { }
        try {
            assertEquals(1, fallbacks)
            assertFalse(source.isAvailable())
            assertEquals(WakeUnavailable.NOT_CONFIGURED, source.health.value.unavailable)
        } finally {
            source.stop()
        }
    }
}
