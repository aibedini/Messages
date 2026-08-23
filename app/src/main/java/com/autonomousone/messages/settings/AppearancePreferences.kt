package com.autonomousone.messages.settings

import android.content.Context
import android.content.SharedPreferences

/** User-facing appearance options: theme preset, dark-mode mode, calendar system. */
class AppearancePreferences(context: Context) {

    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREF_NAME = "appearance_prefs"
        private const val KEY_PRESET = "theme_preset_id"
        private const val KEY_DARK_MODE = "dark_mode"       // SYSTEM | LIGHT | DARK
        private const val KEY_CALENDAR = "calendar_system"  // GREGORIAN | PERSIAN

        const val DARK_SYSTEM = "SYSTEM"
        const val DARK_LIGHT = "LIGHT"
        const val DARK_DARK = "DARK"

        const val CALENDAR_GREGORIAN = "GREGORIAN"
        const val CALENDAR_PERSIAN = "PERSIAN"
    }

    /** Id of a [com.autonomousone.messages.ui.theme.ThemePresets] entry. Default: ocean_blue. */
    var themePresetId: String
        get() = prefs.getString(KEY_PRESET, "ocean_blue") ?: "ocean_blue"
        set(value) = prefs.edit().putString(KEY_PRESET, value).apply()

    var darkMode: String
        get() = prefs.getString(KEY_DARK_MODE, DARK_SYSTEM) ?: DARK_SYSTEM
        set(value) = prefs.edit().putString(KEY_DARK_MODE, value).apply()

    /** Calendar used everywhere dates are rendered. Default: Gregorian. */
    var calendar: String
        get() = prefs.getString(KEY_CALENDAR, CALENDAR_GREGORIAN) ?: CALENDAR_GREGORIAN
        set(value) = prefs.edit().putString(KEY_CALENDAR, value).apply()
}
