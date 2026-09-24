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
import com.autonomousone.messages.diagnostics.DebugFrameMetrics
import com.autonomousone.messages.diagnostics.DebugStrictMode
import com.autonomousone.messages.diagnostics.MainThreadStallWatchdog
import com.autonomousone.messages.diagnostics.ProcessExitDiagnostics
import com.autonomousone.messages.utils.DiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

    /**
     * App-lifetime scope for the Phase 14-18 diagnostics. IO only: none of the
     * startup diagnostic work may run on the main thread, and none of it is
     * allowed to delay [onCreate].
     */
    private val diagnosticsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Holders.init(this)
        DiagnosticLog.initialize(this)
        // Phase 2 observability: missing runtime permissions and Doze-style
        // battery restrictions are the two most common "works in code, silent
        // on device" causes for the SMS pipeline. Log both at process start.
        logMissingRuntimePermissions()
        maybeWarnBatteryRestriction()
        // FIX 2: an install that enrolled its agent identity but never
        // completed a real browser approve would otherwise never produce the
        // encrypted cloud history the PWA needs. Fire the idempotent backfill
        // once at startup (throttled to 7 days) so the history is waiting in
        // the outbox when the user finally links a browser.
        maybeTriggerStartupCloudBackfill()
        scheduleSmartCategoryBackfill()
        scheduleOutboxCleanup()
        scheduleInboundPersistRetry()
        reconcileMissedMessages()
        installDiagnostics()
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

    /**
     * FIX 2 startup trigger (enrolled but never linked / long-idle installs).
     * Runs at most once per 7 days. The actual crawl is idempotent and
     * executes on the coordinator's IO scope; this call only enqueues it.
     * Log tags match the FIX 2 logcat grep: `backfill_triggered_after_*`.
     */
    private fun maybeTriggerStartupCloudBackfill() {
        try {
            val prefs = com.autonomousone.messages.gateway.GatewayPreferences(this)
            if (!prefs.identityRegistered) {
                Log.d("SYNC_COORD", "startup cloud backfill skipped — identity not enrolled")
                return
            }
            val now = System.currentTimeMillis()
            val last = prefs.lastCloudBackfillRunAt
            if (last != 0L && now - last < 7L * 24 * 60 * 60 * 1000) {
                Log.d("SYNC_COORD", "startup cloud backfill skipped — ran ${now - last} ms ago")
                return
            }
            prefs.lastCloudBackfillRunAt = now
            Log.i("SYNC_COORD", "backfill_triggered_after_startup deviceId=app-start")
            com.autonomousone.messages.data.TelephonySyncCoordinator.get(this)
                .requestCloudBackfillForLinkedDevice("app-start")
        } catch (e: Throwable) {
            Log.w("SYNC_COORD", "startup cloud backfill schedule failed", e)
        }
    }

    /**
     * FEATURE 12 — Smart Categories history backfill.
     *
     * IDEMPOTENT and non-blocking: unique WorkManager work with KEEP policy, so
     * every app start collapses into the already-pending sweep instead of
     * stacking duplicates. The worker itself runs one bounded keyset batch per
     * wake-up and persists its cursor, so nothing here touches the 360K-message
     * history synchronously, and an interrupted sweep resumes exactly where it
     * stopped.
     */
    /**
     * ACKED retention (mission §19/§36) — the `gmweb-outbox-cleanup` worker.
     *
     * IDEMPOTENT and non-blocking, like the backfill above: unique PERIODIC work with a KEEP
     * policy, so every app start and every boot collapses into the one already-scheduled job
     * rather than restarting its interval. WorkManager persists it, so a reboot does not lose it.
     *
     * Best-effort: a scheduling failure must never stop the app starting. Failing to schedule
     * cleanup only means the outbox keeps its acknowledged rows, which is the safe direction.
     */
    private fun scheduleOutboxCleanup() {
        try {
            com.autonomousone.messages.sync.OutboxCleanupScheduler.ensureScheduled(this)
            Log.i(
                "OUTBOX_CLEANUP",
                "outbox retention scheduled (acknowledged rows, 48h window)"
            )
        } catch (e: Throwable) {
            Log.w("OUTBOX_CLEANUP", "outbox cleanup could not be scheduled", e)
        }
    }

    /**
     * Inbound messages held because they could not be written (mission §16).
     *
     * The receiver asks for a pass as soon as it holds one; this sweep is the insurance. An enqueue can
     * fail, and a message held by a receiver is exactly the case where relying on a single opportunistic
     * call would be worst — so the same work is also guaranteed to run periodically.
     *
     * IDEMPOTENT and non-blocking, in the same shape as the outbox cleanup above: unique PERIODIC work
     * with KEEP, so every app start and every boot collapses into the one scheduled job.
     */
    private fun scheduleInboundPersistRetry() {
        try {
            com.autonomousone.messages.receiver.PendingInboundWorker.ensureScheduled(this)
        } catch (e: Throwable) {
            Log.w("INBOUND_RETRY", "inbound persist retry could not be scheduled", e)
        }
    }

    /**
     * Mirror → outbox reconciliation (mission §34).
     *
     * THE SAFETY NET for Blocker 1's silent sibling: realtime callbacks are not sufficient for
     * correctness. A message that arrived while the process was dead left no trace at all — no
     * outbox row, nothing logged. This compares the recent mirror window against the outbox and
     * enqueues anything with no durable event, so a missed notification becomes a recoverable
     * event instead of a message that silently never reaches GMweb.
     *
     * Bounded (48 h window, 500 rows) and idempotent: a second run finds nothing. Off the main
     * thread, and best-effort — reconciliation failing must never stop the app starting.
     */
    private fun reconcileMissedMessages() {
        diagnosticsScope.launch {
            try {
                val result = com.autonomousone.messages.data.TelephonySyncCoordinator
                    .get(this@MessagesApp)
                    .reconcileMissingEvents()
                if (result.foundGap) {
                    Log.w(
                        "SYNC_RECONCILE",
                        "recovered ${result.recovered} event(s) that had no durable record " +
                            "(examined ${result.examined})"
                    )
                    com.autonomousone.messages.gateway.health.GatewayLog.record(
                        severity = com.autonomousone.messages.gateway.health.GatewayLogSeverity.WARNING,
                        subsystem = com.autonomousone.messages.gateway.health.GatewayLogSubsystem.SYNC_UPLOAD,
                        code = "RECONCILE_RECOVERED",
                        title = "Recovered missed messages",
                        detail = "${result.recovered} of ${result.examined} recent message(s) " +
                            "had no durable event"
                    )
                } else {
                    Log.i(
                        "SYNC_RECONCILE",
                        "no gaps in ${result.examined} recent message(s)"
                    )
                }
            } catch (e: Throwable) {
                Log.w("SYNC_RECONCILE", "mirror → outbox reconciliation failed", e)
            }
        }
    }

    private fun scheduleSmartCategoryBackfill() {
        try {
            com.autonomousone.messages.classification.ClassificationBackfillWorker
                .enqueue(this)
            Log.i(
                "CATEGORY",
                "smart-category backfill scheduled (bounded, checkpointed, resumable)"
            )
        } catch (e: Throwable) {
            // Classification is best-effort: a scheduling failure must never
            // stop the app from starting or from receiving messages.
            Log.w("CATEGORY", "smart-category backfill could not be scheduled", e)
        }
    }

    /**
     * Phases 14-18. Each component has exactly one entry point and none of
     * them blocks startup:
     *
     *  - [DebugStrictMode.install]          DEBUG-only, log-only StrictMode thread policy;
     *  - [MainThreadStallWatchdog.install]  background probe thread, 2 s / 5 s thresholds;
     *  - [DebugFrameMetrics.install]        DEBUG-only bounded FrameMetrics counters;
     *  - [ProcessExitDiagnostics.install]   API 30+, async historical exit-reason import.
     *
     * Called after DiagnosticLog.initialize so every component can persist
     * through the existing redacting log.
     */
    private fun installDiagnostics() {
        DebugStrictMode.install()
        MainThreadStallWatchdog.install()
        DebugFrameMetrics.install(this)
        ProcessExitDiagnostics.install(this, diagnosticsScope)
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
