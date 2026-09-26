package com.autonomousone.messages.gateway.health

import com.autonomousone.messages.gateway.PollState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Health truth through the REAL chain: recorder → snapshot → report (requirement 4).
 *
 * The report tests construct `PullBridgeHealth` directly, which verifies rendering but not the
 * wiring: a recorder that set `running = true` beside a dead state would still pass every one of
 * them. This closes that gap by driving the actual recorder and rendering its actual snapshot, so the
 * claim "`Running: yes` alongside hours-old silence is impossible" is tested through the code that
 * produces it rather than through synthetic data.
 *
 * The one link still not covered here is `OutboxPoller` itself: it cannot be constructed off-device
 * (`NetworkMonitor` has a private constructor and Android's `Context` cannot be instantiated in JVM),
 * so `publishPollState`'s call into the recorder is verified by compilation and by
 * `PollLoopTest`'s lifecycle rules — not end to end. Stated rather than glossed over.
 */
class PollLifecycleHealthTest {

    private val now = 2_000_000_000L
    private val nineHoursAgo = now - 9 * 60 * 60 * 1000L

    @Before
    fun setUp() {
        GatewayHealthRecorder.resetForTest()
        GatewayHealthRecorder.setDesired(true)
        GatewayHealthRecorder.onNetwork(validated = true, transport = "Wi-Fi", at = now)
    }

    private fun report(): String = GatewayDiagnosticReport.render(
        snapshot = GatewayHealthRecorder.snapshot(now),
        probe = null,
        appVersion = "3.4.11 (118)",
        deliveryMode = "LEGACY_PULL",
        supervisorState = "CONNECTED",
        gatewayDesired = true,
        now = now
    )

    @Test
    fun `a recorded dead loop reports Running no beside a dead state`() {
        // The exact production combination, produced the way production produces it: the loop ended
        // nine hours ago and nothing has polled since.
        GatewayHealthRecorder.onPullStart(nineHoursAgo)
        GatewayHealthRecorder.onPullEmpty(nineHoursAgo, 200)
        GatewayHealthRecorder.setPollerLifecycle(
            running = false,
            state = PollState.POLL_LOOP_DEAD.name,
            generation = 3L,
            startedAt = nineHoursAgo,
            completedAt = nineHoursAgo + 1_000L,
            completionReason = "RETURNED",
            lastCycleStartedAt = nineHoursAgo,
            lastCycleCompletedAt = nineHoursAgo + 1L
        )

        val text = report()

        assertTrue("the running flag must follow the loop: $text", text.contains("Running: no"))
        assertTrue(text, text.contains("State: ${PollState.POLL_LOOP_DEAD.name}"))
        assertTrue("and it must say why the loop ended", text.contains("RETURNED"))
        // The old lie, asserted absent through the real chain rather than a synthetic snapshot.
        assertFalse(
            "a dead loop must never be described as a live bridge: $text",
            text.contains("Running: yes")
        )
    }

    @Test
    fun `a recorded live loop reports Running yes with its generation`() {
        GatewayHealthRecorder.onPullStart(now - 2_000L)
        GatewayHealthRecorder.onPullEmpty(now - 2_000L, 200)
        GatewayHealthRecorder.setPollerLifecycle(
            running = true,
            state = PollState.POLL_REQUEST_IN_FLIGHT.name,
            generation = 12L,
            startedAt = now - 3_000L,
            completedAt = 0L,
            completionReason = null,
            lastCycleStartedAt = now - 2_000L,
            lastCycleCompletedAt = 0L
        )

        val text = report()

        assertTrue(text, text.contains("Running: yes"))
        assertTrue(text, text.contains("State: ${PollState.POLL_REQUEST_IN_FLIGHT.name}"))
        assertTrue("the generation must be visible", text.contains("generation=12"))
        assertTrue(
            "a running loop must not claim to have ended",
            text.contains("still running")
        )
        assertFalse(
            "and must not report an end time it has not reached",
            text.contains("last ended 0s ago")
        )
    }

    @Test
    fun `an unmeasured lifecycle is never rendered as a healthy one`() {
        // Nothing has been recorded: the report must say so rather than defaulting to a running loop.
        val text = report()

        assertTrue(text, text.contains("generation=never started"))
        assertTrue(text, text.contains("no loop has run"))
        assertFalse(
            "the default must never imply a working bridge: $text",
            text.contains("Running: yes")
        )
    }

    @Test
    fun `the recorder never reports running true for a dead lifecycle`() {
        // The invariant stated directly on the model, independent of rendering: these two fields come
        // from one call, so they cannot contradict each other.
        GatewayHealthRecorder.setPollerLifecycle(
            running = false,
            state = PollState.PULL_LOOP_STALLED.name,
            generation = 0L,
            startedAt = 0L,
            completedAt = 0L,
            completionReason = null,
            lastCycleStartedAt = 0L,
            lastCycleCompletedAt = 0L
        )

        val bridge = GatewayHealthRecorder.snapshot(now).pullBridge
        assertEquals(false, bridge.running)
        assertEquals(PollState.PULL_LOOP_STALLED.name, bridge.state)
        // Generation 0 renders as never-started, so it is stored as an observed 0 — the report's
        // "never started" wording is what keeps it honest, not a null here.
        assertEquals(0L, bridge.pollLoopGeneration)
    }
}
