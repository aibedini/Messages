package com.autonomousone.messages.utils

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.autonomousone.messages.MainActivity
import com.autonomousone.messages.R
import com.autonomousone.messages.messaging.ConversationNotificationChannels
import com.autonomousone.messages.messaging.OtpDetector
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.navigation.AppLaunchIntent
import com.autonomousone.messages.receiver.NotificationActionReceiver
import com.autonomousone.messages.repository.ContactRepository
import com.autonomousone.messages.repository.ConversationPreferenceRepository
import com.autonomousone.messages.repository.BlocklistRepository

object NotificationHelper {

    /** Public since v3.4.0: conversation channels fall back to this id. */
    const val CHANNEL_ID = "messages_notification_channel"
    private const val CHANNEL_NAME = "SMS Messages"
    private const val CHANNEL_DESC = "Notifications for incoming SMS messages"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel_name), importance).apply {
                description = context.getString(R.string.notif_channel_desc)
                enableVibration(true)
                setShowBadge(true)
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Extract OTP or verification codes (4 to 8 digits) from message content.
     *
     * DELEGATES to [OtpDetector] — the single detection engine. Kept as a shim
     * because several call sites still reference the historical name; a second
     * OTP engine living beside the detector is exactly what v3.4.0 removes.
     */
    fun extractOtpCode(message: String): String? =
        OtpDetector.detect(sender = "", body = message)?.code

    fun showSmsNotification(context: Context, sms: Sms) {
        // Verify notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        // ── MUTE GATE (v3.4.0) ─────────────────────────────────────────────
        // A muted conversation is never POSTED locally. This suppresses the
        // Android alert ONLY: Room ingest, the gateway, linked-device sync and
        // the unread count are all untouched, and expiry is a comparison against
        // now — no unmute worker is scheduled, so an expired mute simply stops
        // matching. Runs before the channel is created, so muting never has the
        // side effect of creating a channel.
        val preferences = ConversationPreferenceRepository(context)
        val now = System.currentTimeMillis()
        if (preferences.isMutedBlocking(sms.threadId, now)) {
            DiagnosticLog.event("NOTIFICATION", "thread=${sms.threadId} suppressed=muted")
            return
        }
        // Blocked senders are also never notified (the blocklist owns this).
        if (BlocklistRepository.isBlocked(context, sms.sender)) {
            DiagnosticLog.event(
                "NOTIFICATION",
                "thread=${sms.threadId} suppressed=blocked phone=${DiagnosticLog.phoneToken(sms.sender)}"
            )
            return
        }

        createNotificationChannel(context)

        // Resolve contact name if available
        val contactMap = try {
            ContactRepository(context).getContactNameMap()
        } catch (e: Exception) {
            emptyMap()
        }

        val normalizedSender = ContactRepository.normalizePhone(sms.sender)
        val displayName = contactMap[normalizedSender] ?: contactMap[sms.sender] ?: sms.sender
        val notificationId = sms.sender.hashCode()

        // Check for OTP code in message
        val otpCode = extractOtpCode(sms.message)

        // v2.6.9: the tap used to put extra_thread_id/extra_phone/extra_name
        // on a plain MainActivity intent nobody parsed — tapping a
        // notification just opened the app. Now it carries a real action
        // MainActivity (AppLaunchIntent.parse) understands, plus a per-thread
        // data URI so PendingIntents of different threads stay distinct.
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            action = AppLaunchIntent.ACTION_OPEN_CONVERSATION
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(AppLaunchIntent.EXTRA_THREAD_ID, sms.threadId)
            putExtra(AppLaunchIntent.EXTRA_PHONE, sms.sender)
            putExtra(AppLaunchIntent.EXTRA_NAME, displayName)
            data = Uri.parse("messages://conversation/${sms.threadId}")
        }

        val requestCode = if (sms.threadId > 0L) {
            (sms.threadId xor (sms.threadId ushr 32)).toInt()
        } else {
            sms.sender.hashCode()
        }

        val tapPendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Title and body styling (with OTP highlight if detected)
        val titleText = if (otpCode != null) {
            "🔑 OTP: $otpCode • $displayName"
        } else {
            displayName
        }

        // ── CHANNEL SELECTION (v3.4.0) ─────────────────────────────────────
        // Default: the existing global channel, unchanged from v3.3.6. Only a
        // conversation the user explicitly customised gets its own channel, and
        // its flag was set at that moment (never proactively per contact).
        val notificationChannelId = if (preferences.hasCustomNotificationChannelBlocking(sms.threadId)) {
            ConversationNotificationChannels.ensure(context, sms.threadId, displayName)
        } else {
            CHANNEL_ID
        }

        // Quiet hours: notification still appears but silently (no sound/vibrate).
        val inQuietHours = QuietHoursPreferences(context).isInQuietWindow()
        val defaults = if (inQuietHours) 0 else NotificationCompat.DEFAULT_ALL

        val builder = NotificationCompat.Builder(context, notificationChannelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(titleText)
            .setContentText(sms.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(sms.message))
            .setPriority(if (inQuietHours) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            // Hide message body & OTP on the lock screen (public area shows app name only).
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(tapPendingIntent)
            .setDefaults(defaults)

        // 1. Copy OTP Action (if OTP detected)
        if (otpCode != null) {
            val copyOtpIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_COPY_OTP
                putExtra(NotificationActionReceiver.EXTRA_OTP_CODE, otpCode)
                putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            }
            val copyOtpPendingIntent = PendingIntent.getBroadcast(
                context,
                (sms.sender + "_otp").hashCode(),
                copyOtpIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val copyOtpAction = NotificationCompat.Action.Builder(
                R.mipmap.ic_launcher,
                "Copy $otpCode",
                copyOtpPendingIntent
            ).build()
            builder.addAction(copyOtpAction)
        }

        // 2. Inline Direct Reply Action (RemoteInput)
        val remoteInput = RemoteInput.Builder(NotificationActionReceiver.KEY_TEXT_REPLY)
            .setLabel("Reply...")
            .build()

        val replyIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_REPLY
            putExtra(NotificationActionReceiver.EXTRA_THREAD_ID, sms.threadId)
            putExtra(NotificationActionReceiver.EXTRA_PHONE, sms.sender)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            (sms.sender + "_reply").hashCode(),
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val replyAction = NotificationCompat.Action.Builder(
            R.mipmap.ic_launcher,
            "Reply",
            replyPendingIntent
        ).addRemoteInput(remoteInput).build()
        builder.addAction(replyAction)

        // 3. Mark as Read Action
        val markReadIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_MARK_READ
            putExtra(NotificationActionReceiver.EXTRA_THREAD_ID, sms.threadId)
            putExtra(NotificationActionReceiver.EXTRA_PHONE, sms.sender)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val markReadPendingIntent = PendingIntent.getBroadcast(
            context,
            (sms.sender + "_read").hashCode(),
            markReadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val markReadAction = NotificationCompat.Action.Builder(
            R.mipmap.ic_launcher,
            "Mark as read",
            markReadPendingIntent
        ).build()
        builder.addAction(markReadAction)

        // 4. Archive Action — NORMAL messages only (v3.4.0 FEATURE 16).
        //
        // Contextual priority instead of a fixed list: Android shows only ~3
        // collapsed actions, so the OTP notification spends its budget on
        // "Copy code / Reply / Mark as read" (copying the code is the whole
        // reason the user opened it), while a normal message gets
        // "Reply / Mark as read / Archive". Adding Archive to the OTP
        // notification would push the Copy action out of the collapsed view
        // exactly where it is most needed.
        if (otpCode == null) {
            val archiveIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_ARCHIVE
                putExtra(NotificationActionReceiver.EXTRA_THREAD_ID, sms.threadId)
                putExtra(NotificationActionReceiver.EXTRA_PHONE, sms.sender)
                putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            }
            val archivePendingIntent = PendingIntent.getBroadcast(
                context,
                (sms.sender + "_archive").hashCode(),
                archiveIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val archiveAction = NotificationCompat.Action.Builder(
                R.mipmap.ic_launcher,
                context.getString(R.string.notif_action_archive),
                archivePendingIntent
            ).build()
            builder.addAction(archiveAction)
        }

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }
}
