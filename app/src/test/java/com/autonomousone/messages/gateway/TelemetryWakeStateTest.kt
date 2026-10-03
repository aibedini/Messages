package com.autonomousone.messages.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telemetry's trigger semantics: single-flight, dirty, and no dropped trigger.
 *
 * The 60-second loop could not express "the SIM list just changed" or "the user just granted Phone
 * permission" — it was simply late for all of them. Making it event-driven is only safe if a burst
 * cannot become a storm and a trigger cannot be lost, which is what these two rules pin down.
 */
class TelemetryWakeStateTest {

    @Test
    fun `the first trigger runs immediately`() {
        val state = TelemetryWakeState()

        assertTrue(state.beginRun())
        assertFalse("a second concurrent run is refused", state.beginRun())
    }

    @Test
    fun `a trigger during a run schedules exactly one more pass`() {
        val state = TelemetryWakeState()
        assertTrue(state.beginRun())

        state.markTriggered()
        assertTrue("the extra pass must happen", state.endRun())
        // The extra pass is still part of the same busy period, so a trigger during it queues the
        // same way instead of racing it.
        assertTrue(state.isInFlight)
        assertTrue(state.beginRun() == false)
    }

    @Test
    fun `a burst of triggers collapses into one extra pass`() {
        val state = TelemetryWakeState()
        assertTrue(state.beginRun())

        repeat(5) { state.markTriggered() }

        assertTrue(state.endRun())
        assertFalse("and no second extra pass is queued by the same burst", state.endRun())
        assertFalse(state.isInFlight)
    }

    @Test
    fun `a clean run releases the state for the next trigger`() {
        val state = TelemetryWakeState()
        assertTrue(state.beginRun())

        assertFalse("nothing was triggered during the run", state.endRun())
        assertFalse(state.isInFlight)
        assertTrue("the next trigger runs again", state.beginRun())
    }

    @Test
    fun `a trigger after a finished run is never lost`() {
        val state = TelemetryWakeState()
        assertTrue(state.beginRun())
        assertFalse(state.endRun())

        state.markTriggered()

        assertTrue(state.beginRun())
    }

    @Test
    fun `triggers are distinct reasons with stable wire values`() {
        // GMweb reads these strings; renaming one silently changes the meaning of a report.
        assertEquals("STARTUP", TelemetryTrigger.STARTUP.wireValue)
        assertEquals("NETWORK_RECONNECTED", TelemetryTrigger.NETWORK_RECONNECTED.wireValue)
        assertEquals("GATEWAY_CONNECTED", TelemetryTrigger.GATEWAY_CONNECTED.wireValue)
        assertEquals("SUBSCRIPTIONS_CHANGED", TelemetryTrigger.SUBSCRIPTIONS_CHANGED.wireValue)
        assertEquals("PHONE_PERMISSION_GRANTED", TelemetryTrigger.PHONE_PERMISSION_GRANTED.wireValue)
        assertEquals("DEFAULT_SMS_CHANGED", TelemetryTrigger.DEFAULT_SMS_CHANGED.wireValue)
        assertEquals("APP_UPDATED", TelemetryTrigger.APP_UPDATED.wireValue)
        assertEquals("MANUAL_DIAGNOSTIC_REFRESH", TelemetryTrigger.MANUAL_DIAGNOSTIC_REFRESH.wireValue)
        assertEquals("PERIODIC", TelemetryTrigger.PERIODIC.wireValue)
    }

    @Test
    fun `a web-requested refresh is its own trigger, never the diagnostics one`() {
        // Two different actors. If GMweb's refresh reported MANUAL_DIAGNOSTIC_REFRESH, a payload could
        // not answer "did the browser's refresh actually work?".
        assertEquals("REMOTE_REFRESH", TelemetryTrigger.REMOTE_REFRESH.wireValue)
        assertTrue(TelemetryTrigger.REMOTE_REFRESH != TelemetryTrigger.MANUAL_DIAGNOSTIC_REFRESH)

        val values = TelemetryTrigger.entries.map { it.wireValue }
        assertEquals("every trigger must have a distinct wire value", values.size, values.toSet().size)
    }
}
