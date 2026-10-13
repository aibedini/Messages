package com.autonomousone.messages.sms

import com.autonomousone.messages.data.MessageTechnicalEvidence
import com.autonomousone.messages.data.SendSegmentEntity
import com.autonomousone.messages.data.SegmentCallbackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The REAL reported incident, end to end through the evidence pipeline.
 *
 * ## What the phone actually recorded
 *
 * ```text
 * status:              NOT_DELIVERED
 * transport:           SENT_AMBIGUOUS
 * sendFailureCode:     CARRIER_FAILURE_UNKNOWN
 * sendResultCode:      0
 * deliveryEvidence:    FAILED
 * deliveryTpStatus:    99 (0x63)
 * deliveryResultCode:  0
 * parts:               1
 * ```
 *
 * ## The two mistakes this guards against
 *
 * 1. **`in 0x00..0x1f -> DELIVERED`.** Before this fix, a report in the completed range was taken as
 *    confirmed delivery. `0x63` is not in that range, so the incident itself rendered correctly — but
 *    the same bug WOULD have reported `0x01`/`0x02` as delivered, which is a false success. The decoder
 *    tests cover that; this class covers the incident.
 *
 * 2. **Confusing `resultCode = 0` with `TP-Status = 0x00`.** The SENT callback's result 0 is an Android
 *    PendingIntent value; the delivery report's 0x63 is the carrier's TP-Status. They are different
 *    fields from different layers, and collapsing them is exactly how a failure becomes a success.
 */
class DeliveryIncidentTest {

    private val incidentTpStatus = 99 // 0x63

    // ── the decoder's reading of the carrier's statement ─────────────────────

    @Test
    fun `the incident TP-Status decodes as a terminal carrier failure, not a delivery`() {
        val decoded = SmsTpStatus.decode(incidentTpStatus)

        assertEquals(SmsTpStatus.Evidence.FAILED, decoded.evidence)
        assertTrue("the network has stopped trying", decoded.isTerminal)
        assertFalse(decoded.carrierStillRetrying)
        assertEquals("0x63", decoded.hexCode)
        assertTrue(
            "the cause is the carrier's own business, and the app must say so",
            decoded.carrierSpecific
        )
    }

    @Test
    fun `the SENT result 0 and the TP-Status are independent facts`() {
        // The heart of the second mistake. Result 0 must NOT be read as TP-Status 0x00 ("delivered"),
        // and the TP-Status must not be inferred from the result.
        assertEquals(
            "result 0 is a PendingIntent value, not a TP-Status",
            SmsTpStatus.Evidence.DELIVERED,
            SmsTpStatus.decode(0x00).evidence
        )
        assertEquals(
            "and the actual TP-Status is a failure",
            SmsTpStatus.Evidence.FAILED,
            SmsTpStatus.decode(incidentTpStatus).evidence
        )
        assertEquals("RESULT_CANCELED_OR_UNSET", SmsResultCodes.name(0))
    }

    // ── the persisted evidence resolves to the right canonical state ─────────

    @Test
    fun `the incident resolves to NOT_DELIVERED`() {
        val state = SendStateDerivation.evidenceFromPersisted(mirror(incidentRow()), isOutgoing = true)
            .let { SmsStatusPresentationMapper.present(it) }

        assertEquals(SmsUiState.NOT_DELIVERED, state.state)
        assertTrue("the user must be able to retry a terminal carrier failure", state.canRetry)
    }

    @Test
    fun `the incident is NOT reported as sent, delivered or unknown`() {
        val state = SendStateDerivation.evidenceFromPersisted(mirror(incidentRow()), isOutgoing = true)
            .let { SmsStatusPresentationMapper.present(it) }

        assertFalse(state.state == SmsUiState.SENT)
        assertFalse(state.state == SmsUiState.DELIVERED)
        assertFalse("the carrier DID answer, so this is not unknown", state.state == SmsUiState.DELIVERY_UNKNOWN)
        assertFalse(state.state == SmsUiState.NOT_SENT)
    }

    @Test
    fun `transport ambiguity is preserved separately from the delivery verdict`() {
        // The transport callback never gave a positive confirmation. A later carrier failure report may
        // show the network processed the message, but it must not retroactively turn the transport
        // callback into a confirmation.
        val evidence = SendStateDerivation.evidenceFromPersisted(mirror(incidentRow()), isOutgoing = true)

        assertFalse(
            "an ambiguous transport must never become SENT_CONFIRMED",
            evidence.hasSentConfirmation
        )
        assertTrue("the delivery failure is real evidence", evidence.hasDeliveryFailure)
    }

