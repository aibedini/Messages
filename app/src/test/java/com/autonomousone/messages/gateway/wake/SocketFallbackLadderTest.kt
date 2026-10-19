package com.autonomousone.messages.gateway.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** STAGE-4 §4: every failure lands on the POLLING floor with a reported reason. */
class SocketFallbackLadderTest : WakeSocketTestBase() {

    @Test
    fun `a rejected upgrade falls back to polling`() {
        val server = newServer(status = 401)
        val runtime = newRuntime(server, backoffMs = { 60_000L })

        WakeTestHarness.awaitUntil(description = "fallback after HTTP 401") {
            runtime.fallbacks.get() == 1
        }

        assertEquals(WakeUnavailable.TRANSPORT_FAILED, runtime.lastHealth.get().unavailable)
        assertEquals(WakeSocketState.CLOSED, runtime.lastHealth.get().socketState)
        assertEquals(0, runtime.wakes.get())
        assertTrue(server.requestCount.get() >= 1)
    }

    @Test
    fun `a dropped connection falls back and later reconnects with backoff`() {
        val server = newServer()
        val runtime = newRuntime(server, backoffMs = { 25L })
        WakeTestHarness.awaitUntil(description = "first upgrade") { server.upgradeCount.get() == 1 }

        server.dropConnection()

        WakeTestHarness.awaitUntil(description = "fallback after drop") {
            runtime.fallbacks.get() >= 1
        }
        assertEquals(WakeUnavailable.TRANSPORT_FAILED, runtime.lastHealth.get().unavailable)

        WakeTestHarness.awaitUntil(description = "automatic retry") {
            server.upgradeCount.get() >= 2
        }
        assertTrue(runtime.lastHealth.get().reconnectCount >= 1)

        server.sendWake()
        WakeTestHarness.awaitUntil(description = "wake after reconnect") {
            runtime.wakes.get() == 1
        }
    }

    @Test
    fun `a pin mismatch is reported and falls back, never silently bypassed`() {
        val otherCertificate = okhttp3.tls.HeldCertificate.Builder()
            .commonName("other.example")
            .build()
        val wrongPin = WakeTestHarness.pinFor(otherCertificate.certificate)
        val server = newServer()
        val runtime = newRuntime(
            server = server,
            pins = WakeCertificatePins(current = wrongPin),
            backoffMs = { 60_000L },
        )

        WakeTestHarness.awaitUntil(description = "fallback after pin mismatch") {
            runtime.fallbacks.get() == 1
        }
        assertEquals(WakePinning.MISMATCH, runtime.lastHealth.get().pinning)
        assertEquals(WakeUnavailable.TRANSPORT_FAILED, runtime.lastHealth.get().unavailable)
        assertEquals(0, runtime.wakes.get())
    }
}
