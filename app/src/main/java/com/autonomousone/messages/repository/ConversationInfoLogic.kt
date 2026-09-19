package com.autonomousone.messages.repository

import com.autonomousone.messages.data.ConversationPreferenceEntity
import com.autonomousone.messages.data.MessageCategory

/**
 * Conversation Info (v3.4.0 FEATURE 5) — the SCREEN STATE derivation, kept
 * Android-free.
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * A screen that "looks right" is not enough: every row has a state that must be
 * derived from durable user state (mute instant, channel flag, category
 * override, spam report, starred count). Deriving those states inline inside
 * composables would make them untestable, so every non-trivial derivation lives
 * here as a pure function, and the ViewModel only FEEDS it values.
 *
 * Nothing here writes: writes go through [ConversationPreferenceRepository],
 * [MessageUserStateRepository] and [SpamRepository], which own the durable
 * columns. This object decides what the user is TOLD, never what is stored.
 */
object ConversationInfoLogic {

    /** Where a tappable Conversation Info row leads. */
    enum class Destination {
        /** The Media / Links / Files browser for this conversation. */
        MEDIA,

        /** The in-conversation starred list. */
        STARRED,

        /** The global starred browser (not used by a row today; kept total). */
        STARRED_GLOBAL
    }

    /** The header's contact action, mirroring the v3.3.6 participant action. */
    enum class ContactActionLabel {
        ADD_TO_CONTACTS,
        VIEW_CONTACT,
        NONE
    }

    /**
     * A category the user can pick, or the automatic sentinel.
     *
     * [AUTOMATIC] writes `null` to `categoryOverride` — it is not a category,
     * it is the absence of an override, which is exactly why it must be a
     * distinct item in the picker rather than a "NONE" category.
     */
    data class CategoryChoice(val category: MessageCategory?)

    /**
     * Starred-count state, three-valued on purpose.
     *
     * [Unknown] (the count has not arrived, or the query failed) must NOT render
     * as "0": claiming an empty starred list while the count is still loading is
     * a lie the user can see through. The row shows a dash/placeholder instead.
     */
    sealed interface StarredCount {
        data object Unknown : StarredCount
        data object Empty : StarredCount
        data class Value(val count: Int) : StarredCount

        val knownCount: Int?
            get() = when (this) {
                Unknown -> null
                Empty -> 0
                is Value -> count
            }

        /** Badge text, or null when the section must show no number at all. */
        fun badge(): String? = knownCount?.toString()
    }

    /** Mute presets in the order the dialog offers them. */
    val MUTE_PRESETS: List<ConversationMute.Preset> = listOf(
        ConversationMute.Preset.ONE_HOUR,
        ConversationMute.Preset.EIGHT_HOURS,
        ConversationMute.Preset.ONE_DAY,
        ConversationMute.Preset.SEVEN_DAYS,
        ConversationMute.Preset.FOREVER
    )

    /** Categories the picker offers, automatic first. */
    val CATEGORY_CHOICES: List<CategoryChoice> = listOf(
        CategoryChoice(null),
        CategoryChoice(MessageCategory.PERSONAL),
        CategoryChoice(MessageCategory.OTP),
        CategoryChoice(MessageCategory.TRANSACTION),
        CategoryChoice(MessageCategory.PROMOTION),
        CategoryChoice(MessageCategory.SPAM),
        CategoryChoice(MessageCategory.UNKNOWN)
    )

    /** Custom-notifications row state. */
    enum class NotificationChannelMode {
        /** The conversation uses the app's global channel. */
        DEFAULT,

        /** A per-conversation channel exists; Android owns its sound/vibration. */
        CUSTOM
    }

    /**
     * Notifications row. [status] distinguishes "not muted" from "muted until X"
     * from "muted forever", so the screen never has to re-derive it from a Long.
     */
    data class NotificationsRowState(
        val status: MuteStatus,
        val actionLabel: NotificationAction
    ) {
        val isMuted: Boolean get() = status.isMuted

        /** Absolute instant the row should render as "Muted until …", if any. */
        val showMutedUntil: Long?
            get() = (status as? MuteStatus.MutedUntil)?.until
    }

    /** Which primary action the Notifications row offers. */
    enum class NotificationAction { MUTE, UNMUTE }

    /** Custom notifications row. */
    data class CustomNotificationRowState(
        val mode: NotificationChannelMode,
        /** True when picking Custom must RE-OPEN the system settings. */
        val opensSystemSettingsDirectly: Boolean
    ) {
        val isCustom: Boolean get() = mode == NotificationChannelMode.CUSTOM
    }

    /** The complete per-row state the Conversation Info screen renders. */
    data class Sections(
        val notifications: NotificationsRowState,
        val customNotifications: CustomNotificationRowState,
        val media: Destination?,
        val starred: Destination?,
        val starredCount: StarredCount,
        val category: CategoryChoice,
        val categoryIsAutomatic: Boolean,
        val spamReported: Boolean,
        /** True when the address is currently on the user's blocklist. */
        val blocked: Boolean,
        /** False when the thread has no dialable single number (e.g. a group). */
        val canBlock: Boolean
    )

