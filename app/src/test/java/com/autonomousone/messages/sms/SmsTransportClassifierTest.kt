package com.autonomousone.messages.sms

import android.telephony.SmsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The result-code classification matrix.
 *
 * Why this exists: on the real phone a burst of SMS started failing and every later message kept
 * failing. The result codes were logged as `CUSTOM_<n>` (or not at all), so a radio rate limit was
 * indistinguishable from a carrier refusal or a balance problem. These tests pin the interpretation of
 * every code we can act on — including the two that mean "slow down" (`5` and `106`) and the generic
 * one that must stay ambiguous, because retrying it blindly could duplicate a message that was already
 * sent.
 *
 * NOTE: written and executed as part of this change (pure, no Android runtime).
 */
class SmsTransportClassifierTest {

    private fun classify(code: Int, radioErrorCode: Int? = null) =
        SmsTransportClassifier.classify(code, radioErrorCode)

    // ── success ──────────────────────────────────────────────────────────────

    @Test
    fun `RESULT_OK is confirmed success`() {
        val verdict = classify(android.app.Activity.RESULT_OK)

        assertTrue(verdict.isSuccess)
        assertNull(verdict.failure)
        assertEquals(SendEvidence.CONFIRMED, verdict.evidence)
        assertEquals(RetrySafety.NOT_APPLICABLE, verdict.retrySafety)
        assertFalse(verdict.shouldThrottle)
    }

    // ── rate limiting: the codes this incident is most likely made of ────────

    @Test
    fun `queue limit exceeded is throttling, not a plain failure`() {
        val verdict = classify(SmsManager.RESULT_ERROR_LIMIT_EXCEEDED)

        assertEquals(SmsTransportFailure.QUEUE_LIMIT_EXCEEDED, verdict.failure)
        assertTrue("the gate must open a cooldown", verdict.shouldThrottle)
        assertEquals(RetrySafety.WAIT, verdict.retrySafety)
        assertEquals(SendEvidence.REJECTED, verdict.evidence)
    }

    @Test
    fun `RIL rate limited is throttling and keeps its symbolic name`() {
        val verdict = classify(SmsManager.RESULT_RIL_REQUEST_RATE_LIMITED, radioErrorCode = 42)

        assertEquals(SmsTransportFailure.RIL_RATE_LIMITED, verdict.failure)
        assertTrue(verdict.shouldThrottle)
        assertEquals(RetrySafety.WAIT, verdict.retrySafety)
        assertEquals("RESULT_RIL_REQUEST_RATE_LIMITED", verdict.resultCodeName)
        assertEquals(42, verdict.radioErrorCode)
    }

    @Test
    fun `the rate-limit codes are never reported as unknown vendor codes`() {
        assertEquals("RESULT_RIL_REQUEST_RATE_LIMITED", SmsResultCodes.name(106))
        assertTrue(SmsResultCodes.isKnown(SmsManager.RESULT_RIL_REQUEST_RATE_LIMITED))
        assertFalse(SmsResultCodes.name(SmsManager.RESULT_ERROR_LIMIT_EXCEEDED).startsWith("UNKNOWN"))
    }

    // ── retry requested by the radio ────────────────────────────────────────

    @Test
    fun `RIL retry required is its own code, not a modem failure`() {
        val verdict = classify(SmsManager.RESULT_RIL_SMS_SEND_FAIL_RETRY)

        assertEquals(SmsTransportFailure.RIL_RETRY_REQUIRED, verdict.failure)
        assertEquals(SendEvidence.REJECTED, verdict.evidence)
        assertEquals(RetrySafety.WAIT, verdict.retrySafety)
    }

    // ── definite pre-acceptance refusals: a retry cannot duplicate ──────────

    @Test
    fun `no service, radio off and null pdu are definite failures`() {
        assertEquals(SmsTransportFailure.NO_SERVICE, classify(SmsManager.RESULT_ERROR_NO_SERVICE).failure)
        assertEquals(SmsTransportFailure.RADIO_OFF, classify(SmsManager.RESULT_ERROR_RADIO_OFF).failure)
        assertEquals(SmsTransportFailure.NULL_PDU, classify(SmsManager.RESULT_ERROR_NULL_PDU).failure)
        for (code in listOf(
            SmsManager.RESULT_ERROR_NO_SERVICE,
            SmsManager.RESULT_ERROR_RADIO_OFF,
            SmsManager.RESULT_ERROR_NULL_PDU
        )) {
            assertEquals("code $code must be safe to retry", RetrySafety.SAFE, classify(code).retrySafety)
            assertEquals(SendEvidence.REJECTED, classify(code).evidence)
        }
    }

