package com.autonomousone.messages.diagnostics

import android.content.Context
import android.content.SharedPreferences

/**
 * Diagnostics switches that are OFF by default in release builds.
 *
 * They live in the same `runtime_diagnostics` file the one-time battery-optimisation prompt already
 * uses, so diagnostics state stays in one place rather than growing a second store per flag.
 *
 * Why a preference at all (docs/battery-plan/STAGE-1-quick-wins.md §1.3): `MainThreadStallWatchdog`
 * used to install unconditionally, including release builds, where a foreground service keeps the
 * process alive and the probe thread posts to the main looper four times a second — 345 600 probes a
 * day for a diagnostic the user cannot act on. The capability is worth keeping for the ANR work, but
 * it must be something a developer opts into, not a bill every user pays.
 */
class DiagnosticsPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /**
     * Run the main-thread stall watchdog outside DEBUG builds.
     *
     * Default `false`: release builds are DEBUG-gated off unless someone is deliberately collecting
     * stall evidence on a device.
     */
    var stallWatchdogEnabled: Boolean
        get() = prefs.getBoolean(KEY_STALL_WATCHDOG, false)
        set(value) = prefs.edit().putBoolean(KEY_STALL_WATCHDOG, value).apply()

    companion object {
        /** Shared with [com.autonomousone.messages.MessagesApp]'s battery-optimisation prompt. */
        const val PREF_NAME = "runtime_diagnostics"
        const val KEY_STALL_WATCHDOG = "stall_watchdog_enabled"
    }
}
