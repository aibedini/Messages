package com.autonomousone.messages.gateway.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 2 — the polling source's lifecycle must be the pollers' own contract, not a second one.
 *
 * The important case is the third test: "start() was called once" is NOT "the loop is running now".
 * `ConnectionSupervisor` depends on being able to replace a poller whose loop died, so the guard here
 * is a liveness check, not a boolean latch.
 */
class PollingWakeSourceTest {

    private var starts = 0
    private var stops = 0
    private var running = false

    private fun source(clockMs: Long = 1_000L) = PollingWakeSource(
        startPollers = {
            starts++
            running = true
        },
        stopPollers = {
            stops++
            running = false
        },
        pollerRunning = { running },
        clock = { clockMs },
    )

    @Test
    fun `start twice on a live poller does not double-start`() {
        val source = source()
        source.start { }
        source.start { }
        assertEquals(1, starts)
    }

    @Test
    fun `stop then start restarts`() {
        val source = source()
        source.start { }
        source.stop()
        source.start { }
        assertEquals(2, starts)
        assertEquals(1, stops)
    }

    @Test
    fun `a dead poller is restarted even though start was already called`() {
        val source = source()
        source.start { }
        // The loop exited on its own (policy break, escaped exception): the poller reports not
        // running, which is exactly what the supervisor's recovery path needs to be able to repair.
        running = false
        source.start { }
        assertEquals(2, starts)
    }

    @Test
    fun `stop is idempotent`() {
        val source = source()
        source.start { }
        source.stop()
        source.stop()
        assertEquals(1, stops)
    }

    @Test
    fun `health reports the lifecycle without carrying identifiers`() {
        val source = source(clockMs = 7_777L)
        assertFalse(source.health.value.active)
        assertEquals(WakeSourceId.POLLING, source.health.value.id)
        assertTrue(source.isAvailable())

        source.start { }
        assertTrue(source.health.value.active)
        assertEquals(7_777L, source.health.value.lastAttemptAt)
        assertEquals(0L, source.health.value.wakeCount)

        source.stop()
        assertFalse(source.health.value.active)
    }

    @Test
    fun `polling mode never needs the wake callback`() {
        // In POLLING the poller IS the wake mechanism; a callback here would mean a second wake path.
        val source = source()
        var wakeCallbacks = 0
        source.start { wakeCallbacks++ }
        assertEquals(0, wakeCallbacks)
    }
}
