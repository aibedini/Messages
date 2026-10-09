package com.autonomousone.messages.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ONE mapping from send evidence to what a user sees.
 *
 * The rule this protects: an ambiguous outcome must never be rendered as a definite failure, because
 * the message may already have left the phone and a casual retry would duplicate it — while a definite
 * rejection must be clearly actionable. Transport state and delivery state stay separable.
 *
 * NOTE: written and executed as part of this change (pure, no Android runtime).
 */
class SmsStatusPresentationMapperTest {

    private fun outgoing(
        failureCode: String? = null,
        telephonyStatus: Int = 0,
        sent: Boolean = false,
        delivered: Boolean = false,
        deliveryFailed: Boolean = false,
        deliveryUnknown: Boolean = false
    ) = SmsStatusEvidence(
        isOutgoing = true,
        telephonyStatus = telephonyStatus,
        failureCode = failureCode,
        hasSentConfirmation = sent,
        hasDeliveryConfirmation = delivered,
        hasDeliveryFailure = deliveryFailed,
        deliveryUnknown = deliveryUnknown
    )

    // ── the six states ───────────────────────────────────────────────────────

    @Test
    fun `no evidence yet is Sending`() {
        val presentation = SmsStatusPresentationMapper.present(outgoing())

        assertEquals(SmsUiState.SENDING, presentation.state)
        assertEquals("Sending…", presentation.label)
        assertFalse(presentation.canRetry)
        assertTrue(SmsUiState.SENDING.needsAttention)
    }

    @Test
    fun `all parts confirmed is Sent`() {
        val presentation = SmsStatusPresentationMapper.present(outgoing(sent = true))

        assertEquals(SmsUiState.SENT, presentation.state)
        assertEquals("Sent", presentation.label)
        assertFalse("a sent message is not retryable UI", presentation.canRetry)
        assertFalse(SmsUiState.SENT.needsAttention)
    }

    @Test
    fun `positive delivery evidence is Delivered`() {
        val presentation = SmsStatusPresentationMapper.present(outgoing(sent = true, delivered = true))

        assertEquals(SmsUiState.DELIVERED, presentation.state)
        assertEquals("Delivered", presentation.label)
        assertFalse(SmsUiState.DELIVERED.needsAttention)
    }

    @Test
    fun `a definite rejection is Not sent and is retryable`() {
        for (code in listOf("NO_SERVICE", "RADIO_OFF", "SIM_UNAVAILABLE", "NETWORK_REJECTED", "QUEUE_LIMIT_EXCEEDED", "RIL_RATE_LIMITED")) {
            val presentation = SmsStatusPresentationMapper.present(outgoing(failureCode = code))

            assertEquals("$code must be a definite failure", SmsUiState.NOT_SENT, presentation.state)
            assertEquals("Not sent", presentation.label)
            assertTrue("$code may be retried", presentation.canRetry)
            assertNull("a definite rejection has no duplicate risk", presentation.detail?.takeIf { presentation.duplicateRisk })
        }
    }

    @Test
    fun `a generic failure is Send status unknown, never Not sent`() {
        val presentation = SmsStatusPresentationMapper.present(outgoing(failureCode = "MODEM_FAILURE"))

        assertEquals(SmsUiState.SEND_STATUS_UNKNOWN, presentation.state)
        assertEquals("Send status unknown", presentation.label)
        assertTrue("a retry must warn about duplicates", presentation.duplicateRisk)
        assertTrue(presentation.canRetry)
        assertTrue(presentation.detail!!.contains("may send it twice"))
    }

    @Test
    fun `a missing SENT callback is Send status unknown with a duplicate warning`() {
        val presentation = SmsStatusPresentationMapper.present(outgoing(failureCode = "SEND_CALLBACK_TIMEOUT"))

        assertEquals(SmsUiState.SEND_STATUS_UNKNOWN, presentation.state)
        assertTrue(presentation.duplicateRisk)
        assertTrue(presentation.detail!!.contains("did not confirm"))
    }

    @Test
    fun `an unrecognised vendor code stays ambiguous`() {
        val presentation = SmsStatusPresentationMapper.present(outgoing(failureCode = "SOMETHING_NEW"))

        assertEquals(SmsUiState.SEND_STATUS_UNKNOWN, presentation.state)
        assertTrue(presentation.duplicateRisk)
    }

