package com.autonomousone.messages.sms

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v2.6.13: delivery-report semantics for multipart SMS.
 *
 * Field report: a multi-part Persian (UCS-2) message sent on a network with
 * delivery reports enabled came back with the red FAILED mark even though the
 * recipient received it. Root cause: one part's DELIVERED broadcast returned
 * an error and the failure "poisoned" the whole message — a SENT-failure and
 * a DELIVERY-failure were conflated into the same sticky STATUS_FAILED.
 *
 * The contract (Google Messages behaviour):
 *  - SENT part failed        → FAILED. The message never left the modem.
 *  - all SENT parts OK       → floor = SENT. Delivery can only UPGRADE.
 *  - DELIVERED part failed   → stays SENT (carrier-level report gap; the
 *                              message was already accepted AND sent).
 *  - all DELIVERED parts OK  → Delivered.
 */
class SmsStatusPolicyTest {

    private fun status(
        sentOk: Boolean,
        delivered: Boolean,
        deliveredOk: Boolean,
        sentFailSticky: Boolean = false,
        dlvFailSticky: Boolean = false,
        sentPartsDone: Int = 1,
        partCount: Int = 1
    ): Int = SmsStatusPolicy.nextStatus(
        phase = if (delivered) SmsStatusPolicy.Phase.DELIVERED else SmsStatusPolicy.Phase.SENT,
        partOk = if (delivered) deliveredOk else sentOk,
        sentFailSticky = sentFailSticky,
        dlvFailSticky = dlvFailSticky,
        sentPartsDone = sentPartsDone,
        dlvPartsDone = if (delivered) 1 else 0,
        partCount = partCount
    )

    @Test
    fun `sent part failed is FAILED`() {
        assertEquals(
            android.provider.Telephony.Sms.STATUS_FAILED,
            status(sentOk = false, delivered = false, deliveredOk = false)
        )
    }

    @Test
    fun `sent sticky failure wins over later delivery success`() {
        assertEquals(
            android.provider.Telephony.Sms.STATUS_FAILED,
            status(sentOk = true, delivered = true, deliveredOk = true, sentFailSticky = true)
        )
    }

    @Test
    fun `all sent parts ok is SENT`() {
        assertEquals(
            android.provider.Telephony.Sms.STATUS_NONE,
            SmsStatusPolicy.nextStatus(
                phase = SmsStatusPolicy.Phase.SENT,
                partOk = true,
                sentFailSticky = false,
                dlvFailSticky = false,
                sentPartsDone = 3,
                dlvPartsDone = 0,
                partCount = 3
            )
        )
    }

    @Test
    fun `partial sent parts stay PENDING`() {
        assertEquals(
            android.provider.Telephony.Sms.STATUS_PENDING,
            SmsStatusPolicy.nextStatus(
                phase = SmsStatusPolicy.Phase.SENT,
                partOk = true,
                sentFailSticky = false,
                dlvFailSticky = false,
                sentPartsDone = 2,
                dlvPartsDone = 0,
                partCount = 3
            )
        )
    }

    @Test
    fun `delivered part failure does NOT fail a fully-sent message`() {
        // THE regression: 3-part Persian SMS, SENT 3/3 OK, one DELIVERED report
        // errored. Message reached the recipient; the mark must stay SENT.
        assertEquals(
            android.provider.Telephony.Sms.STATUS_NONE,
            SmsStatusPolicy.nextStatus(
                phase = SmsStatusPolicy.Phase.DELIVERED,
                partOk = false,
                sentFailSticky = false,
                dlvFailSticky = false,
                sentPartsDone = 3,
                dlvPartsDone = 1,
                partCount = 3
            )
        )
    }

    @Test
    fun `all delivered parts ok is Delivered`() {
        assertEquals(
            android.provider.Telephony.Sms.STATUS_COMPLETE,
            SmsStatusPolicy.nextStatus(
                phase = SmsStatusPolicy.Phase.DELIVERED,
                partOk = true,
                sentFailSticky = false,
                dlvFailSticky = false,
                sentPartsDone = 3,
                dlvPartsDone = 3,
                partCount = 3
            )
        )
    }

    @Test
    fun `partial delivered parts stay SENT until complete`() {
        assertEquals(
            android.provider.Telephony.Sms.STATUS_NONE,
            SmsStatusPolicy.nextStatus(
                phase = SmsStatusPolicy.Phase.DELIVERED,
                partOk = true,
                sentFailSticky = false,
                dlvFailSticky = false,
                sentPartsDone = 3,
                dlvPartsDone = 1,
                partCount = 3
            )
        )
    }

    @Test
    fun `single part delivered ok is Delivered`() {
        assertEquals(
            android.provider.Telephony.Sms.STATUS_COMPLETE,
            status(sentOk = true, delivered = true, deliveredOk = true)
        )
    }

    @Test
    fun `dlv failure sticky still cannot fail a sent message`() {
        // Even after a delivery failure was observed for one part, a later
        // delivered part must not flip the message to FAILED.
        assertEquals(
            android.provider.Telephony.Sms.STATUS_NONE,
            SmsStatusPolicy.nextStatus(
                phase = SmsStatusPolicy.Phase.DELIVERED,
                partOk = true,
                sentFailSticky = false,
                dlvFailSticky = true,
                sentPartsDone = 3,
                dlvPartsDone = 2,
                partCount = 3
            )
        )
    }
}
