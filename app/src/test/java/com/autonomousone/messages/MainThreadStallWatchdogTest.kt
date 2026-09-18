package com.autonomousone.messages

import com.autonomousone.messages.diagnostics.StallBreadcrumbs
import com.autonomousone.messages.diagnostics.StallReportSink
import com.autonomousone.messages.diagnostics.StallSeverity
import com.autonomousone.messages.diagnostics.StallWatchdogEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 15 watchdog behaviour, exercised through the pure engine so no
 * emulator, Looper or real main thread is involved.
 */
class MainThreadStallWatchdogTest {

    private class RecordingSink : StallReportSink {
        val reports = mutableListOf<Pair<StallSeverity, StallBreadcrumbs>>()

        override fun report(severity: StallSeverity, breadcrumbs: StallBreadcrumbs) {
            reports += severity to breadcrumbs
        }

        fun severities(): List<StallSeverity> = reports.map { it.first }
    }

    private fun engine(sink: StallReportSink) = StallWatchdogEngine(
        sink = sink,
        minReportIntervalMs = 30_000L,
    )

    @Test
    fun `no report below the warning threshold`() {
        val sink = RecordingSink()
        val watchdog = engine(sink)
        watchdog.onProbePosted(0L)
        assertEquals(StallSeverity.NONE, watchdog.onLagSample(1_999L))
        assertTrue(sink.reports.isEmpty())
    }

    @Test
    fun `warning fires at about two seconds`() {
        val sink = RecordingSink()
        val watchdog = engine(sink)
        watchdog.onProbePosted(0L)
        assertEquals(StallSeverity.WARNING, watchdog.onLagSample(2_000L))
        assertEquals(listOf(StallSeverity.WARNING), sink.severities())
        assertEquals(2_000L, sink.reports.single().second.stallDurationMs)
        assertEquals(StallSeverity.WARNING, sink.reports.single().second.severity)
    }

    @Test
    fun `critical fires at about five seconds in the same stall`() {
        val sink = RecordingSink()
        val watchdog = engine(sink)
        watchdog.onProbePosted(0L)
        assertEquals(StallSeverity.WARNING, watchdog.onLagSample(2_000L))
        assertEquals(StallSeverity.CRITICAL, watchdog.onLagSample(5_000L))
        assertEquals(listOf(StallSeverity.WARNING, StallSeverity.CRITICAL), sink.severities())
        assertEquals(5_000L, sink.reports[1].second.stallDurationMs)
    }

    @Test
    fun `at most one warning and one critical per stall`() {
        val sink = RecordingSink()
        val watchdog = engine(sink)
        watchdog.onProbePosted(0L)
        assertEquals(StallSeverity.WARNING, watchdog.onLagSample(2_000L))
        assertEquals(StallSeverity.NONE, watchdog.onLagSample(3_000L))
        assertEquals(StallSeverity.CRITICAL, watchdog.onLagSample(5_500L))
        assertEquals(StallSeverity.NONE, watchdog.onLagSample(6_000L))
        assertEquals(listOf(StallSeverity.WARNING, StallSeverity.CRITICAL), sink.severities())
    }

    @Test
    fun `a new stall inside the minimum report interval is suppressed`() {
        val sink = RecordingSink()
        val watchdog = engine(sink)
        watchdog.onProbePosted(0L)
        assertEquals(StallSeverity.WARNING, watchdog.onLagSample(2_000L))
        watchdog.onProbeExecuted(2_100L)

        watchdog.onProbePosted(3_000L)
        assertEquals(StallSeverity.NONE, watchdog.onLagSample(5_000L))
        assertEquals(1, sink.reports.size)
        watchdog.onProbeExecuted(5_100L)

        watchdog.onProbePosted(40_000L)
        assertEquals(StallSeverity.WARNING, watchdog.onLagSample(42_000L))
        assertEquals(2, sink.reports.size)
    }

    @Test
    fun `breadcrumbs are attached to every report`() {
        val sink = RecordingSink()
        val expected = StallBreadcrumbs(
            currentScreen = "Conversation",
            conversationToken = "token-hash",
            visibleMessageCount = 42,
            syncOperation = "tail_delta",
            exactRepairQueueDepth = 3,
            historyBackfillState = "complete",
            roomOperation = "ingest",
            providerOperation = "exact_read",
        )
        val watchdog = StallWatchdogEngine(
            sink = sink,
            minReportIntervalMs = 30_000L,
            breadcrumbs = { severity, duration -> expected.copy(severity = severity, stallDurationMs = duration) },
        )
        watchdog.onProbePosted(0L)
        watchdog.onLagSample(5_000L)
        val attached = sink.reports.single().second
        assertEquals(StallSeverity.CRITICAL, attached.severity)
        assertEquals(5_000L, attached.stallDurationMs)
        assertEquals("tail_delta", attached.syncOperation)
        assertEquals("token-hash", attached.conversationToken)
        assertEquals(42, attached.visibleMessageCount)
        // The raw id is not part of the type at all; only the hashed token is.
        assertFalse(attached.describe().contains("+98"))
    }

    @Test
    fun `watchdog exposes no process death or restart action`() {
        val methods = StallReportSink::class.java.declaredMethods
            .filter { !it.isSynthetic }
            .map { it.name }
        assertTrue(methods.contains("report"))
        for (name in methods) {
            val lower = name.lowercase()
            assertFalse("unexpected killer action: " + name, lower.contains("kill"))
            assertFalse("unexpected restart action: " + name, lower.contains("restart"))
            assertFalse("unexpected exit action: " + name, lower.contains("exit"))
            assertFalse("unexpected destroy action: " + name, lower.contains("destroy"))
        }
        // And the only thing the engine ever does with the sink is report.
        val sink = RecordingSink()
        val watchdog = engine(sink)
        watchdog.onProbePosted(0L)
        watchdog.onLagSample(10_000L)
        assertEquals(listOf(StallSeverity.CRITICAL), sink.severities())
    }
}
