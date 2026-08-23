package com.autonomousone.messages.utils

/**
 * Process-wide calendar-system selection used by the date formatters.
 * Kept as a plain volatile flag so non-compose utilities can read it cheaply;
 * [com.autonomousone.messages.ui.theme.ThemeController] keeps it in sync with prefs.
 */
object CalendarBridge {
    enum class Type { GREGORIAN, PERSIAN }

    @Volatile
    var current: Type = Type.GREGORIAN
}
