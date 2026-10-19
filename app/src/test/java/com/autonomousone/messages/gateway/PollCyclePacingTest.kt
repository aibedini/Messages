package com.autonomousone.messages.gateway

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 1.2 — the idle gap the `OutboxPoller` KDoc promised and the code did not implement.
 *
 * The production defect: `runLoop` had no delay on its success path, so a `PARTIAL_WAKE_LOCK` was
 * released and re-acquired immediately and held for essentially all wall-clock time. The CPU never
 * reached deep sleep — that is the heat, not the work.
 */
class PollCyclePacingTest {

    @Test
    fun `an empty cycle waits the documented idle gap`() {
        assertEquals(5_000L, PollCyclePacing.IDLE_GAP_MS)
        assertEquals(5_000L, PollCyclePacing.gapMsAfter(carriedTask = false))
    }

    @Test
    fun `a cycle that carried a task is not delayed`() {
        // A busy queue must drain without a 5 s pause between messages.
        assertEquals(0L, PollCyclePacing.gapMsAfter(carriedTask = true))
    }

    @Test
    fun `the gap is a real wait of the configured length`() = runTest {
        val wake = Channel<Unit>(Channel.CONFLATED)
        awaitCycleGap(wake, PollCyclePacing.IDLE_GAP_MS)
        assertEquals(PollCyclePacing.IDLE_GAP_MS, currentTime)
    }

    @Test
    fun `retryNow interrupts the gap so a manual reconnect polls at once`() = runTest {
        val wake = Channel<Unit>(Channel.CONFLATED)
        var returned = false
        val job = launch {
            awaitCycleGap(wake, PollCyclePacing.IDLE_GAP_MS)
            returned = true
        }
        advanceTimeBy(50)
        wake.trySend(Unit) // what OutboxPoller.retryNow() does
        advanceUntilIdle()
        assertTrue("the gap must end on a wake, not on the clock", returned)
        assertTrue("returned after ${currentTime} ms", currentTime < PollCyclePacing.IDLE_GAP_MS)
        job.join()
    }

    @Test
    fun `a zero gap does not suspend at all`() = runTest {
        val wake = Channel<Unit>(Channel.CONFLATED)
        awaitCycleGap(wake, 0L)
        assertEquals(0L, currentTime)
    }
}
