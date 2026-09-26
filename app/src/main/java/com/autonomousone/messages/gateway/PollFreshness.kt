package com.autonomousone.messages.gateway

/**
 * Whether the pull loop is *actually* working, derived from the loop's own timeouts (mission §13).
 *
 * ── WHY AN ACTIVE JOB IS NOT PROOF OF LIVENESS ──
 *
 * The production failure showed an active-looking bridge that had not polled for nine hours. Fixing
 * the lifecycle makes a dead loop report dead — but it does not cover the *other* way this bridge can
 * go quiet: a loop that is `isActive` and blocked. The network-wait branch suspends on
 * `onlineFlow().first { it }`, and a socket read can hang for the whole read timeout. Neither is a
 * dead job, and neither produces a request while it waits.
 *
 * So liveness is judged by **freshness**: how long ago the loop last demonstrably did something
 * compared with how long a healthy cycle is allowed to take. The window is DERIVED from the real
 * constants rather than picked, and it is deliberately generous — a false `PULL_LOOP_STALLED` would
 * trigger a needless restart, so the rule must not fire on a merely slow cycle.
 *
 * ── THE DERIVATION ──
 *
 * The longest legitimate single cycle is a full-timeout pull, then a full-timeout drain, then an ack:
 *
 *     PULL_TIMEOUT_MS (40s) + DRAIN_TIMEOUT_MS (120s) + ACK_TIMEOUT_MS (15s) = 175s
 *
 * A failed cycle adds the error backoff (5s), and the loop is idle only in that gap, so a healthy
 * loop always starts its next cycle within ~180s. [MAX_HEALTHY_IDLE_MS] doubles that for slow devices
 * and a cold radio — **6 minutes**, which is five hundred times smaller than the nine hours observed
 * and still nowhere near tight enough to fire spuriously.
 */
object PollFreshness {

    /** Server hold when the queue is empty. */
    const val LONG_POLL_MS = 25_000L

    /** Long-poll read timeout: the pull is allowed to take this long. */
    const val PULL_TIMEOUT_MS = 40_000L

    /** How long one delivery is awaited before the cycle gives up on it. */
    const val DRAIN_TIMEOUT_MS = 120_000L

    /** Budget for reporting one outcome to GMweb. */
    const val ACK_TIMEOUT_MS = 15_000L

    /** Backoff between failed cycles. */
    const val ERROR_RETRY_MS = 5_000L

    /** The longest cycle that is still healthy, from the constants above. */
    const val LONGEST_HEALTHY_CYCLE_MS = PULL_TIMEOUT_MS + DRAIN_TIMEOUT_MS + ACK_TIMEOUT_MS

    /**
     * How long the bridge may be silent before it is declared stalled.
     *
     * Two full-length cycles, which covers a slow cycle followed by one backoff.
     */
    const val MAX_HEALTHY_IDLE_MS = 2 * (LONGEST_HEALTHY_CYCLE_MS + ERROR_RETRY_MS)

    /**
     * True when an active loop has produced no activity for longer than [MAX_HEALTHY_IDLE_MS].
     *
     * @param now current wall clock.
     * @param isActive whether a loop job is genuinely active (see [PollLoop.isActive]). An inactive
     *   loop is not "stalled" — it is dead, which is a different (and already visible) fact, so this
     *   returns false for it.
     * @param lastActivityAt the last moment the loop did something, or 0 when nothing is known.
     *   `0` means we cannot judge, and refusing to judge is the honest answer: reporting a stall on
     *   no evidence would trigger a restart of a loop that may be fine.
     * @param online whether the device currently has a validated route. Waiting for the network is
     *   GATED BEHAVIOUR — the bridge is deliberately hanging up zero requests — so it must not be
     *   reported as a stall. Its honest label is `POLL_WAITING_NETWORK`.
     */
    fun isStalled(
        now: Long,
        isActive: Boolean,
        lastActivityAt: Long,
        online: Boolean = true
    ): Boolean {
        if (!isActive) return false
        if (!online) return false
        if (lastActivityAt <= 0L) return false
        val silentFor = now - lastActivityAt
        // A clock jump backwards (time set, NTP correction) must not be read as a stall.
        if (silentFor < 0L) return false
        return silentFor > MAX_HEALTHY_IDLE_MS
    }

    /** The machine-readable reason for a diagnostic line. */
    const val STALLED = "PULL_LOOP_STALLED"
}
