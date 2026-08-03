package com.autonomousone.messages.utils

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.autonomousone.messages.MainActivity
import com.autonomousone.messages.R
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.receiver.NotificationActionReceiver
import com.autonomousone.messages.repository.ContactRepository

object NotificationHelper {

    private const val CHANNEL_ID = "messages_notification_channel"
    private const val CHANNEL_NAME = "SMS Messages"
    private const val CHANNEL_DESC = "Notifications for incoming SMS messages"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = CHANNEL_DESC
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

        // Tap action pending intent to open MainActivity
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("extra_thread_id", sms.threadId)
            putExtra("extra_phone", sms.sender)
            putExtra("extra_name", displayName)
        }

        val tapPendingIntent = PendingIntent.getActivity(
            context,
            sms.sender.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Title and body styling (with OTP highlight if detected)
        val titleText = if (otpCode != null) {
            "🔑 OTP: $otpCode • $displayName"
        } else {
            displayName
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(titleText)
            .setContentText(sms.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(sms.message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(tapPendingIntent)
            .setDefaults(NotificationCompat.DEFAULT_ALL)

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
