package com.autonomousone.messages

import com.autonomousone.messages.data.ConversationPreferenceEntity
import com.autonomousone.messages.repository.ConversationMute
import com.autonomousone.messages.repository.MuteStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mute arithmetic for Conversation Info (v3.4.0 FEATURE 5).
 *
 * Mute is stored as ONE absolute instant and expires BY COMPARISON, so the
 * arithmetic and the expiry rule are the whole feature: a mute that ends a
 * millisecond early, or an "expired mute" that keeps suppressing notifications,
 * is a user-visible bug.
 */
class ConversationMuteTest {

    private val hour = 60L * 60L * 1000L
    private val day = 24L * hour
    private val now = 1_700_000_000_000L

    @Test
    fun `each preset maps to its exact duration`() {
        assertEquals(now + hour, ConversationMute.mutedUntil(now, ConversationMute.Preset.ONE_HOUR))
        assertEquals(now + 8 * hour, ConversationMute.mutedUntil(now, ConversationMute.Preset.EIGHT_HOURS))
        assertEquals(now + day, ConversationMute.mutedUntil(now, ConversationMute.Preset.ONE_DAY))
        assertEquals(now + 7 * day, ConversationMute.mutedUntil(now, ConversationMute.Preset.SEVEN_DAYS))
    }

    @Test
    fun `forever is the documented sentinel and never expires by comparison`() {
        val until = ConversationMute.mutedUntil(now, ConversationMute.Preset.FOREVER)
        assertEquals(ConversationPreferenceEntity.MUTE_FOREVER, until)
        assertEquals(Long.MAX_VALUE, until)
        assertTrue(ConversationMute.isMuted(until, now))
        // Even far in the future the sentinel still reads as muted.
        assertTrue(ConversationMute.isMuted(until, Long.MAX_VALUE - 1))
    }

    @Test
    fun `unmute writes zero which is also the never-muted value`() {
        assertEquals(0L, ConversationMute.NOT_MUTED)
        assertFalse(ConversationMute.isMuted(ConversationMute.NOT_MUTED, now))
    }

    @Test
    fun `mute expires at the boundary by comparison only`() {
        val until = ConversationMute.mutedUntil(now, ConversationMute.Preset.ONE_HOUR)
        assertTrue("one millisecond before the deadline is still muted", ConversationMute.isMuted(until, until - 1))
        assertFalse("at the deadline the mute is over", ConversationMute.isMuted(until, until))
        assertFalse("after the deadline the mute is over", ConversationMute.isMuted(until, until + 1))
    }

    @Test
    fun `a mute is never scheduled in the past for an unusable clock`() {
        // now <= 0 means "no defined calendar instant": the honest answer is
        // forever, NOT an instant in the past (which would look like a mute the
        // app silently refused to apply).
        assertEquals(
            ConversationPreferenceEntity.MUTE_FOREVER,
            ConversationMute.mutedUntil(0L, ConversationMute.Preset.ONE_HOUR)
        )
        assertEquals(
            ConversationPreferenceEntity.MUTE_FOREVER,
            ConversationMute.mutedUntil(-5L, ConversationMute.Preset.SEVEN_DAYS)
        )
    }

    @Test
    fun `duration arithmetic saturates instead of wrapping negative`() {
        val almostMax = Long.MAX_VALUE - 10L
        val until = ConversationMute.mutedUntil(almostMax, ConversationMute.Preset.SEVEN_DAYS)
        // A wrapped (negative) value would read as "not muted" and drop the mute.
        assertEquals(ConversationPreferenceEntity.MUTE_FOREVER, until)
        assertTrue(ConversationMute.isMuted(until, almostMax))
    }

    // ── Row state ───────────────────────────────────────────────────────────

    @Test
    fun `mute status distinguishes not-muted, until and forever`() {
        assertEquals(MuteStatus.NotMuted, MuteStatus.from(0L, now))

        val until = now + hour
        assertEquals(MuteStatus.MutedUntil(until), MuteStatus.from(until, now))

        assertEquals(
            MuteStatus.MutedForever,
            MuteStatus.from(ConversationPreferenceEntity.MUTE_FOREVER, now)
        )
    }

    @Test
    fun `an expired mute is not-muted rather than a stale muted row`() {
        val expired = now - 1
        assertEquals(MuteStatus.NotMuted, MuteStatus.from(expired, now))
        assertFalse(MuteStatus.from(expired, now).isMuted)
    }

    @Test
    fun `mute status roundtrips back to the stored instant`() {
        val until = now + 7 * day
        assertEquals(ConversationPreferenceEntity.MUTE_FOREVER, MuteStatus.MutedForever.mutedUntil)
        assertEquals(until, MuteStatus.MutedUntil(until).mutedUntil)
        assertEquals(0L, MuteStatus.NotMuted.mutedUntil)
    }

    @Test
    fun `mute is independent of the custom notification channel`() {
        // A muted conversation keeps its channel configuration, and customising
        // the channel never changes the mute instant: the two live in different
        // columns and neither writer touches the other.
        val muted = ConversationPreferenceEntity(
            threadId = 7L,
            mutedUntil = now + hour,
            customNotificationChannel = true
        )
        assertTrue(muted.isMuted(now))
        assertTrue(muted.customNotificationChannel)
    }
}
