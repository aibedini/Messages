package com.autonomousone.messages.gateway

import com.autonomousone.messages.messaging.SimDiscovery
import com.autonomousone.messages.messaging.SimDiscoveryResult
import com.autonomousone.messages.messaging.SimInfo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The payload rules that decide whether GMweb can trust what it is shown.
 *
 * The important one: **a missing permission must not make the phone look offline.** The report still
 * travels, with `available = false` and its reason, because "this phone is online and cannot list its
 * SIMs" is a different — and actionable — fact from "this phone is unreachable".
 */
class TelemetryPayloadSectionsTest {

    private val sim1 = SimInfo(1, 0, "Carrier A", "SIM 1", "+989120000000", true)
    private val sim2 = SimInfo(2, 1, "Carrier B", "SIM 2", "+989120000001", false)

    private fun label(value: String) = DeviceTelemetry.safeSimLabel(value)

    private fun subscriptions(
        discovery: SimDiscoveryResult,
        permission: Boolean = true,
        defaultSubId: Int = 1,
        lastChangedAt: Long? = null
    ) = TelemetryPayloadSections.smsSubscriptions(
        discovery = discovery,
        permissionGranted = permission,
        defaultSubscriptionId = defaultSubId,
        lastChangedAt = lastChangedAt,
        safeLabel = ::label
    )

    // ── permission missing ───────────────────────────────────────────────────

    @Test
    fun `a missing phone permission is reported inside a normal payload`() {
        val section = subscriptions(SimDiscoveryResult.PermissionMissing, permission = false)

        assertFalse("the question could not be asked", section.getBoolean("available"))
        assertFalse(section.getBoolean("permissionGranted"))
        assertEquals(SimDiscovery.REASON_PERMISSION_MISSING, section.getString("reason"))
        assertEquals(0, section.getJSONArray("items").length())
        // and it is still a payload, not a transport failure: the caller posts it.
        assertTrue(section.has("defaultSubscriptionId"))
    }

    @Test
    fun `a platform failure is its own reason, never an empty list of SIMs`() {
        val section = subscriptions(
            SimDiscoveryResult.Failed(SimDiscovery.REASON_PLATFORM_FAILURE)
        )

        assertFalse(section.getBoolean("available"))
        assertEquals(SimDiscovery.REASON_PLATFORM_FAILURE, section.getString("reason"))
        assertTrue("permission WAS granted in this case", section.getBoolean("permissionGranted"))
    }

    // ── zero, one and two SIMs ───────────────────────────────────────────────

    @Test
    fun `zero active subscriptions is an ANSWERED question`() {
        val section = subscriptions(SimDiscoveryResult.Available(emptyList()))

        assertTrue("available means the device answered", section.getBoolean("available"))
        assertFalse(section.has("reason"))
        assertEquals(0, section.getJSONArray("items").length())
    }

    @Test
    fun `one SIM reports its subscription and the default flag`() {
        val section = subscriptions(SimDiscoveryResult.Available(listOf(sim1)))
        val item = section.getJSONArray("items").getJSONObject(0)

        assertEquals(1, item.getInt("subscriptionId"))
        assertEquals(0, item.getInt("slotIndex"))
        assertTrue(item.getBoolean("isDefaultSms"))
        assertTrue(item.getBoolean("isActive"))
        assertEquals(1, section.getInt("defaultSubscriptionId"))
    }

    @Test
    fun `dual SIM reports both subscriptions and the correct default`() {
        // The per-item flag and the section's defaultSubscriptionId come from the SAME platform
        // answer in production; the fixture is built that way so the test cannot assert a state the
        // device could never produce (SIM 2 designated as the default line).
        val notDefault = sim1.copy(isSystemDefault = false)
        val isDefault = sim2.copy(isSystemDefault = true)
        val section = subscriptions(
            SimDiscoveryResult.Available(listOf(notDefault, isDefault)),
            defaultSubId = 2
        )
        val items = section.getJSONArray("items")

        assertEquals(2, items.length())
        assertEquals(1, items.getJSONObject(0).getInt("subscriptionId"))
        assertEquals(2, items.getJSONObject(1).getInt("subscriptionId"))
        assertFalse(items.getJSONObject(0).getBoolean("isDefaultSms"))
        assertTrue(items.getJSONObject(1).getBoolean("isDefaultSms"))
        assertEquals(2, section.getInt("defaultSubscriptionId"))
    }

    @Test
    fun `a SIM change timestamp is carried when known and absent when not`() {
        assertTrue(
            subscriptions(SimDiscoveryResult.Available(listOf(sim1)), lastChangedAt = 123L)
                .getLong("lastChangedAt") == 123L
        )
        assertFalse(
            subscriptions(SimDiscoveryResult.Available(listOf(sim1)), lastChangedAt = null)
                .has("lastChangedAt")
        )
    }

    // ── privacy ──────────────────────────────────────────────────────────────

    @Test
    fun `no phone number, IMSI or ICCID ever leaves in a SIM item`() {
        val item = subscriptions(SimDiscoveryResult.Available(listOf(sim1)))
            .getJSONArray("items").getJSONObject(0)
        val keys = item.keys().asSequence().toSet()

        assertEquals(
            setOf("subscriptionId", "slotIndex", "displayName", "carrierName", "isDefaultSms", "isActive"),
            keys
        )
        for (forbidden in listOf("number", "imsi", "iccid", "phoneNumber")) {
            assertFalse("$forbidden must never be sent", keys.contains(forbidden))
        }
        // And a label that looks like a phone number is sanitised rather than sent.
        val numericLabel = DeviceTelemetry.safeSimLabel("+98 912 000 0000")
        assertFalse(numericLabel.contains("912"))
    }

    // ── capabilities ─────────────────────────────────────────────────────────

    @Test
    fun `capabilities advertise the encrypted command types of THIS build`() {
        val capabilities = TelemetryPayloadSections.capabilities()
        val types = capabilities.getJSONArray("commandTypes")

        val list = (0 until types.length()).map { types.getString(it) }
        assertTrue(list.contains("SEND_SMS"))
        assertTrue(list.contains("MARK_THREAD_READ"))
        assertTrue("GMweb needs to know a refresh can be requested", list.contains("REFRESH_DEVICE_TELEMETRY"))
        assertEquals(TelemetryPayloadSections.COMMAND_TYPES.size, list.size)
    }

    @Test
    fun `the capability list is local build evidence, never server input`() {
        // The section takes no parameter at all: there is no input for a server to influence.
        val parameters = TelemetryPayloadSections::class.java.declaredMethods
            .first { it.name == "capabilities" }
            .parameterTypes

        assertEquals(0, parameters.size)
    }

    @Test
    fun `permissions are reported separately so listing and sending cannot be confused`() {
        val permissions = TelemetryPayloadSections.permissions(
            readPhoneState = true,
            sendSms = false,
            defaultSmsRole = false
        )

        assertTrue(permissions.getBoolean("readPhoneState"))
        assertFalse(permissions.getBoolean("sendSms"))
        assertFalse(permissions.getBoolean("defaultSmsRole"))
    }

    @Test
    fun `the payload sections are valid JSON when serialised`() {
        val rendered = JSONObject()
            .put("smsSubscriptions", subscriptions(SimDiscoveryResult.Available(listOf(sim1, sim2))))
            .put("capabilities", TelemetryPayloadSections.capabilities())
            .toString()

        val parsed = JSONObject(rendered)
        assertEquals(2, parsed.getJSONObject("smsSubscriptions").getJSONArray("items").length())
        assertEquals(3, parsed.getJSONObject("capabilities").getJSONArray("commandTypes").length())
    }
}
