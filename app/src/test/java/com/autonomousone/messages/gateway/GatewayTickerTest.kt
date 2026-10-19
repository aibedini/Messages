package com.autonomousone.messages.gateway

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 1.5 — one 60 s wake-up instead of five drifting ones.
 *
 * These are scheduler tests, not component tests: what matters here is that a tick reaches every
 * subscriber, that one failing subscriber cannot take the tick down, that `kick()` is immediate
 * without double-firing, and that `stop()` really stops.
 */
class GatewayTickerTest {

    @Test
    fun `one tick invokes every subscriber exactly once in registration order`() = runTest {
        val ticker = GatewayTicker(backgroundScope)
        val calls = mutableListOf<String>()
        ticker.subscribe("heartbeat") { calls += "heartbeat" }
        ticker.subscribe("telemetry") { calls += "telemetry" }
        ticker.subscribe("uploader") { calls += "uploader" }

        ticker.start()
        advanceTimeBy(GatewayTicker.TICK_INTERVAL_MS)
        runCurrent()

        assertEquals(listOf("heartbeat", "telemetry", "uploader"), calls)
        ticker.stop()
    }

    @Test
    fun `a throwing subscriber does not prevent the others and the ticker survives`() = runTest {
        val ticker = GatewayTicker(backgroundScope)
        val calls = mutableListOf<String>()
        ticker.subscribe("broken") { error("subscriber exploded") }
        ticker.subscribe("healthy") { calls += "healthy" }

        ticker.start()
        advanceTimeBy(GatewayTicker.TICK_INTERVAL_MS)
        runCurrent()
        assertEquals(listOf("healthy"), calls)

        // The loop must still be running: a second tick reaches the healthy subscriber again.
        advanceTimeBy(GatewayTicker.TICK_INTERVAL_MS)
        runCurrent()
        assertEquals(listOf("healthy", "healthy"), calls)
        assertTrue(ticker.isRunning)
        ticker.stop()
    }

    @Test
    fun `kick fires immediately and does not double-fire the scheduled tick`() = runTest {
        val ticker = GatewayTicker(backgroundScope)
        var ticks = 0
        ticker.subscribe("any") { ticks++ }

        ticker.start()
        advanceTimeBy(1_000)
        ticker.kick()
        runCurrent()
        assertEquals("a kick must not wait for the interval", 1, ticks)

        // The kick restarts the interval; the original 60 s tick must not fire as well.
        advanceTimeBy(GatewayTicker.TICK_INTERVAL_MS - 1_000 - 1)
        runCurrent()
        assertEquals("the kick must not be duplicated by the original schedule", 1, ticks)

        // One interval after the KICK (t = 1 s + 60 s), the scheduled tick happens on its own.
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals("the scheduled tick still happens on its own cadence", 2, ticks)
        ticker.stop()
    }

    @Test
    fun `after stop no further ticks are delivered`() = runTest {
        val ticker = GatewayTicker(backgroundScope)
        var ticks = 0
        ticker.subscribe("any") { ticks++ }

        ticker.start()
        advanceTimeBy(GatewayTicker.TICK_INTERVAL_MS)
        runCurrent()
        assertEquals(1, ticks)

        ticker.stop()
        assertFalse(ticker.isRunning)
        advanceTimeBy(GatewayTicker.TICK_INTERVAL_MS * 3)
        runCurrent()
        assertEquals(1, ticks)
    }

    @Test
    fun `the shared interval is one minute`() {
        assertEquals(60_000L, GatewayTicker.TICK_INTERVAL_MS)
    }
}
