package com.autonomousone.messages.gateway

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Owns the lifetime of one long-poll loop (mission §12 / §13).
 *
 * ── THE DEFECT THIS EXISTS TO MAKE IMPOSSIBLE ──
 *
 * The poller used to hold `@Volatile private var running` as the gate for `start()`:
 *
 * ```kotlin
 * fun start() { if (running) return; running = true; pollJob = scope.launch { … } }
 * val isRunning get() = running && pollJob?.isActive == true
 * ```
 *
 * Nothing cleared `running` when the loop exited on its own — and the loop has several such exits
 * (the access-policy check `break`s out of the `while`, and anything thrown between iterations
 * escapes it). The result was a **permanent zombie**, reproduced on a real device: the pull bridge
 * reported `Running: yes · State: POLLING` with its last poll nine hours old and
 * `Consecutive failures: 0`, because no request was being made at all.
 *
 * The self-healing path made it worse rather than better. `ConnectionSupervisor.retryNow()` does the
 * right thing —
 *
 * ```kotlin
 * if (!components.isPollerRunning()) components.startPoller()
 * components.wakePoller()
 * ```
 *
 * — but `isPollerRunning()` was false (the job really had finished) while `start()` still returned
 * early on the stale boolean. So the repair did nothing, the wake-up was delivered to a channel with
 * no consumer, and the bridge stayed dead until the app was stopped and restarted.
 *
 * ── THE RULES ENCODED HERE ──
 *
 * 1. **The job is the truth.** [isActive] asks the current job, never a remembered flag, so
 *    "started once" and "running now" cannot be confused.
 * 2. **`start()` is idempotent against an ACTIVE loop**, not against history: a dead loop is always
 *    replaceable, and a live one never spawns a second.
 * 3. **Every exit cleans up.** The body runs inside `try/finally`, so a return, a policy exit, a
 *    throw and a cancellation all reach [finish] — `stop()` is no longer the only path that tidies.
 * 4. **A finishing loop never clobbers its replacement.** Each run carries a generation; [finish]
 *    acts only if that generation is still current, so an old job completing late cannot clear the
 *    state (or the health flag) of the loop that replaced it.
 *
 * Android-free on purpose: the lifecycle is the part that failed, and it must be testable in JVM.
 * The caller supplies the body and an [onFinished] callback for the Android-side consequences
 * (health flags, `StateFlow`), so this class has no idea a `Context` exists.
 */
internal class PollLoop(private val scope: CoroutineScope) {

    /** Why a loop stopped being active. Reported, never inferred. */
    enum class End {
        /** Never started, or the loop is still running. */
        NOT_STARTED,

        /** The body returned on its own — the loop decided to stop (policy or network exit). */
        RETURNED,

        /** The body threw. */
        FAILED,

        /** Cancelled, either by [stop] or by the owning scope going away. */
        CANCELLED
    }

    private val lock = Any()
    private var generation = 0L
    private var job: Job? = null

    /**
     * The current run's completion callback, held here so it can be invoked EXACTLY ONCE per run by
     * whichever side ends it.
     *
     * Without this, [stop] had to choose between two wrong options: let the cancelled body's `finally`
     * report the end (and risk a LATE finisher clearing a newer loop's health flags), or suppress it
     * and leave the caller to remember to clean up after `stop` — the "rule restated at a call site"
     * shape this project keeps removing. Holding the callback lets `stop` report the end itself and
     * keeps the late finisher silent.
     */
    private var onFinishedForRun: ((End) -> Unit)? = null

    /** Generation of the loop that is current now; 0 before the first start. */
    val currentGeneration: Long get() = synchronized(lock) { generation }

    /**
     * True while a loop is genuinely running.
     *
     * The ONLY liveness question a caller may ask. It consults the job, so a loop that exited —
     * for any reason — reports false immediately.
     */
    val isActive: Boolean get() = synchronized(lock) { job?.isActive == true }

