package com.autonomousone.messages.repository

import com.autonomousone.messages.data.ConversationPreferenceEntity

/**
 * Mute presets for Conversation Info (v3.4.0 FEATURE 5).
 *
 * THE MUTE RULE (why this object exists)
 * -------------------------------------
 * Mute is stored as ONE absolute instant on the canonical preference row
 * (`conversation_preferences.mutedUntil`) and expires BY COMPARISON — there is
 * no unmute worker and no per-thread job to leak. Consequences this type makes
 * explicit:
 *
 *  - [ConversationPreferenceEntity.MUTE_FOREVER] (`Long.MAX_VALUE`) means "until
 *    unmuted"; a comparison against it is always `> now`, so it never needs a
 *    special case downstream.
 *  - Unmute writes `0`, which is also what an absent row means, so "never muted"
 *    and "unmuted" are the same durable state instead of two.
 *  - Muting is INDEPENDENT of the per-conversation notification channel: a muted
 *    conversation keeps its channel configuration, and customising the channel
 *    never changes the mute instant.
 *
 * Pure and Android-free on purpose: the arithmetic is unit-tested on the JVM
 * (ConversationMuteTest) without Room, Context or a ContentResolver.
 */
object ConversationMute {

    /** One hour, the shortest preset. */
    const val HOUR = 60L * 60L * 1000L

    /** The offered durations. [Forever] has no end instant. */
    enum class Preset(val durationMillis: Long?) {
        ONE_HOUR(HOUR),
        EIGHT_HOURS(8L * HOUR),
        ONE_DAY(24L * HOUR),
        SEVEN_DAYS(7L * 24L * HOUR),

        /**
         * No end: stored as [ConversationPreferenceEntity.MUTE_FOREVER]. Expiry is
         * therefore never reached by comparison and only `unmute` ends it.
         */
        FOREVER(null)
    }

    /**
     * The instant a newly chosen preset mutes until.
     *
     * Saturating on purpose: `now + duration` can only overflow for a `now`
     * within ~292 million years of [Long.MAX_VALUE], but a wrapped negative value
     * would read as "not muted" and silently drop the user's mute. [now] values
     * that are not positive (0 = unset, negative = a clock before the epoch) mean
     * there is no defined calendar instant, so the only honest answer is
     * "forever" — never an instant in the past, which would look like a mute the
     * app refused to apply.
     */
    fun mutedUntil(now: Long, preset: Preset): Long {
        val duration = preset.durationMillis ?: return ConversationPreferenceEntity.MUTE_FOREVER
        if (now <= 0L) return ConversationPreferenceEntity.MUTE_FOREVER
        val end = now + duration
        return if (end < now) ConversationPreferenceEntity.MUTE_FOREVER else end
    }

    /**
     * Muted RIGHT NOW? Mirrors [ConversationPreferenceEntity.isMuted] and the
     * `observeMutedThreadIds` SQL predicate; kept as one call so screens, tests
     * and the notification gate cannot drift apart.
     */
    fun isMuted(mutedUntil: Long, now: Long): Boolean =
        mutedUntil == ConversationPreferenceEntity.MUTE_FOREVER ||
            (mutedUntil > 0L && mutedUntil > now)

    /** The instant an UNMUTE writes. */
    const val NOT_MUTED: Long = 0L
}

/**
 * How the Notifications row should read, as a closed set instead of a nullable
 * string. The screen renders each case with its own localized string; the label
 * LOGIC (which case applies) is what this type pins, so it is testable without
 * Android.
 */
sealed interface MuteStatus {

    /** Not muted: the row offers the presets. */
    data object NotMuted : MuteStatus

    /** Muted with no end instant: the row offers Unmute. */
    data object MutedForever : MuteStatus

    /** Muted until [until]: the row shows "Muted until …" and offers Unmute. */
    data class MutedUntil(val until: Long) : MuteStatus

    val isMuted: Boolean
        get() = this !is NotMuted

    val mutedUntil: Long
        get() = when (this) {
            NotMuted -> ConversationMute.NOT_MUTED
            MutedForever -> ConversationPreferenceEntity.MUTE_FOREVER
            is MutedUntil -> until
        }

    companion object {
        /**
         * Derives the row state from the stored instant. An EXPIRED mute is
         * [NotMuted]: expiry is a comparison, so a stale past instant must never
         * keep the row claiming "muted".
         */
        fun from(mutedUntil: Long, now: Long): MuteStatus = when {
            mutedUntil == ConversationPreferenceEntity.MUTE_FOREVER -> MutedForever
            ConversationMute.isMuted(mutedUntil, now) -> MutedUntil(mutedUntil)
            else -> NotMuted
        }
    }
}
