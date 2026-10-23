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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.autonomousone.messages.R
import com.autonomousone.messages.evechat.EveChatSession
import com.autonomousone.messages.evechat.messageRes
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
            text = stringResource(R.string.eve_notifications_off),
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
        }) { Text(stringResource(R.string.eve_notifications_enable)) }
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
        title = { Text(stringResource(R.string.eve_status_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusLine(
                    stringResource(R.string.eve_status_connection),
                    stringResource(connection.messageRes())
                )
                StatusLine(
                    stringResource(R.string.eve_status_push),
                    stringResource(pushStatus.messageRes())
                )
                if (lastPushAt > 0) {
                    StatusLine(stringResource(R.string.eve_status_last_push), formatFullTimestamp(lastPushAt))
                }
                StatusLine(
                    stringResource(R.string.eve_status_polling),
                    stringResource(
                        if (pushStatus == EveChatPushRegistrar.Status.ACTIVE) {
                            R.string.eve_status_poll_30
                        } else {
                            R.string.eve_status_poll_15
                        }
                    )
                )
                StatusLine(stringResource(R.string.eve_status_server), server)
                StatusLine(stringResource(R.string.eve_status_account), email)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    )
}

@Composable
private fun StatusLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}


