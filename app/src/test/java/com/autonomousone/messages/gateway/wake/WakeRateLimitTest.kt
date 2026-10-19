package com.autonomousone.messages.gateway.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** STAGE-4 §2.2: a burst collapses to at most one pull per second. */
class WakeRateLimitTest : WakeSocketTestBase() {

    @Test
    fun `one hundred frames in one second produce at most one onWake`() {
        val clock = MutableClock(300_000L)
        val server = newServer()
        val runtime = newRuntime(server, clock = clock)
        WakeTestHarness.awaitUntil(description = "upgrade") { server.upgradeCount.get() == 1 }

        repeat(100) { server.sendWake() }

        WakeTestHarness.awaitUntil(description = "all frames counted") {
            val health = runtime.lastHealth.get()
            health.wakeCount + health.suppressedWakes >= 100L
        }
        assertEquals(1, runtime.wakes.get())
        assertEquals(99L, runtime.lastHealth.get().suppressedWakes)

        // The next second earns one wake again: the limit is a rate, not a permanent mute.
        clock.now += 1_001L
        server.sendWake()
        WakeTestHarness.awaitUntil(description = "the next allowed wake") {
            runtime.wakes.get() == 2
        }
        assertTrue(runtime.lastHealth.get().wakeCount >= 2L)
    }

    @Test
    fun `the floor is exactly one second`() {
        val clock = MutableClock(400_000L)
        val server = newServer()
        val runtime = newRuntime(server, clock = clock)
        WakeTestHarness.awaitUntil(description = "upgrade") { server.upgradeCount.get() == 1 }

        server.sendWake()
        WakeTestHarness.awaitUntil(description = "first wake") { runtime.wakes.get() == 1 }

        clock.now += 999L
        server.sendWake()
        Thread.sleep(100L)
        assertEquals(1, runtime.wakes.get())

        clock.now += 2L // 1_001 ms since the first wake
        server.sendWake()
        WakeTestHarness.awaitUntil(description = "second wake after the floor") {
            runtime.wakes.get() == 2
        }
    }
}
