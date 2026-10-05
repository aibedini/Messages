package com.autonomousone.messages.sms

import android.provider.Telephony
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PendingIntent identity for SMS callbacks.
 *
 * The Android trap this pins: `PendingIntent` equality is decided by (requestCode, Intent
 * `filterEquals`) and **extras are not part of it**. So two callbacks that share a requestCode are the
 * SAME PendingIntent — `FLAG_UPDATE_CURRENT` then silently rewrites its extras, and a carrier verdict
 * correlates to the wrong message or the wrong part. Multipart makes it worse: every part of one
 * message is submitted in a single call, so a code that ignored `partIndex` would collapse all of
 * them into one callback identity.
 *
 * `statusRequestCode` is pure on purpose: uniqueness is asserted here instead of trusted to
 * arithmetic in a builder nobody can test.
 *
 * NOTE: written, deliberately NOT executed (this task forbids running Gradle).
 */
class SmsStatusRequestCodeTest {

    private val sent = SmsStatusReceiver.ACTION_SMS_SENT
    private val delivered = SmsStatusReceiver.ACTION_SMS_DELIVERED

    @Test
    fun `the two phases of one part never collide`() {
        assertNotEquals(
            "a SENT callback and a DELIVERY callback must be different PendingIntents",
            SmsSender.statusRequestCode(sent, rowId = 42L, partIndex = 0),
            SmsSender.statusRequestCode(delivered, rowId = 42L, partIndex = 0)
        )
    }

    @Test
    fun `different messages never collide, for either phase`() {
        for (action in listOf(sent, delivered)) {
            assertNotEquals(
                SmsSender.statusRequestCode(action, rowId = 1L, partIndex = 0),
                SmsSender.statusRequestCode(action, rowId = 2L, partIndex = 0)
            )
        }
    }

    @Test
    fun `every part of a multipart message has its own identity`() {
        val codes = (0 until 6).map { part -> SmsSender.statusRequestCode(delivered, 99L, part) }

        assertEquals("six parts need six distinct callbacks", 6, codes.toSet().size)
    }

    @Test
    fun `identity is deterministic so a re-send of the same row reuses it`() {
        // Determinism is deliberate: the same (action, row, part) must resolve to the same
        // PendingIntent so FLAG_UPDATE_CURRENT refreshes it rather than creating a second one.
        assertEquals(
            SmsSender.statusRequestCode(delivered, 7L, 1),
            SmsSender.statusRequestCode(delivered, 7L, 1)
        )
    }

    @Test
    fun `the four-way cross product is collision-free for a realistic batch`() {
        // 200 messages × 3 parts × 2 phases: the concurrency the real gateway produces in a burst.
        val codes = HashSet<Int>()
        for (row in 1L..200L) {
            for (part in 0 until 3) {
                assertTrue(codes.add(SmsSender.statusRequestCode(sent, row, part)))
                assertTrue(codes.add(SmsSender.statusRequestCode(delivered, row, part)))
            }
        }
        assertEquals(1200, codes.size)
    }

    @Test
    fun `the aggregate policy distinguishes a full delivery from a partial one`() {
        // The mission's multipart example, expressed against the ONE aggregation rule the receiver
        // uses. Callbacks arrive 2,1,3 and part 2 is delivered twice: the counts (not an order) decide,
        // so the duplicate cannot double-count and the aggregate is only COMPLETE with all three.
        val afterPart2 = SmsStatusPolicy.nextStatus(
            sentConfirmedParts = 3, sentUnconfirmedParts = 0, sentFailedParts = 0,
            dlvPartsDone = 1, dlvPartsPending = 0, dlvPartsFailed = 0, partCount = 3
        )
        assertNotEquals(
            "one delivered part must not deliver the whole message",
            Telephony.Sms.STATUS_COMPLETE,
            afterPart2
        )

        val afterPart2Again = SmsStatusPolicy.nextStatus(
            sentConfirmedParts = 3, sentUnconfirmedParts = 0, sentFailedParts = 0,
            dlvPartsDone = 1, dlvPartsPending = 0, dlvPartsFailed = 0, partCount = 3
        )
        assertEquals("a duplicate callback changes nothing", afterPart2, afterPart2Again)

        val allThree = SmsStatusPolicy.nextStatus(
            sentConfirmedParts = 3, sentUnconfirmedParts = 0, sentFailedParts = 0,
            dlvPartsDone = 3, dlvPartsPending = 0, dlvPartsFailed = 0, partCount = 3
        )
        assertEquals(Telephony.Sms.STATUS_COMPLETE, allThree)

        val oneMissing = SmsStatusPolicy.nextStatus(
            sentConfirmedParts = 3, sentUnconfirmedParts = 0, sentFailedParts = 0,
            dlvPartsDone = 2, dlvPartsPending = 0, dlvPartsFailed = 0, partCount = 3
        )
        assertNotEquals(
            "a missing DLR must never be rounded up to delivered",
            Telephony.Sms.STATUS_COMPLETE,
            oneMissing
        )
    }

    @Test
    fun `one refused part fails the aggregate`() {
        val status = SmsStatusPolicy.nextStatus(
            sentConfirmedParts = 2, sentUnconfirmedParts = 0, sentFailedParts = 0,
            dlvPartsDone = 2, dlvPartsPending = 0, dlvPartsFailed = 1, partCount = 3
        )

        assertEquals(Telephony.Sms.STATUS_FAILED, status)
    }
}