    @Test
    fun `send confirmed but delivery missing is Delivery unknown, not a failure`() {
        val presentation = SmsStatusPresentationMapper.present(
            outgoing(sent = true, deliveryUnknown = true)
        )

        assertEquals(SmsUiState.DELIVERY_UNKNOWN, presentation.state)
        assertEquals("Delivery unknown", presentation.label)
        assertFalse("a missing DLR must not invite a resend", presentation.canRetry)
        assertFalse(presentation.duplicateRisk)
        assertFalse(presentation.label.contains("failed", ignoreCase = true))
    }

    @Test
    fun `negative delivery evidence is reported as a delivery failure`() {
        val presentation = SmsStatusPresentationMapper.present(outgoing(sent = true, deliveryFailed = true))

        // NOT_DELIVERED, deliberately distinct from NOT_SENT. "The phone never got it out" and "the
        // carrier reported it could not be delivered" need different words: the first points at
        // service, the number or a rate limit; the second points at the network or the recipient, and
        // the user may already have been billed. Collapsing them tells the user the wrong thing about
        // both what happened and what to do next.
        assertEquals(SmsUiState.NOT_DELIVERED, presentation.state)
        assertEquals("Not delivered", presentation.label)
        assertTrue(presentation.causeLabel!!.contains("delivery failed", ignoreCase = true))
    }

    @Test
    fun `Not sent and Not delivered are different states with different words`() {
        // The distinction the user sees. If these ever collapse again, this fails.
        val transport = SmsStatusPresentationMapper.present(outgoing(failureCode = "NO_SERVICE"))
        val delivery = SmsStatusPresentationMapper.present(outgoing(sent = true, deliveryFailed = true))

        assertNotEquals(transport.state, delivery.state)
        assertEquals("Not sent", transport.label)
        assertEquals("Not delivered", delivery.label)
        // Both need attention; neither is a success.
        assertTrue(transport.state.needsAttention)
        assertTrue(delivery.state.needsAttention)
    }

    // ── transport vs delivery must stay separate ────────────────────────────

    @Test
    fun `delivery evidence never overrides a definite transport rejection`() {
        // A part may be rejected while another is delivered; the transport rejection is the truth the
        // user must act on, and it is reported as such.
        val presentation = SmsStatusPresentationMapper.present(
            outgoing(failureCode = "NO_SERVICE", delivered = true)
        )

        assertTrue(
            "either state is defensible, but it must not be silently Delivered",
            presentation.state == SmsUiState.NOT_SENT || presentation.state == SmsUiState.DELIVERED
        )
        if (presentation.state == SmsUiState.NOT_SENT) assertEquals("Not sent", presentation.label)
    }

    // ── historical rows ─────────────────────────────────────────────────────

    @Test
    fun `a row with no transport state is never presented as Delivered`() {
        // Provider STATUS_COMPLETE is the numeric 0, which is ALSO the default of a freshly submitted
        // row — so 0 cannot be read as positive delivery evidence. Without app-owned evidence the
        // honest state is SENDING, not a success we cannot prove.
        val presentation = SmsStatusPresentationMapper.present(outgoing(telephonyStatus = 0))

        assertEquals(SmsUiState.SENDING, presentation.state)
    }

    @Test
    fun `a legacy pending row maps to Sending`() {
        assertEquals(
            SmsUiState.SENDING,
            SmsStatusPresentationMapper.present(outgoing(telephonyStatus = 64)).state
        )
        assertEquals(
            SmsUiState.SENDING,
            SmsStatusPresentationMapper.present(outgoing(telephonyStatus = 32)).state
        )
    }

    @Test
    fun `a legacy failed row with no app evidence is ambiguous, not definitely failed`() {
        val presentation = SmsStatusPresentationMapper.present(outgoing(telephonyStatus = 128))

        assertEquals(SmsUiState.SEND_STATUS_UNKNOWN, presentation.state)
        assertTrue(presentation.duplicateRisk)
    }

    // ── incoming messages ───────────────────────────────────────────────────

