package com.autonomousone.messages.gateway

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * STAGE-2 §2.5 — an earlier tick is ADDITIVE: it never resets or skips the shared 60 s cadence.
 */
class GatewayTickerEarlierTickAdditiveTest {

    @Test
    fun `an earlier tick does not reset or skip the shared 60 s tick`() = runTest {
        val ticker = GatewayTicker(backgroundScope, clock = { testScheduler.currentTime })
        val calls = mutableListOf<Long>()
        ticker.subscribe("a") { calls += testScheduler.currentTime }
        ticker.start()

        // A ladder retry at t = 1 s … 2 s, while the shared cadence started at t = 0.
        advanceTimeBy(1_000)
        ticker.requestEarlierTick("a", 1_000)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf(2_000L), calls)

        // The shared tick is still due at t = 60 s, not 2 s + 60 s.
        advanceTimeBy(GatewayTicker.TICK_INTERVAL_MS - 2_000); runCurrent()
        assertEquals(listOf(2_000L, 60_000L), calls)
        ticker.stop()
    }

    @Test
    fun `an earlier tick never dispatches the other subscribers`() = runTest {
        val ticker = GatewayTicker(backgroundScope, clock = { testScheduler.currentTime })
        val a = mutableListOf<Long>()
        val b = mutableListOf<Long>()
        ticker.subscribe("a") { a += testScheduler.currentTime }
        ticker.subscribe("b") { b += testScheduler.currentTime }
        ticker.start()

        ticker.requestEarlierTick("a", 1_000)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf(1_000L), a)
        assertEquals(emptyList<Long>(), b)
        ticker.stop()
    }
}
