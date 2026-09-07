package com.autonomousone.messages

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.autonomousone.messages.utils.DiagnosticLog

/**
 * App-owned process hooks.
 *
 * Global crash context: coroutine jobs inside ViewModels are guarded, but a
 * crash can still come from the provider callbacks, notification actions, or
 * third-party receivers. Without a handler the process dies and logcat
 * rotates past it before anyone looks — the user just sees "the app closed".
 * We LOG the full context and delegate to the previous default handler so
 * the platform still produces its standard tombstone/ANR trail. Never
 * swallow: a dead process with a written log beats a dead process blind.
 */
class MessagesApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Holders.init(this)
        DiagnosticLog.initialize(this)
        // Phase 2 observability: missing runtime permissions and Doze-style
        // battery restrictions are the two most common "works in code, silent
        // on device" causes for the SMS pipeline. Log both at process start.
        logMissingRuntimePermissions()
        maybeWarnBatteryRestriction()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                // getRunningTasks only returns our own stack since API 21 and
                // Activity callbacks don't reach here cheaply — keep the
                // line honest: thread name + full trace. The ViewModels'
                // crashGuard logs carry the structured fields (threadId,
                // page, syncState); this one is the last-resort net.
                Log.e("CRASH_GUARD", "Uncaught on '${thread.name}'", error)
                DiagnosticLog.event("CRASH", "uncaught thread=${thread.name}", error)
            } catch (_: Throwable) {
                // Logging must never mask the original crash.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Log any runtime-critical permission that is currently denied. */
    private fun logMissingRuntimePermissions() {
        val required = listOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_CONTACTS,
        )
        for (permission in required) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                Log.w("Permissions", "missing_runtime_permission name=$permission")
            }
        }
    }

    /**
     * One-time battery-restriction warning. Doze kills background work (outbox
     * drain, command poll, observer callbacks); a whitelist keeps the gateway
     * alive. Logs at WARN and posts a single high-priority notification that
     * opens the platform's battery-unrestricted settings page.
     */
    private fun maybeWarnBatteryRestriction() {
        val pm = getSystemService(PowerManager::class.java)
        val whitelisted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(packageName)
        } else {
            true
        }
        if (whitelisted) {
            Log.i("BatteryOptimization", "app_whitelisted=true")
            return
        }
        Log.w("BatteryOptimization", "app_not_whitelisted — background sync may be killed")
        val prefs: SharedPreferences = getSharedPreferences("runtime_diagnostics", Context.MODE_PRIVATE)
        if (prefs.getBoolean("battery_prompt_shown", false)) return
        prefs.edit().putBoolean("battery_prompt_shown", true).apply()
        try {
            val channelId = "gmweb_battery"
            val nm = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "Background sync", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val settingsIntent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            val pi = PendingIntent.getActivity(
                this, 0, settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            nm.notify(
                4201,
                NotificationCompat.Builder(this, channelId)
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle("Allow background sync")
                    .setContentText("Open Settings → Battery → Unrestricted so incoming SMS reach the browser.")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (e: Exception) {
            Log.w("BatteryOptimization", "battery prompt notification failed", e)
        }
    }
}
