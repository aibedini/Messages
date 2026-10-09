package com.autonomousone.messages.sms

import com.autonomousone.messages.data.MessageEntity
import com.autonomousone.messages.data.SegmentCallbackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The durable app-owned send state, and the derivation from ledger evidence to it.
 *
 * ## The defect these tests pin
 *
 * `Telephony.Sms.STATUS_PENDING` means BOTH "still collecting callbacks" and "an ambiguous result
 * arrived". The app used to keep no other record, so after a process death a message whose fate was
 * genuinely unknown came back looking like it was still sending — and a user who then retried could
 * send it twice. Mission §2 requires the ambiguous state to SURVIVE the restart.
 *
 * These tests therefore assert the derivation directly (pure, no Android) and the mapping from
 * persisted state to presentation, including the case that matters most:
 *
 * ```text
 * before restart: SEND_STATUS_UNKNOWN  ->  after restart: SEND_STATUS_UNKNOWN
 * ```
 */
class SendTransportStateTest {

    // ── the derivation ───────────────────────────────────────────────────────

    @Test
    fun `no submission and no callback means the send never reached the radio`() {
        assertEquals(
            SendTransportState.NOT_SENT,
            resolveSendTransportState(emptyList(), submittedPartsCount = 0, partCount = 1)
        )
    }

    @Test
    fun `a submitted part with no callback is still sending`() {
        // The radio took it and we have heard nothing yet. This is the state a process kill lands in,
        // and it must NOT be presented as either a success or a failure.
        assertEquals(
            SendTransportState.SENT_PENDING,
            resolveSendTransportState(
                listOf(SegmentCallbackVerdict.PENDING),
                submittedPartsCount = 1,
                partCount = 1
            )
        )
    }

    @Test
    fun `every part confirmed is sent`() {
        assertEquals(
            SendTransportState.SENT_CONFIRMED,
            resolveSendTransportState(
                listOf(SegmentCallbackVerdict.CONFIRMED, SegmentCallbackVerdict.CONFIRMED),
                submittedPartsCount = 2,
                partCount = 2
            )
        )
    }

    @Test
    fun `a partial confirmation is not yet sent`() {
        // Only the WHOLE body being confirmed makes "Sent" true; one part of a multipart message
        // arriving is not evidence about the others.
        assertEquals(
            SendTransportState.SENT_PENDING,
            resolveSendTransportState(
                listOf(SegmentCallbackVerdict.CONFIRMED, SegmentCallbackVerdict.PENDING),
                submittedPartsCount = 2,
                partCount = 2
            )
        )
    }

    @Test
    fun `an ambiguous part is unknown, not sent and not failed`() {
        // THE case mission §2 names: GENERIC_FAILURE may still have been accepted by the SMSC.
        assertEquals(
            SendTransportState.SENT_AMBIGUOUS,
            resolveSendTransportState(
                listOf(SegmentCallbackVerdict.AMBIGUOUS),
                submittedPartsCount = 1,
                partCount = 1,
                failureCode = SmsSendFailure.CarrierFailureUnknown.code
            )
        )
    }

    @Test
    fun `ambiguity outranks a sibling part that is still pending`() {
        // More waiting cannot turn an already-ambiguous part into a definite answer, so the ambiguous
        // verdict is terminal knowledge and must not decay back to "Sending…".
        assertEquals(
            SendTransportState.SENT_AMBIGUOUS,
            resolveSendTransportState(
                listOf(SegmentCallbackVerdict.AMBIGUOUS, SegmentCallbackVerdict.PENDING),
                submittedPartsCount = 2,
                partCount = 2
            )
        )
    }

    @Test
    fun `a definite refusal is not sent`() {
        assertEquals(
            SendTransportState.NOT_SENT,
            resolveSendTransportState(
                listOf(SegmentCallbackVerdict.FAILED),
                submittedPartsCount = 0,
                partCount = 1,
                failureCode = SmsSendFailure.NoService.code
            )
        )
    }

    @Test
    fun `a definite refusal outranks a confirmed sibling`() {
        // One part permanently refused means the body was not delivered intact, so the logical message
        // is a failure even though another part was accepted.
        assertEquals(
            SendTransportState.NOT_SENT,
            resolveSendTransportState(
                listOf(SegmentCallbackVerdict.CONFIRMED, SegmentCallbackVerdict.FAILED),
                submittedPartsCount = 2,
                partCount = 2
            )
        )
    }

