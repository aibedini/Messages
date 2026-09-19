package com.autonomousone.messages.messaging

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.autonomousone.messages.utils.NotificationHelper

/**
 * Per-conversation Android notification channels (v3.4.0 FEATURE 4).
 *
 * Android O+ channels are the REAL authority for custom sound/vibration, so
 * this object creates real system channels instead of inventing an app-side
 * setting that would disagree with the system UI.
 *
 * Channels are created ONLY when the user explicitly picks "Customize
 * notifications" for one conversation — never proactively for every contact.
 * A conversation that has no channel here keeps using the existing global
 * channel, so default behaviour is byte-for-byte what v3.3.6 did.
 *
 * The user's system sound/vibration choices are NEVER reset: [ensure] is an
 * idempotent create-or-update that only sets the channel NAME and importance.
 */
object ConversationNotificationChannels {

    /** Stable, collision-free per-thread channel id. */
    fun channelId(threadId: Long): String = "conversation_$threadId"

    /**
     * Creates (or renames) the conversation channel and returns the channel id
     * to post with.
     *
     * The returned id is ALWAYS valid: on API < 26 it is the global channel, and
     * on API 26+ a missing channel is created here. Callers therefore never need
     * a fallback branch of their own.
     */
    fun ensure(context: Context, threadId: Long, displayName: String): String {
        val id = channelId(threadId)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Pre-O there are no channels; the global channel is the only path.
            return NotificationHelper.CHANNEL_ID
        }
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(id)
        val desiredName = displayName.ifBlank { "Conversation $threadId" }
        if (existing == null) {
            val channel = NotificationChannel(
                id,
                desiredName,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for $desiredName"
                enableVibration(true)
                setShowBadge(true)
            }
            manager.createNotificationChannel(channel)
        } else if (existing.name != desiredName) {
            // Renaming is allowed; re-writing sound/vibration is NOT, because
            // that would discard the user's own choices in system settings.
            existing.name = desiredName
            existing.description = "Notifications for $desiredName"
            manager.createNotificationChannel(existing)
        }
        return id
    }

    /** True when the conversation currently has its own channel. */
    fun exists(context: Context, threadId: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.getNotificationChannel(channelId(threadId)) != null
    }

    /**
     * Opens the SYSTEM per-channel settings screen. Deep-linking to the system
     * sheet (rather than a fake in-app toggle) is the whole point: the OS owns
     * sound/vibration/importance and its state can be trusted.
     */
    fun openSystemSettings(context: Context, threadId: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, channelId(threadId))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            // Some OEM builds reject the deep link; fall back to the app
            // notification settings screen instead of crashing.
            try {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (_: Exception) {
                // Nothing actionable — the user can still reach it manually.
            }
        }
    }
}