package com.autonomousone.messages.diagnostics

import android.annotation.TargetApi
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import com.autonomousone.messages.utils.DiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 14: import the platform's record of *past* process deaths.
 *
 * Android 11 (API 30) keeps a per-package history of why the process ended —
 * ANR, Java crash, native crash, low memory, excessive resource usage — plus a
 * bounded trace stream for the most recent ones. Without this import the only
 * evidence that the app was killed is a logcat line that has already rotated
 * away by the time anyone looks.
 *
 * Guarantees:
 *  - API-gated: on API < 30 [install] returns immediately and nothing below it
 *    is ever referenced at runtime;
 *  - non-blocking: [install] only launches a coroutine on the caller's scope;
 *    the query and every trace read happen on [Dispatchers.IO];
 *  - bounded trace reads: a trace is never read past
 *    [ExitReasonSanitizer.MAX_EXCERPT_BYTES] (64 KiB) and only ever as a
 *    sanitized excerpt (see [ExitReasonSanitizer]);
 *  - de-duplicated: only records newer than the last imported timestamp are
 *    written, so restarts do not re-log the same history.
 */
object ProcessExitDiagnostics {

    private const val TAG = "EXIT_DIAGNOSTICS"
    private const val PREFS = "runtime_diagnostics"
    private const val KEY_LAST_SEEN_MS = "exit_info_last_seen_ms"

    /** One page of history is plenty; a large maxNum makes the platform work harder. */
    private const val MAX_RECORDS = 10

    /**
     * Single entry point. Safe to call from [android.app.Application.onCreate]:
     * the work is handed to [scope] and returns immediately.
     */
    fun install(context: Context, scope: CoroutineScope) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.i(TAG, "historical_exit_reasons unavailable below API 30")
            return
        }
        val appContext = context.applicationContext
        scope.launch {
            try {
                Api30History.ingest(appContext)
            } catch (error: Throwable) {
                // Diagnostics must never take the process down at startup.
                Log.w(TAG, "exit reason import failed", error)
            }
        }
    }

    /**
     * All API-30-only code lives here so the [Build.VERSION.SDK_INT] guard in
     * [install] is the single, obvious gate.
     */
    @TargetApi(Build.VERSION_CODES.R)
    private object Api30History {

        private val capturedReasons: Set<Int> = setOf(
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        )

        suspend fun ingest(context: Context) {
            withContext(Dispatchers.IO) {
                val manager = context.getSystemService(ActivityManager::class.java)
                    ?: return@withContext
                val records = try {
                    manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_RECORDS)
                } catch (error: Throwable) {
                    Log.w(TAG, "getHistoricalProcessExitReasons failed", error)
                    return@withContext
                }
                if (records.isEmpty()) return@withContext

                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val lastSeenMs = prefs.getLong(KEY_LAST_SEEN_MS, 0L)
                var newestSeenMs = lastSeenMs
                var reported = 0
                // Oldest first so the log reads chronologically.
                for (record in records.sortedBy { it.timestamp }) {
                    if (record.timestamp > newestSeenMs) newestSeenMs = record.timestamp
                    if (record.timestamp <= lastSeenMs) continue
                    if (record.reason !in capturedReasons) continue
                    DiagnosticLog.event("EXIT_INFO", describe(record))
                    reported++
                }
                if (newestSeenMs > lastSeenMs) {
                    prefs.edit().putLong(KEY_LAST_SEEN_MS, newestSeenMs).apply()
                }
                Log.i(TAG, "exit_reason_import reported=" + reported + " scanned=" + records.size)
            }
        }

        private fun describe(record: ApplicationExitInfo): String = buildString {
            append("reason=").append(reasonLabel(record.reason))
            append(" ts=").append(record.timestamp)
            append(" pid=").append(record.pid)
            append(" status=").append(record.status)
            append(" importance=").append(record.importance)
            append(" pss_kb=").append(record.pss)
            append(" rss_kb=").append(record.rss)
            val description = ExitReasonSanitizer.sanitize(record.description)
            if (description.isNotEmpty()) append(" description=").append(description)
            val excerpt = readTraceExcerpt(record)
            if (excerpt.isNotEmpty()) append(" trace=").append(excerpt)
        }

        private fun readTraceExcerpt(record: ApplicationExitInfo): String = try {
            // Strictly bounded: at most 64 KiB, sanitized, persisted much shorter.
            ExitReasonSanitizer.sanitizeExcerpt(record.traceInputStream)
        } catch (error: Throwable) {
            Log.w(TAG, "trace excerpt unavailable", error)
            ""
        }

        private fun reasonLabel(reason: Int): String = when (reason) {
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_CRASH -> "CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
            else -> "OTHER_" + reason
        }
    }
}
