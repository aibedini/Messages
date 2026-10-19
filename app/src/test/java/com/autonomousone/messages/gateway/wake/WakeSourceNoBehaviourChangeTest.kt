package com.autonomousone.messages.gateway.wake

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Stage 2's acceptance criterion: with AUTO, the sequence of poller start/stop calls is identical to
 * the pre-refactor sequence.
 *
 * The pre-refactor sequence is what `ConnectionSupervisor.reconcile()` does today on the LEGACY_PULL
 * path: `startCommandPoller()` and then the pull-poller start. The refactor may move those calls
 * through [PollingWakeSource], but it may not change which poller starts, or in what order.
 */
class WakeSourceNoBehaviourChangeTest {

    /** Idempotent pollers, mirroring the real `start()` contracts. */
    private class RecordingPollers {
        val calls = mutableListOf<String>()
        private var outbox = false
        private var command = false

        fun startOutbox() {
            if (!outbox) {
                outbox = true
                calls += "outbox.start"
            }
        }

        fun startCommand() {
            if (!command) {
                command = true
                calls += "command.start"
            }
        }

        fun stopOutbox() {
            if (outbox) {
                outbox = false
                calls += "outbox.stop"
            }
        }

        fun stopCommand() {
            if (command) {
                command = false
                calls += "command.stop"
            }
        }

        val outboxRunning: Boolean get() = outbox
    }

    private fun autoResolution() = WakeSourceSelector.resolve(
        WakeSourceSelector.Inputs(
            configured = WakeMode.AUTO,
            fcmAvailable = false,
            socketAvailable = false,
        )
    )

    private fun pollingSource(pollers: RecordingPollers) = PollingWakeSource(
        startPollers = {
            pollers.startOutbox()
            pollers.startCommand()
        },
        stopPollers = {
            pollers.stopOutbox()
            pollers.stopCommand()
        },
        pollerRunning = { pollers.outboxRunning },
    )

    @Test
    fun `AUTO starts exactly the same pollers in the same order as before the refactor`() {
        // Before: the supervisor's two existing hooks, in this order.
        val before = RecordingPollers()
        before.startCommand()
        before.startOutbox()

        // After: the same two hooks, with the pull side routed through the wake source.
        val after = RecordingPollers()
        after.startCommand()
        val resolution = autoResolution()
        assertEquals(WakeSourceId.POLLING, resolution.chosen)
        pollingSource(after).start { }

        assertEquals(before.calls, after.calls)
        assertEquals(listOf("command.start", "outbox.start"), after.calls)
    }

    @Test
    fun `repeated reconciles are still no-ops on a live pair`() {
        val pollers = RecordingPollers()
        val source = pollingSource(pollers)
        repeat(5) {
            pollers.startCommand()
            source.start { }
        }
        assertEquals(listOf("command.start", "outbox.start"), pollers.calls)
    }

    @Test
    fun `the stall-restart path still restarts only the pull poller`() {
        // ConnectionSupervisor's RESTART branch stops and starts the POLLER, never the command
        // channel. Routing the pull start through the wake source must not widen that.
        val pollers = RecordingPollers()
        val source = pollingSource(pollers)
        pollers.startCommand()
        source.start { }

        pollers.stopOutbox()
        source.start { }

        assertEquals(
            listOf("command.start", "outbox.start", "outbox.stop", "outbox.start"),
            pollers.calls
        )
    }
}