    // ── Row-state derivations ───────────────────────────────────────────────

    /**
     * Notifications row.
     *
     * [now] is passed in rather than read here so the expiry comparison is
     * deterministic in tests and so the whole row state is a pure function of
     * stored state + one clock read.
     */
    fun notificationsRow(mutedUntil: Long, now: Long): NotificationsRowState {
        val status = MuteStatus.from(mutedUntil, now)
        return NotificationsRowState(
            status = status,
            actionLabel = if (status.isMuted) NotificationAction.UNMUTE else NotificationAction.MUTE
        )
    }

    /**
     * Custom notifications row.
     *
     * A conversation that already has its own channel re-opens the SYSTEM sheet
     * when the row is tapped: the channel is the authority for sound/vibration,
     * so there is nothing left for this app to configure and offering an
     * app-side "choose a sound" screen would be a fake setting.
     */
    fun customNotificationsRow(customChannelEnabled: Boolean): CustomNotificationRowState =
        CustomNotificationRowState(
            mode = if (customChannelEnabled) {
                NotificationChannelMode.CUSTOM
            } else {
                NotificationChannelMode.DEFAULT
            },
            opensSystemSettingsDirectly = customChannelEnabled
        )

    /** Starred count from a nullable/absent count. */
    fun starredCount(count: Int?): StarredCount = when {
        count == null -> StarredCount.Unknown
        count <= 0 -> StarredCount.Empty
        else -> StarredCount.Value(count)
    }

    /**
     * Category row: the OVERRIDE when set, otherwise the automatic placeholder.
     *
     * The rule (pinned by test): `null` override means Automatic. An override
     * always wins over the automatic classifier, and a blank stored value is
     * treated as absent rather than as an unknown category — a blank override is
     * data corruption, not a user choice.
     */
    fun categoryChoice(categoryOverride: String?): CategoryChoice {
        val raw = categoryOverride?.takeIf { it.isNotBlank() } ?: return CategoryChoice(null)
        return CategoryChoice(MessageCategory.from(raw))
    }

    fun isAutomatic(choice: CategoryChoice): Boolean = choice.category == null

    /**
     * The effective category shown to the user, reusing the spam precedence so
     * the row agrees with Home's filter: an active spam report outranks the
     * override, which outranks the classifier.
     */
    fun effectiveCategory(
        preference: ConversationPreferenceEntity?,
        automatic: MessageCategory?
    ): MessageCategory = SpamReportPolicy.effectiveCategory(preference, automatic)

    /** Spam & blocking section state. */
    fun spamSection(
        spamReported: Boolean,
        blocked: Boolean,
        canBlock: Boolean
    ): SpamSectionState = SpamSectionState(
        spamReported = spamReported,
        blocked = blocked,
        canBlock = canBlock
    )

    /** Contact action label for the header, from the v3.3.6 action vocabulary. */
    fun contactActionLabel(action: ParticipantContactAction): ContactActionLabel = when (action) {
        ParticipantContactAction.ADD_TO_CONTACTS -> ContactActionLabel.ADD_TO_CONTACTS
        ParticipantContactAction.VIEW_CONTACT -> ContactActionLabel.VIEW_CONTACT
        ParticipantContactAction.NONE -> ContactActionLabel.NONE
    }

    fun sections(
        preference: ConversationPreferenceEntity?,
        now: Long,
        starredCount: Int?,
        blocked: Boolean,
        participant: ConversationParticipantState,
        assetCount: Int? = null
    ): Sections = Sections(
        notifications = notificationsRow(preference?.mutedUntil ?: 0L, now),
        customNotifications = customNotificationsRow(
            preference?.customNotificationChannel == true
        ),
        // A destination is offered when there is something to open; a
        // conversation with no indexed assets still opens the browser (it has
        // its own Empty state), so media is always navigable for a real thread.
        media = Destination.MEDIA,
        starred = Destination.STARRED,
        starredCount = starredCount(starredCount),
        category = categoryChoice(preference?.categoryOverride),
        categoryIsAutomatic = categoryChoice(preference?.categoryOverride).category == null,
        spamReported = preference?.spam == true,
        blocked = blocked,
        canBlock = participant.hasDialableNumber
    )

    /**
     * The media row's secondary text: the indexed-asset count when it is known,
     * otherwise null so the row shows no fabricated number. `null` also covers
     * "not loaded yet" (`assetCount == null`) and the empty case (`0`).
     */
    fun mediaSubtitle(assetCount: Int?): Int? = assetCount?.takeIf { it > 0 }
}

/** Spam & blocking section state (kept separate so it is easy to assert on). */
data class SpamSectionState(
    val spamReported: Boolean,
    val blocked: Boolean,
    val canBlock: Boolean
) {
    /** Block vs Unblock label state. */
    val blockAction: BlockAction get() = if (blocked) BlockAction.UNBLOCK else BlockAction.BLOCK

    val reportAction: ReportAction
        get() = if (spamReported) ReportAction.NOT_SPAM else ReportAction.REPORT_SPAM

    enum class BlockAction { BLOCK, UNBLOCK }

    enum class ReportAction { REPORT_SPAM, NOT_SPAM }
}
