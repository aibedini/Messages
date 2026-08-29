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
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.navigation.AppLaunchIntent
import com.autonomousone.messages.receiver.NotificationActionReceiver
import com.autonomousone.messages.repository.ContactRepository

object NotificationHelper {

    private const val CHANNEL_ID = "messages_notification_channel"
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
     */
    fun extractOtpCode(message: String): String? {
        if (message.isBlank()) return null
        val lowerMsg = message.lowercase()
        val keywords = listOf("otp", "code", "verification", "passcode", "pin", "security", "one time", "auth", "verif", "secret")
        val hasKeyword = keywords.any { lowerMsg.contains(it) }

        if (!hasKeyword) return null

        val regex = Regex("""\b(\d{4,8})\b""")
        val match = regex.find(message)
        return match?.groupValues?.get(1)
    }

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

        // Quiet hours: notification still appears but silently (no sound/vibrate).
        val inQuietHours = QuietHoursPreferences(context).isInQuietWindow()
        val defaults = if (inQuietHours) 0 else NotificationCompat.DEFAULT_ALL

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
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

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }
}
