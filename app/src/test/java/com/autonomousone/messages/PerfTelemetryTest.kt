package com.autonomousone.messages

import com.autonomousone.messages.diagnostics.MetricStats
import com.autonomousone.messages.diagnostics.PerfMetric
import com.autonomousone.messages.diagnostics.PerfTelemetry
import com.autonomousone.messages.diagnostics.RollingSamples
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 18 local telemetry: bounded memory, deterministic percentiles, and
 * "no samples" instead of a fabricated zero.
 */
class PerfTelemetryTest {

    @Test
    fun `ring buffer evicts the oldest sample`() {
        val samples = RollingSamples(capacity = 3)
        samples.add(1L)
        samples.add(2L)
        samples.add(3L)
        samples.add(4L)
        assertEquals(3, samples.size())
        assertArrayEquals(longArrayOf(2L, 3L, 4L), samples.snapshot())
    }

    @Test
    fun `p50 and p95 are computed correctly`() {
        val samples = RollingSamples(capacity = 100)
        for (value in 1L..100L) samples.add(value)
        assertEquals(50L, samples.percentile(0.50))
        assertEquals(95L, samples.percentile(0.95))
    }

    @Test
    fun `empty samples report no samples rather than zero`() {
        val samples = RollingSamples(capacity = 4)
        val stats = samples.stats()
        assertEquals(0, stats.count)
        assertNull(stats.p50Ms)
        assertNull(stats.p95Ms)
        assertFalse(stats.hasSamples)
        assertEquals(MetricStats.NO_SAMPLES, stats.describe())
    }

    @Test
    fun `telemetry keeps metrics independent`() {
        PerfTelemetry.resetAll()
        for (value in 1L..10L) PerfTelemetry.record(PerfMetric.TAIL_DELTA, value * 10L)
        val tail = PerfTelemetry.stats(PerfMetric.TAIL_DELTA)
        assertEquals(10, tail.count)
        assertEquals(50L, tail.p50Ms)
        assertEquals(100L, tail.p95Ms)
        assertTrue(PerfTelemetry.hasSamples(PerfMetric.TAIL_DELTA))

        val untouched = PerfTelemetry.stats(PerfMetric.FOR_THREAD)
        assertEquals(0, untouched.count)
        assertNull(untouched.p50Ms)
        assertNull(untouched.p95Ms)
        assertFalse(PerfTelemetry.hasSamples(PerfMetric.FOR_THREAD))
    }

    @Test
    fun `snapshot reports every metric without inventing values`() {
        PerfTelemetry.resetAll()
        val snapshot = PerfTelemetry.snapshot()
        assertEquals(PerfMetric.entries.size, snapshot.size)
        for ((_, stats) in snapshot) {
            assertEquals(0, stats.count)
            assertNull(stats.p50Ms)
            assertNull(stats.p95Ms)
            assertFalse(stats.hasSamples)
        }
    }

    @Test
    fun `negative durations are ignored`() {
        PerfTelemetry.reset(PerfMetric.HISTORY_BACKFILL_BATCH)
        PerfTelemetry.record(PerfMetric.HISTORY_BACKFILL_BATCH, -1L)
        assertFalse(PerfTelemetry.hasSamples(PerfMetric.HISTORY_BACKFILL_BATCH))
    }

    @Test
    fun `elapsed milliseconds are never negative`() {
        assertTrue(PerfTelemetry.elapsedMs(PerfTelemetry.mark()) >= 0L)
        assertTrue(PerfTelemetry.elapsedMs(System.nanoTime() + 1_000_000_000L) >= 0L)
    }

    @Test
    fun `reset clears only the targeted metric`() {
        PerfTelemetry.resetAll()
        PerfTelemetry.record(PerfMetric.TAIL_DELTA, 5L)
        PerfTelemetry.record(PerfMetric.FOR_THREAD, 7L)
        PerfTelemetry.reset(PerfMetric.TAIL_DELTA)
        assertFalse(PerfTelemetry.hasSamples(PerfMetric.TAIL_DELTA))
        assertTrue(PerfTelemetry.hasSamples(PerfMetric.FOR_THREAD))
        PerfTelemetry.resetAll()
    }
}