    // ── Lifecycle telemetry (requirement 12). Counts and timings only: no request ids, no
    // recipients, nothing that a diagnostic export could leak. ────────────────────────────────
    @Volatile var startedAt: Long = 0L
        private set
    @Volatile var finishedAt: Long = 0L
        private set
    @Volatile var end: End = End.NOT_STARTED
        private set
    @Volatile var lastCycleStartedAt: Long = 0L
        private set
    @Volatile var lastCycleFinishedAt: Long = 0L
        private set

    /**
     * Start the loop unless one is genuinely running.
     *
     * @param onFinished invoked exactly once per run — on a self-exit, a throw, a cancellation, or an
     *   explicit [stop] — and only for the run that is still current. This is where the caller clears
     *   its health flags and state, so no call site has to remember to.
     * @return true when a new loop was started, false when an active one already existed.
     */
    fun start(onFinished: (End) -> Unit = {}, body: suspend (generation: Long) -> Unit): Boolean {
        val mine: Long
        synchronized(lock) {
            if (job?.isActive == true) return false
            generation += 1
            mine = generation
            startedAt = System.currentTimeMillis()
            finishedAt = 0L
            end = End.NOT_STARTED
            lastCycleStartedAt = 0L
            lastCycleFinishedAt = 0L
            onFinishedForRun = onFinished
            job = scope.launch {
                var outcome = End.RETURNED
                try {
                    body(mine)
                } catch (cancelled: CancellationException) {
                    // Cooperative cancellation is a normal end, not a failure: the loop must still
                    // tidy up and report why, and the exception must keep propagating.
                    outcome = End.CANCELLED
                    throw cancelled
                } catch (failed: Throwable) {
                    outcome = End.FAILED
                } finally {
                    finish(mine, outcome)
                }
            }
        }
        return true
    }

    /**
     * Cancel the current loop and immediately make it replaceable.
     *
     * `job = null` under the same lock as the cancellation is deliberate: a cancelled job can stay
     * `isActive` for a moment, and if [start] consulted it in that window it would refuse to restart
     * — the very zombie this class exists to prevent, re-entered through `stop()`.
     *
     * The end is reported here rather than by the cancelled body, so the callback still fires exactly
     * once per run while the late `finally` is suppressed by the generation check.
     */
    fun stop() {
        val victim: Job?
        val callback: ((End) -> Unit)?
        synchronized(lock) {
            generation += 1
            victim = job
            callback = onFinishedForRun
            onFinishedForRun = null
            job = null
            end = End.CANCELLED
            finishedAt = System.currentTimeMillis()
        }
        victim?.cancel()
        if (victim != null) callback?.invoke(End.CANCELLED)
    }

    /** Mark the start of one pull cycle, for the freshness watchdog. */
    fun onCycleStarted() {
        lastCycleStartedAt = System.currentTimeMillis()
    }

    /** Mark the end of one pull cycle, for the freshness watchdog. */
    fun onCycleFinished() {
        lastCycleFinishedAt = System.currentTimeMillis()
    }

    /**
     * The most recent moment the loop demonstrably did something.
     *
     * Falls back to [startedAt] so a loop that started and then stalled is still judged from its
     * start rather than reporting "no activity" for ever.
     */
    fun lastActivityAt(): Long = maxOf(lastCycleFinishedAt, lastCycleStartedAt, startedAt)

    private fun finish(mine: Long, outcome: End) {
        val callback = synchronized(lock) {
            // A LATE finisher must not touch a newer loop's state. Without this, an old job
            // completing after its replacement started would clear the new loop's health flag —
            // the same class of lie as the original stale boolean, one layer down.
            if (mine != generation) return
            job = null
            finishedAt = System.currentTimeMillis()
            end = outcome
            onFinishedForRun.also { onFinishedForRun = null }
        }
        callback?.invoke(outcome)
    }
}
