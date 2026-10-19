package com.autonomousone.messages.gateway

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * STAGE-2 §2.5 — `requestEarlierTick`: per-subscriber, coalescing, floored.
 *
 * The heartbeat's ladder is 1 s → 2 s → 4 s → … A fixed 60 s tick cannot express that, and a global
 * earlier tick would drag telemetry and the trust publisher along with it, re-creating the burst of
 * simultaneous requests §2.5 exists to remove.
 */
class GatewayTickerEarlierTickTest {

    @Test
    fun `an earlier tick reaches only the subscriber that asked for it`() = runTest {
        val ticker = GatewayTicker(backgroundScope, clock = { testScheduler.currentTime })
        val calls = mutableListOf<String>()
        ticker.subscribe("a") { calls += "a" }
        ticker.subscribe("b") { calls += "b" }
        ticker.start()

        ticker.requestEarlierTick("a", 1_000)
        advanceTimeBy(999); runCurrent()
        assertEquals(emptyList<String>(), calls)
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf("a"), calls)
        ticker.stop()
    }

    @Test
    fun `the earliest outstanding request wins and does not stack`() = runTest {
        val ticker = GatewayTicker(backgroundScope, clock = { testScheduler.currentTime })
        var ticks = 0
        ticker.subscribe("a") { ticks++ }
        ticker.start()

        ticker.requestEarlierTick("a", 5_000)
        ticker.requestEarlierTick("a", 3_000)
        ticker.requestEarlierTick("a", 1_000)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, ticks)
        advanceTimeBy(10_000); runCurrent()
        assertEquals("later requests must not stack", 1, ticks)
        ticker.stop()
    }

    @Test
    fun `a later request after a fired one is honoured`() = runTest {
        val ticker = GatewayTicker(backgroundScope, clock = { testScheduler.currentTime })
        var ticks = 0
        ticker.subscribe("a") { ticks++ }
        ticker.start()

        ticker.requestEarlierTick("a", 1_000)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, ticks)
        ticker.requestEarlierTick("a", 2_000)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(2, ticks)
        ticker.stop()
    }

    @Test
    fun `the one second floor is enforced`() = runTest {
        val ticker = GatewayTicker(backgroundScope, clock = { testScheduler.currentTime })
        var ticks = 0
        ticker.subscribe("a") { ticks++ }
        ticker.start()

        ticker.requestEarlierTick("a", 10)
        advanceTimeBy(GatewayTicker.MIN_EARLIER_DELAY_MS - 1); runCurrent()
        assertEquals("a sub-second request must not fire early", 0, ticks)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, ticks)
        ticker.stop()
    }
}
