package com.autonomousone.messages.gateway.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** STAGE-4 §2.2: sustained excess disconnects, backs off and reports TRANSPORT_FAILED. */
class WakeAbuseDisconnectTest : WakeSocketTestBase() {

    @Test
    fun `two abusive windows disconnect and fall back`() {
        val clock = MutableClock(100_000L)
        val server = newServer()
        val runtime = newRuntime(
            server = server,
            clock = clock,
            backoffMs = { 60_000L },
            configOverride = {
                it.copy(
                    rateWindowMs = 100L,
                    rateCeilingPerWindow = 2,
                    abusiveWindowsBeforeDisconnect = 2,
                    wakeFloorMs = 1_000L,
                )
            },
        )
        WakeTestHarness.awaitUntil(description = "upgrade") { server.upgradeCount.get() == 1 }

        repeat(3) { server.sendWake() }
        WakeTestHarness.awaitUntil(description = "first window counted") {
            val health = runtime.lastHealth.get()
            health.wakeCount + health.suppressedWakes >= 3L
        }

        clock.now += 150L
        repeat(3) { server.sendWake() }
        WakeTestHarness.awaitUntil(description = "second window counted") {
            val health = runtime.lastHealth.get()
            health.wakeCount + health.suppressedWakes >= 6L
        }

        clock.now += 150L
        server.sendWake()

        WakeTestHarness.awaitUntil(description = "abuse disconnect") {
            runtime.fallbacks.get() >= 1
        }
        assertEquals(WakeUnavailable.TRANSPORT_FAILED, runtime.lastHealth.get().unavailable)
        assertEquals(WakeSocketState.CLOSED, runtime.lastHealth.get().socketState)
        assertTrue(runtime.lastHealth.get().reconnectCount >= 1)
    }
}
