package com.autonomousone.messages.gateway

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * STAGE-2 §2.5 — `ConnectionSupervisor.reconcile` must run LAST in tick order: it can start and stop
 * the other subscribers, so it has to observe their post-tick state, not race them.
 */
class GatewayTickerOrderTest {

    @Test
    fun `the reconcile subscriber runs last`() = runTest {
        val ticker = GatewayTicker(backgroundScope)
        val order = mutableListOf<String>()
        ticker.subscribe("event-uploader") { order += "uploader" }
        ticker.subscribe("device-telemetry") { order += "telemetry" }
        ticker.subscribe("heartbeat") { order += "heartbeat" }
        ticker.subscribe("connection-supervisor") { order += "reconcile" }

        ticker.start()
        advanceTimeBy(GatewayTicker.TICK_INTERVAL_MS); runCurrent()

        assertEquals(
            listOf("uploader", "telemetry", "heartbeat", "reconcile"),
            order
        )
        ticker.stop()
    }
}
