package com.autonomousone.messages.sms

import android.telephony.SmsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Failure copy must be honest about what Android actually knows.
 *
 * The rule this protects (mission §10/§33/§59): there is no portable Android result for "insufficient
 * prepaid balance". A prepaid SIM with no balance and a carrier outage both arrive as
 * `GENERIC_FAILURE`/`NETWORK_REJECT`, so the UI may say the carrier rejected the message and may hint
 * that balance *might* be involved — it may never state a balance problem as fact.
 *
 * NOTE: written and executed as part of this change (pure, no Android runtime).
 */
class SmsFailurePresentationTest {

    @Test
    fun `a generic failure never claims the balance is the cause`() {
        val copy = SmsFailurePresentation.forCode(SmsTransportFailure.CARRIER_FAILURE_UNKNOWN.persistable)

        assertFalse(copy.primary.contains("balance", ignoreCase = true))
        assertNotNull(copy.detail)
        assertTrue(
            "balance may only be mentioned as a possible cause",
            copy.actionHint!!.contains("balance", ignoreCase = true)
        )
        assertFalse("never state a zero balance", copy.primary.contains("zero", ignoreCase = true))
    }

    @Test
    fun `no presentation anywhere asserts a balance problem`() {
        for (failure in SmsTransportFailure.entries) {
            val copy = SmsFailurePresentation.forCode(failure.persistable)
            for (text in listOfNotNull(copy.primary, copy.detail, copy.actionHint)) {
                assertFalse(
                    "${failure.persistable} asserts a balance problem: $text",
                    text.contains("balance is", ignoreCase = true) ||
                        text.contains("no balance", ignoreCase = true) ||
                        text.contains("insufficient balance", ignoreCase = true)
                )
            }
        }
    }

    @Test
    fun `a network rejection says what happened and hints at balance as a possibility`() {
        val copy = SmsFailurePresentation.forCode(SmsTransportFailure.NETWORK_REJECTED.persistable)

        assertEquals("Network rejected the message", copy.primary)
        assertTrue(copy.actionHint!!.contains("balance", ignoreCase = true))
        assertTrue(copy.actionHint.contains("carrier", ignoreCase = true))
    }

    @Test
    fun `rate limiting is presented as a wait, never as a user error`() {
        val copy = SmsFailurePresentation.forCode(SmsTransportFailure.RIL_RATE_LIMITED.persistable)

        assertEquals("Too many SMS requests", copy.primary)
        assertTrue(copy.actionHint!!.contains("Wait", ignoreCase = true))
        assertFalse(copy.duplicateRisk)
    }

    @Test
    fun `ambiguous outcomes are flagged as duplicate risks`() {
        for (failure in listOf(
            SmsTransportFailure.CARRIER_FAILURE_UNKNOWN,
            SmsTransportFailure.SEND_CALLBACK_TIMEOUT,
            SmsTransportFailure.MODEM_ERROR
        )) {
            assertTrue(
                "${failure.persistable} must warn about a duplicate",
                SmsFailurePresentation.forCode(failure.persistable).duplicateRisk
            )
        }
        for (failure in listOf(
            SmsTransportFailure.NO_SERVICE,
            SmsTransportFailure.RADIO_OFF,
            SmsTransportFailure.SIM_UNAVAILABLE,
            SmsTransportFailure.INVALID_SMSC
        )) {
            assertFalse(
                "${failure.persistable} cannot duplicate anything",
                SmsFailurePresentation.forCode(failure.persistable).duplicateRisk
            )
        }
    }

    @Test
    fun `transport failure and delivery failure read differently`() {
        val transport = SmsFailurePresentation.forCode(SmsTransportFailure.NETWORK_REJECTED.persistable)
        val delivery = SmsFailurePresentation.deliveryFailedCopy(deliveryStatus = 0x40)

        assertTrue(transport.primary.contains("rejected", ignoreCase = true))
        assertTrue(delivery.primary.startsWith("Sent"))
        assertTrue(delivery.primary.contains("delivery failed", ignoreCase = true))
        assertFalse(delivery.primary == transport.primary)
    }

    @Test
    fun `a missing delivery report is never presented as a failed send`() {
        val copy = SmsFailurePresentation.forCode(SmsTransportFailure.DELIVERY_UNKNOWN.persistable)

        assertEquals("Sent · Delivery unknown", copy.primary)
        assertTrue(copy.detail!!.contains("never confirmed delivery"))
        assertFalse(copy.primary.contains("failed", ignoreCase = true))
        assertFalse("a missing DLR must not invite a resend", copy.duplicateRisk)
    }

    @Test
    fun `a send timeout is presented as unknown, not as failure`() {
        val copy = SmsFailurePresentation.forCode(SmsTransportFailure.SEND_CALLBACK_TIMEOUT.persistable)

        assertEquals("Send result is unknown", copy.primary)
        assertTrue(copy.detail!!.contains("did not confirm"))
    }

    @Test
    fun `an unknown code still produces usable, honest copy`() {
        val copy = SmsFailurePresentation.forCode("SOMETHING_WE_HAVE_NOT_SEEN")

        assertEquals("Message could not be sent", copy.primary)
        assertNotNull(copy.actionHint)
        assertFalse(copy.primary.contains("balance", ignoreCase = true))
    }

    @Test
    fun `presentation can be derived straight from a verdict`() {
        val verdict = SmsTransportClassifier.classify(SmsManager.RESULT_RIL_REQUEST_RATE_LIMITED)
        val copy = SmsFailurePresentation.forVerdict(verdict)

        assertEquals("Too many SMS requests", copy!!.primary)
        assertEquals(null, SmsFailurePresentation.forVerdict(SmsTransportClassifier.classify(android.app.Activity.RESULT_OK)))
    }

    @Test
    fun `every transport failure has presentation copy`() {
        for (failure in SmsTransportFailure.entries) {
            val copy = SmsFailurePresentation.forCode(failure.persistable)
            assertTrue("${failure.persistable} needs a primary line", copy.primary.isNotBlank())
            assertFalse(
                "an explicit entry must not fall back to the generic sentence",
                copy.primary == "Message could not be sent"
            )
        }
    }
}
