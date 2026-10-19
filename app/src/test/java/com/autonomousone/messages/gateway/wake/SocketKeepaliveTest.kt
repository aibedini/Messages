package com.autonomousone.messages.gateway.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** STAGE-4 §3: OkHttp's protocol ping keeps an otherwise idle socket alive. */
class SocketKeepaliveTest : WakeSocketTestBase() {

    @Test
    fun `pings are sent and the connection still delivers a wake afterwards`() {
        val server = newServer()
        val runtime = newRuntime(server, pingIntervalMs = 100L)
        WakeTestHarness.awaitUntil(description = "upgrade") { server.upgradeCount.get() == 1 }

        WakeTestHarness.awaitUntil(timeoutMs = 5_000L, description = "two protocol pings") {
            server.pingCount.get() >= 2
        }
        // The wake below proves the connection survived those ping/pong rounds; OkHttp fails a
        // socket whose pong does not come back, so a live socket is the keepalive proof.
        assertTrue(server.pingCount.get() >= 2)

        server.sendWake()
        WakeTestHarness.awaitUntil(description = "wake on the kept-alive socket") {
            runtime.wakes.get() == 1
        }
        assertEquals(100L, runtime.lastHealth.get().pingIntervalMs)
        assertEquals(WakeSocketState.OPEN, runtime.lastHealth.get().socketState)
    }

    @Test
    fun `the shipped ping interval is four minutes`() {
        assertEquals(240_000L, SocketWakeConfig(
            endpoint = WakeSocketEndpoint("wss://example.invalid/gateway/wake", "example.invalid"),
            deviceId = "d"
        ).pingIntervalMs)
    }
}
