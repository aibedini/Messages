package com.autonomousone.messages

import com.autonomousone.messages.data.MessageEntity
import com.autonomousone.messages.repository.TrashProviderRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PROVIDER range a purge may delete (v3.4.0 FEATURE 8).
 *
 * A purge is irreversible and happens on the user's real SMS database, so the
 * boundary arithmetic is pinned here instead of being buried in a
 * ContentResolver call. Two rules matter most:
 *
 *  1. a message NEWER than the tombstone cutoff is never in the range — that is
 *     the message that brought the conversation back;
 *  2. a row on the SAME date from the OTHER source is never in the range (the
 *     cross-source guard `MessageCutoff` documents), so a brand-new SMS that
 *     shares the millisecond of an MMS cutoff cannot be destroyed by a purge.
 *
 * The MMS unit trap is pinned too: `Telephony.Mms.DATE` is in SECONDS while the
 * whole app model is in milliseconds.
 */
class TrashProviderRangeTest {

    private val cutoffDate = 1_700_000_000_000L

    private fun smsRange(cutoffSource: String = MessageEntity.SOURCE_SMS, cutoffId: Long = 100L) =
        TrashProviderRange.selectionFor(
            source = MessageEntity.SOURCE_SMS,
            threadId = 7L,
            cutoffDate = cutoffDate,
            cutoffSource = cutoffSource,
            cutoffProviderId = cutoffId
        )!!

    private fun mmsRange(cutoffSource: String = MessageEntity.SOURCE_MMS, cutoffId: Long = 100L) =
        TrashProviderRange.selectionFor(
            source = MessageEntity.SOURCE_MMS,
            threadId = 7L,
            cutoffDate = cutoffDate,
            cutoffSource = cutoffSource,
            cutoffProviderId = cutoffId
        )!!

    @Test
    fun `an unknown source has no provider range`() {
        assertNull(
            TrashProviderRange.selectionFor(
                source = "cloud",
                threadId = 7L,
                cutoffDate = cutoffDate,
                cutoffSource = MessageEntity.SOURCE_SMS,
                cutoffProviderId = 1L
            )
        )
        assertNull(
            "threadId 0 is not an addressable provider thread",
            TrashProviderRange.selectionFor(
                source = MessageEntity.SOURCE_SMS,
                threadId = 0L,
                cutoffDate = cutoffDate,
                cutoffSource = MessageEntity.SOURCE_SMS,
                cutoffProviderId = 1L
            )
        )
    }

    @Test
    fun `a same-source range is bounded at the cutoff row itself`() {
        val range = smsRange()
        assertTrue(
            "the cutoff row is part of the deleted snapshot",
            range.selection.contains("$SMS_DATE = ? AND $SMS_ID <= ?")
        )
        assertEquals(
            listOf("7", "$cutoffDate", "$cutoffDate", "100"),
            range.selectionArgs.toList()
        )
    }

    @Test
    fun `a cross-source range keeps every same-date row`() {
        val range = smsRange(cutoffSource = MessageEntity.SOURCE_MMS)
        assertFalse(
            "a same-date row from the other source is NEW and must not be deleted",
            range.selection.contains("$SMS_DATE = ?")
        )
        assertTrue(range.selection.contains("$SMS_DATE < ?"))
        assertEquals(listOf("7", "$cutoffDate"), range.selectionArgs.toList())
    }

    @Test
    fun `the mms range converts the cutoff to provider seconds`() {
        val range = mmsRange()
        val seconds = (cutoffDate / 1000L).toString()
        assertEquals(listOf("7", seconds, seconds, "100"), range.selectionArgs.toList())
        assertTrue(range.selection.contains("$MMS_DATE < ?"))
        assertTrue(range.selection.contains("$MMS_DATE = ? AND $MMS_ID <= ?"))
    }

    @Test
    fun `the mms range keeps a same-second sms cutoff out of the deleted set`() {
        val range = mmsRange(cutoffSource = MessageEntity.SOURCE_SMS)
        assertFalse(range.selection.contains("$MMS_DATE = ?"))
        assertEquals(listOf("7", (cutoffDate / 1000L).toString()), range.selectionArgs.toList())
    }

    @Test
    fun `every selection is thread-scoped`() {
        listOf(
            smsRange(),
            smsRange(cutoffSource = MessageEntity.SOURCE_MMS),
            mmsRange(),
            mmsRange(cutoffSource = MessageEntity.SOURCE_SMS)
        ).forEach { range ->
            assertTrue("a purge must never leave the thread", range.selection.startsWith("thread_id = ?"))
            assertEquals("7", range.selectionArgs.first())
        }
        assertNotNull(smsRange())
    }

    // ── The Kotlin mirror of the same rule ─────────────────────────────────

    @Test
    fun `the kotlin mirror agrees with the cutoff semantics`() {
        fun inRange(date: Long, source: String, id: Long, cutoffSource: String = MessageEntity.SOURCE_SMS) =
            TrashProviderRange.isInSnapshot(
                date = date,
                source = source,
                providerId = id,
                cutoffDate = cutoffDate,
                cutoffSource = cutoffSource,
                cutoffProviderId = 100L
            )

        assertTrue("older rows belong to the snapshot", inRange(cutoffDate - 1, MessageEntity.SOURCE_SMS, 5L))
        assertTrue("the cutoff row belongs to the snapshot", inRange(cutoffDate, MessageEntity.SOURCE_SMS, 100L))
        assertTrue("a same-date lower id belongs to the snapshot", inRange(cutoffDate, MessageEntity.SOURCE_SMS, 99L))
        assertFalse("a same-date higher id is NEW", inRange(cutoffDate, MessageEntity.SOURCE_SMS, 101L))
        assertFalse("a newer message is NEW", inRange(cutoffDate + 1, MessageEntity.SOURCE_SMS, 1L))
        assertFalse(
            "a same-date cross-source row is NEW (the guard against destroying a fresh SMS)",
            inRange(cutoffDate, MessageEntity.SOURCE_MMS, 1L)
        )
    }

    private companion object {
        // Provider column names, read from Telephony's compile-time constants.
        const val SMS_ID = android.provider.Telephony.Sms._ID
        const val SMS_DATE = android.provider.Telephony.Sms.DATE
        const val MMS_ID = android.provider.Telephony.Mms._ID
        const val MMS_DATE = android.provider.Telephony.Mms.DATE
    }
}
