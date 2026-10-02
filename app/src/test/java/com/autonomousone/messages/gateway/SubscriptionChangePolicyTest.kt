package com.autonomousone.messages.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The subscription listener is a HINT, not a fact: it fires several times for one physical event (an
 * eSIM toggle), and it fires for changes this app does not care about. Reporting telemetry on every
 * callback would be a burst of identical POSTs; reporting only when the observable facts differ is
 * both cheaper and more honest.
 */
class SubscriptionChangePolicyTest {

    private fun snap(ids: List<Int>, defaultSms: Int = 1) = SubscriptionSnapshot(ids, defaultSms)

    @Test
    fun `the first observation is a change`() {
        assertTrue(SubscriptionChangePolicy.changed(null, snap(listOf(1))))
    }

    @Test
    fun `a repeated callback with identical state is not a change`() {
        val previous = snap(listOf(1, 2))

        assertFalse(SubscriptionChangePolicy.changed(previous, snap(listOf(1, 2))))
        // Order must not matter: the same set of lines is the same state.
        assertFalse(SubscriptionChangePolicy.changed(previous, snap(listOf(2, 1))))
    }

    @Test
    fun `a removed SIM is a change`() {
        assertTrue(SubscriptionChangePolicy.changed(snap(listOf(1, 2)), snap(listOf(1))))
    }

    @Test
    fun `an added SIM or eSIM is a change`() {
        assertTrue(SubscriptionChangePolicy.changed(snap(listOf(1)), snap(listOf(1, 2))))
    }

    @Test
    fun `a moved default SMS line is a change and is named as such`() {
        val previous = snap(listOf(1, 2), defaultSms = 1)
        val current = snap(listOf(1, 2), defaultSms = 2)

        assertTrue(SubscriptionChangePolicy.changed(previous, current))
        assertTrue(SubscriptionChangePolicy.defaultChanged(previous, current))
    }

    @Test
    fun `a SIM change with the default unchanged is not reported as a default change`() {
        val previous = snap(listOf(1, 2), defaultSms = 1)
        val current = snap(listOf(1), defaultSms = 1)

        assertTrue(SubscriptionChangePolicy.changed(previous, current))
        assertFalse(SubscriptionChangePolicy.defaultChanged(previous, current))
    }

    @Test
    fun `a subscription id change for the same slot is a change`() {
        // Subscription ids are not eternal device identity: they can move after a reboot, an eSIM
        // profile change or a SIM swap, and a stale id must be reported rather than assumed.
        assertTrue(SubscriptionChangePolicy.changed(snap(listOf(1)), snap(listOf(7))))
    }

    @Test
    fun `zero active subscriptions is a state, not an absence of information`() {
        assertTrue(SubscriptionChangePolicy.changed(snap(listOf(1)), snap(emptyList())))
        assertFalse(SubscriptionChangePolicy.changed(snap(emptyList()), snap(emptyList())))
    }

    @Test
    fun `the snapshot carries no phone number or SIM identifier beyond the subscription id`() {
        // The only identifier that may leave the device is the Android subscription id: no number,
        // no IMSI, no ICCID, no carrier-profile secret.
        val fields = SubscriptionSnapshot::class.java.declaredFields.map { it.name.lowercase() }

        assertFalse("a phone-number field must not exist", fields.any { it.contains("number") })
        assertFalse("an IMSI field must not exist", fields.any { it.contains("imsi") })
        assertFalse("an ICCID field must not exist", fields.any { it.contains("iccid") })
    }
}
