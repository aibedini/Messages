package com.autonomousone.messages.sms

import android.provider.Telephony
import android.telephony.SmsManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Provider status is derived from positive callback evidence, and a definite
 * refusal is never rendered as success.
 */
class SmsStatusPolicyTest {

    private fun s(
        sentDone: Int = 1,
        unconfirmed: Int = 0,
        sentFailed: Int = 0,
        dlvDone: Int = 0,
        dlvPending: Int = 0,
        dlvFailed: Int = 0,
        parts: Int = 1
    ) = SmsStatusPolicy.nextStatus(
        sentConfirmedParts = sentDone,
        sentUnconfirmedParts = unconfirmed,
        sentFailedParts = sentFailed,
        dlvPartsDone = dlvDone,
        dlvPartsPending = dlvPending,
        dlvPartsFailed = dlvFailed,
        partCount = parts
    )

    @Test fun `3gpp completed statuses are delivered`() {
        assertEquals(SmsStatusPolicy.DeliveryEvidence.DELIVERED, SmsStatusPolicy.classify3gppTpStatus(0x00))
        assertEquals(SmsStatusPolicy.DeliveryEvidence.DELIVERED, SmsStatusPolicy.classify3gppTpStatus(0x1f))
    }

    @Test fun `3gpp temporary statuses remain pending`() {
        assertEquals(SmsStatusPolicy.DeliveryEvidence.TEMPORARY, SmsStatusPolicy.classify3gppTpStatus(0x20))
        assertEquals(SmsStatusPolicy.DeliveryEvidence.TEMPORARY, SmsStatusPolicy.classify3gppTpStatus(0x3f))
    }

    @Test fun `3gpp permanent and stopped retry statuses are failed`() {
        assertEquals(SmsStatusPolicy.DeliveryEvidence.FAILED, SmsStatusPolicy.classify3gppTpStatus(0x40))
        assertEquals(SmsStatusPolicy.DeliveryEvidence.FAILED, SmsStatusPolicy.classify3gppTpStatus(0x7f))
    }

    @Test fun `unknown 3gpp status never invents a verdict`() {
        assertEquals(SmsStatusPolicy.DeliveryEvidence.UNKNOWN, SmsStatusPolicy.classify3gppTpStatus(0x80))
    }

    @Test fun `documented 3gpp2 received status is delivered`() {
        assertEquals(SmsStatusPolicy.DeliveryEvidence.DELIVERED, SmsStatusPolicy.classify3gpp2Status(2 shl 16))
        assertEquals(SmsStatusPolicy.DeliveryEvidence.UNKNOWN, SmsStatusPolicy.classify3gpp2Status(3 shl 16))
    }

    @Test fun allSentCallbacksReceivedIsSent() {
        assertEquals(Telephony.Sms.STATUS_NONE, s(sentDone = 3, parts = 3))
    }

    @Test fun partialSentPartsStayPending() {
        assertEquals(Telephony.Sms.STATUS_PENDING, s(sentDone = 2, parts = 3))
    }

    @Test fun deliveredPartFailureDoesNotFailSentMessage() {
        assertEquals(Telephony.Sms.STATUS_NONE, s(sentDone = 3, dlvDone = 1, parts = 3))
    }

    @Test fun allDeliveredPartsOkIsDelivered() {
        assertEquals(Telephony.Sms.STATUS_COMPLETE, s(sentDone = 3, dlvDone = 3, parts = 3))
    }

    @Test fun partialDeliveredStaySent() {
        assertEquals(Telephony.Sms.STATUS_NONE, s(sentDone = 3, dlvDone = 1, parts = 3))
    }

    @Test fun temporaryNetworkReportIsPending() {
        assertEquals(Telephony.Sms.STATUS_PENDING, s(sentDone = 1, dlvPending = 1))
    }

    @Test fun permanentNetworkReportIsFailed() {
        assertEquals(Telephony.Sms.STATUS_FAILED, s(sentDone = 1, dlvFailed = 1))
    }

    @Test fun allPartsDeliveredOutranksOlderFailureEvidence() {
        assertEquals(
            Telephony.Sms.STATUS_COMPLETE,
            s(sentDone = 2, dlvDone = 2, dlvFailed = 1, parts = 2)
        )
    }

    @Test fun oneFailedMultipartPartFailsLogicalMessage() {
        assertEquals(
            Telephony.Sms.STATUS_FAILED,
            s(sentDone = 3, dlvDone = 2, dlvFailed = 1, parts = 3)
        )
    }

    // ---- PART 25 REGRESSION -------------------------------------------------
    // "SIM has no usable credit / the carrier refuses the SMS, the SmsManager
    //  method did not throw, the SENT callback returns a modem failure."
    // That must NEVER render as a normal successful single tick.

    @Test fun definiteRefusalIsAVisibleFailure() {
        assertEquals(Telephony.Sms.STATUS_FAILED, s(sentDone = 0, sentFailed = 1, parts = 1))
    }

    @Test fun ambiguousResultIsNeverASuccessTick() {
        // GENERIC_FAILURE: the SMSC may still accept it, so it is not Failed --
        // but it is absolutely not "Sent" either.
        assertEquals(Telephony.Sms.STATUS_PENDING, s(sentDone = 0, unconfirmed = 1, parts = 1))
    }

    @Test fun oneAmbiguousPartOfAMultipartIsNotSuccess() {
        assertEquals(
            Telephony.Sms.STATUS_PENDING,
            s(sentDone = 1, unconfirmed = 1, parts = 2)
        )
    }

