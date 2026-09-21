package com.autonomousone.messages.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The agent liveness request contract.
 *
 * THE ROOT CAUSE THIS PINS: the diagnostic AUTH probe hand-rolled its own body — adding a
 * `diagnostic` field and omitting `batteryLevel`/`networkType` — and GMweb answered HTTP 400
 * while the heartbeat, posting to the same path with the same credential, succeeded every
 * minute. The 400 was then reported to the user as "GMweb rejected this device's key".
 *
 * One builder now serves both, and the key set is asserted so a well-meaning extra field cannot
 * silently break the contract again.
 */
class AgentLivenessRequestTest {

    private fun body() = AgentLivenessRequest.body(
        appVersion = "3.4.7",
        batteryLevel = 87,
        networkType = "mobile",
        timestamp = 1_700_000_000_000L,
        sourceDeviceId = "device-1"
    )

    @Test
    fun `theBodyCarriesExactlyTheContractedKeys`() {
        val keys = body().keys().asSequence().toSet()

        assertEquals(AgentLivenessRequest.KEYS, keys)
    }

    /**
     * The specific field that broke it. An unknown key is what a strict server rejects, and
     * "diagnostic" was invented by the probe alone.
     */
    @Test
    fun `noDiagnosticOnlyFieldIsSent`() {
        val keys = body().keys().asSequence().toSet()

        assertFalse("an invented field is what the server rejected", keys.contains("diagnostic"))
    }

    @Test
    fun `theFieldsTheProbeUsedToOmitArePresent`() {
        val json = body()

        assertTrue(json.has(AgentLivenessRequest.KEY_BATTERY_LEVEL))
        assertTrue(json.has(AgentLivenessRequest.KEY_NETWORK_TYPE))
    }

    @Test
    fun `theLivenessBodyEnqueuesNothing`() {
        val json = body()

        // An EMPTY events array is what makes this a pure liveness ping: the server ingests it
        // as {accepted:[],duplicates:0} without touching a sequence.
        assertEquals(0, json.getJSONArray(AgentLivenessRequest.KEY_EVENTS).length())
    }

    @Test
    fun `valuesAreCarriedThroughUnchanged`() {
        val json = body()

        assertEquals("3.4.7", json.getString(AgentLivenessRequest.KEY_APP_VERSION))
        assertEquals(87, json.getInt(AgentLivenessRequest.KEY_BATTERY_LEVEL))
        assertEquals("mobile", json.getString(AgentLivenessRequest.KEY_NETWORK_TYPE))
        assertEquals(1_700_000_000_000L, json.getLong(AgentLivenessRequest.KEY_TIMESTAMP))
        assertEquals("device-1", json.getString(AgentLivenessRequest.KEY_SOURCE_DEVICE_ID))
    }

    @Test
    fun `anUnknownBatteryLevelIsStillSentAsTheHeartbeatAlwaysHas`() {
        val json = AgentLivenessRequest.body("1", -1, "unknown", 0L, "d")

        assertEquals(-1, json.getInt(AgentLivenessRequest.KEY_BATTERY_LEVEL))
        assertEquals("unknown", json.getString(AgentLivenessRequest.KEY_NETWORK_TYPE))
    }

    @Test
    fun `noMessageContentCanAppearInALivenessBody`() {
        val keys = body().keys().asSequence().toSet()

        listOf("text", "body", "message", "to", "phone").forEach { forbidden ->
            assertFalse("a liveness ping carries no message content: $forbidden", keys.contains(forbidden))
        }
    }
}
