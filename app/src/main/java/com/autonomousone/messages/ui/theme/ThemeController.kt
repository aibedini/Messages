package com.autonomousone.messages.ui.theme

import android.content.Context
import com.autonomousone.messages.settings.AppearancePreferences
import com.autonomousone.messages.utils.CalendarBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide appearance state (theme preset, dark-mode, calendar).
 * Loaded once from [AppearancePreferences] at app start and updated by the
 * Appearance settings screen; [MessagesTheme] collects it so changes apply live.
 */
object ThemeController {

    enum class Mode { SYSTEM, LIGHT, DARK }

    data class State(
        val presetId: String = ThemePresets.DEFAULT_ID,
        val mode: Mode = Mode.SYSTEM,
        val calendarPersian: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var prefs: AppearancePreferences? = null

    /** Call once in Activity.onCreate before setContent. */
    fun init(context: Context) {
        if (prefs != null) return
        val p = AppearancePreferences(context.applicationContext)
        prefs = p
        val persian = p.calendar == AppearancePreferences.CALENDAR_PERSIAN
        CalendarBridge.current =
            if (persian) CalendarBridge.Type.PERSIAN else CalendarBridge.Type.GREGORIAN
        _state.value = State(
            presetId = p.themePresetId,
            mode = runCatching { Mode.valueOf(p.darkMode) }.getOrDefault(Mode.SYSTEM),
            calendarPersian = persian
        )
    }

    fun setPreset(id: String) {
        prefs?.themePresetId = id
        _state.value = _state.value.copy(presetId = id)
    }

    fun setMode(mode: Mode) {
        prefs?.darkMode = mode.name
        _state.value = _state.value.copy(mode = mode)
    }

    fun setCalendar(persian: Boolean) {
        val value = if (persian) {
            AppearancePreferences.CALENDAR_PERSIAN
        } else {
            AppearancePreferences.CALENDAR_GREGORIAN
        }
        prefs?.calendar = value
        CalendarBridge.current =
            if (persian) CalendarBridge.Type.PERSIAN else CalendarBridge.Type.GREGORIAN
        _state.value = _state.value.copy(calendarPersian = persian)
    }
}
