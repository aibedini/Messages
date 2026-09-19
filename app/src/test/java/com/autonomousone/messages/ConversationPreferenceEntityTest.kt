package com.autonomousone.messages

import com.autonomousone.messages.data.ConversationPreferenceEntity
import com.autonomousone.messages.data.MessageCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-conversation user-state semantics (v3.4.0 FEATURES 2/3/12/15).
 *
 * These are the rules the sync engine and the notification path both rely on.
 * Mute expiry is deliberately a COMPARISON, never a scheduled job: an expired
 * mute simply stops matching.
 */
class ConversationPreferenceEntityTest {

    private val hour = 60L * 60 * 1000

    @Test
    fun `mute expires by comparison - no worker needed`() {
        val muted = ConversationPreferenceEntity(
            threadId = 7,
            mutedUntil = 1_000_000L
        )
        assertTrue("inside the window", muted.isMuted(now = 999_999L))
        assertFalse("window elapsed", muted.isMuted(now = 1_000_000L))
        assertFalse("well past the window", muted.isMuted(now = 5_000_000L))
    }

    @Test
    fun `mute forever survives any future time`() {
        val forever = ConversationPreferenceEntity(
            threadId = 7,
            mutedUntil = ConversationPreferenceEntity.MUTE_FOREVER
        )
        assertTrue(forever.isMuted(now = 0L))
        assertTrue(forever.isMuted(now = Long.MAX_VALUE / 2))
    }

    @Test
    fun `zero mute means never muted`() {
        val active = ConversationPreferenceEntity(threadId = 7, mutedUntil = 0L)
        assertFalse(active.isMuted(now = 0L))
        assertFalse(active.isMuted(now = 1L))
    }

    @Test
    fun `stale mute in the past is not muted`() {
        val stale = ConversationPreferenceEntity(
            threadId = 7,
            mutedUntil = 1_000L
        )
        assertFalse(stale.isMuted(now = 2_000L))
    }

    @Test
    fun `finite mute of one hour`() {
        val now = 1_000_000L
        val oneHour = ConversationPreferenceEntity(
            threadId = 7,
            mutedUntil = now + hour
        )
        assertTrue(oneHour.isMuted(now))
        assertFalse(oneHour.isMuted(now + hour))
    }

    @Test
    fun `user category override always wins over the automatic category`() {
        val overridden = ConversationPreferenceEntity(
            threadId = 7,
            categoryOverride = MessageCategory.PROMOTION.name
        )
        assertEquals(
            MessageCategory.PROMOTION,
            overridden.effectiveCategory(automatic = MessageCategory.OTP)
        )
    }

    @Test
    fun `no override falls back to the automatic category`() {
        val automatic = ConversationPreferenceEntity(threadId = 7)
        assertEquals(
            MessageCategory.OTP,
            automatic.effectiveCategory(automatic = MessageCategory.OTP)
        )
    }

    @Test
    fun `unknown stored category name falls back to UNKNOWN`() {
        val broken = ConversationPreferenceEntity(
            threadId = 7,
            categoryOverride = "NOT_A_CATEGORY"
        )
        assertEquals(
            MessageCategory.UNKNOWN,
            broken.effectiveCategory(automatic = MessageCategory.PERSONAL)
        )
    }

    @Test
    fun `unknown enum name resolves to UNKNOWN`() {
        assertEquals(
            MessageCategory.UNKNOWN,
            MessageCategory.from("GIBBERISH")
        )
        assertEquals(
            MessageCategory.SPAM,
            MessageCategory.from("SPAM")
        )
        assertEquals(
            MessageCategory.UNKNOWN,
            MessageCategory.from(null)
        )
    }

    @Test
    fun `fresh defaults are inert - nothing is unread muted or spam`() {
        val fresh = ConversationPreferenceEntity(threadId = 7)
        assertFalse(fresh.manualUnread)
        assertEquals(0L, fresh.mutedUntil)
        assertFalse(fresh.customNotificationChannel)
        assertEquals(null, fresh.categoryOverride)
        assertFalse(fresh.spam)
        assertEquals(0L, fresh.spamReportedAt)
        assertFalse(fresh.spamBlockedByReport)
    }
}