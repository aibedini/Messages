package com.autonomousone.messages.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autonomousone.messages.messaging.CustomRetentionRange
import com.autonomousone.messages.messaging.OtpCleanupScheduler
import com.autonomousone.messages.messaging.OtpRetentionPreferences
import com.autonomousone.messages.repository.OtpRetentionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State for the "OTP & verification codes" card in Settings > Messaging
 * (v3.4.0 FEATURE 14).
 *
 * Every mutation goes through [OtpRetentionPreferences] (the single store) and
 * then asks [OtpCleanupScheduler] to re-point the ONE unique work at the new
 * next-deadline. Turning the switch OFF cancels that work outright, so "off"
 * cannot leave a job behind that would clean something later.
 *
 * NOTHING here deletes a message: the card explains that cleanup MOVES messages
 * to Trash, where the user can still restore them.
 */
class OtpRetentionViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val prefs = OtpRetentionPreferences(application)
    private val service = OtpRetentionService.get(application)

    // ── Observable state ────────────────────────────────────────────────────

    /** The global switch. OFF on a fresh install. */
    var enabled by mutableStateOf(prefs.enabled)
        private set

    var retentionMillis by mutableStateOf(prefs.retentionMillis)
        private set

    /** Non-null while the Custom editor is open (transient UI state only). */
    var customOpen by mutableStateOf(false)
        private set

    var customValue by mutableStateOf("")
        private set

    var customUnit by mutableStateOf(CustomRetentionRange.Unit.HOURS)
        private set

    /** Visible feedback for a rejected custom value; null = no error. */
    var customError by mutableStateOf<CustomRetentionRange.Rejection?>(null)
        private set

    /** Messages currently enrolled in automatic cleanup. */
    var enrolledCount by mutableStateOf(0)
        private set

    /** Next cleanup instant, or 0 when nothing is scheduled. */
    var nextEligibleAt by mutableStateOf(0L)
        private set

    /** True while the explicit "apply to existing" action is running. */
    var applying by mutableStateOf(false)
        private set

    /** Non-null after the explicit action, describing what it did. */
    var applyResult by mutableStateOf<OtpRetentionService.SweepOutcome?>(null)
        private set

    init {
        viewModelScope.launch {
            prefs.stateFlow().collectLatest { state ->
                enabled = state.enabled
                retentionMillis = state.retentionMillis
            }
        }
        refreshDiagnostics()
    }

    // ── Reads ───────────────────────────────────────────────────────────────

    /** Counts + next deadline. Cheap: one COUNT and one MIN, both index-backed. */
    fun refreshDiagnostics() {
        viewModelScope.launch(Dispatchers.IO) {
            val count = runCatching { service.enrolledCount() }.getOrDefault(0)
            val next = runCatching { service.nextEligibleAt() }.getOrDefault(null) ?: 0L
            withContext(Dispatchers.Main) {
                enrolledCount = count
                nextEligibleAt = next
            }
        }
    }

    fun isPreset(millis: Long): Boolean = millis in CustomRetentionRange.PRESETS

    // ── The switch ──────────────────────────────────────────────────────────

    /**
     * Turns global OTP cleanup on or off.
     *
     * Switching ON never cleans anything by itself: no existing message is
     * enrolled (that needs the explicit action below), and only messages arriving
     * from now on are scheduled. Switching OFF cancels the pending work and leaves
     * durable deadlines untouched, so turning it back on resumes the schedule.
     */
    fun setEnabled(value: Boolean) {
        prefs.enabled = value
        enabled = value
        applyResult = null
        if (value) {
            OtpCleanupScheduler.reschedule(getApplication())
        } else {
            OtpCleanupScheduler.cancelPending(getApplication())
        }
        refreshDiagnostics()
    }

    // ── Retention duration ──────────────────────────────────────────────────

    /** Applies one of the fixed presets (1h / 6h / 24h / 3d / 7d). */
    fun selectPreset(millis: Long) {
        val stored = prefs.setRetentionMillisValidated(millis)
        retentionMillis = stored
        customOpen = false
        customError = null
        if (prefs.enabled) OtpCleanupScheduler.reschedule(getApplication())
    }

    /** Opens the Custom editor seeded with the value currently in force. */
    fun openCustomEditor() {
        customOpen = true
        customError = null
        val hours = CustomRetentionRange.hoursOf(retentionMillis)
        customUnit = if (hours % 24L == 0L && hours >= 24L) {
            CustomRetentionRange.Unit.DAYS
        } else {
            CustomRetentionRange.Unit.HOURS
        }
        customValue = if (customUnit == CustomRetentionRange.Unit.DAYS) {
            (hours / 24L).toString()
        } else {
            hours.toString()
        }
    }

    fun dismissCustomEditor() {
        customOpen = false
        customError = null
    }

    fun updateCustomValue(value: String) {
        // Digits only: the validator rejects everything else anyway, and filtering
        // here keeps the field from ever showing a value that cannot be stored.
        customValue = value.filter { it.isDigit() }.take(MAX_CUSTOM_DIGITS)
        customError = null
    }

    fun selectCustomUnit(unit: CustomRetentionRange.Unit) {
        customUnit = unit
        customError = null
    }

    /**
     * Commits the custom value.
     *
     * @return true when it was accepted. On rejection [customError] is set so the
     *         screen shows visible feedback and keeps the editor open — an
     *         invalid range is never silently clamped into effect.
     */
    fun commitCustomRetention(): Boolean {
        val result = CustomRetentionRange.validate(customValue, customUnit)
        if (!result.isValid) {
            customError = result.rejection
            return false
        }
        val stored = prefs.setRetentionMillisValidated(result.millis!!)
        retentionMillis = stored
        customOpen = false
        customError = null
        if (prefs.enabled) OtpCleanupScheduler.reschedule(getApplication())
        return true
    }

    /** The clamped value the UI may offer after a rejection. */
    fun suggestedCustomValue(): String = customError?.clampedHours?.toString().orEmpty()

    // ── The explicit action ─────────────────────────────────────────────────

    /**
     * "Apply to existing OTP messages".
     *
     * Requires an explicit tap and a running cleanup switch; it never runs as a
     * side effect of [setEnabled]. Result is reported through [applyResult] so the
     * screen can say exactly how many messages were enrolled, and whether the
     * pass finished.
     */
    fun applyToExisting() {
        if (applying) return
        applying = true
        applyResult = null
        viewModelScope.launch {
            val outcome = runCatching {
                service.applyToExistingOtpMessages()
            }.getOrNull()
            applying = false
            applyResult = outcome
            refreshDiagnostics()
        }
    }

    fun clearApplyResult() {
        applyResult = null
    }

    private companion object {
        /** Longer than 30 days expressed in days, so the field cannot overflow. */
        const val MAX_CUSTOM_DIGITS = 5
    }
}
