package com.autonomousone.messages.gateway

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One 60-second wake-up for the whole gateway stack.
 *
 * Five components each owned their own `delay(60_000)` loop — `HeartbeatManager`, `DeviceTelemetry`,
 * `EventUploader`, `ConnectionSupervisor` and `TrustStatementPublisher` — started at five different
 * moments, so their phases drifted apart and the radio could be woken up to five times a minute for
 * work that fits in one wake-up. Battery cost is dominated by how often the radio and CPU leave idle,
 * not by the work done once awake, so the fix is to share one tick.
 *
 * This is a SCHEDULER ONLY. Each subscriber keeps its own eligibility checks, its own backoff ladder
 * and its own health reporting: a subscriber that throws, or that is not eligible this minute, must
 * not affect the others, and the tick must survive it.
 *
 * Deliberately Android-free apart from `Log` (a no-op in JVM unit tests) so the fan-out, isolation,
 * kick and stop rules are plain unit tests — the same precedent as [PollLoop].
 */
internal class GatewayTicker(
    private val scope: CoroutineScope,
    private val intervalMs: Long = TICK_INTERVAL_MS,
    /** Injectable so tests can run on virtual time; production uses wall-clock millis. */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun interface Subscriber {
        suspend fun onTick(nowMs: Long)
    }

    private class Registration(val name: String, val subscriber: Subscriber)

    private val registrations = CopyOnWriteArrayList<Registration>()

    /** Conflated: five nudges in a burst are one extra tick, never five. */
    private val kicks = Channel<Unit>(Channel.CONFLATED)

    /**
     * Per-subscriber earlier deadlines, in the [clock]'s milliseconds.
     *
     * Only the EARLIEST outstanding request per subscriber is kept, so repeated requests coalesce and
     * a subscriber cannot queue a backlog of ticks.
     */
    private val earlierTicks = ConcurrentHashMap<String, Long>()

    /** Conflated wake for the earlier-tick loop. */
    private val earlierWake = Channel<Unit>(Channel.CONFLATED)

    private var earlierJob: Job? = null

    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    /** Registers a subscriber. Order is preserved and is the execution order. */
    fun subscribe(name: String, subscriber: Subscriber): GatewayTicker {
        registrations += Registration(name, subscriber)
        return this
    }

    fun start() {
        if (job?.isActive == true) return
        // A kick that arrived before the loop existed means "tick now"; the first scheduled tick is
        // not due for [intervalMs], so honour it rather than leaving it latched in the channel.
        val startImmediately = kicks.tryReceive().isSuccess
        job = scope.launch {
            if (!startImmediately) withTimeoutOrNull(intervalMs) { kicks.receive() }
            while (isActive) {
                dispatch()
                withTimeoutOrNull(intervalMs) { kicks.receive() }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        earlierJob?.cancel()
        earlierJob = null
        earlierTicks.clear()
        while (earlierWake.tryReceive().isSuccess) {
            // conflated channel: drop a pending wake that no loop will consume
        }
    }

    /** Fire now, out of band (manual reconnect). Conflated, and does not cancel the scheduled tick. */
    fun kick() {
        kicks.trySend(Unit)
    }

    // ── Per-subscriber earlier ticks (STAGE-2 §2.5) ─────────────────────────

    /**
     * Ask for an out-of-band tick for ONE subscriber, earlier than the shared 60 s cadence.
     *
     * This is what lets a laddered subscriber (heartbeat: 1 s → 5 min) keep its own fast retry while
     * still sharing the common wake-up for steady-state work. Rules, from §2.5:
     *
     *  - **per subscriber**: only [subscriber] is dispatched, never the others;
     *  - **coalescing**: only the earliest outstanding deadline is kept, so repeated requests do not
     *    stack and a subscriber cannot queue a backlog;
     *  - **floor**: [delayMs] is clamped to [MIN_EARLIER_DELAY_MS], so a buggy ladder cannot turn the
     *    ticker into a hot loop;
     *  - **additive**: the shared tick is untouched — this never resets or skips it.
     */
    fun requestEarlierTick(subscriber: String, delayMs: Long) {
        val due = clock() + maxOf(delayMs, MIN_EARLIER_DELAY_MS)
        earlierTicks.merge(subscriber, due) { existing, requested -> minOf(existing, requested) }
        earlierWake.trySend(Unit)
        ensureEarlierLoop()
    }

    private fun ensureEarlierLoop() {
        if (earlierJob?.isActive == true) return
        earlierJob = scope.launch {
            while (isActive) {
                val now = clock()
                val due = earlierTicks.values.minOrNull()
                if (due == null) {
                    earlierWake.receive()
                    continue
                }
                if (due > now) {
                    withTimeoutOrNull((due - now).coerceAtLeast(1L)) { earlierWake.receive() }
                    continue
                }
                // Dispatch every subscriber whose deadline has passed. remove(key, value) is
                // compare-and-remove: a request that arrived while we were waking up is kept.
                for (entry in earlierTicks.entries.filter { it.value <= now }) {
                    if (earlierTicks.remove(entry.key, entry.value)) {
                        dispatchOne(entry.key, now)
                    }
                }
            }
        }
    }

    private suspend fun dispatchOne(name: String, nowMs: Long) {
        val registration = registrations.firstOrNull { it.name == name } ?: return
        try {
            registration.subscriber.onTick(nowMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "gateway ticker subscriber '" + name + "' failed on an earlier tick", e)
        }
    }

    private suspend fun dispatch() {
        val startedAt = System.currentTimeMillis()
        for (registration in registrations) {
            try {
                registration.subscriber.onTick(startedAt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // One throwing subscriber must not kill the tick for the rest.
                Log.w(TAG, "gateway ticker subscriber '" + registration.name + "' failed", e)
            }
        }
        val tookMs = System.currentTimeMillis() - startedAt
        if (tookMs > intervalMs) {
            // Reported rather than fixed silently: going parallel would re-create several radio
            // events in one burst, which is the problem this class exists to avoid.
            Log.w(
                TAG,
                "gateway tick took " + tookMs + "ms (interval " + intervalMs + "ms) — subscribers " +
                    "run sequentially and one of them is starving the rest"
            )
        }
    }

    companion object {
        private const val TAG = "GATEWAY_TICKER"

        /** The single 60 s wake-up the stack shares. */
        const val TICK_INTERVAL_MS = 60_000L

        /**
         * Lower bound for [requestEarlierTick].
         *
         * A subscriber whose backoff ladder is buggy (or zero) must not be able to turn the shared
         * ticker into a hot loop. One second is far below every real ladder step in the stack.
         */
        const val MIN_EARLIER_DELAY_MS = 1_000L
    }
}
