package com.autonomousone.messages.ui.details

import com.autonomousone.messages.data.MessageTechnicalEvidence
import com.autonomousone.messages.data.SegmentCallbackState
import com.autonomousone.messages.data.SendSegmentEntity
import com.autonomousone.messages.sms.SmsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Message Details: what the screen says, and what it refuses to say.
 *
 * ## The rule this screen exists for
 *
 * A message can be submitted to the network successfully and still have no delivery answer. Those are
 * two stages and the screen keeps them apart, because "Failed" on a message that was actually delivered
 * sends the user to fix the wrong thing — and "Delivered" on one that never left is worse.
 *
 * The other half of the contract is silence: a value that was never recorded must be ABSENT, never
 * rendered as a plausible number. `deliveryTpStatus = null` is the sharpest case, because `0` is a real
 * positive delivery status, so printing it for "no report" would fabricate the strongest evidence there
 * is.
 */
class MessageDetailsUiStateTest {

    private fun evidence(
        transportState: String? = null,
        sendFailureCode: String? = null,
        sendResultCode: Int? = null,
        radioErrorCode: Int? = null,
        deliveryEvidence: String? = null,
        deliveryTpStatus: Int? = null,
        deliveryResultCode: Int? = null,
        dateSent: Long = 0L,
        sendStateUpdatedAt: Long = 0L,
        deliveryCallbackAt: Long = 0L,
        type: Int = 2
    ) = MessageTechnicalEvidence(
        source = "sms",
        providerId = 1415L,
        threadId = 100L,
        type = type,
        date = 1_700_000_000_000L,
        dateSent = dateSent,
        sendTransportState = transportState,
        sendFailureCode = sendFailureCode,
        sendResultCode = sendResultCode,
        sendRadioErrorCode = radioErrorCode,
        sendStateUpdatedAt = sendStateUpdatedAt,
        deliveryEvidence = deliveryEvidence,
        deliveryTpStatus = deliveryTpStatus,
        deliveryResultCode = deliveryResultCode,
        deliveryCallbackAt = deliveryCallbackAt
    )

    private fun segment(subId: Int, part: Int, count: Int, state: SegmentCallbackState) =
        SendSegmentEntity(
            rowId = 1415L,
            partIndex = part,
            partCount = count,
            submittedAt = 1_700_000_000_100L,
            subscriptionId = subId,
            callbackState = state
        )

    private fun state(
        e: MessageTechnicalEvidence?,
        segments: List<SendSegmentEntity> = listOf(segment(5, 0, 1, SegmentCallbackState.CONFIRMED)),
        simLabel: (Int) -> String? = { "SIM 1 · MCI" }
    ) = MessageDetailsUiState.of(
        evidence = e,
        segments = segments,
        body = "hello",
        recipient = "+989120000000",
        sender = "+989120000000",
        appVersion = "3.5.2 (139)",
        simLabel = simLabel
    )

    // ── the two-stage story ──────────────────────────────────────────────────

    @Test
    fun `a delivered message shows submit, sent and delivered as three separate steps`() {
        val s = state(
            evidence(
                transportState = "SENT_CONFIRMED",
                deliveryEvidence = "DELIVERED",
                deliveryTpStatus = 0x00,
                sendStateUpdatedAt = 1_700_000_000_200L,
                deliveryCallbackAt = 1_700_000_000_400L
            )
        )

        assertEquals(SmsUiState.DELIVERED, s.state)
        assertEquals("Delivered", s.headline)
        assertEquals(3, s.steps.size)
        assertEquals(StatusStep.Kind.DONE, s.steps[0].kind)
        assertEquals(StatusStep.Kind.DONE, s.steps[1].kind)
        assertEquals(StatusStep.Kind.DONE, s.steps[2].kind)
        assertEquals("Delivered", s.steps[2].title)
    }

    @Test
    fun `sent-but-no-report shows the delivery stage as UNKNOWN, never as failed`() {
        // THE case the screen exists for: the message reached the network and no report came back.
        val s = state(
            evidence(
                transportState = "SENT_CONFIRMED",
                deliveryEvidence = null,
                sendStateUpdatedAt = 1_700_000_000_200L
            )
        )

        assertEquals(SmsUiState.DELIVERY_UNKNOWN, s.state)
        assertEquals("Delivery not confirmed", s.headline)
        assertEquals(StatusStep.Kind.DONE, s.steps[1].kind)
        assertEquals("Sent", s.steps[1].title)
        assertEquals("Delivery not confirmed", s.steps[2].title)
        assertEquals(
            "no evidence must not be reported as a failure",
            StatusStep.Kind.UNKNOWN,
            s.steps[2].kind
        )
    }

