package com.autonomousone.messages.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stall rule — because an ACTIVE job is not proof of a live bridge (mission §13).
 *
 * The production failure showed nine hours of silence behind `Running: yes`. Fixing the lifecycle
 * makes a *dead* loop report dead, but it does not cover the other way this bridge goes quiet: a job
 * that is alive and blocked. The network-wait branch suspends until the network returns, and a socket
 * read can hang for its whole timeout; neither issues a request while it waits, and neither makes the
 * job inactive.
 *
 * So liveness is judged by freshness against a window DERIVED from the loop's own timeouts. The
 * window is the whole point: too tight and a slow cycle triggers a needless restart, too loose and the
 * nine-hour silence becomes acceptable again.
 */
class PollFreshnessTest {

    @Test
    fun `the window is derived from the real cycle timeouts`() {
        // Not a magic number: the longest healthy cycle is a full-timeout pull, a full-timeout drain
        // and an ack, and the window allows two of those plus a backoff.
        assertEquals(
            PollFreshness.PULL_TIMEOUT_MS + PollFreshness.DRAIN_TIMEOUT_MS + PollFreshness.ACK_TIMEOUT_MS,
            PollFreshness.LONGEST_HEALTHY_CYCLE_MS
        )
        assertEquals(
            2 * (PollFreshness.LONGEST_HEALTHY_CYCLE_MS + PollFreshness.ERROR_RETRY_MS),
            PollFreshness.MAX_HEALTHY_IDLE_MS
        )
        // The point of the exercise: minutes, not hours.
        assertTrue(
            "the window must be minutes, not the nine hours observed: ${PollFreshness.MAX_HEALTHY_IDLE_MS}",
            PollFreshness.MAX_HEALTHY_IDLE_MS <= 10 * 60_000L
        )
        assertTrue(
            "…and must comfortably exceed the longest possible healthy cycle",
            PollFreshness.MAX_HEALTHY_IDLE_MS > PollFreshness.LONGEST_HEALTHY_CYCLE_MS * 2
        )
    }

    @Test
    fun `a long-but-legal cycle is never reported as stalled`() {
        // A full pull timeout plus a full drain timeout is a slow cycle, not a stall. Firing here
        // would restart a loop that is working, which is worse than waiting.
        val cycleEnd = 1_000_000L
        val justFinishedALongCycle = cycleEnd
        assertFalse(
            PollFreshness.isStalled(
                now = cycleEnd + PollFreshness.LONGEST_HEALTHY_CYCLE_MS,
                isActive = true,
                lastActivityAt = justFinishedALongCycle
            )
        )
    }

    @Test
    fun `the nine-hour silence would have been caught`() {
        val lastActivity = 1_000_000L
        val nineHoursLater = lastActivity + 9 * 60 * 60 * 1000L

        assertTrue(
            "nine hours of silence behind an active job must be a stall",
            PollFreshness.isStalled(now = nineHoursLater, isActive = true, lastActivityAt = lastActivity)
        )
    }

    @Test
    fun `a dead loop is not a stalled loop`() {
        // Different facts, different remedies: a dead loop needs starting, a stalled one needs
        // restarting. Reporting "stalled" for a loop that is not active would hide the lifecycle bug
        // behind the watchdog.
        assertFalse(
            PollFreshness.isStalled(now = 10_000_000L, isActive = false, lastActivityAt = 1_000L)
        )
    }

    @Test
    fun `waiting for the network is not a stall`() {
        // The bridge deliberately hangs up zero requests while offline, so silence is the intended
        // behaviour. Its honest label is POLL_WAITING_NETWORK, and restarting the loop would not
        // produce a single extra request.
        assertFalse(
            PollFreshness.isStalled(
                now = 1_000_000L + 9 * 60 * 60 * 1000L,
                isActive = true,
                lastActivityAt = 1_000_000L,
                online = false
            )
        )
    }

    @Test
    fun `no evidence is not a stall`() {
        // Nothing has ever run: reporting a stall would restart a loop that may be about to start.
        assertFalse(PollFreshness.isStalled(now = 5_000_000L, isActive = true, lastActivityAt = 0L))
    }

    @Test
    fun `a clock that jumps backwards is not a stall`() {
        // A time-set or NTP correction must not be read as hours of silence.
        assertFalse(
            PollFreshness.isStalled(now = 1_000L, isActive = true, lastActivityAt = 9_000_000L)
        )
    }

    @Test
    fun `the threshold itself is the boundary`() {
        val last = 1_000_000L
        assertFalse(
            "exactly at the window is not yet a stall",
            PollFreshness.isStalled(
                now = last + PollFreshness.MAX_HEALTHY_IDLE_MS,
                isActive = true,
                lastActivityAt = last
            )
        )
        assertTrue(
            "one millisecond past it is",
            PollFreshness.isStalled(
                now = last + PollFreshness.MAX_HEALTHY_IDLE_MS + 1,
                isActive = true,
                lastActivityAt = last
            )
        )
    }
}