    @Test
    fun `a callback-first row is not mistaken for a refusal`() {
        // The callback can reach the ledger before the submission write commits, leaving submittedAt
        // null on a row that WAS submitted. "Any callback reported" is stronger evidence than the
        // submission count, so this must not read as "never reached the radio".
        assertEquals(
            SendTransportState.SENT_PENDING,
            resolveSendTransportState(
                listOf(SegmentCallbackVerdict.PENDING),
                submittedPartsCount = 0,
                partCount = 1
            )
        )
    }

    @Test
    fun `a zero part count is treated as one part and never divides by zero`() {
        assertEquals(
            SendTransportState.SENT_CONFIRMED,
            resolveSendTransportState(
                listOf(SegmentCallbackVerdict.CONFIRMED),
                submittedPartsCount = 1,
                partCount = 0
            )
        )
    }

    // ── parsing ──────────────────────────────────────────────────────────────

    @Test
    fun `a persisted name round trips`() {
        for (state in SendTransportState.entries) {
            assertEquals(state, SendTransportState.from(state.name))
        }
    }

    @Test
    fun `an unknown persisted value degrades to unknown instead of throwing`() {
        // A value written by a NEWER build must not crash an older one.
        assertEquals(SendTransportState.UNKNOWN, SendTransportState.from(null))
        assertEquals(SendTransportState.UNKNOWN, SendTransportState.from(""))
        assertEquals(SendTransportState.UNKNOWN, SendTransportState.from("SOMETHING_NEW"))
        assertEquals(SendTransportState.UNKNOWN, SendTransportState.from("sent_pending"))
    }

    @Test
    fun `the policy verdict names cannot drift from the ledger enum`() {
        // The restatement in SegmentCallbackVerdict exists to keep transport policy free of a
        // data-layer dependency; the cost of a restatement is drift, so it is closed here.
        assertEquals(
            SegmentCallbackState.entries.map { it.name }.sorted(),
            SegmentCallbackVerdict.entries.map { it.name }.sorted()
        )
    }

    // ── persisted state -> presentation (the post-restart contract) ──────────

    private fun message(
        state: SendTransportState?,
        failureCode: String? = null,
        status: Int = 0
    ) = MessageEntity(
        source = MessageEntity.SOURCE_SMS,
        providerId = 1L,
        threadId = 100L,
        normalizedAddress = "+989120000000",
        rawAddress = "+989120000000",
        body = "hello",
        date = 1L,
        type = 2,
        status = status,
        read = true,
        sendTransportState = state?.name,
        sendFailureCode = failureCode
    )

    private fun present(state: SendTransportState?, failureCode: String? = null, status: Int = 0) =
        SmsStatusPresentationMapper.present(
            SendStateDerivation.evidenceFor(message(state, failureCode, status), isOutgoing = true)
        )

    @Test
    fun `SENDING survives a restart`() {
        assertEquals(SmsUiState.SENDING, present(SendTransportState.SENT_PENDING).state)
    }

    @Test
    fun `SENT survives a restart`() {
        assertEquals(SmsUiState.SENT, present(SendTransportState.SENT_CONFIRMED).state)
    }

    @Test
    fun `NOT_SENT survives a restart`() {
        assertEquals(
            SmsUiState.NOT_SENT,
            present(SendTransportState.NOT_SENT, SmsSendFailure.NoService.code).state
        )
    }

    @Test
    fun `SEND_STATUS_UNKNOWN survives a restart`() {
        // The mission §2 assertion, verbatim: an ambiguous generic failure must come back ambiguous,
        // NOT Sent, NOT Sending and NOT Not sent.
        val after = present(
            SendTransportState.SENT_AMBIGUOUS,
            SmsSendFailure.CarrierFailureUnknown.code
        )

        assertEquals(SmsUiState.SEND_STATUS_UNKNOWN, after.state)
        assertTrue("the user must be warned a retry could duplicate", after.duplicateRisk)
        assertTrue(after.canRetry)
    }

    @Test
    fun `SEND_STATUS_UNKNOWN does not decay when no cause code was recorded`() {
        // Absence of a cause is not evidence of success: a code-less ambiguous row must stay ambiguous.
        assertEquals(SmsUiState.SEND_STATUS_UNKNOWN, present(SendTransportState.SENT_AMBIGUOUS).state)
    }