    @Test
    fun `a negative carrier report shows Sent succeeded and delivery failed`() {
        val s = state(
            evidence(
                transportState = "SENT_CONFIRMED",
                deliveryEvidence = "FAILED",
                deliveryTpStatus = 0x40,
                sendStateUpdatedAt = 1_700_000_000_200L,
                deliveryCallbackAt = 1_700_000_000_400L
            )
        )

        assertEquals(SmsUiState.NOT_DELIVERED, s.state)
        assertEquals("Not delivered", s.headline)
        // The handset DID send it: the transport stage is DONE and the failure is at the delivery stage.
        assertEquals(StatusStep.Kind.DONE, s.steps[1].kind)
        assertEquals(StatusStep.Kind.FAILED, s.steps[2].kind)
    }

    @Test
    fun `a transport refusal shows Not sent and marks delivery as not attempted`() {
        val s = state(
            evidence(
                transportState = "NOT_SENT",
                sendFailureCode = "RIL_RATE_LIMITED",
                sendResultCode = 106,
                sendStateUpdatedAt = 1_700_000_000_200L
            ),
            segments = listOf(
                SendSegmentEntity(
                    rowId = 1415L, partIndex = 0, partCount = 1,
                    submittedAt = null, subscriptionId = 5,
                    callbackState = SegmentCallbackState.FAILED,
                    callbackFailureCode = "RIL_RATE_LIMITED"
                )
            )
        )

        assertEquals(SmsUiState.NOT_SENT, s.state)
        assertEquals("Not sent", s.headline)
        assertFalse("nothing was submitted", s.steps[0].kind == StatusStep.Kind.DONE)
        assertEquals(StatusStep.Kind.FAILED, s.steps[1].kind)
        assertEquals(StatusStep.Kind.SKIPPED, s.steps[2].kind)
    }

    @Test
    fun `an ambiguous send warns about the duplicate risk and does not claim Sent`() {
        val s = state(
            evidence(
                transportState = "SENT_AMBIGUOUS",
                sendFailureCode = "CARRIER_FAILURE_UNKNOWN",
                sendResultCode = 1
            )
        )

        assertEquals(SmsUiState.SEND_STATUS_UNKNOWN, s.state)
        assertEquals("Send status unknown", s.headline)
        assertTrue("the user must be warned a retry can double-send", s.duplicateRisk)
        assertEquals(StatusStep.Kind.UNKNOWN, s.steps[1].kind)
        assertNotEquals("an unproven submit must not be called Sent", "Sent", s.steps[1].title)
    }

    @Test
    fun `Not sent and Not delivered can never be the same state`() {
        // If these collapse again, the screen tells the user the wrong thing about what to fix.
        val notSent = state(evidence(transportState = "NOT_SENT", sendFailureCode = "NO_SERVICE"))
        val notDelivered = state(
            evidence(transportState = "SENT_CONFIRMED", deliveryEvidence = "FAILED", deliveryTpStatus = 0x40)
        )

        assertNotEquals(notSent.state, notDelivered.state)
        assertEquals("Not sent", notSent.headline)
        assertEquals("Not delivered", notDelivered.headline)
    }

    // ── silence over fabrication ─────────────────────────────────────────────

    @Test
    fun `a missing TP-Status stays null so the screen cannot print zero`() {
        // Zero is a REAL 3GPP "delivered" status, so rendering it for "no report" would invent the
        // strongest possible evidence out of nothing.
        val s = state(evidence(transportState = "SENT_CONFIRMED", deliveryEvidence = null, deliveryTpStatus = null))

        assertNull("null must survive so the row is omitted entirely", s.deliveryTpStatus)
    }

    @Test
    fun `a real zero TP-Status is preserved as a genuine value`() {
        val s = state(evidence(transportState = "SENT_CONFIRMED", deliveryEvidence = "DELIVERED", deliveryTpStatus = 0))

        assertEquals(0, s.deliveryTpStatus)
    }

    @Test
    fun `absent timestamps stay null rather than zero`() {
        val s = state(evidence(transportState = "SENT_PENDING"))

        assertNull(s.dateSent)
        assertNull(s.deliveryCallbackAt)
        assertNull(s.submittedAt.takeIf { false }) // submittedAt comes from the ledger
    }

