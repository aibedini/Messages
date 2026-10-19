package com.autonomousone.messages.gateway

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * STAGE-2 §2.5 — GatewayTicker's scheduling of a BACKOFF LADDER.
 *
 * This test does **not** exercise `HeartbeatManager` (which needs a `Context` and is not wired to the
 * ticker in this round). It pins the ticker-side property the future wiring depends on: a subscriber
 * that fails can request its next attempt via `requestEarlierTick`, and the ticker honours a 1 s step
 * rather than collapsing it into the 60 s cadence. The ladder arithmetic here is the heartbeat's
 * shape (1 s, doubled), used as a model — not the manager's code.
 */
class GatewayTickerBackoffLadderTest {

    @Test
    fun `the first retry after a failed heartbeat is about one second, not sixty`() = runTest {
        val ticker = GatewayTicker(backgroundScope, clock = { testScheduler.currentTime })
        val attempts = mutableListOf<Long>()
        // Exactly HeartbeatManager's ladder shape: INITIAL_BACKOFF_MS = 1 s, doubled on each failure.
        var backoffMs = 1_000L

        ticker.subscribe("heartbeat") {
            attempts += testScheduler.currentTime
            if (attempts.size < 3) {
                ticker.requestEarlierTick("heartbeat", backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(300_000L)
            }
        }
        ticker.start()

        advanceTimeBy(GatewayTicker.TICK_INTERVAL_MS); runCurrent() // shared tick, attempt 1 fails
        advanceTimeBy(1_000); runCurrent()                          // retry after 1 s, attempt 2 fails
        advanceTimeBy(2_000); runCurrent()                          // retry after 2 s, attempt 3

        assertEquals(listOf(60_000L, 61_000L, 63_000L), attempts)
        ticker.stop()
    }
}
