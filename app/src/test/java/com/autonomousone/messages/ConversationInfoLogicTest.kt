package com.autonomousone.messages

import com.autonomousone.messages.data.ConversationPreferenceEntity
import com.autonomousone.messages.data.MessageCategory
import com.autonomousone.messages.repository.ConversationInfoLogic
import com.autonomousone.messages.repository.ConversationMute
import com.autonomousone.messages.repository.ConversationParticipantActions
import com.autonomousone.messages.repository.ConversationParticipantState
import com.autonomousone.messages.repository.MuteStatus
import com.autonomousone.messages.repository.ParticipantContactAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Conversation Info row-state / section-state derivation (v3.4.0 FEATURE 5).
 *
 * Every row state is a pure function of durable user state plus one clock read,
 * so the WHOLE screen's visual contract is pinned here without Android, Room or
 * Compose.
 */
class ConversationInfoLogicTest {

    private val now = 1_700_000_000_000L

    private fun participant(
        phone: String = "+989121234567",
        known: Boolean = false,
        lookup: String? = null
    ) = ConversationParticipantState(
        phone = phone,
        normalizedPhone = phone,
        displayName = if (known) "Ali" else phone,
        isKnownContact = known,
        contactLookupUri = lookup
    )

    private fun preference(
        mutedUntil: Long = 0L,
        customChannel: Boolean = false,
        override: String? = null,
        spam: Boolean = false
    ) = ConversationPreferenceEntity(
        threadId = 7L,
        mutedUntil = mutedUntil,
        customNotificationChannel = customChannel,
        categoryOverride = override,
        spam = spam
    )

    // ── Notifications ───────────────────────────────────────────────────────

    @Test
    fun `not-muted row offers mute`() {
        val row = ConversationInfoLogic.notificationsRow(0L, now)
        assertFalse(row.isMuted)
        assertEquals(ConversationInfoLogic.NotificationAction.MUTE, row.actionLabel)
        assertNull(row.showMutedUntil)
    }

    @Test
    fun `muted row offers unmute and exposes the instant`() {
        val until = now + ConversationMute.HOUR
        val row = ConversationInfoLogic.notificationsRow(until, now)
        assertTrue(row.isMuted)
        assertEquals(ConversationInfoLogic.NotificationAction.UNMUTE, row.actionLabel)
        assertEquals(until, row.showMutedUntil)
    }

    @Test
    fun `forever-muted row offers unmute with no date to show`() {
        val row = ConversationInfoLogic.notificationsRow(
            ConversationPreferenceEntity.MUTE_FOREVER,
            now
        )
        assertTrue(row.isMuted)
        assertEquals(ConversationInfoLogic.NotificationAction.UNMUTE, row.actionLabel)
        assertNull("'Muted until <sentinel>' must never be rendered", row.showMutedUntil)
    }

    @Test
    fun `an expired mute renders as not muted`() {
        val row = ConversationInfoLogic.notificationsRow(now - 1, now)
        assertFalse(row.isMuted)
        assertEquals(ConversationInfoLogic.NotificationAction.MUTE, row.actionLabel)
        assertTrue(row.status is MuteStatus.NotMuted)
    }

    // ── Custom notifications ────────────────────────────────────────────────

    @Test
    fun `custom notifications row reflects the durable channel flag`() {
        val default = ConversationInfoLogic.customNotificationsRow(false)
        assertFalse(default.isCustom)
        assertEquals(
            ConversationInfoLogic.NotificationChannelMode.DEFAULT,
            default.mode
        )
        assertFalse(default.opensSystemSettingsDirectly)

        val custom = ConversationInfoLogic.customNotificationsRow(true)
        assertTrue(custom.isCustom)
        assertEquals(
            ConversationInfoLogic.NotificationChannelMode.CUSTOM,
            custom.mode
        )
        // Already custom → tapping the row RE-OPENS the system sheet, because the
        // channel (not this app) owns sound/vibration.
        assertTrue(custom.opensSystemSettingsDirectly)
    }

