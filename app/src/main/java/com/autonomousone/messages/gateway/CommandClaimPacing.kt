package com.autonomousone.messages.gateway

/**
 * Idle pacing for the command channel, extracted from [SecureCommandPoller] so the rule that keeps a
 * long-poll from becoming a HOT loop is a pure decision a JVM test can pin.
 *
 * Why this exists (docs/battery-plan/STAGE-1-quick-wins.md §1.1): claiming every 2 s is 43 200
 * authenticated HTTPS requests a day, each waking the radio. The preferred shape is the long-poll
 * [OutboxPoller] already uses — the server holds the request until work appears, so pickup gets
 * FASTER while the request count falls by ~92 %.
 *
 * The hazard is the opposite direction: an older GMweb ignores `waitMs` and answers "no commands"
 * immediately. A client that then re-claims without waiting turns a 2-second poll into an unbounded
 * hot loop — far worse than the regression being fixed. [afterEmptyClaim] is the whole guard: a
 * response that came back in less than half the requested hold proves the server did NOT wait, and
 * the documented 2-second fallback (the ADR-003 SLO path) applies.
 */
internal object CommandClaimPacing {

    /** Server hold for an empty claim. Mirrors [OutboxPoller.LONG_POLL_MS]. */
    const val LONG_POLL_MS = 25_000L

    /** Long-poll hold + margin, mirroring [OutboxPoller.PULL_TIMEOUT_MS]. */
    const val TIMEOUT_MS = 40_000L

    /**
     * Cadence when the server does NOT support `waitMs` on the claim route.
     *
     * This is the ADR-003 `command pickup P95 < 3s` fallback, not a battery knob: raising it without
     * an ADR amendment trades an accepted SLO for battery, which Stage 1 forbids.
     */
    const val QUIET_MS = 2_000L

    /** A hold of at least half the requested window proves the server waited. */
    const val HOLD_THRESHOLD_MS = LONG_POLL_MS / 2

    /**
     * What the loop should do after a claim that returned no commands.
     *
     * @param waitMs 0 when the server already absorbed the idle wait, otherwise [QUIET_MS].
     * @param longPollSupported `true`/`false` once a claim has proven either behaviour; `null` only
     *   when nothing has been observed yet (no empty claim has completed).
     */
    data class Decision(val waitMs: Long, val longPollSupported: Boolean?)

    fun afterEmptyClaim(heldMs: Long): Decision =
        if (heldMs >= HOLD_THRESHOLD_MS) {
            // The server honoured waitMs: the idle wait already happened inside the request.
            Decision(waitMs = 0L, longPollSupported = true)
        } else {
            // Instant answer: waitMs unsupported (or the queue was just empty on a fast server).
            // Sleep the documented fallback so the loop can never spin.
            Decision(waitMs = QUIET_MS, longPollSupported = false)
        }
}
