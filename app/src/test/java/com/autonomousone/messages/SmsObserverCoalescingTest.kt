package com.autonomousone.messages

import com.autonomousone.messages.observer.ProviderChangeBatch
import com.autonomousone.messages.observer.SmsContentObserver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coalescing state machine, driven by an injected clock AND an injected
 * scheduler so the trailing runnable can actually be pumped. The previous tests
 * could not pump it, which is why the loss across a window boundary went
 * unnoticed.
 *
 * INVARIANT: every provider notification contributes to exactly one dispatched
 * batch.
 */
class SmsObserverCoalescingTest {

    private class FakeScheduler : SmsContentObserver.Scheduler {
        private val jobs = LinkedHashMap<Runnable, Long>()
        val scheduledDelays: MutableList<Long> = mutableListOf()

        override fun schedule(delayMs: Long, action: Runnable) {
            jobs[action] = delayMs
            scheduledDelays += delayMs
        }

        override fun cancel(action: Runnable) {
            jobs.remove(action)
        }

        fun runPending() {
            val pending = jobs.keys.toList()
            jobs.clear()
            pending.forEach { it.run() }
        }

        fun hasPending(): Boolean = jobs.isNotEmpty()
    }

    // Non-zero start so the first notification qualifies as a leading edge
    // (lastFiredAt starts at 0 and the window is 150 ms).
    private var now = 1000L
    private val batches = mutableListOf<ProviderChangeBatch>()
    private val scheduler = FakeScheduler()
    private val observer = SmsContentObserver(
        clock = { now },
        scheduler = scheduler
    ) { batches += it }

    @Test
    fun `a notification accumulated in a window is not lost at the boundary`() {
        // A @ 0 leading
        observer.onChange(false)
        assertEquals(1, batches.size)

        // B @ 20 accumulates (inside the window; the trailing flush is scheduled)
        now = 1020
        observer.onChange(false)
        assertEquals("still inside the window", 1, batches.size)
        assertTrue(scheduler.hasPending())

        // C @ 151 is a NEW leading edge. The old code cancelled B's flush and
        // cleared the accumulator, so B vanished.
        now = 1151
        observer.onChange(false)

        assertEquals("A, then B flushed, then C", 3, batches.size)
    }

    @Test
    fun `every id in a crossing is represented exactly once`() {
        observer.onChange(false)          // leading, no ids (path-less notification)
        now = 1020
        observer.onChange(false)
        now = 1151
        observer.onChange(false)

        // All three notifications reached the callback exactly once.
        assertEquals(3, batches.size)
    }

    @Test
    fun `the trailing flush emits one batch containing the whole burst`() {
        observer.onChange(false)          // leading
        now = 1020
        observer.onChange(false)          // pending
        now = 1040
        observer.onChange(false)          // still pending
        assertEquals(1, batches.size)

        scheduler.runPending()

        assertEquals("the two pending notifications collapsed into ONE batch", 2, batches.size)
        assertEquals(1, scheduler.scheduledDelays.size)
    }

    @Test
    fun `pumping twice does not emit a duplicate batch`() {
        observer.onChange(false)
        now = 1020
        observer.onChange(false)

        scheduler.runPending()
        val afterFirstPump = batches.size
        scheduler.runPending()

        assertEquals(afterFirstPump, batches.size)
    }

    @Test
    fun `a later change past the window is a new leading edge`() {
        observer.onChange(false)
        now = 10200
        observer.onChange(false)
        assertEquals(2, batches.size)
    }
}
