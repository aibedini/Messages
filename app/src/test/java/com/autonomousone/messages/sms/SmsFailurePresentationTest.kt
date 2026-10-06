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
 * The rule this protects (mission §10/§22): there is no portable Android result for "insufficient
 * prepaid balance". A prepaid SIM with no balance and a carrier outage both arrive as
 * `GENERIC_FAILURE`/`NETWORK_REJECT`, so the UI may say the carrier rejected the message and may hint
 * that balance *might* be involved — it may never state a balance problem as fact.
 *
 * NOTE: written and executed as part of this change (pure, no Android runtime).
 */
class SmsFailurePresentationTest {

    /** Every user-visible code in the canonical taxonomy. */
    private val PRESENTED_CODES = listOf(
        "NO_SERVICE", "RADIO_OFF", "RADIO_UNAVAILABLE", "NULL_PDU", "SIM_UNAVAILABLE",
        "NO_DEFAULT_SMS_SUBSCRIPTION", "QUEUE_LIMIT_EXCEEDED", "RIL_RATE_LIMITED",
        "RIL_RETRY_REQUIRED", "NETWORK_REJECTED", "NETWORK_NOT_READY", "NETWORK_ERROR",
        "MODEM_FAILURE", "MODEM_INVALID_STATE", "SYSTEM_ERROR", "NO_RESOURCES",
        "INVALID_SMSC", "INVALID_DESTINATION", "INVALID_SMS_FORMAT", "ENCODING_ERROR",
        "FDN_RESTRICTED", "SHORT_CODE_NOT_ALLOWED", "OPERATION_NOT_ALLOWED",
        "ACCESS_BARRED", "BLOCKED_DUE_TO_CALL", "DISPATCH_REJECTED",
        "CARRIER_REJECTED", "CARRIER_FAILURE_UNKNOWN", "SEND_CALLBACK_TIMEOUT",
        "DELIVERY_REPORT_TIMEOUT", "DELIVERY_UNKNOWN"
    )

    @Test
    fun `a generic failure never claims the balance is the cause`() {
        val copy = SmsFailurePresentation.forCode("CARRIER_FAILURE_UNKNOWN")

        assertFalse(copy.primary.contains("balance", ignoreCase = true))
        assertNotNull(copy.detail)
        assertTrue(
            "balance may only be mentioned as a possible cause",
            copy.actionHint!!.contains("balance", ignoreCase = true)
        )
        assertFalse(copy.primary.contains("zero", ignoreCase = true))
    }

    @Test
    fun `no presentation anywhere asserts a balance problem`() {
        for (code in PRESENTED_CODES) {
            val copy = SmsFailurePresentation.forCode(code)
            for (text in listOfNotNull(copy.primary, copy.detail, copy.actionHint)) {
                assertFalse(
                    "$code asserts a balance problem: $text",
                    text.contains("balance is", ignoreCase = true) ||
                        text.contains("no balance", ignoreCase = true) ||
                        text.contains("insufficient balance", ignoreCase = true) ||
                        text.contains("no credit", ignoreCase = true)
                )
            }
        }
    }

    @Test
    fun `a network rejection says what happened and hints at balance as a possibility`() {
        val copy = SmsFailurePresentation.forCode("NETWORK_REJECTED")

        assertEquals("Network rejected the message", copy.primary)
        assertTrue(copy.actionHint!!.contains("balance", ignoreCase = true))
        assertTrue(copy.actionHint.contains("carrier", ignoreCase = true))
    }

    @Test
    fun `rate limiting is presented as a wait, never as a user error`() {
        val copy = SmsFailurePresentation.forCode("RIL_RATE_LIMITED")

        assertEquals("Too many SMS requests", copy.primary)
        assertTrue(copy.actionHint!!.contains("Wait", ignoreCase = true))
        assertFalse(copy.duplicateRisk)
    }

    @Test
    fun `ambiguous outcomes are flagged as duplicate risks`() {
        for (code in listOf("CARRIER_FAILURE_UNKNOWN", "SEND_CALLBACK_TIMEOUT", "MODEM_FAILURE")) {
            assertTrue(
                "$code must warn about a duplicate",
                SmsFailurePresentation.forCode(code).duplicateRisk
            )
        }
        for (code in listOf("NO_SERVICE", "RADIO_OFF", "SIM_UNAVAILABLE", "INVALID_SMSC")) {
            assertFalse(
                "$code cannot duplicate anything",
                SmsFailurePresentation.forCode(code).duplicateRisk
            )
        }
    }

    @Test
    fun `transport failure and delivery failure read differently`() {
        val transport = SmsFailurePresentation.forCode("NETWORK_REJECTED")
        val delivery = SmsFailurePresentation.deliveryFailedCopy(deliveryStatus = 0x40)

        assertTrue(transport.primary.contains("rejected", ignoreCase = true))
        assertTrue(delivery.primary.startsWith("Sent"))
        assertTrue(delivery.primary.contains("delivery failed", ignoreCase = true))
        assertFalse(delivery.primary == transport.primary)
    }

    @Test
    fun `a missing delivery report is never presented as a failed send`() {
        val copy = SmsFailurePresentation.forCode("DELIVERY_UNKNOWN")

        assertEquals("Sent · Delivery unknown", copy.primary)
        assertTrue(copy.detail!!.contains("never confirmed delivery"))
        assertFalse(copy.primary.contains("failed", ignoreCase = true))
        assertFalse("a missing DLR must not invite a resend", copy.duplicateRisk)
    }

    @Test
    fun `a send timeout is presented as unknown, not as failure`() {
        val copy = SmsFailurePresentation.forCode("SEND_CALLBACK_TIMEOUT")

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

        assertEquals("Too many SMS requests", SmsFailurePresentation.forVerdict(verdict)!!.primary)
        assertEquals(
            null,
            SmsFailurePresentation.forVerdict(SmsTransportClassifier.classify(android.app.Activity.RESULT_OK))
        )
    }

    @Test
    fun `every transport failure has presentation copy`() {
        for (code in PRESENTED_CODES) {
            val copy = SmsFailurePresentation.forCode(code)
            assertTrue("$code needs a primary line", copy.primary.isNotBlank())
            assertFalse(
                "$code fell back to the generic sentence",
                copy.primary == "Message could not be sent"
            )
        }
    }

    @Test
    fun `the classifier's own codes all have copy`() {
        for (code in listOf(
            SmsManager.RESULT_ERROR_NO_SERVICE,
            SmsManager.RESULT_ERROR_RADIO_OFF,
            SmsManager.RESULT_ERROR_NULL_PDU,
            SmsManager.RESULT_ERROR_GENERIC_FAILURE,
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED,
            SmsManager.RESULT_RIL_REQUEST_RATE_LIMITED,
            SmsManager.RESULT_RIL_SMS_SEND_FAIL_RETRY,
            SmsManager.RESULT_RIL_NETWORK_REJECT,
            SmsManager.RESULT_RIL_NETWORK_NOT_READY,
            SmsManager.RESULT_RIL_SIM_ABSENT,
            SmsManager.RESULT_RIL_INVALID_SMSC_ADDRESS
        )) {
            val verdict = SmsTransportClassifier.classify(code)
            assertNotNull("$code must have copy", SmsFailurePresentation.forVerdict(verdict))
        }
    }
}
