package com.autonomousone.messages.gateway.wake

import com.autonomousone.messages.gateway.OutboxPoller
import com.autonomousone.messages.gateway.SecureCommandPoller
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Today's behaviour behind the [WakeSource] seam: the two pollers ARE both the wake mechanism and
 * the fetch mechanism.
 *
 * This class owns no transport logic on purpose. In POLLING mode `onWake` is never called — there is
 * nothing to wake, because the loop that would be woken is the loop doing the polling. In SOCKET or
 * FCM mode a different source fires `onWake`, and the caller points it at
 * [OutboxPoller.retryNow], which already interrupts the backoff through its conflated channel. That
 * is the one piece of plumbing Stage 2 relies on, and it already existed.
 *
 * The lifecycle guard is deliberately a LIVENESS check rather than a plain boolean: if the poller's
 * loop died (poll-loop zombie guard), `start()` must be able to repair it — "started once" is not
 * "running now".
 */
internal class PollingWakeSource(
    private val startPollers: () -> Unit,
    private val stopPollers: () -> Unit,
    private val pollerRunning: () -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
) : WakeSource {

    /** Production wiring: the two existing pollers, which remain the only data path. */
    constructor(
        poller: OutboxPoller,
        commandPoller: SecureCommandPoller,
    ) : this(
        startPollers = {
            poller.start()
            commandPoller.start()
        },
        stopPollers = {
            poller.stop()
            commandPoller.stop()
        },
        pollerRunning = { poller.isRunning },
    )

    override val id = WakeSourceId.POLLING

    private val _health = MutableStateFlow(WakeHealth(id = id, active = false))
    override val health: StateFlow<WakeHealth> = _health.asStateFlow()

    @Volatile
    private var started = false

    /** Always the floor: needs no platform and no server capability. */
    override fun isAvailable(): Boolean = true

    override fun start(onWake: () -> Unit) {
        // Idempotent against a LIVE poller only. A dead loop must still be replaceable, exactly as
        // ConnectionSupervisor's recovery path requires.
        if (started && pollerRunning()) return
        started = true
        startPollers()
        val now = clock()
        _health.value = _health.value.copy(
            active = true,
            unavailable = null,
            lastAttemptAt = now,
            consecutiveFailures = 0
        )
    }

    override fun stop() {
        if (!started) return
        started = false
        stopPollers()
        _health.value = _health.value.copy(active = false)
    }
}