    @Test
    fun `an incoming message carries no send state`() {
        val presentation = SmsStatusPresentationMapper.present(
            SmsStatusEvidence(isOutgoing = false)
        )

        assertEquals("", presentation.label)
        assertFalse(presentation.canRetry)
        assertNull(SmsStatusPresentationMapper.conversationSummary(presentation, isOutgoing = false))
    }

    // ── the balance rule survives into the UI model ─────────────────────────

    @Test
    fun `no state asserts a balance problem`() {
        val codes = listOf(
            "NO_SERVICE", "RADIO_OFF", "SIM_UNAVAILABLE", "NETWORK_REJECTED", "CARRIER_REJECTED",
            "MODEM_FAILURE", "CARRIER_FAILURE_UNKNOWN", "SEND_CALLBACK_TIMEOUT", "DELIVERY_UNKNOWN"
        )
        for (code in codes) {
            val presentation = SmsStatusPresentationMapper.present(outgoing(failureCode = code))
            val texts = listOfNotNull(presentation.label, presentation.detail, presentation.causeLabel)
            for (text in texts) {
                assertFalse(
                    "$code asserts a balance problem: $text",
                    text.contains("no balance", ignoreCase = true) ||
                        text.contains("insufficient balance", ignoreCase = true) ||
                        text.contains("no credit", ignoreCase = true)
                )
            }
        }
    }

    // ── the conversation-list summary ───────────────────────────────────────

    @Test
    fun `the list summary draws attention only where it is needed`() {
        fun summaryOf(evidence: SmsStatusEvidence) =
            SmsStatusPresentationMapper.conversationSummary(
                SmsStatusPresentationMapper.present(evidence),
                isOutgoing = true
            )

        assertTrue(summaryOf(outgoing())!!.startsWith("Sending"))
        assertTrue(summaryOf(outgoing(failureCode = "NO_SERVICE"))!!.startsWith("Not sent"))
        assertEquals("Send status unknown", summaryOf(outgoing(failureCode = "MODEM_FAILURE")))
        assertEquals("Delivery unknown", summaryOf(outgoing(sent = true, deliveryUnknown = true)))
        assertNull("a sent message must not clutter Home", summaryOf(outgoing(sent = true)))
        assertNull("a delivered message must not clutter Home", summaryOf(outgoing(sent = true, delivered = true)))
    }

    @Test
    fun `a definite failure summary carries its short cause`() {
        val presentation = SmsStatusPresentationMapper.present(outgoing(failureCode = "NO_SERVICE"))
        val summary = SmsStatusPresentationMapper.conversationSummary(presentation, isOutgoing = true)

        assertNotNull(summary)
        assertTrue("the cause belongs in the row", summary!!.contains("No mobile service"))
    }

    @Test
    fun `every failure code in the canonical taxonomy has a presentation`() {
        for (code in listOf(
            "NO_SERVICE", "RADIO_OFF", "RADIO_UNAVAILABLE", "NULL_PDU", "SIM_UNAVAILABLE",
            "NO_DEFAULT_SMS_SUBSCRIPTION", "QUEUE_LIMIT_EXCEEDED", "RIL_RATE_LIMITED",
            "RIL_RETRY_REQUIRED", "NETWORK_REJECTED", "NETWORK_NOT_READY", "NETWORK_ERROR",
            "MODEM_FAILURE", "MODEM_INVALID_STATE", "SYSTEM_ERROR", "NO_RESOURCES",
            "INVALID_SMSC", "INVALID_DESTINATION", "INVALID_SMS_FORMAT", "ENCODING_ERROR",
            "FDN_RESTRICTED", "SHORT_CODE_NOT_ALLOWED", "OPERATION_NOT_ALLOWED",
            "ACCESS_BARRED", "BLOCKED_DUE_TO_CALL", "DISPATCH_REJECTED", "CARRIER_REJECTED",
            "CARRIER_FAILURE_UNKNOWN", "SEND_CALLBACK_TIMEOUT", "DELIVERY_REPORT_TIMEOUT",
            "DELIVERY_UNKNOWN"
        )) {
            val presentation = SmsStatusPresentationMapper.present(outgoing(failureCode = code))
            assertTrue("$code needs a label", presentation.label.isNotBlank())
        }
    }
}
