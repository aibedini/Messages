package com.autonomousone.messages.evechat.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.autonomousone.messages.evechat.EveChatSession
import com.autonomousone.messages.evechat.push.EveChatPushRegistrar
import com.autonomousone.messages.utils.formatFullTimestamp

private fun notificationsAllowed(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return false
    return NotificationManagerCompat.from(context).areNotificationsEnabled()
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Push and polling both end in a notification; without permission the agent misses chats silently. */
@Composable
fun NotificationsOffBanner() {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(notificationsAllowed(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) allowed = notificationsAllowed(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = notificationsAllowed(context)
    }
    if (allowed) return

    fun openSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "اعلان‌ها خاموش است؛ پیام‌های جدید پشتیبانی را از دست می‌دهید",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = {
            val activity = context.findActivity()
            val canAsk = Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                (activity == null || ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS) ||
                    !askedBefore(context))
            if (canAsk) {
                markAsked(context)
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                openSettings()
            }
        }) { Text("فعال‌سازی") }
    }
}

private const val PREFS = "evechat_ui"
private const val KEY_ASKED = "notification_permission_asked"

private fun askedBefore(context: Context) =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)

private fun markAsked(context: Context) =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ASKED, true).apply()

@Composable
fun SyncStatusDialog(
    connection: EveChatSession.Connection,
    pushStatus: EveChatPushRegistrar.Status,
    lastPushAt: Long,
    server: String,
    email: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("وضعیت همگام‌سازی") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusLine("اتصال زنده", connectionLabel(connection))
                StatusLine("اعلان لحظه‌ای (FCM)", pushLabel(pushStatus))
                if (lastPushAt > 0) StatusLine("آخرین اعلان دریافتی", formatFullTimestamp(lastPushAt))
                StatusLine(
                    "بررسی دوره‌ای در پس‌زمینه",
                    if (pushStatus == EveChatPushRegistrar.Status.ACTIVE) "هر ۳۰ دقیقه" else "هر ۱۵ دقیقه"
                )
                StatusLine("سرور", server)
                StatusLine("حساب", email)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("بستن") } }
    )
}

@Composable
private fun StatusLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

internal fun pushLabel(status: EveChatPushRegistrar.Status) = when (status) {
    EveChatPushRegistrar.Status.ACTIVE -> "فعال"
    EveChatPushRegistrar.Status.REGISTERING -> "در حال ثبت دستگاه…"
    EveChatPushRegistrar.Status.FAILED -> "ثبت ناموفق؛ خودکار دوباره تلاش می‌شود"
    EveChatPushRegistrar.Status.SERVER_UNSUPPORTED -> "سرور هنوز از اعلان لحظه‌ای پشتیبانی نمی‌کند"
    EveChatPushRegistrar.Status.NOT_CONFIGURED -> "Firebase در این نسخه برنامه پیکربندی نشده است"
    EveChatPushRegistrar.Status.NO_PLAY_SERVICES -> "سرویس‌های Google Play روی این دستگاه در دسترس نیست"
    EveChatPushRegistrar.Status.DISABLED -> "غیرفعال"
}
