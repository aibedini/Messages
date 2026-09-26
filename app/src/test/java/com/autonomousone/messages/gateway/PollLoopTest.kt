package com.autonomousone.messages.gateway

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The poll-loop lifecycle — the production defect of v3.4.10 (117), reproduced and pinned.
 *
 * On a real device the pull bridge reported `Running: yes · State: POLLING` with its **last poll nine
 * hours old** and `Consecutive failures: 0`. Nothing was wrong with the server, the credential or the
 * network: the Android→GMweb uploader was succeeding every few seconds. The pull loop had simply
 * stopped, and its observable state went on claiming it was running.
 *
 * The pre-fix implementation was:
 *
 * ```kotlin
 * fun start() { if (running) return; running = true; pollJob = scope.launch { while (isActive) { … } } }
 * val isRunning get() = running && pollJob?.isActive == true
 * ```
 *
 * with no `finally` anywhere. A loop that exited on its own — the access-policy `break`, or anything
 * thrown between iterations — left `running = true` for ever, so `isRunning` was false while
 * `start()` refused to do anything. `ConnectionSupervisor.retryNow()` noticed the dead loop and
 * called `startPoller()`, which returned early; the wake-up then went into a channel with no
 * consumer. The bridge stayed dead until the app was force-stopped.
 *
 * These tests use wall-clock waits rather than virtual time on purpose: the rule under test is about
 * a JOB reaching a terminal state and the state it leaves behind, and the same code must behave that
 * way in production where nothing controls the clock.
 */
class PollLoopTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** Bounded wait for the loop to leave the active state; never an unbounded spin. */
    private fun awaitInactive(loop: PollLoop, withinMs: Long = 5_000L): Boolean = runBlocking {
        withTimeoutOrNull(withinMs) {
            while (loop.isActive) delay(10)
            true
        } ?: false
    }

    // ── Requirement 6: the exact production defect ───────────────────────────

    @Test
    fun `a loop that exits on its own stops being active and can be replaced`() {
        // THE regression. The body returns immediately, which is what the policy `break` did; the
        // pre-fix code left `running = true` here and every later start() was a no-op.
        val loop = PollLoop(scope)
        val finished = mutableListOf<PollLoop.End>()

        assertTrue("the first start must run", loop.start(onFinished = { finished += it }) { })
        assertTrue("the loop must be reported inactive after its body returns", awaitInactive(loop))
        assertTrue(
            "the lifecycle must be told why it ended: $finished",
            finished.contains(PollLoop.End.RETURNED)
        )

        // The supervisor's repair path: `if (!isPollerRunning()) startPoller()`.
        assertFalse("a finished loop must not claim to be running", loop.isActive)
        val restarted = mutableListOf<PollLoop.End>()
        assertTrue(
            "a dead loop MUST be replaceable — this is what the supervisor relies on",
            loop.start(onFinished = { restarted += it }) { }
        )
        assertTrue("the replacement loop must actually run and finish", awaitInactive(loop))
        assertTrue("the replacement must report its own end", restarted.contains(PollLoop.End.RETURNED))
    }

    @Test
    fun `a reconnect against a live loop does not create a second one`() {
        // Requirement 5: repeated start/reconnect must never produce two concurrent long-poll jobs.
        val loop = PollLoop(scope)
        var bodiesStarted = 0

        assertTrue(loop.start { bodiesStarted++; delay(1_000) })
        repeat(5) {
            assertFalse(
                "an ACTIVE loop must make start() a no-op, not a second job",
                loop.start { bodiesStarted++; delay(1_000) }
            )
        }
        assertEquals("exactly one loop body ran", 1, bodiesStarted)
        assertTrue(loop.isActive)
    }

    @Test
    fun `a start racing an already dead loop always produces a running loop`() {
        // The zombie's core invariant, stated positively: whatever happens, after start() either a
        // loop is active or a new one was created. Never "start returned and nothing polls".
        val loop = PollLoop(scope)
        repeat(20) {
            loop.start { }                    // returns at once
            awaitInactive(loop)
            val created = loop.start { delay(500) }
            assertTrue("a start against a dead loop must create one", created)
            assertTrue("and it must be active", loop.isActive)
            loop.stop()
        }
    }

    // ── Requirement 8: cancellation ─────────────────────────────────────────

    @Test
    fun `cancellation never leaves the loop claiming to be active`() {
        val loop = PollLoop(scope)
        val finished = mutableListOf<PollLoop.End>()
        loop.start(onFinished = { finished += it }) { delay(60_000) }
        assertTrue(loop.isActive)

        loop.stop()

        assertFalse("stop() must make the loop inactive at once", loop.isActive)
        runBlocking {
            withTimeoutOrNull(2_000L) {
                while (finished.isEmpty()) delay(10)
            }
        }
        assertTrue(
            "cancellation must be reported, not swallowed: $finished",
            finished.contains(PollLoop.End.CANCELLED)
        )
        // And stop() must leave the loop replaceable immediately, even before the cancel settles —
        // otherwise stop() becomes the next zombie, refusing to restart.
        assertTrue("stop() must not block restart", loop.start { })
    }

    @Test
    fun `a cancelled owning scope leaves nothing active`() {
        val ownScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val loop = PollLoop(ownScope)
        loop.start { delay(60_000) }
        assertTrue(loop.isActive)

        ownScope.cancel()

        assertTrue("a cancelled scope must not leave an active loop", awaitInactive(loop))
    }

    // ── Requirement 2/4: an old finisher must not clobber its replacement ────

    @Test
    fun `a late-finishing old loop cannot clear the state of a newer one`() {
        val loop = PollLoop(scope)
        val ends = mutableListOf<PollLoop.End>()

        // First loop: returns after a delay, so its finally runs LATE.
        loop.start(onFinished = { ends += it }) { delay(300) }
        // Replace it before it finishes. stop() invalidates the old generation.
        loop.stop()
        val created = loop.start(onFinished = { ends += it }) { delay(60_000) }

        assertTrue("the replacement must be started", created)
        assertTrue("the replacement must be active", loop.isActive)
        assertEquals(
            "the replacement must own the current generation",
            3L,
            loop.currentGeneration
        )
        // Let the old body's finally run after the replacement started.
        runBlocking { delay(500) }

        assertTrue(
            "a late finisher must NOT clear the newer loop's liveness — this is how a stale cleanup " +
                "recreates the zombie one layer down",
            loop.isActive
        )
        assertEquals(
            "and it must not report its own end as the current one",
            PollLoop.End.NOT_STARTED,
            loop.end
        )
    }

    @Test
    fun `a throwing body is reported as failed and still cleans up`() {
        val loop = PollLoop(scope)
        val finished = mutableListOf<PollLoop.End>()

        loop.start(onFinished = { finished += it }) { throw IllegalStateException("boom") }

        assertTrue(awaitInactive(loop))
        assertTrue(
            "a throw must be reported, not silently swallowed: $finished",
            finished.contains(PollLoop.End.FAILED)
        )
        assertTrue("and a failed loop must be restartable", loop.start { })
    }

    // ── Telemetry (requirement 12) ──────────────────────────────────────────

    @Test
    fun `the loop reports its own generation and timings`() {
        val loop = PollLoop(scope)
        assertEquals("nothing has run yet", 0L, loop.currentGeneration)
        assertEquals(PollLoop.End.NOT_STARTED, loop.end)

        loop.start { loop.onCycleStarted(); loop.onCycleFinished() }
        awaitInactive(loop)

        assertEquals("the first run is generation 1", 1L, loop.currentGeneration)
        assertTrue("startedAt must be set", loop.startedAt > 0L)
        assertTrue("finishedAt must be set once it ended", loop.finishedAt >= loop.startedAt)
        assertTrue("cycle activity must be recorded", loop.lastCycleStartedAt > 0L)
        assertTrue("and its end too", loop.lastCycleFinishedAt >= loop.lastCycleStartedAt)
        assertTrue("lastActivityAt must reflect the cycle", loop.lastActivityAt() > 0L)
    }

    @Test
    fun `concurrent starts create exactly one loop`() {
        // Requirement 5 under contention: the supervisor, a manual reconnect and the boot path can
        // all call start() around the same moment.
        val loop = PollLoop(scope)
        val created = java.util.concurrent.atomic.AtomicInteger(0)
        val winners = java.util.concurrent.atomic.AtomicInteger(0)

        runBlocking {
            val jobs = (1..32).map {
                launch(Dispatchers.Default) {
                    if (loop.start { created.incrementAndGet(); delay(2_000) }) winners.incrementAndGet()
                }
            }
            jobs.forEach { it.join() }
        }

        assertEquals("exactly one caller may create a loop", 1, winners.get())
        assertEquals("and exactly one body may run", 1, created.get())
        assertTrue(loop.isActive)
    }

    @Test
    fun `generations advance so a stale handle is detectable`() {
        val loop = PollLoop(scope)
        loop.start { }
        awaitInactive(loop)
        val first = loop.currentGeneration
        loop.start { }
        awaitInactive(loop)
        assertNotEquals("a restart is a new generation", first, loop.currentGeneration)
    }
}