    @Test
    fun `an ambiguous transport with NO delivery report stays SEND_STATUS_UNKNOWN`() {
        // The control case: strip the delivery report and the message must fall back to "we cannot say
        // whether it left", never to a success.
        val state = SendStateDerivation.evidenceFromPersisted(
            mirror(incidentRow(deliveryEvidence = null, tpStatus = null)),
            isOutgoing = true
        ).let { SmsStatusPresentationMapper.present(it) }

        assertEquals(SmsUiState.SEND_STATUS_UNKNOWN, state.state)
    }

    // ── the new evidence class does not become a false success ──────────────

    @Test
    fun `a 0x01 report never renders as delivered`() {
        // The bug the decoder fix addresses, checked through the SAME pipeline the UI uses.
        val state = SendStateDerivation.evidenceFromPersisted(
            mirror(incidentRow(transportState = "SENT_CONFIRMED", deliveryEvidence = "NOT_CONFIRMED", tpStatus = 0x01)),
            isOutgoing = true
        ).let { SmsStatusPresentationMapper.present(it) }

        assertFalse("0x01 explicitly denies confirmation", state.state == SmsUiState.DELIVERED)
        assertEquals(SmsUiState.DELIVERY_UNKNOWN, state.state)
    }

    @Test
    fun `the persisted classification of 0x01 is NOT_CONFIRMED and not FAILED`() {
        // Persistence is where the classification is frozen. If this said FAILED, the UI would tell the
        // user the message was not delivered — which the network never said.
        assertEquals(
            SmsStatusPolicy.DeliveryEvidence.NOT_CONFIRMED,
            SmsStatusPolicy.classify3gppTpStatus(0x01)
        )
        assertEquals(
            SmsStatusPolicy.DeliveryEvidence.NOT_CONFIRMED,
            SmsStatusPolicy.classify3gppTpStatus(0x02)
        )
    }

    @Test
    fun `a NOT_CONFIRMED report outranks a later UNKNOWN and cannot be erased by it`() {
        // Monotonicity: real evidence must not decay back to "we know nothing".
        assertTrue(
            DeliveryEvidenceRank.NOT_CONFIRMED.ordinal > DeliveryEvidenceRank.UNKNOWN.ordinal
        )
        assertEquals(
            DeliveryEvidenceRank.NOT_CONFIRMED,
            DeliveryEvidenceRank.fromEvidenceName("NOT_CONFIRMED")
        )
        assertFalse(
            "an UNKNOWN report must not overwrite a real recorded answer",
            DeliveryEvidenceRank.UNKNOWN.ordinal >= DeliveryEvidenceRank.NOT_CONFIRMED.ordinal
        )
    }

    @Test
    fun `a NOT_CONFIRMED report is still weaker than a real failure`() {
        assertTrue(DeliveryEvidenceRank.FAILED.ordinal > DeliveryEvidenceRank.NOT_CONFIRMED.ordinal)
        assertTrue(DeliveryEvidenceRank.DELIVERED.ordinal > DeliveryEvidenceRank.FAILED.ordinal)
    }

    // ── multipart ────────────────────────────────────────────────────────────

    @Test
    fun `a multipart with one carrier failure is NOT_DELIVERED overall`() {
        // One part terminally failed, two delivered: the body was not delivered intact.
        val status = SmsStatusPolicy.nextStatus(
            sentConfirmedParts = 3,
            sentUnconfirmedParts = 0,
            sentFailedParts = 0,
            dlvPartsDone = 2,
            dlvPartsPending = 0,
            dlvPartsFailed = 1,
            dlvPartsNotConfirmed = 0,
            partCount = 3
        )

        assertEquals(android.provider.Telephony.Sms.STATUS_FAILED, status)
    }

    @Test
    fun `a multipart is never delivered unless every part is confirmed`() {
        val notAll = SmsStatusPolicy.nextStatus(
            sentConfirmedParts = 3, sentUnconfirmedParts = 0, sentFailedParts = 0,
            dlvPartsDone = 2, dlvPartsPending = 0, dlvPartsFailed = 0,
            dlvPartsNotConfirmed = 0, partCount = 3
        )
        assertFalse(notAll == android.provider.Telephony.Sms.STATUS_COMPLETE)

        val all = SmsStatusPolicy.nextStatus(
            sentConfirmedParts = 3, sentUnconfirmedParts = 0, sentFailedParts = 0,
            dlvPartsDone = 3, dlvPartsPending = 0, dlvPartsFailed = 0,
            dlvPartsNotConfirmed = 0, partCount = 3
        )
        assertEquals(android.provider.Telephony.Sms.STATUS_COMPLETE, all)
    }