    @Test fun oneRefusedPartFailsTheWholeMultipart() {
        assertEquals(
            Telephony.Sms.STATUS_FAILED,
            s(sentDone = 2, sentFailed = 1, parts = 3)
        )
    }

    @Test fun refusalOutranksAmbiguityInTheSameMessage() {
        assertEquals(
            Telephony.Sms.STATUS_FAILED,
            s(sentDone = 1, unconfirmed = 1, sentFailed = 1, parts = 3)
        )
    }

    @Test fun provenDeliveryOutranksADefiniteRefusal() {
        assertEquals(
            Telephony.Sms.STATUS_COMPLETE,
            s(sentDone = 0, sentFailed = 1, dlvDone = 1, parts = 1)
        )
    }

    // ---- result-code classification ----------------------------------------

    @Test fun sentResultCodesSplitIntoConfirmedUnconfirmedAndFailed() {
        assertEquals(SentPartVerdict.CONFIRMED, SmsSendPolicy.classifySentResult(RESULT_OK))
        assertEquals(
            SentPartVerdict.UNCONFIRMED,
            SmsSendPolicy.classifySentResult(SmsManager.RESULT_ERROR_GENERIC_FAILURE)
        )
        assertEquals(
            SentPartVerdict.FAILED,
            SmsSendPolicy.classifySentResult(SmsManager.RESULT_ERROR_NO_SERVICE)
        )
        assertEquals(
            SentPartVerdict.FAILED,
            SmsSendPolicy.classifySentResult(SmsManager.RESULT_ERROR_RADIO_OFF)
        )
        assertEquals(
            SentPartVerdict.FAILED,
            SmsSendPolicy.classifySentResult(SmsManager.RESULT_ERROR_NULL_PDU)
        )
    }

    @Test fun hardFailuresCarryATypedPersistableCode() {
        assertEquals("NO_SERVICE", SmsSendPolicy.hardFailure(SmsManager.RESULT_ERROR_NO_SERVICE)?.code)
        assertEquals("RADIO_OFF", SmsSendPolicy.hardFailure(SmsManager.RESULT_ERROR_RADIO_OFF)?.code)
        assertEquals("NULL_PDU", SmsSendPolicy.hardFailure(SmsManager.RESULT_ERROR_NULL_PDU)?.code)
        // Ambiguous and confirmed results are NOT hard failures.
        assertEquals(null, SmsSendPolicy.hardFailure(SmsManager.RESULT_ERROR_GENERIC_FAILURE))
        assertEquals(null, SmsSendPolicy.hardFailure(RESULT_OK))
    }

    @Test fun ambiguousResultKeepsADiagnosticReason() {
        val reason = SmsSendPolicy.ambiguousReason(SmsManager.RESULT_ERROR_GENERIC_FAILURE, 42)
        assertEquals("MODEM_FAILURE", reason.code)
        assertEquals(
            SmsSendFailure.ModemFailure(SmsManager.RESULT_ERROR_GENERIC_FAILURE, 42),
            reason
        )
    }

    @Test fun dispatchRejectionHasItsOwnStableCode() {
        assertEquals("DISPATCH_REJECTED", SmsSendFailure.DispatchRejected(null).code)
    }

    // ---- durable state machine ---------------------------------------------

    private fun state(
        confirmed: Int = 0,
        unconfirmed: Int = 0,
        failed: Int = 0,
        dlv: Int = 0,
        parts: Int = 1,
        dispatched: Boolean = true
    ) = SmsStatusPolicy.aggregateSendState(
        sentConfirmedParts = confirmed,
        sentUnconfirmedParts = unconfirmed,
        sentFailedParts = failed,
        dlvPartsDone = dlv,
        partCount = parts,
        dispatched = dispatched
    )

    @Test fun durableStateFollowsTheSameEvidence() {
        assertEquals(SendState.QUEUED, state(dispatched = false))
        assertEquals(SendState.DISPATCHED, state())
        assertEquals(SendState.SENT_CONFIRMED, state(confirmed = 2, parts = 2))
        assertEquals(SendState.SEND_UNCONFIRMED, state(unconfirmed = 1))
        assertEquals(SendState.FAILED, state(failed = 1))
        assertEquals(SendState.DELIVERED, state(confirmed = 2, dlv = 2, parts = 2))
    }

    @Test fun stateNeverDowngradesAStrongerVerdict() {
        assertEquals(SendState.DELIVERED, SendState.advance(SendState.DELIVERED, SendState.DISPATCHED))
        assertEquals(SendState.DELIVERED, SendState.advance(SendState.SENT_CONFIRMED, SendState.DELIVERED))
        assertEquals(SendState.DELIVERED, SendState.advance(SendState.SEND_UNCONFIRMED, SendState.DELIVERED))
        assertEquals(
            SendState.SENT_CONFIRMED,
            SendState.advance(SendState.SENT_CONFIRMED, SendState.SEND_UNCONFIRMED)
        )
        assertEquals(SendState.FAILED, SendState.advance(SendState.FAILED, SendState.SENT_CONFIRMED))
        assertEquals(SendState.DISPATCHED, SendState.advance(SendState.DISPATCHING, SendState.DISPATCHED))
    }

    private companion object {
        /** Activity.RESULT_OK, spelled out so this file needs no Android import. */
        const val RESULT_OK = -1
    }
}