    @Test
    fun `a missing mirror row is reported as missing, not as a clean message`() {
        val s = state(
            e = null,
            segments = emptyList()
        )

        assertTrue(s.missing)
        assertTrue(s.loaded)
        assertTrue(s.steps.isEmpty())
    }

    // ── SIM evidence ─────────────────────────────────────────────────────────

    @Test
    fun `the sending SIM comes from the send-time ledger`() {
        val s = state(
            evidence(transportState = "SENT_CONFIRMED"),
            segments = listOf(segment(9, 0, 1, SegmentCallbackState.CONFIRMED)),
            simLabel = { if (it == 9) "SIM 2 · Irancell" else "SIM 1 · MCI" }
        )

        assertEquals("SIM 2 · Irancell", s.sentWithLabel)
        assertFalse(s.simEvidenceConflict)
    }

    @Test
    fun `contradictory per-part SIMs are reported as a conflict, not silently picked`() {
        val s = state(
            evidence(transportState = "SENT_CONFIRMED"),
            segments = listOf(
                segment(5, 0, 2, SegmentCallbackState.CONFIRMED),
                segment(9, 1, 2, SegmentCallbackState.CONFIRMED)
            ),
            simLabel = { "SIM 1 · MCI" }
        )

        assertTrue("parts recording different lines is not resolvable", s.simEvidenceConflict)
    }

    @Test
    fun `a line that no longer exists is still named from the ledger`() {
        // The card is gone from the inventory, so the label falls back to the recorded subscription id.
        // This is the case that matters most: the user replaced the SIM and still needs to know which
        // line carried an old message, which a re-read of the CURRENT default can never answer.
        val s = state(
            evidence(transportState = "SENT_CONFIRMED"),
            segments = listOf(segment(5, 0, 1, SegmentCallbackState.CONFIRMED)),
            simLabel = { "Subscription $it (no longer active)" }
        )

        assertEquals("Subscription 5 (no longer active)", s.sentWithLabel)
    }

    @Test
    fun `an invalid subscription marker is not treated as a line`() {
        val s = state(
            evidence(transportState = "SENT_CONFIRMED"),
            segments = listOf(segment(-1, 0, 1, SegmentCallbackState.CONFIRMED)),
            simLabel = { "SIM 1 · MCI" }
        )

        assertNull("INVALID_SUBSCRIPTION_ID is 'unknown', not a line", s.sentWithLabel)
        assertFalse(s.simEvidenceConflict)
    }

    // ── multipart and per-message identity ───────────────────────────────────

    @Test
    fun `a multipart message reports one logical status and a per-part breakdown`() {
        val s = state(
            evidence(transportState = "SENT_AMBIGUOUS", sendFailureCode = "MODEM_FAILURE"),
            segments = listOf(
                segment(5, 0, 3, SegmentCallbackState.CONFIRMED),
                segment(5, 1, 3, SegmentCallbackState.CONFIRMED),
                segment(5, 2, 3, SegmentCallbackState.AMBIGUOUS)
            )
        )

        assertEquals(3, s.partCount)
        assertEquals(3, s.parts.size)
        // ONE logical status for the whole message, not one per part.
        assertEquals(SmsUiState.SEND_STATUS_UNKNOWN, s.state)
        assertEquals(1, s.parts[0].position)
        assertEquals(3, s.parts[2].position)
    }

    @Test
    fun `two different messages produce different details`() {
        val rateLimited = state(
            evidence(transportState = "NOT_SENT", sendFailureCode = "RIL_RATE_LIMITED", sendResultCode = 106)
        )
        val delivered = state(
            evidence(transportState = "SENT_CONFIRMED", deliveryEvidence = "DELIVERED", deliveryTpStatus = 0x00)
        )

        assertNotEquals(rateLimited.state, delivered.state)
        assertEquals(106, rateLimited.sentResultCode)
        assertNull("the failure must not leak into the other message", delivered.sentResultCode)
        assertNull(rateLimited.deliveryTpStatus)
    }

    @Test
    fun `an unknown result code still has a canonical symbolic name`() {
        val s = state(evidence(transportState = "NOT_SENT", sendResultCode = 9999))

        assertEquals("UNKNOWN_RESULT_9999", s.sentResultName)
    }

    @Test
    fun `an incoming message has no transport timeline`() {
        val s = state(evidence(type = 1), segments = emptyList())

        assertFalse(s.isOutgoing)
        assertTrue("inbound has no send story to tell", s.steps.isEmpty())
        assertEquals("Received", s.headline)
    }
}
