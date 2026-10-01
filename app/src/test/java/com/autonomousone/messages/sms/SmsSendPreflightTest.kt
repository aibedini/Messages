package com.autonomousone.messages.sms

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The send-readiness decision table.
 *
 * `/ready` used to answer `isListening && isDefaultSmsApp() && queueRunning`,
 * so a device with `SEND_SMS` revoked or with the selected SIM removed reported
 * `{"status":"ready"}` while every send was doomed. Each row below is one of the
 * hard blockers that must now be visible, with a machine-readable reason.
 */
class SmsSendPreflightTest {

    private val sim1 = SmsSubscription(1, 0, "SIM 1", "Carrier A")
    private val sim2 = SmsSubscription(2, 1, "SIM 2", "Carrier B")

    private fun evaluate(
        gatewayRunning: Boolean = true,
        queueRunning: Boolean = true,
        defaultSmsApp: Boolean = true,
        sendSmsPermission: Boolean = true,
        activeSubscriptions: List<SmsSubscription> = listOf(sim1),
        telephonyStateKnown: Boolean = true,
        selectedSubscriptionId: Int? = null
    ) = SmsSendPreflight.evaluate(
        gatewayRunning, queueRunning, defaultSmsApp, sendSmsPermission,
        activeSubscriptions, telephonyStateKnown, selectedSubscriptionId
    )

    // ── healthy ──────────────────────────────────────────────────────────────

    @Test
    fun `a healthy device is send ready with no blockers`() {
        val preflight = evaluate(activeSubscriptions = listOf(sim1, sim2))

        assertTrue(preflight.sendReady)
        assertTrue(preflight.gatewayReady)
        assertEquals(emptyList<String>(), preflight.blockingReasons)
    }

    @Test
    fun `an explicitly selected active sim is send ready`() {
        val preflight = evaluate(activeSubscriptions = listOf(sim1, sim2), selectedSubscriptionId = 2)

        assertTrue(preflight.sendReady)
        assertTrue(preflight.selectedSubscriptionAvailable)
    }

    // ── hard blockers ────────────────────────────────────────────────────────

    @Test
    fun `revoked send_sms is a blocker and is never reported as ready`() {
        val preflight = evaluate(sendSmsPermission = false)

        assertFalse(preflight.sendReady)
        assertTrue(preflight.blockingReasons.contains(SmsBlockReason.PERMISSION_DENIED))
    }

    @Test
    fun `losing the sms role is a blocker`() {
        val preflight = evaluate(defaultSmsApp = false)

        assertFalse(preflight.sendReady)
        assertTrue(preflight.blockingReasons.contains(SmsBlockReason.NOT_DEFAULT_SMS_APP))
    }

    @Test
    fun `no active subscription at all is a blocker`() {
        val preflight = evaluate(activeSubscriptions = emptyList())

        assertFalse(preflight.sendReady)
        assertTrue(preflight.blockingReasons.contains(SmsBlockReason.NO_ACTIVE_SUBSCRIPTION))
    }

    @Test
    fun `an explicitly selected sim that is not active is blocked and never a fallback`() {
        val preflight = evaluate(activeSubscriptions = listOf(sim1, sim2), selectedSubscriptionId = 7)

        assertFalse(preflight.sendReady)
        assertTrue(preflight.blockingReasons.contains(SmsBlockReason.SIM_UNAVAILABLE))
        assertFalse(preflight.selectedSubscriptionAvailable)
    }

    @Test
    fun `a stopped gateway or queue is a blocker`() {
        val noGateway = evaluate(gatewayRunning = false)
        assertFalse(noGateway.sendReady)
        assertFalse(noGateway.gatewayReady)
        assertTrue(noGateway.blockingReasons.contains(SmsBlockReason.GATEWAY_NOT_RUNNING))

        val noQueue = evaluate(queueRunning = false)
        assertFalse(noQueue.sendReady)
        assertFalse(noQueue.gatewayReady)
        assertTrue(noQueue.blockingReasons.contains(SmsBlockReason.QUEUE_NOT_RUNNING))
    }