    @Test
    fun `SIM, smsc and FDN problems map to their own codes`() {
        assertEquals(SmsTransportFailure.SIM_UNAVAILABLE, classify(SmsManager.RESULT_RIL_SIM_ABSENT).failure)
        assertEquals(
            SmsTransportFailure.INVALID_SMSC,
            classify(SmsManager.RESULT_RIL_INVALID_SMSC_ADDRESS).failure
        )
        assertEquals(
            SmsTransportFailure.FDN_RESTRICTED,
            classify(SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE).failure
        )
        assertEquals(
            SmsTransportFailure.SHORT_CODE_NOT_ALLOWED,
            classify(SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED).failure
        )
    }

    // ── network level ───────────────────────────────────────────────────────

    @Test
    fun `network reject and network not ready are distinct and wait-worthy`() {
        assertEquals(
            SmsTransportFailure.NETWORK_REJECTED,
            classify(SmsManager.RESULT_RIL_NETWORK_REJECT).failure
        )
        assertEquals(
            SmsTransportFailure.NETWORK_NOT_READY,
            classify(SmsManager.RESULT_RIL_NETWORK_NOT_READY).failure
        )
        assertEquals(RetrySafety.WAIT, classify(SmsManager.RESULT_RIL_NETWORK_REJECT).retrySafety)
    }

    @Test
    fun `modem and network errors stay ambiguous so a retry warns about duplicates`() {
        for (code in listOf(SmsManager.RESULT_RIL_MODEM_ERR, SmsManager.RESULT_RIL_NETWORK_ERR)) {
            val verdict = classify(code)
            assertEquals(SendEvidence.AMBIGUOUS, verdict.evidence)
            assertEquals(RetrySafety.POSSIBLE_DUPLICATE, verdict.retrySafety)
        }
        assertEquals(SmsTransportFailure.MODEM_ERROR, classify(SmsManager.RESULT_RIL_MODEM_ERR).failure)
        assertEquals(SmsTransportFailure.NETWORK_ERROR, classify(SmsManager.RESULT_RIL_NETWORK_ERR).failure)
    }

    // ── the generic result must never become a verdict we do not have ───────

    @Test
    fun `GENERIC_FAILURE is ambiguous carrier-unknown, never a definite failure`() {
        val verdict = classify(SmsManager.RESULT_ERROR_GENERIC_FAILURE, radioErrorCode = 34)

        assertEquals(SmsTransportFailure.CARRIER_FAILURE_UNKNOWN, verdict.failure)
        assertEquals(SendEvidence.AMBIGUOUS, verdict.evidence)
        assertEquals(RetrySafety.POSSIBLE_DUPLICATE, verdict.retrySafety)
        assertEquals(34, verdict.radioErrorCode)
    }

    @Test
    fun `an unrecognised vendor code is ambiguous, not invented`() {
        val verdict = classify(9_999)

        assertEquals(SmsTransportFailure.CARRIER_FAILURE_UNKNOWN, verdict.failure)
        assertEquals(SendEvidence.AMBIGUOUS, verdict.evidence)
        assertEquals("UNKNOWN_RESULT_9999", verdict.resultCodeName)
    }

    // ── the mapper itself ───────────────────────────────────────────────────

    @Test
    fun `every recognised code has a stable symbolic name`() {
        val entries = SmsResultCodes.entries()

        assertTrue("the table must not be empty", entries.size > 20)
        assertEquals("codes must be unique", entries.size, entries.map { it.first }.toSet().size)
        for ((code, name) in entries) {
            assertTrue("$code must have a name", name.isNotBlank())
            assertFalse("$name must not be a CUSTOM_ placeholder", name.startsWith("CUSTOM_"))
            assertEquals(name, SmsResultCodes.name(code))
            assertTrue(SmsResultCodes.isKnown(code))
        }
    }

    @Test
    fun `the RIL family is present by name, not by number`() {
        for (expected in listOf(
            "RESULT_RIL_RADIO_NOT_AVAILABLE", "RESULT_RIL_SMS_SEND_FAIL_RETRY", "RESULT_RIL_NETWORK_REJECT",
            "RESULT_RIL_REQUEST_RATE_LIMITED", "RESULT_RIL_MODEM_ERR", "RESULT_RIL_NETWORK_ERR",
            "RESULT_RIL_SIM_ABSENT", "RESULT_RIL_ACCESS_BARRED", "RESULT_RIL_BLOCKED_DUE_TO_CALL"
        )) {
            assertTrue(
                "$expected must be mapped",
                SmsResultCodes.entries().any { it.second == expected }
            )
        }
    }

    @Test
    fun `every failure carries a persistable stable code`() {
        val codes = SmsTransportFailure.entries.map { it.persistable }

        assertEquals(codes.size, codes.toSet().size)
        assertTrue(codes.all { it.isNotBlank() && it == it.uppercase() })
        assertNotNull(SmsTransportFailure.SEND_CALLBACK_TIMEOUT.persistable)
        assertNotNull(SmsTransportFailure.DELIVERY_UNKNOWN.persistable)
    }
}
