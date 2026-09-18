package com.autonomousone.messages.diagnostics

import android.os.StrictMode
import android.util.Log
import com.autonomousone.messages.BuildConfig

/**
 * Phase 17.11: DEBUG-only StrictMode thread policy.
 *
 * Detects main-thread disk reads, disk writes and network use. There is NO
 * death penalty and NO dialog penalty — violations are written to logcat
 * only, so a debug build can never be killed or interrupted by its own
 * instrumentation, and a release build installs nothing at all.
 *
 * Call once, from the main thread, during Application.onCreate.
 */
object DebugStrictMode {

    private const val TAG = "STRICT_MODE"

    /** Installs the log-only thread policy in debug builds; no-op otherwise. */
    fun install() {
        if (!BuildConfig.DEBUG) return
        try {
            val policy = StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build()
            StrictMode.setThreadPolicy(policy)
            Log.i(TAG, "strict_mode_thread_policy=disk_read,disk_write,network penalty=log")
        } catch (error: Throwable) {
            Log.w(TAG, "strict mode install failed", error)
        }
    }
}