    @Test
    fun `every blocker is reported at once, not just the first`() {
        val preflight = evaluate(
            gatewayRunning = false,
            queueRunning = false,
            defaultSmsApp = false,
            sendSmsPermission = false,
            activeSubscriptions = emptyList()
        )

        assertTrue(preflight.blockingReasons.containsAll(
            listOf(
                SmsBlockReason.GATEWAY_NOT_RUNNING,
                SmsBlockReason.QUEUE_NOT_RUNNING,
                SmsBlockReason.PERMISSION_DENIED,
                SmsBlockReason.NOT_DEFAULT_SMS_APP,
                SmsBlockReason.NO_ACTIVE_SUBSCRIPTION
            )
        ))
    }

    // ── unknown is not the same as absent ────────────────────────────────────

    @Test
    fun `an unreadable subscription list does not fabricate a sim blocker`() {
        // No READ_PHONE_STATE, or the platform refused to answer. Refusing every
        // send because the app could not look would be worse than the risk it
        // guards: the manager-level fail-closed check still refuses a PROVEN
        // mismatch at send time (SendSimPolicy).
        val preflight = evaluate(
            activeSubscriptions = emptyList(),
            telephonyStateKnown = false,
            selectedSubscriptionId = 7
        )

        assertFalse(preflight.subscriptionsKnown)
        assertTrue(preflight.sendReady)
        assertTrue(preflight.selectedSubscriptionAvailable)
    }

    // ── wire shape of /ready ─────────────────────────────────────────────────

    @Test
    fun `healthy ready json carries capability details and an empty blocker list`() {
        val json = evaluate(activeSubscriptions = listOf(sim1, sim2), selectedSubscriptionId = 2).toJson()

        assertEquals("ready", json.getString("status"))
        assertTrue(json.getBoolean("ready"))
        assertTrue(json.getBoolean("sendReady"))
        assertTrue(json.getBoolean("gatewayReady"))
        assertTrue(json.getBoolean("sendSmsPermission"))
        assertTrue(json.getBoolean("defaultSmsApp"))
        assertTrue(json.getBoolean("serverRunning"))
        assertEquals(2, json.getInt("selectedSubscriptionId"))
        assertTrue(json.getBoolean("selectedSubscriptionAvailable"))
        assertEquals(0, json.getJSONArray("blockingReasons").length())
        assertEquals(2, json.getJSONArray("activeSubscriptions").length())
        assertFalse("a healthy body must not claim not_ready", json.has("error"))
    }

    @Test
    fun `blocked ready json explains exactly why`() {
        val json = evaluate(sendSmsPermission = false, activeSubscriptions = emptyList()).toJson()

        assertEquals("not_ready", json.getString("status"))
        assertEquals("not_ready", json.getString("error"))
        assertFalse(json.getBoolean("ready"))
        assertFalse(json.getBoolean("sendReady"))
        val reasons = json.getJSONArray("blockingReasons")
        val list = (0 until reasons.length()).map { reasons.getString(it) }
        assertTrue(list.contains(SmsBlockReason.PERMISSION_DENIED))
        assertTrue(list.contains(SmsBlockReason.NO_ACTIVE_SUBSCRIPTION))
    }

    @Test
    fun `active subscriptions expose only what selection needs`() {
        val entry = evaluate(activeSubscriptions = listOf(sim1)).toJson()
            .getJSONArray("activeSubscriptions").getJSONObject(0)

        assertEquals(1, entry.getInt("subscriptionId"))
        assertEquals(0, entry.getInt("slotIndex"))
        assertEquals("SIM 1", entry.getString("displayName"))
        assertEquals("Carrier A", entry.getString("carrierName"))
        assertTrue(entry.getBoolean("active"))
        // No IMSI/ICCID/phone number may travel in a readiness probe.
        assertFalse(entry.has("number"))
        assertFalse(entry.has("imsi"))
        assertFalse(entry.has("iccid"))
    }

    @Test
    fun `no selection is reported as null, not as a sentinel subscription`() {
        val json = evaluate(selectedSubscriptionId = null).toJson()

        assertTrue(json.isNull("selectedSubscriptionId"))
        assertTrue(json.getBoolean("selectedSubscriptionAvailable"))
    }

    @Test
    fun `json round trips through a string as valid json`() {
        val rendered = evaluate(activeSubscriptions = listOf(sim1, sim2)).toJson().toString()
        val parsed = JSONObject(rendered)

        assertEquals("ready", parsed.getString("status"))
        assertEquals(2, parsed.getJSONArray("activeSubscriptions").length())
    }
}
