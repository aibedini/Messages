package com.autonomousone.messages.utils

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

/**
 * Quiet hours: a daily window during which notification sounds/vibration are
 * suppressed for incoming messages. The notification still appears (silent),
 * so nothing is missed — it just doesn't ring at 3am.
 *
 * Window may wrap past midnight (e.g. 22 → 7).
 */
class QuietHoursPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "quiet_hours_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_START_HOUR = "start_hour"
        private const val KEY_END_HOUR = "end_hour"
    }

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** Start hour (0–23, Asia/local device time). */
    var startHour: Int
        get() = prefs.getInt(KEY_START_HOUR, 22)
        set(value) = prefs.edit().putInt(KEY_START_HOUR, value.coerceIn(0, 23)).apply()

    /** End hour (0–23). */
    var endHour: Int
        get() = prefs.getInt(KEY_END_HOUR, 7)
        set(value) = prefs.edit().putInt(KEY_END_HOUR, value.coerceIn(0, 23)).apply()

    /** True when the current device time falls inside the quiet window. */
    fun isInQuietWindow(now: Calendar = Calendar.getInstance()): Boolean {
        if (!enabled) return false
        val start = startHour
        val end = endHour
        if (start == end) return false // zero-length window = disabled
        val h = now.get(Calendar.HOUR_OF_DAY)
        return if (start < end) h in start until end
        else h >= start || h < end // wraps midnight
    }
}