    // ── Starred count ───────────────────────────────────────────────────────

    @Test
    fun `starred count has three distinct states`() {
        assertEquals(ConversationInfoLogic.StarredCount.Unknown, ConversationInfoLogic.starredCount(null))
        assertEquals(ConversationInfoLogic.StarredCount.Empty, ConversationInfoLogic.starredCount(0))
        assertEquals(ConversationInfoLogic.StarredCount.Value(3), ConversationInfoLogic.starredCount(3))

        assertNull("unknown must not render as 0", ConversationInfoLogic.StarredCount.Unknown.badge())
        assertEquals("0", ConversationInfoLogic.StarredCount.Empty.badge())
        assertEquals("12", ConversationInfoLogic.StarredCount.Value(12).badge())
        assertNull(ConversationInfoLogic.StarredCount.Unknown.knownCount)
    }

    // ── Category ────────────────────────────────────────────────────────────

    @Test
    fun `null override is automatic`() {
        val choice = ConversationInfoLogic.categoryChoice(null)
        assertNull(choice.category)
        assertTrue(ConversationInfoLogic.isAutomatic(choice))
    }

    @Test
    fun `explicit override always wins`() {
        val choice = ConversationInfoLogic.categoryChoice(MessageCategory.PROMOTION.name)
        assertEquals(MessageCategory.PROMOTION, choice.category)
        assertFalse(ConversationInfoLogic.isAutomatic(choice))

        val effective = ConversationInfoLogic.effectiveCategory(
            preference(override = MessageCategory.PROMOTION.name),
            MessageCategory.PERSONAL
        )
        assertEquals(MessageCategory.PROMOTION, effective)
    }

    @Test
    fun `a blank stored override is treated as automatic not as unknown`() {
        // Blank is data corruption, not a user choice; claiming an "explicit
        // Unknown" would hide the classifier's real answer.
        val choice = ConversationInfoLogic.categoryChoice("  ")
        assertNull(choice.category)
        assertTrue(ConversationInfoLogic.isAutomatic(choice))
    }

    @Test
    fun `an unparsable override falls back to UNKNOWN explicitly`() {
        assertEquals(
            MessageCategory.UNKNOWN,
            ConversationInfoLogic.categoryChoice("NOT_A_CATEGORY").category
        )
    }

    @Test
    fun `an active spam report outranks the override`() {
        val effective = ConversationInfoLogic.effectiveCategory(
            preference(override = MessageCategory.PERSONAL.name, spam = true),
            MessageCategory.PERSONAL
        )
        assertEquals(MessageCategory.SPAM, effective)
    }

    @Test
    fun `with no override the automatic answer is used`() {
        assertEquals(
            MessageCategory.OTP,
            ConversationInfoLogic.effectiveCategory(preference(), MessageCategory.OTP)
        )
        assertEquals(
            MessageCategory.UNKNOWN,
            ConversationInfoLogic.effectiveCategory(preference(), null)
        )
    }

    // ── Spam & blocking ─────────────────────────────────────────────────────

    @Test
    fun `spam section maps block and report actions`() {
        val clean = ConversationInfoLogic.spamSection(spamReported = false, blocked = false, canBlock = true)
        assertEquals(
            com.autonomousone.messages.repository.SpamSectionState.BlockAction.BLOCK,
            clean.blockAction
        )
        assertEquals(
            com.autonomousone.messages.repository.SpamSectionState.ReportAction.REPORT_SPAM,
            clean.reportAction
        )

        val reported = ConversationInfoLogic.spamSection(spamReported = true, blocked = true, canBlock = true)
        assertEquals(
            com.autonomousone.messages.repository.SpamSectionState.BlockAction.UNBLOCK,
            reported.blockAction
        )
        assertEquals(
            com.autonomousone.messages.repository.SpamSectionState.ReportAction.NOT_SPAM,
            reported.reportAction
        )
    }

