package com.autonomousone.messages.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The six facts that used to be called "POLLING" (requirement 13), and the recovery each implies.
 *
 * The production report read `Running: yes · State: POLLING · Last poll started: 9h ago ·
 * Consecutive failures: 0`. Every line was individually true and the combination was a lie: one label
 * covered a live long-poll, a backoff sleep, a deliberate wait for the network, an idle gap between
 * cycles, and a loop that had been dead for nine hours. Nobody could tell a healthy bridge from a
 * corpse, which is why the failure survived nine hours.
 *
 * These tests pin the labels AND the precedence between them, because the order is the part that
 * decides which story a reader is told when two facts are true at once.
 */
class PollStatePolicyTest {

    @Test
    fun `a loop that is not running is DEAD whatever its stale flags say`() {
        // The nine-hour case, and the reason DEAD outranks everything: if nothing is running, the
        // remedy is to start it. Calling it "waiting for network" or "backing off" — both of which
        // describe the bridge working as designed — would point the reader away from the fault.
        assertEquals(
            PollState.POLL_LOOP_DEAD,
            PollStatePolicy.derive(
                isActive = false,
                stalled = false,
                requestInFlight = true,      // stale: a previous cycle never cleared it
                backingOff = true,           // stale
                waitingForNetwork = true     // stale
            )
        )
    }

    @Test
    fun `an active but silent loop is STALLED`() {
        assertEquals(
            PollState.PULL_LOOP_STALLED,
            PollStatePolicy.derive(
                isActive = true,
                stalled = true,
                requestInFlight = false,
                backingOff = false,
                waitingForNetwork = false
            )
        )
    }

    @Test
    fun `a genuinely stalled loop outranks a waiting-for-network flag`() {
        // Which of two true-looking flags wins is a SAFETY question, not a cosmetic one.
        //
        // In the real caller these cannot both be true: `PollFreshness` refuses to judge an offline
        // bridge as stalled, and the waiting flag is only set while offline. So this only arises from
        // a caller passing the wrong `online`. The precedence chooses which way to be wrong:
        //
        //   STALLED wins  → worst case, one wasted restart on a device with no route (the new loop
        //                   immediately waits for the network again). Bounded by the healthy window.
        //   WAITING wins  → worst case, a genuinely silent bridge is left alone and reports healthy,
        //                   which is EXACTLY the nine-hour failure this work exists to end.
        //
        // So the silence-hiding direction is the one that must lose. The flags are diagnostic inputs,
        // not a licence to excuse an absence of activity.
        assertEquals(
            PollState.PULL_LOOP_STALLED,
            PollStatePolicy.derive(
                isActive = true,
                stalled = true,
                requestInFlight = false,
                backingOff = false,
                waitingForNetwork = true
            )
        )
    }

    @Test
    fun `waiting for the network outranks a backoff`() {
        // Both are legitimate silences. If the radio is down, the reason no cycle is running is the
        // network; reporting a backoff would send someone to look at the server.
        assertEquals(
            PollState.POLL_WAITING_NETWORK,
            PollStatePolicy.derive(
                isActive = true,
                stalled = false,
                requestInFlight = false,
                backingOff = true,
                waitingForNetwork = true
            )
        )
    }

    @Test
    fun `a failed cycle waiting out its backoff says so`() {
        assertEquals(
            PollState.POLL_BACKOFF,
            PollStatePolicy.derive(
                isActive = true,
                stalled = false,
                requestInFlight = false,
                backingOff = true,
                waitingForNetwork = false
            )
        )
    }

    @Test
    fun `an open long-poll is the strongest signal and outranks a plain active job`() {
        assertEquals(
            PollState.POLL_REQUEST_IN_FLIGHT,
            PollStatePolicy.derive(
                isActive = true,
                stalled = false,
                requestInFlight = true,
                backingOff = false,
                waitingForNetwork = false
            )
        )
    }

    @Test
    fun `an idle loop between cycles is a plain active job, not a request`() {
        assertEquals(
            PollState.POLLER_JOB_ACTIVE,
            PollStatePolicy.derive(
                isActive = true,
                stalled = false,
                requestInFlight = false,
                backingOff = false,
                waitingForNetwork = false
            )
        )
    }

    // ── The recovery each state implies ─────────────────────────────────────

    @Test
    fun `a dead loop is started and a stalled one is replaced`() {
        assertEquals(PollStatePolicy.Recovery.START, PollStatePolicy.recovery(isActive = false, stalled = false))
        assertEquals(PollStatePolicy.Recovery.RESTART, PollStatePolicy.recovery(isActive = true, stalled = true))
        assertEquals(PollStatePolicy.Recovery.NONE, PollStatePolicy.recovery(isActive = true, stalled = false))
    }

    @Test
    fun `a dead loop is never reported as merely stalled`() {
        // Asking for a restart of something that is not running would leave it not running: the
        // supervisor's `stop; start` sequence happens to work today, but the DECISION must be START so
        // that a caller which only starts still does the right thing.
        assertEquals(PollStatePolicy.Recovery.START, PollStatePolicy.recovery(isActive = false, stalled = true))
    }

    @Test
    fun `every label the diagnostic can print is a distinct string`() {
        // These names go straight into the report as `State: …`, and they are what a support
        // conversation reads. Two labels collapsing into one string would silently restore the
        // ambiguity this set exists to remove.
        val names = PollState.entries.map { it.name }
        assertEquals("no label may be duplicated", names.size, names.toSet().size)
        listOf(
            "POLLER_JOB_ACTIVE", "POLL_REQUEST_IN_FLIGHT", "POLL_BACKOFF",
            "POLL_WAITING_NETWORK", "POLL_LOOP_DEAD", "PULL_LOOP_STALLED"
        ).forEach {
            assertEquals("$it must remain a printable label", true, names.contains(it))
        }
    }

    // ── Requirement 7: the loop's own gate ──────────────────────────────────

    @Test
    fun `the user's intent outranks the radio`() {
        // THE order that matters. A device switched off AND offline must EXIT. Checking the network
        // first would park it in a wait-for-network that cannot end, so disabling the gateway out of
        // coverage would leave a live loop spinning for ever — a silent bridge with a
        // legitimate-looking reason, which is the failure this whole change exists to end.
        assertEquals(
            PollStatePolicy.Iteration.EXIT,
            PollStatePolicy.nextIteration(canTransmit = false, online = false)
        )
        assertEquals(
            PollStatePolicy.Iteration.EXIT,
            PollStatePolicy.nextIteration(canTransmit = false, online = true)
        )
    }

    @Test
    fun `an eligible loop with no route waits rather than exits or dials`() {
        assertEquals(
            PollStatePolicy.Iteration.WAIT_FOR_NETWORK,
            PollStatePolicy.nextIteration(canTransmit = true, online = false)
        )
    }

    @Test
    fun `an eligible loop with a route polls`() {
        assertEquals(
            PollStatePolicy.Iteration.TRANSMIT,
            PollStatePolicy.nextIteration(canTransmit = true, online = true)
        )
    }

    @Test
    fun `eligibility returning is a fresh loop, not a resumed one`() {
        // The lifecycle half of requirement 7, stated as the pair the supervisor relies on: when the
        // gate closes the loop ENDS (rather than idling), and a later start must therefore create a
        // new one. If the loop merely parked, "the gateway was off for an hour" and "the loop has been
        // waiting for an hour" would be the same observable state.
        assertEquals(
            "a closed gate must end the loop, not suspend it",
            PollStatePolicy.Iteration.EXIT,
            PollStatePolicy.nextIteration(canTransmit = false, online = true)
        )
    }
}
