package com.autonomousone.messages

import com.autonomousone.messages.repository.BulkConcurrency
import com.autonomousone.messages.repository.boundedParallelMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * FEATURE 10 — the provider-write fan-out must be BOUNDED.
 *
 * A 300-conversation selection used to be a plausible "loop and fire" shape; the
 * rule this test defends is that no bulk action may ever hold more than
 * [BulkConcurrency.MAX_PARALLEL] provider writes open at once.
 */
class BulkBoundedConcurrencyTest {

    @Test
    fun `the configured maximum is a small, sane ceiling`() {
        // Explicitly asserted so a future edit cannot silently raise it to
        // "unbounded" (or drop it to 0).
        assertTrue(
            "MAX_PARALLEL must stay in 2..4, was ${BulkConcurrency.MAX_PARALLEL}",
            BulkConcurrency.MAX_PARALLEL in 2..4
        )
        assertEquals(3, BulkConcurrency.MAX_PARALLEL)
    }

    @Test
    fun `N items never exceed the configured maximum in flight`() = runBlocking {
        val items = (1L..25L).toList()
        val inFlight = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)
        val completed = AtomicInteger(0)

        val results = boundedParallelMap(items) { id ->
            val now = inFlight.incrementAndGet()
            maxObserved.updateAndGet { current -> maxOf(current, now) }
            try {
                delay(5)
                completed.incrementAndGet()
                id
            } finally {
                inFlight.decrementAndGet()
            }
        }

        assertEquals(items.size, results.size)
        assertEquals(items.size, completed.get())
        assertTrue("expected overlap, saw max=$maxObserved", maxObserved.get() > 1)
        assertTrue(
            "max in flight was ${maxObserved.get()}, cap is ${BulkConcurrency.MAX_PARALLEL}",
            maxObserved.get() <= BulkConcurrency.MAX_PARALLEL
        )
        assertTrue(items.all { it in results.map { r -> r.getOrThrow() } })
    }

    @Test
    fun `an explicit lower maximum is honoured`() = runBlocking {
        val inFlight = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)

        boundedParallelMap((1..12).toList(), maxParallel = 2) {
            val now = inFlight.incrementAndGet()
            maxObserved.updateAndGet { current -> maxOf(current, now) }
            try {
                delay(3)
            } finally {
                inFlight.decrementAndGet()
            }
        }

        assertTrue(maxObserved.get() <= 2)
    }

    @Test
    fun `a failing item becomes a per-item failure and never a thrown batch`() = runBlocking {
        val results = boundedParallelMap(listOf(1L, 2L, 3L)) { id ->
            if (id == 2L) throw IllegalStateException("provider write failed")
            id
        }

        assertEquals(3, results.size)
        assertTrue(results[0].isSuccess)
        assertTrue(results[1].isFailure)
        assertEquals("provider write failed", results[1].exceptionOrNull()?.message)
        assertTrue(results[2].isSuccess)
    }

    @Test
    fun `an empty input runs no work at all`() = runBlocking {
        var calls = 0
        val results = boundedParallelMap(emptyList<Long>()) { calls++ }
        assertTrue(results.isEmpty())
        assertEquals(0, calls)
    }

    @Test
    fun `an invalid maximum is rejected instead of silently running unbounded`() {
        val failure = runCatching {
            runBlocking { boundedParallelMap(listOf(1L), maxParallel = 0) { it } }
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertFalse(failure == null)
    }
}