    @Test
    fun `a group participant cannot be blocked`() {
        val group = ConversationParticipantState(
            phone = "0912,0935",
            normalizedPhone = "0912,0935",
            displayName = "Group",
            isKnownContact = false
        )
        assertFalse(ConversationInfoLogic.spamSection(false, false, group.hasDialableNumber).canBlock)
    }

    // ── Contact action ──────────────────────────────────────────────────────

    @Test
    fun `contact action mirrors the v336 participant vocabulary`() {
        assertEquals(
            ConversationInfoLogic.ContactActionLabel.ADD_TO_CONTACTS,
            ConversationInfoLogic.contactActionLabel(
                ConversationParticipantActions.primaryContactAction(participant())
            )
        )
        assertEquals(
            ConversationInfoLogic.ContactActionLabel.VIEW_CONTACT,
            ConversationInfoLogic.contactActionLabel(
                ConversationParticipantActions.primaryContactAction(
                    participant(known = true, lookup = "content://contacts/lookup/1")
                )
            )
        )
        assertEquals(
            ConversationInfoLogic.ContactActionLabel.NONE,
            ConversationInfoLogic.contactActionLabel(
                ConversationParticipantActions.primaryContactAction(
                    ConversationParticipantState("", "", "", false)
                )
            )
        )
        assertEquals(ParticipantContactAction.NONE, ParticipantContactAction.NONE)
    }

    // ── Full section assembly ───────────────────────────────────────────────

    @Test
    fun `sections assemble from preference plus live counts`() {
        val until = now + ConversationMute.HOUR
        val sections = ConversationInfoLogic.sections(
            preference = preference(mutedUntil = until, customChannel = true, override = null),
            now = now,
            starredCount = 4,
            blocked = true,
            participant = participant(known = true, lookup = "content://c/1"),
            assetCount = 9
        )

        assertTrue(sections.notifications.isMuted)
        assertEquals(until, sections.notifications.showMutedUntil)
        assertTrue(sections.customNotifications.isCustom)
        assertEquals(ConversationInfoLogic.Destination.MEDIA, sections.media)
        assertEquals(ConversationInfoLogic.Destination.STARRED, sections.starred)
        assertEquals(ConversationInfoLogic.StarredCount.Value(4), sections.starredCount)
        assertTrue(sections.categoryIsAutomatic)
        assertTrue(sections.blocked)
        assertTrue(sections.canBlock)
        assertFalse(sections.spamReported)
    }

    @Test
    fun `sections with no state at all are all defaults`() {
        val sections = ConversationInfoLogic.sections(
            preference = null,
            now = now,
            starredCount = 0,
            blocked = false,
            participant = participant()
        )
        assertFalse(sections.notifications.isMuted)
        assertFalse(sections.customNotifications.isCustom)
        assertEquals(ConversationInfoLogic.StarredCount.Empty, sections.starredCount)
        assertTrue(sections.categoryIsAutomatic)
        assertFalse(sections.spamReported)
    }

    @Test
    fun `media subtitle shows a count only when there is something to show`() {
        assertNull("no indexed assets = no fabricated number", ConversationInfoLogic.mediaSubtitle(0))
        assertNull("not loaded yet = no number", ConversationInfoLogic.mediaSubtitle(null))
        assertEquals(7, ConversationInfoLogic.mediaSubtitle(7))
    }

    @Test
    fun `mute presets and category choices are offered in a stable order`() {
        assertEquals(
            listOf(
                ConversationMute.Preset.ONE_HOUR,
                ConversationMute.Preset.EIGHT_HOURS,
                ConversationMute.Preset.ONE_DAY,
                ConversationMute.Preset.SEVEN_DAYS,
                ConversationMute.Preset.FOREVER
            ),
            ConversationInfoLogic.MUTE_PRESETS
        )
        assertNull("Automatic must be offered first and be the null choice",
            ConversationInfoLogic.CATEGORY_CHOICES.first().category)
        assertEquals(
            MessageCategory.entries.toSet(),
            ConversationInfoLogic.CATEGORY_CHOICES.mapNotNull { it.category }.toSet()
        )
    }
}
