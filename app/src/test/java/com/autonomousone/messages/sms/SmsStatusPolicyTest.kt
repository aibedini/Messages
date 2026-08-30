package com.autonomousone.messages.sms

import android.provider.Telephony
import org.junit.Assert.assertEquals
import org.junit.Test

/** Provider status is derived from positive callback evidence only. */
class SmsStatusPolicyTest {

    private fun s(
        sentDone: Int = 1,
        dlvDone: Int = 0,
        dlvPending: Int = 0,
        dlvFailed: Int = 0,
        parts: Int = 1
    ) = SmsStatusPolicy.nextStatus(
        sentPartsDone = sentDone,
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
}
