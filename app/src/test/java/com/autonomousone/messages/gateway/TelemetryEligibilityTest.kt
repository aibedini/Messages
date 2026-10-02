package com.autonomousone.messages.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The telemetry gate must name WHY it is closed.
 *
 * This is the regression that a week of production could not explain: GMweb received ZERO
 * `/api/v1/agent/device-telemetry` requests from a phone that was uploading events and polling the
 * pull bridge with HTTP 200. The gate was a bare Boolean over three prefs, so "the gateway is off",
 * "the device is not enrolled" and "no server configured" were indistinguishable — and all three
 * looked identical to "telemetry is healthy".
 */
class TelemetryEligibilityTest {

    @Test
    fun `all three conditions met is eligible with no reason`() {
        val result = TelemetryEligibility.evaluate(
            isEnabled = true,
            identityRegistered = true,
            serverOrigin = "https://gmweb.example"
        )

        assertTrue(result.eligible)
        assertNull(result.reason)
    }

    @Test
    fun `a disabled gateway is named as such, not as a misconfiguration`() {
        val result = TelemetryEligibility.evaluate(
            isEnabled = false,
            identityRegistered = true,
            serverOrigin = "https://gmweb.example"
        )

        assertFalse(result.eligible)
        assertEquals(TelemetryEligibility.GATEWAY_DISABLED, result.reason)
    }

    @Test
    fun `an unenrolled device is named separately from a disabled gateway`() {
        val result = TelemetryEligibility.evaluate(
            isEnabled = true,
            identityRegistered = false,
            serverOrigin = "https://gmweb.example"
        )

        assertFalse(result.eligible)
        assertEquals(TelemetryEligibility.IDENTITY_NOT_REGISTERED, result.reason)
    }

    @Test
    fun `a missing server origin is named separately from both`() {
        val result = TelemetryEligibility.evaluate(
            isEnabled = true,
            identityRegistered = true,
            serverOrigin = ""
        )

        assertFalse(result.eligible)
        assertEquals(TelemetryEligibility.NO_GMWEB_ORIGIN, result.reason)
    }

    @Test
    fun `the reason is never null when ineligible`() {
        val cases = listOf(
            Triple(false, false, ""),
            Triple(false, true, "https://x"),
            Triple(true, false, "https://x"),
            Triple(true, true, "")
        )
        for ((enabled, identity, origin) in cases) {
            val result = TelemetryEligibility.evaluate(enabled, identity, origin)
            assertFalse(result.eligible)
            assertTrue("a closed gate must always name a reason ($enabled/$identity/$origin)",
                !result.reason.isNullOrBlank())
        }
    }

    @Test
    fun `the reason codes are the stable strings diagnostics prints`() {
        assertEquals("GATEWAY_DISABLED", TelemetryEligibility.GATEWAY_DISABLED)
        assertEquals("IDENTITY_NOT_REGISTERED", TelemetryEligibility.IDENTITY_NOT_REGISTERED)
        assertEquals("NO_GMWEB_ORIGIN", TelemetryEligibility.NO_GMWEB_ORIGIN)
    }

    @Test
    fun `the live eligibility state records what the reporter evaluated`() {
        TelemetryEligibilityState.resetForTest()
        assertNull("nothing has been evaluated yet", TelemetryEligibilityState.current())

        val evaluated = TelemetryEligibility.evaluate(false, true, "https://x")
        TelemetryEligibilityState.record(evaluated)

        assertEquals(evaluated, TelemetryEligibilityState.current())
        TelemetryEligibilityState.resetForTest()
    }
}
