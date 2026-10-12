package com.autonomousone.messages.ui.details

import com.autonomousone.messages.data.MessageTechnicalEvidence
import com.autonomousone.messages.data.SegmentCallbackState
import com.autonomousone.messages.data.SendSegmentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Message Details for a RECEIVED message.
 *
 * ## Why the details screen had to be opened up to incoming messages
 *
 * Details was reachable only from the outgoing-only context menu, so a received SMS — the case where a
 * user most often wonders where a message came from — had no route to it at all. Making it reachable
 * created a new obligation, and that obligation is what this class pins down:
 *
 *   A received message has NO SEND STORY, so the screen must not invent one.
 *
 * The specific failure mode to guard against is the interesting one: this phone never submitted the
 * message, so there is no transport state, no SMSC acknowledgement, no delivery report, and above all
 * no "Retry" — a retry button on a message the user RECEIVED would offer to re-send somebody else's
 * message back to them. That is not a cosmetic bug.
 */
class IncomingMessageDetailsTest {

    /** `MESSAGE_TYPE_INBOX` is 1; `MESSAGE_TYPE_SENT` is 2. */
    private val inbound = 1
    private val outbound = 2

    private fun evidence(
        type: Int = 1,
        deliveryTpStatus: Int? = null,
        deliveryEvidence: String? = null,
        sendTransportState: String? = null,
        sendResultCode: Int? = null,
        sendFailureCode: String? = null,
        sendRadioErrorCode: Int? = null
    ) = MessageTechnicalEvidence(
        source = "sms",
        providerId = 4242L,
        threadId = 77L,
        type = type,
        date = 1_700_000_000_000L,
        dateSent = 0L,
        sendTransportState = sendTransportState,
        sendFailureCode = sendFailureCode,
        sendResultCode = sendResultCode,
        sendRadioErrorCode = sendRadioErrorCode,
        sendStateUpdatedAt = 0L,
        deliveryEvidence = deliveryEvidence,
        deliveryTpStatus = deliveryTpStatus,
        deliveryResultCode = null,
        deliveryCallbackAt = 0L
    )

    private fun state(
        e: MessageTechnicalEvidence?,
        segments: List<SendSegmentEntity> = emptyList()
    ) = MessageDetailsUiState.of(
        evidence = e,
        segments = segments,
        body = "received text",
        recipient = "+989120000000",
        sender = "+989130000000",
        appVersion = "3.5.2 (139)",
        simLabel = { "SIM 1 · MCI" }
    )

    @Test
    fun `a received message reports itself as incoming and shows Received`() {
        val s = state(evidence(type = inbound))

        assertFalse(s.isOutgoing)
        assertEquals("Received", s.headline)
    }

    @Test
    fun `a received message produces NO transport or delivery steps`() {
        // One word must not stand in for a timeline this phone cannot observe. An empty list is what
        // makes the screen say nothing rather than say something false.
        val s = state(evidence(type = inbound))

        assertTrue("no send story exists for a received message", s.steps.isEmpty())
    }

    @Test
    fun `a received message is never offered a Retry`() {
        // The sharpest consequence of getting this wrong: "Retry" on an inbound message would offer to
        // send somebody else's message back to them.
        val s = state(evidence(type = inbound))

        assertFalse("a received message must never be retryable", s.canRetry)
        assertNull("and it has no state to retry from", s.state)
    }

    @Test
    fun `a received message shows no duplicate-send warning`() {
        // The duplicate risk belongs to an ambiguous SUBMIT. Nothing was submitted here.
        val s = state(evidence(type = inbound))

        assertFalse(s.duplicateRisk)
    }

    @Test
    fun `incoming evidence cannot leak send diagnostics even if a row carries them`() {
        // Defensive: the mirror row for an inbound message should hold no send columns. If a future
        // writer ever populates them, the screen must still not present them as this message's send
        // story, and it must certainly not become retryable.
        val s = state(
            evidence(
                type = inbound,
                sendTransportState = "SENT_CONFIRMED",
                sendResultCode = 0,
                deliveryEvidence = "DELIVERED",
                deliveryTpStatus = 0
            )
        )

        assertFalse("still not retryable", s.canRetry)
        assertEquals("and still no timeline", 0, s.steps.size)
        assertNull("and no state", s.state)
    }

    @Test
    fun `an OUTGOING message is unaffected by the incoming rules`() {
        // The control case: the same evidence shape, but sent. The timeline must appear.
        val s = state(
            evidence(
                type = outbound,
                sendTransportState = "SENT_CONFIRMED",
                deliveryEvidence = "DELIVERED",
                deliveryTpStatus = 0
            )
        )

        assertTrue(s.isOutgoing)
        assertEquals("Delivered", s.headline)
        assertEquals(3, s.steps.size)
    }

    @Test
    fun `a failed OUTGOING message is still retryable`() {
        // Incoming must not be made safe by disabling retry everywhere.
        val s = state(
            evidence(type = outbound, sendTransportState = "NOT_SENT", sendFailureCode = "NO_SERVICE")
        )

        assertTrue("a genuine send failure keeps its retry", s.canRetry)
    }

    @Test
    fun `a received message with no submission keeps every send field absent`() {
        // Absent must stay absent rather than render as a plausible zero. `deliveryTpStatus = 0` is a
        // REAL 3GPP delivered status, so printing it for "no report" would fabricate the strongest
        // evidence there is.
        val s = state(evidence(type = inbound))

        assertNull(s.submittedAt)
        assertNull(s.transportState)
        assertNull(s.sentResultCode)
        assertNull(s.failureCode)
        assertNull(s.deliveryTpStatus)
        assertNull(s.deliveryCallbackAt)
        assertNull(s.dateSent)
    }

    @Test
    fun `a received message still shows its real sender and receive time`() {
        // The point of opening Details to incoming messages: real, checkable facts.
        val s = state(evidence(type = inbound))

        assertEquals("+989130000000", s.sender)
        assertEquals(1_700_000_000_000L, s.createdAt)
    }

    @Test
    fun `an incoming message never claims a send-time SIM`() {
        // The ledger records which line SUBMITTED a message. Nothing was submitted, so there is no
        // sending line to name — and an empty ledger must not resolve to a default.
        val s = state(evidence(type = inbound), segments = emptyList())

        assertNull(s.sentWithLabel)
        assertFalse(s.simEvidenceConflict)
    }

    @Test
    fun `a multipart incoming message keeps its identity and gains no send timeline`() {
        // Segment evidence is a record of how an OUTGOING message was SPLIT FOR SUBMISSION. If a row
        // somehow carries segments, they must not become a send story for a message this phone received.
        val s = state(
            evidence(type = inbound),
            segments = listOf(
                SendSegmentEntity(
                    rowId = 4242L, partIndex = 0, partCount = 2,
                    submittedAt = 1_700_000_000_100L, subscriptionId = 5,
                    callbackState = SegmentCallbackState.CONFIRMED
                )
            )
        )

        assertTrue("no timeline for an inbound message", s.steps.isEmpty())
        assertEquals("Received", s.headline)
        // Identity is still exact, so Details describes THIS message and not a neighbour.
        assertEquals(4242L, s.providerId)
        assertEquals("sms", s.source)
        assertFalse(s.canRetry)
    }
}
