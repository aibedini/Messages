package com.autonomousone.messages.sms

import android.provider.Telephony
import android.telephony.SmsManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v2.6.14 semantics: SENT failure is provisional (radios lie about UCS-2
 * submits); ANY successful DELIVERED report refutes it; delivery failures
 * never downgrade a fully-sent message.
 */
class SmsStatusPolicyTest {

    private fun s(
        phase: SmsStatusPolicy.Phase,
        ok: Boolean,
        sentFail: Boolean = false,
        sentDone: Int = 1,
        dlvDone: Int = 0,
        parts: Int = 1
    ) = SmsStatusPolicy.nextStatus(
        phase = phase,
        partOk = ok,
        sentFailSticky = sentFail,
        dlvFailSticky = false,
        sentPartsDone = sentDone,
        dlvPartsDone = dlvDone,
        partCount = parts
    )

    private val SENT = SmsStatusPolicy.Phase.SENT
    private val DLV = SmsStatusPolicy.Phase.DELIVERED

    @Test fun genericSentFailureIsAmbiguous() {
        assertEquals(
            true,
            SmsStatusPolicy.isAmbiguousSentFailure(SENT, SmsManager.RESULT_ERROR_GENERIC_FAILURE)
        )
    }

    @Test fun concreteSentFailuresRemainAuthoritative() {
        assertEquals(
            false,
            SmsStatusPolicy.isAmbiguousSentFailure(SENT, SmsManager.RESULT_ERROR_NO_SERVICE)
        )
        assertEquals(
            false,
            SmsStatusPolicy.isAmbiguousSentFailure(SENT, SmsManager.RESULT_ERROR_RADIO_OFF)
        )
    }

    @Test fun genericDeliveryFailureIsOnlyAReportGap() {
        assertEquals(
            false,
            SmsStatusPolicy.isAmbiguousSentFailure(DLV, SmsManager.RESULT_ERROR_GENERIC_FAILURE)
        )
    }

    @Test fun sentPartFailedIsFailed() {
        assertEquals(Telephony.Sms.STATUS_FAILED, s(SENT, ok = false))
    }

    @Test fun sentFailStickyKeepsFailedWithoutDeliveryEvidence() {
        assertEquals(Telephony.Sms.STATUS_FAILED, s(SENT, ok = true, sentFail = true, sentDone = 1))
    }

    @Test fun allSentPartsOkIsSent() {
        assertEquals(Telephony.Sms.STATUS_NONE, s(SENT, ok = true, sentDone = 3, parts = 3))
    }

    @Test fun partialSentPartsStayPending() {
        assertEquals(Telephony.Sms.STATUS_PENDING, s(SENT, ok = true, sentDone = 2, parts = 3))
    }

    @Test fun deliveredPartFailureDoesNotFailSentMessage() {
        assertEquals(Telephony.Sms.STATUS_NONE, s(DLV, ok = false, sentDone = 3, dlvDone = 1, parts = 3))
    }

    @Test fun allDeliveredPartsOkIsDelivered() {
        assertEquals(Telephony.Sms.STATUS_COMPLETE, s(DLV, ok = true, sentDone = 3, dlvDone = 3, parts = 3))
    }

    @Test fun partialDeliveredStaySent() {
        assertEquals(Telephony.Sms.STATUS_NONE, s(DLV, ok = true, sentDone = 3, dlvDone = 1, parts = 3))
    }

    @Test fun deliveredSuccessREFUTESEarlierSentFailure() {
        // THE v2.6.13 bug: radio lied about GENERIC_FAILURE, delivery proves
        // the message reached the handset → FAILED must lift to Delivered.
        assertEquals(Telephony.Sms.STATUS_COMPLETE, s(DLV, ok = true, sentFail = true, sentDone = 1, dlvDone = 1, parts = 1))
    }

    @Test fun singleDeliveredOnMultipartLiftsFailedToSend() {
        // One part delivered refutes FAILED; rest still pending reports → SENT.
        assertEquals(Telephony.Sms.STATUS_NONE, s(DLV, ok = true, sentFail = true, sentDone = 3, dlvDone = 1, parts = 3))
    }

    @Test fun deliveredAfterFailedSinglePartStaysCompleteWhenAllOk() {
        assertEquals(Telephony.Sms.STATUS_COMPLETE, s(DLV, ok = true, sentFail = true, sentDone = 2, dlvDone = 2, parts = 2))
    }
}