    @Test
    fun `a confirmed send with no delivery evidence is SENT, not DELIVERY_UNKNOWN`() {
        // Absence of evidence is not evidence of absence. The mapper reports DELIVERY_UNKNOWN only on
        // POSITIVE proof that a report was awaited and never became conclusive; defaulting to it would
        // label every ordinary message "Delivery unknown".
        val after = SmsStatusPresentationMapper.present(
            SendStateDerivation.evidenceFor(
                message(SendTransportState.SENT_CONFIRMED),
                isOutgoing = true
            )
        )

        assertEquals(SmsUiState.SENT, after.state)
    }

    @Test
    fun `explicit delivery-unknown evidence is honoured`() {
        // The mapper's own contract, reached through this derivation: when a caller HAS the proof, the
        // state is reported.
        val after = SmsStatusPresentationMapper.present(
            SendStateDerivation.evidenceFor(
                message(SendTransportState.SENT_CONFIRMED),
                isOutgoing = true,
                deliveryUnknown = true
            )
        )

        assertEquals(SmsUiState.DELIVERY_UNKNOWN, after.state)
    }

    @Test
    fun `a delivery report upgrades a confirmed send to DELIVERED`() {
        val after = SmsStatusPresentationMapper.present(
            SendStateDerivation.evidenceFor(
                message(SendTransportState.SENT_CONFIRMED),
                isOutgoing = true,
                hasDeliveryConfirmation = true
            )
        )

        assertEquals(SmsUiState.DELIVERED, after.state)
    }

    @Test
    fun `a delivery failure on a confirmed send is NOT_DELIVERED`() {
        val after = SmsStatusPresentationMapper.present(
            SendStateDerivation.evidenceFor(
                message(SendTransportState.SENT_CONFIRMED),
                isOutgoing = true,
                hasDeliveryFailure = true
            )
        )

        // The transport half succeeded and the DELIVERY half did not. Reporting that as NOT_SENT would
        // tell the user the phone failed to send a message the network actually received.
        assertEquals(SmsUiState.NOT_DELIVERED, after.state)
    }

    @Test
    fun `a historical row with no app state still reads its provider status`() {
        // Every existing install migrates to a NULL verdict, and those messages must keep rendering
        // exactly as before rather than all becoming "unknown".
        assertEquals(
            SmsUiState.SEND_STATUS_UNKNOWN,
            present(null, status = 128).state
        )
        assertEquals(SmsUiState.SENDING, present(null, status = 32).state)
        assertEquals(SmsUiState.SENDING, present(null, status = 64).state)
    }

    @Test
    fun `transport state never overrides delivery evidence`() {
        // The two halves stay separate: a candidate "Sent" cannot mask a carrier delivery failure, and
        // an ambiguous transport verdict cannot be upgraded by nothing at all.
        val failedDelivery = SmsStatusPresentationMapper.present(
            SendStateDerivation.evidenceFor(
                message(SendTransportState.SENT_PENDING),
                isOutgoing = true,
                hasDeliveryFailure = true
            )
        )

        assertEquals(SmsUiState.NOT_DELIVERED, failedDelivery.state)
        assertFalse(
            "an ambiguous transport verdict must not become a delivery claim",
            SendStateDerivation.evidenceFor(
                message(SendTransportState.SENT_AMBIGUOUS, SmsSendFailure.CarrierFailureUnknown.code),
                isOutgoing = true
            ).hasSentConfirmation
        )
    }

    @Test
    fun `an inbound message carries no send state`() {
        val evidence = SendStateDerivation.evidenceFor(
            message(SendTransportState.SENT_CONFIRMED),
            isOutgoing = false
        )

        assertFalse(evidence.isOutgoing)
        assertEquals("", SmsStatusPresentationMapper.present(evidence).label)
    }

    @Test
    fun `a blank failure code is treated as absent, never as a cause`() {
        val evidence = SendStateDerivation.evidenceFor(
            message(SendTransportState.SENT_AMBIGUOUS, failureCode = "   "),
            isOutgoing = true
        )

        assertNull(evidence.failureCode?.takeIf { it.isBlank() })
        assertEquals(
            SmsUiState.SEND_STATUS_UNKNOWN,
            SmsStatusPresentationMapper.present(evidence).state
        )
    }
}
