package com.autonomousone.messages

import com.autonomousone.messages.data.MessageCutoff
import com.autonomousone.messages.data.TrashedThreadEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tombstone correctness (v3.4.0 FEATURE 8).
 *
 * The invariant that matters most: a conversation deleted at T1 must STAY
 * hidden, and a message that arrives at T2 > T1 must be VISIBLE and re-create
 * the conversation. Hiding a brand-new message is the worst possible failure
 * mode, so these tests pin the cross-source guard that prevents exactly that.
 */
class MessageCutoffTest {

    /** A 360K-scale tombstone: newest row at deletion was an MMS at T1. */
    private val mmsCutoff = TrashedThreadEntity(
        threadId = 7,
        deletedAt = 1_000L,
        purgeAt = 30L * 24 * 60 * 60 * 1000,
        cutoffDate = 1_700_000_000_000L,
        cutoffSource = "mms",
        cutoffProviderId = 100
    )

    /** Same shape but the cutoff row was an SMS. */
    private val smsCutoff = TrashedThreadEntity(
        threadId = 7,
        deletedAt = 1_000L,
        purgeAt = 30L * 24 * 60 * 60 * 1000,
        cutoffDate = 1_700_000_000_000L,
        cutoffSource = "sms",
        cutoffProviderId = 100
    )

    @Test
    fun `no tombstone means everything is visible`() {
        assertTrue(MessageCutoff.isVisible(null, 0L, "sms", 0L))
    }

    @Test
    fun `messages older than the cutoff are hidden`() {
        assertTrue(MessageCutoff.isHidden(mmsCutoff, 1_699_999_999_999L, "sms", 99))
        assertTrue(MessageCutoff.isHidden(mmsCutoff, 1_600_000_000_000L, "mms", 500))
        assertFalse(MessageCutoff.isVisible(mmsCutoff, 1_699_999_999_999L, "sms", 99))
    }

    @Test
    fun `messages newer than the cutoff are visible`() {
        assertTrue(MessageCutoff.isVisible(mmsCutoff, 1_700_000_000_001L, "sms", 1))
        assertTrue(MessageCutoff.isVisible(mmsCutoff, 1_700_000_100_000L, "sms", 200))
    }

    @Test
    fun `the cutoff row itself belongs to the deleted snapshot`() {
        assertTrue(
            MessageCutoff.isHidden(mmsCutoff, mmsCutoff.cutoffDate, "mms", mmsCutoff.cutoffProviderId)
        )
        assertFalse(
            MessageCutoff.isVisible(mmsCutoff, mmsCutoff.cutoffDate, "mms", mmsCutoff.cutoffProviderId)
        )
    }

    @Test
    fun `a same-day same-source row with a LOWER provider id is part of the snapshot`() {
        // Provider ids are monotonic within a table, so a lower id is older and
        // belongs to the deleted history.
        assertTrue(MessageCutoff.isHidden(smsCutoff, smsCutoff.cutoffDate, "sms", 99))
        assertFalse(MessageCutoff.isVisible(smsCutoff, smsCutoff.cutoffDate, "sms", 99))
    }

    @Test
    fun `a same-day same-source row with a HIGHER provider id is a NEW message`() {
        assertTrue(MessageCutoff.isVisible(smsCutoff, smsCutoff.cutoffDate, "sms", 101))
        assertFalse(MessageCutoff.isHidden(smsCutoff, smsCutoff.cutoffDate, "sms", 101))
    }

    /**
     * THE GUARD: an SMS arriving in the same millisecond as an MMS cutoff must
     * NOT be swallowed by the tombstone. A naive lexicographic `<=` on
     * (date, source, providerId) would classify it as old history ("sms" <
     * "mms") and the user would silently never see it.
     */
    @Test
    fun `a new SMS sharing the millisecond of an MMS cutoff is visible`() {
        assertFalse(
            MessageCutoff.isHidden(mmsCutoff, mmsCutoff.cutoffDate, "sms", 1)
        )
        assertTrue(
            MessageCutoff.isVisible(mmsCutoff, mmsCutoff.cutoffDate, "sms", 1)
        )
    }

    @Test
    fun `a new MMS sharing the millisecond of an SMS cutoff is visible`() {
        assertTrue(
            MessageCutoff.isVisible(smsCutoff, smsCutoff.cutoffDate, "mms", 1)
        )
    }

    @Test
    fun `a zero provider id cutoff hides only its own row - a higher id is a NEW message`() {
        // A tombstone captured from a thread whose newest row had providerId 0
        // (e.g. an empty thread at trash time) must not hide rows that arrived
        // after it: only the at-or-before range is the deleted snapshot.
        val empty = mmsCutoff.copy(cutoffProviderId = 0L)
        assertTrue("the cutoff row itself is part of the snapshot",
            MessageCutoff.isHidden(empty, empty.cutoffDate, "mms", 0L))
        assertFalse("a higher provider id at the same date is NEW",
            MessageCutoff.isHidden(empty, empty.cutoffDate, "mms", 1L))
        assertTrue(
            MessageCutoff.isVisible(empty, empty.cutoffDate, "mms", 1L)
        )
    }

    /**
     * The SQL and the Kotlin mirror must be maintained together. These
     * assertions fail if someone edits one side without the other.
     */
    @Test
    fun `sql predicates agree with the kotlin mirror`() {
        val hidden = MessageCutoff.HIDDEN_BY_TOMBSTONE_SQL
        assertTrue("hides strictly older dates", hidden.contains("m.date < t.cutoffDate"))
        assertTrue(
            "same date + same source + <= provider id",
            hidden.contains("m.date = t.cutoffDate AND m.source = t.cutoffSource") &&
                hidden.contains("m.providerId <= t.cutoffProviderId")
        )
        assertFalse(
            "SQL must NOT hide a same-date cross-source row",
            hidden.contains("m.source <> t.cutoffSource")
        )

        val visible = MessageCutoff.VISIBLE_UNDER_TOMBSTONE_SQL
        assertTrue(
            "visible is the exact complement: different source is NEW",
            visible.contains("m.date = t.cutoffDate AND m.source <> t.cutoffSource")
        )
        assertTrue(visible.contains("m.providerId > t.cutoffProviderId"))
        assertFalse(visible.contains("m.providerId <= t.cutoffProviderId"))
    }
}