    @Test
    fun `a multipart where a part is NOT_CONFIRMED is not delivered and not failed`() {
        val status = SmsStatusPolicy.nextStatus(
            sentConfirmedParts = 2, sentUnconfirmedParts = 0, sentFailedParts = 0,
            dlvPartsDone = 1, dlvPartsPending = 0, dlvPartsFailed = 0,
            dlvPartsNotConfirmed = 1, partCount = 2
        )

        assertFalse("not every part is confirmed", status == android.provider.Telephony.Sms.STATUS_COMPLETE)
        assertFalse("nothing failed, so this must not read as failure", status == android.provider.Telephony.Sms.STATUS_FAILED)
        assertEquals(android.provider.Telephony.Sms.STATUS_NONE, status)
    }

    @Test
    fun `a NOT_CONFIRMED part is no longer PENDING`() {
        // Without the new input the part would look unreported and the message would wait forever for a
        // report the carrier has already finished with.
        val status = SmsStatusPolicy.nextStatus(
            sentConfirmedParts = 1, sentUnconfirmedParts = 0, sentFailedParts = 0,
            dlvPartsDone = 0, dlvPartsPending = 0, dlvPartsFailed = 0,
            dlvPartsNotConfirmed = 1, partCount = 1
        )

        assertFalse(
            "the network gave its final answer",
            status == android.provider.Telephony.Sms.STATUS_PENDING
        )
    }

    // ── no fabricated causes ─────────────────────────────────────────────────

    @Test
    fun `the incident never names a cause the evidence cannot support`() {
        // Insufficient balance, a blocked recipient, an off phone, SIM failure and throttling all need
        // independent evidence. The carrier said only "could not complete delivery".
        val decoded = SmsTpStatus.decode(incidentTpStatus)
        val text = (decoded.reason + " " + decoded.symbolicName).lowercase()

        for (claim in listOf("balance", "credit", "charge", "blocked", "switched off", "throttl", "sim")) {
            assertFalse("must not claim '$claim': ${decoded.reason}", text.contains(claim))
        }
    }

    @Test
    fun `the incident keeps every raw field for the technical export`() {
        val e = incidentRow()

        assertNotNull(e.sendResultCode)
        assertEquals(0, e.sendResultCode)
        assertEquals("CARRIER_FAILURE_UNKNOWN", e.sendFailureCode)
        assertEquals("SENT_AMBIGUOUS", e.sendTransportState)
        assertEquals("FAILED", e.deliveryEvidence)
        assertEquals(incidentTpStatus, e.deliveryTpStatus)
        assertEquals(0, e.deliveryResultCode)
        assertEquals(641_664L, e.providerId)
        assertEquals(12_062L, e.threadId)
    }

    /** The Room mirror row for the incident, built exactly as production builds it. */
    private fun mirror(e: MessageTechnicalEvidence) = com.autonomousone.messages.data.MessageEntity(
        source = e.source, providerId = e.providerId, threadId = e.threadId,
        normalizedAddress = "", rawAddress = "", body = "", date = e.date, type = e.type,
        status = 0, dateSent = e.dateSent, read = true,
        sendTransportState = e.sendTransportState, sendFailureCode = e.sendFailureCode,
        sendResultCode = e.sendResultCode, sendRadioErrorCode = e.sendRadioErrorCode,
        sendStateUpdatedAt = e.sendStateUpdatedAt, deliveryCallbackAt = e.deliveryCallbackAt,
        deliveryTpStatus = e.deliveryTpStatus, deliveryEvidence = e.deliveryEvidence,
        deliveryResultCode = e.deliveryResultCode
    )

    private fun incidentRow(
        transportState: String = "SENT_AMBIGUOUS",
        deliveryEvidence: String? = "FAILED",
        tpStatus: Int? = incidentTpStatus
    ) = MessageTechnicalEvidence(
        source = "sms",
        providerId = 641_664L,
        threadId = 12_062L,
        type = 2,
        date = 1_791_834_501_568L,
        dateSent = 0L,
        sendTransportState = transportState,
        sendFailureCode = "CARRIER_FAILURE_UNKNOWN",
        sendResultCode = 0,
        sendRadioErrorCode = null,
        sendStateUpdatedAt = 1_791_834_502_622L,
        deliveryEvidence = deliveryEvidence,
        deliveryTpStatus = tpStatus,
        deliveryResultCode = 0,
        deliveryCallbackAt = 1_791_834_502_716L
    )
}
