package com.autonomousone.messages.gateway

/**
 * What the pull bridge is ACTUALLY doing, one label per distinct fact (mission §13).
 *
 * ── WHY ONE WORD FOR SIX FACTS WAS THE BUG'S DISGUISE ──
 *
 * The production report said:
 *
 *     Running: yes
 *     State: POLLING
 *     Last poll started: 9h ago
 *     Consecutive failures: 0
 *
 * Every one of those lines was technically true and the combination was a lie: "POLLING" covered a
 * live request, a backoff sleep, a deliberate wait for the network, an idle gap, and a loop that had
 * been dead for nine hours. A reader could not tell a healthy bridge from a corpse, which is exactly
 * why the failure survived nine hours unnoticed.
 *
 * So the state is DERIVED from observed facts rather than asserted, and each label answers a
 * different question:
 *
 *   POLL_LOOP_DEAD         nothing is running — the supervisor must start it.
 *   PULL_LOOP_STALLED      something is running and has produced nothing for too long — restart it.
 *   POLL_WAITING_NETWORK   deliberately hanging up zero requests — healthy, do nothing.
 *   POLL_BACKOFF           a failed cycle is being waited out — healthy, do nothing.
 *   POLL_REQUEST_IN_FLIGHT the long-poll is open right now — the strongest "it works" signal.
 *   POLLER_JOB_ACTIVE      the loop is alive between cycles — healthy, do nothing.
 */
enum class PollState {
    POLLER_JOB_ACTIVE,
    POLL_REQUEST_IN_FLIGHT,
    POLL_BACKOFF,
    POLL_WAITING_NETWORK,
    POLL_LOOP_DEAD,
    PULL_LOOP_STALLED
}

/**
 * The derivation, and the recovery decision, as pure functions.
 *
 * Pure because these are the rules that were got wrong and because neither needs Android: the caller
 * supplies what it observed, so the precedence between facts can be asserted directly instead of being
 * inferred from a log.
 */
object PollStatePolicy {

    /**
     * Precedence matters as much as the labels.
     *
     * The most actionable fact wins, and the order is deliberate:
     *
     *  1. **DEAD first.** A loop that is not running is not "waiting for network" or "backing off",
     *     however those flags were left; the fix is to start it, and calling it anything else hides
     *     that. This is the case the nine-hour report was in.
     *  2. **STALLED next.** Running but silent past the healthy window needs a restart.
     *  3. **WAITING_NETWORK before BACKOFF.** Both are legitimate silences; if the radio is down, the
     *     reason a cycle is not running is the network, and reporting a backoff would point the reader
     *     at the server.
     *  4. **REQUEST_IN_FLIGHT before JOB_ACTIVE** so the strongest evidence of a working bridge is
     *     what a reader sees while it is working.
     */
    fun derive(
        isActive: Boolean,
        stalled: Boolean,
        requestInFlight: Boolean,
        backingOff: Boolean,
        waitingForNetwork: Boolean
    ): PollState = when {
        !isActive -> PollState.POLL_LOOP_DEAD
        stalled -> PollState.PULL_LOOP_STALLED
        waitingForNetwork -> PollState.POLL_WAITING_NETWORK
        backingOff -> PollState.POLL_BACKOFF
        requestInFlight -> PollState.POLL_REQUEST_IN_FLIGHT
        else -> PollState.POLLER_JOB_ACTIVE
    }

    /** What the supervisor should do about the state it just derived. */
    enum class Recovery {
        /** Nothing is wrong. */
        NONE,

        /** No loop is running: start one. */
        START,

        /** A loop is running but silent: replace it. */
        RESTART
    }

    /** What one iteration of the poll loop should do next. */
    enum class Iteration {
        /** Pull (and deliver and ack). */
        TRANSMIT,

        /** The radio is down: wait for it, issuing no requests. */
        WAIT_FOR_NETWORK,

        /** Stop the loop entirely. */
        EXIT
    }

    /**
     * The loop's own gate, as a decision instead of two inline branches (requirement 7).
     *
     * THE ORDER IS LOAD-BEARING, and this is why it is a function rather than two `if`s in a loop
     * body: **the user's intent outranks the radio**. A device that is switched off AND offline must
     * EXIT; checking the network first would park it in a wait-for-network that cannot end, so
     * disabling the gateway while out of coverage would leave a live loop spinning for ever — the
     * silent-bridge shape again, this time with a legitimate-looking reason.
     *
     * The consequence worth stating: when eligibility returns (the user re-enables the gateway, or
     * consent is restored) the loop that EXITed is gone, and the supervisor starts a FRESH one. That
     * is the correct behaviour and it depends on `start()` accepting a dead loop, which is the fix
     * this whole change is built on.
     */
    fun nextIteration(canTransmit: Boolean, online: Boolean): Iteration = when {
        !canTransmit -> Iteration.EXIT
        !online -> Iteration.WAIT_FOR_NETWORK
        else -> Iteration.TRANSMIT
    }

    /**
     * The bounded recovery for a bridge that is not working.
     *
     * Bounded by construction, which is why it is a decision and not a timer: a restart resets the
     * loop's activity clock, so a healthy replacement cannot re-trigger a restart until a full healthy
     * window has passed again. A loop that stalls immediately after every restart is therefore
     * restarted at most once per window — it cannot become a hot restart loop, and each attempt is
     * recorded as an end reason for the next diagnostic.
     *
     * `waitingForNetwork` and `backingOff` deliberately produce NONE: both are the bridge working as
     * designed, and restarting would not produce a single extra request.
     */
    fun recovery(isActive: Boolean, stalled: Boolean): Recovery = when {
        !isActive -> Recovery.START
        stalled -> Recovery.RESTART
        else -> Recovery.NONE
    }
}
