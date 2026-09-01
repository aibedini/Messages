package com.autonomousone.messages.security

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.autonomousone.messages.R

/**
 * ADR-006 §11 — per-message ASK prompt for FINANCIAL_NOTIFICATION messages
 * when the user's financial policy is "Ask".
 *
 * PRIVACY CONTRACT: the notification carries NO message content, NO sender
 * beyond the resolved name the user already sees, NO body, NO OTP — only the
 * question "sync a financial notification from <name>?" and two actions.
 * Default (unanswered/swiped) = keep local, forever (§16 fail-closed).
 */
object AskPrompt {

    private const val CHANNEL_ID = "firewall_ask_channel"
    private const val TAG = "FIREWALL_ASK"

    const val ACTION_ALLOW_SYNC = "com.autonomousone.messages.FIREWALL_ALLOW_SYNC"
    const val ACTION_KEEP_LOCAL = "com.autonomousone.messages.FIREWALL_KEEP_LOCAL"
    const val EXTRA_SOURCE = "extra_ask_source"
    const val EXTRA_PROVIDER_ID = "extra_ask_provider_id"
    const val EXTRA_NOTIF_ID = "extra_ask_notif_id"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.firewall_ask_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.firewall_ask_channel_desc)
            }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    /** Content-free financial-sync prompt. Silent-ish importance, no vibration. */
    fun notifyFinancialAsk(context: Context, source: String, providerId: Long, sender: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return // no permission → silently stays local (fail-closed anyway)
        }
        createChannel(context)

        val notifId = ("ask_" + source + "_" + providerId).hashCode()

        val allowIntent = Intent(context, AskActionReceiver::class.java).apply {
            action = ACTION_ALLOW_SYNC
            putExtra(EXTRA_SOURCE, source)
            putExtra(EXTRA_PROVIDER_ID, providerId)
            putExtra(EXTRA_NOTIF_ID, notifId)
        }
        val denyIntent = Intent(context, AskActionReceiver::class.java).apply {
            action = ACTION_KEEP_LOCAL
            putExtra(EXTRA_SOURCE, source)
            putExtra(EXTRA_PROVIDER_ID, providerId)
            putExtra(EXTRA_NOTIF_ID, notifId)
        }

        val displayName = if (sender.isNotBlank()) sender else context.getString(R.string.firewall_ask_unknown_sender)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.firewall_ask_title))
            .setContentText(
                context.getString(R.string.firewall_ask_text, displayName)
            )
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.firewall_ask_text, displayName))
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SYSTEM)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setAutoCancel(true)
            .addAction(
                NotificationCompat.Action.Builder(
                    R.mipmap.ic_launcher,
                    context.getString(R.string.firewall_ask_allow),
                    PendingIntent.getBroadcast(
                        context, notifId, allowIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                ).build()
            )
            .addAction(
                NotificationCompat.Action.Builder(
                    R.mipmap.ic_launcher,
                    context.getString(R.string.firewall_ask_deny),
                    PendingIntent.getBroadcast(
                        context, notifId + 1, denyIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                ).build()
            )

        try {
            NotificationManagerCompat.from(context).notify(notifId, builder.build())
        } catch (e: SecurityException) {
            android.util.Log.w(TAG, "ask notification denied: ${e.message}")
        }
    }
}

/** Handles the two prompt actions; writes the durable per-message verdict. */
class AskActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val appContext = context.applicationContext ?: return
        val source = intent.getStringExtra(AskPrompt.EXTRA_SOURCE) ?: return
        val providerId = intent.getLongExtra(AskPrompt.EXTRA_PROVIDER_ID, 0L)
        val notifId = intent.getIntExtra(AskPrompt.EXTRA_NOTIF_ID, 0)

        when (intent.action) {
            AskPrompt.ACTION_ALLOW_SYNC -> AskPolicyLedger.allowSync(appContext, source, providerId)
            AskPrompt.ACTION_KEEP_LOCAL -> AskPolicyLedger.keepLocal(appContext, source, providerId)
        }
        if (notifId != 0) {
            NotificationManagerCompat.from(appContext).cancel(notifId)
        }
    }
}
