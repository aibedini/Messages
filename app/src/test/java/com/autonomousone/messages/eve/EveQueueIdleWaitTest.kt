package com.autonomousone.messages.eve

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 1.4 — the Eve send queue's idle wait.
 *
 * The worker slept a fixed 400 ms between empty scans: 2.5 wake-ups a second, 216 000 a day, for a
 * queue that had nothing to do. The fix waits on a condition signalled by `offer`, bounded by the
 * nearest deferred deadline. These tests pin the two properties that make that safe rather than slow:
 * a new message still wakes the worker immediately, and `stop()` still ends a parked thread.
 */
class EveQueueIdleWaitTest {

    private val store = EveSmsQueue.MemoryStore()

    /** Loads a fixed set of records, so a DEFERRED record can be present at bootstrap. */
    private class LoadingStore(private val loaded: List<EveSmsQueue.Record>) : EveSmsQueue.Store {
        override fun load(): Pair<List<EveSmsQueue.Record>, Map<String, String>> =
            loaded to emptyMap()

        override fun save(
            records: List<EveSmsQueue.Record>,
            idempotency: Map<String, String>
        ) = Unit
    }

    @After
    fun tearDown() {
        EveSmsQueue.stop()
    }

    @Test
    fun `a new message wakes the worker immediately`() {
        // Repeated so a lost-signal race between "drain found nothing" and "start waiting" would
        // surface as a slow iteration, not as an occasional flake in CI.
        repeat(25) { iteration ->
            EveSmsQueue.resetForTest(store, sender = { true })
            EveSmsQueue.bootstrap(EveSmsQueue.MemoryStore(), sender = { true })

            val startedAt = System.nanoTime()
            val result = EveSmsQueue.enqueue(
                to = "+989120000000",
                text = "hello $iteration",
                priority = "normal",
                idempotencyKey = null
            )
            val sent = awaitStatus(result.record.requestId, EveSmsQueue.Status.SENT, withinMs = 2_000)
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            assertTrue("iteration $iteration never drained", sent)
            assertTrue(
                "iteration $iteration took ${elapsedMs}ms — offer must signal the worker, not wait 400ms",
                elapsedMs < 500
            )
        }
    }

    @Test
    fun `the wait is bounded by the nearest deferred deadline`() {
        val now = System.currentTimeMillis()
        val deferred = record("deferred-1", EveSmsQueue.Status.DEFERRED, deferredUntil = now + 15_000)
        val loading = LoadingStore(listOf(deferred))
        EveSmsQueue.resetForTest(loading, sender = { true })
        EveSmsQueue.bootstrap(loading, sender = { true })

        val dueInMs = EveSmsQueue.nextDeferredDueInMs(nowMs = now)
        assertTrue("expected ≈15s, got $dueInMs", dueInMs != null && dueInMs in 14_000L..15_000L)
    }

    @Test
    fun `no deferred record means the idle backstop applies`() {
        EveSmsQueue.resetForTest(EveSmsQueue.MemoryStore(), sender = { true })
        EveSmsQueue.bootstrap(EveSmsQueue.MemoryStore(), sender = { true })

        assertNull(EveSmsQueue.nextDeferredDueInMs())
        assertEquals(60_000L, EveSmsQueue.IDLE_WAIT_MS)
    }

    @Test
    fun `stop ends a worker parked in the wait`() {
        EveSmsQueue.resetForTest(EveSmsQueue.MemoryStore(), sender = { true })
        EveSmsQueue.bootstrap(EveSmsQueue.MemoryStore(), sender = { true })
        // Let it reach the idle wait (empty queue, no deferred records).
        Thread.sleep(150)
        val worker = EveSmsQueue.workerThreadForTest()
        assertTrue("the worker must be alive before stop()", worker?.isAlive == true)

        EveSmsQueue.stop()
        worker?.join(2_000)

        assertFalse("stop() must end a thread waiting on the work signal", worker?.isAlive == true)
    }

    private fun awaitStatus(
        requestId: String,
        status: EveSmsQueue.Status,
        withinMs: Long
    ): Boolean {
        val deadline = System.currentTimeMillis() + withinMs
        while (System.currentTimeMillis() < deadline) {
            if (EveSmsQueue.status(requestId)?.status == status) return true
            Thread.sleep(2)
        }
        return EveSmsQueue.status(requestId)?.status == status
    }

    private fun record(
        requestId: String,
        status: EveSmsQueue.Status,
        deferredUntil: Long
    ) = EveSmsQueue.Record(
        requestId = requestId,
        jobId = "job-$requestId",
        to = "+989120000000",
        text = "hello",
        priority = "normal",
        priorityLevel = 5,
        status = status,
        createdAt = 1_000L,
        deferredUntil = deferredUntil
    )
}
