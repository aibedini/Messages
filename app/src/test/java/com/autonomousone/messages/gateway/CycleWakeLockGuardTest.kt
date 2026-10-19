package com.autonomousone.messages.gateway

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Stage 1.2 — the wake lock may never leak.
 *
 * `OutboxPoller` holds a `PARTIAL_WAKE_LOCK` for the active part of a cycle. If a throwing cycle
 * skipped the release, the CPU would stay awake for the whole 90 s safety timeout (and be re-armed
 * on the next cycle), which is the very drain this stage exists to remove.
 */
class CycleWakeLockGuardTest {

    @Test
    fun `acquire and release are balanced on the success path`() = runBlocking {
        val events = mutableListOf<String>()
        val guard = CycleWakeLockGuard(
            acquire = { events += "acquire" },
            release = { events += "release" }
        )

        val result = guard.withLock {
            events += "work"
            "done"
        }

        assertEquals("done", result)
        assertEquals(listOf("acquire", "work", "release"), events)
    }

    @Test
    fun `a throwing cycle still releases the lock`() = runBlocking {
        val events = mutableListOf<String>()
        val guard = CycleWakeLockGuard(
            acquire = { events += "acquire" },
            release = { events += "release" }
        )

        try {
            guard.withLock<Unit> {
                events += "work"
                throw IllegalStateException("transport failure")
            }
            fail("the exception must propagate")
        } catch (expected: IllegalStateException) {
            assertEquals("transport failure", expected.message)
        }

        assertEquals(listOf("acquire", "work", "release"), events)
    }

    @Test
    fun `a cancelled cycle still releases the lock`() = runBlocking {
        val events = mutableListOf<String>()
        val guard = CycleWakeLockGuard(
            acquire = { events += "acquire" },
            release = { events += "release" }
        )

        try {
            guard.withLock<Unit> { throw kotlinx.coroutines.CancellationException("stopped") }
            fail("cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // expected
        }

        assertEquals(listOf("acquire", "release"), events)
    }
}
