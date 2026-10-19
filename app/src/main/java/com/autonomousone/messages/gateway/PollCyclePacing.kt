package com.autonomousone.messages.gateway

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pacing for [OutboxPoller]'s cycles, extracted so the rule "the wake lock is released for a real
 * idle gap" is a pure decision a JVM test can pin (docs/battery-plan/STAGE-1-quick-wins.md §1.2).
 *
 * Before this, `runLoop` had no delay on the success path: it re-entered the loop immediately after
 * releasing the `PARTIAL_WAKE_LOCK` and re-acquired it at once, so the lock was effectively held for
 * the whole life of the process and the CPU never reached deep sleep. The KDoc claimed a "~5 s" gap
 * that the code did not implement.
 */
internal object PollCyclePacing {

    /**
     * Idle gap between successful cycles — the window in which the CPU may reach deep sleep.
     *
     * The wake lock is released for the whole of it. Rollback is `IDLE_GAP_MS = 0`.
     */
    const val IDLE_GAP_MS = 5_000L

    /**
     * 0 when the cycle actually carried a task (a busy queue must drain without a pause between
     * messages), otherwise [IDLE_GAP_MS].
     */
    fun gapMsAfter(carriedTask: Boolean): Long = if (carriedTask) 0L else IDLE_GAP_MS
}

/**
 * Wait out [gapMs], but return immediately when [wake] fires.
 *
 * [OutboxPoller.retryNow] feeds that channel, so a manual reconnect still polls at once instead of
 * sitting out the gap — the gap is an energy decision, never a latency one.
 */
internal suspend fun awaitCycleGap(wake: Channel<Unit>, gapMs: Long) {
    if (gapMs <= 0L) return
    withTimeoutOrNull(gapMs) { wake.receive() }
}
