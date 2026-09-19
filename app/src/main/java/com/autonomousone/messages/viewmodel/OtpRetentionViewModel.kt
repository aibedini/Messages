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

    /**
     * The global switch. OFF on a fresh install.
     *
     * NOTE ON SHAPE: these are private mutable states in front of public read-only
     * properties, NOT `var … private set`. A `var` named for the setting (e.g.
     * `enabled`) synthesises a `setX` method, and that collides on the JVM with the
     * `setEnabled(Boolean)` ACTION below:
     *
     *     Platform declaration clash: the following declarations have the same JVM
     *     signature (setEnabled(Z)V)
     *
     * Compose reads the value identically either way; only the writer differs, and
     * the writer is a named action that also re-points the cleanup work.
     */
    private var enabledState by mutableStateOf(prefs.enabled)
    val enabled: Boolean get() = enabledState

    private var retentionMillisState by mutableStateOf(prefs.retentionMillis)
    val retentionMillis: Long get() = retentionMillisState

    /** Non-null while the Custom editor is open (transient UI state only). */
    private var customOpenState by mutableStateOf(false)
    val customOpen: Boolean get() = customOpenState

    private var customValueState by mutableStateOf("")
    val customValue: String get() = customValueState

    private var customUnitState by mutableStateOf(CustomRetentionRange.Unit.HOURS)
    val customUnit: CustomRetentionRange.Unit get() = customUnitState

    /** Visible feedback for a rejected custom value; null = no error. */
    private var customErrorState by mutableStateOf<CustomRetentionRange.Rejection?>(null)
    val customError: CustomRetentionRange.Rejection? get() = customErrorState

    /** Messages currently enrolled in automatic cleanup. */
    private var enrolledCountState by mutableStateOf(0)
    val enrolledCount: Int get() = enrolledCountState

    /** Next cleanup instant, or 0 when nothing is scheduled. */
    private var nextEligibleAtState by mutableStateOf(0L)
    val nextEligibleAt: Long get() = nextEligibleAtState

    /** True while the explicit "apply to existing" action is running. */
    private var applyingState by mutableStateOf(false)
    val applying: Boolean get() = applyingState

    /** Non-null after the explicit action, describing what it did. */
    private var applyResultState by mutableStateOf<OtpRetentionService.SweepOutcome?>(null)
    val applyResult: OtpRetentionService.SweepOutcome? get() = applyResultState

    init {
        viewModelScope.launch {
            prefs.stateFlow().collectLatest { state ->
                enabledState = state.enabled
                retentionMillisState = state.retentionMillis
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
                enrolledCountState = count
                nextEligibleAtState = next
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
        enabledState = value
        applyResultState = null
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
        retentionMillisState = stored
        customOpenState = false
        customErrorState = null
        if (prefs.enabled) OtpCleanupScheduler.reschedule(getApplication())
    }

    /** Opens the Custom editor seeded with the value currently in force. */
    fun openCustomEditor() {
        customOpenState = true
        customErrorState = null
        val hours = CustomRetentionRange.hoursOf(retentionMillis)
        customUnitState = if (hours % 24L == 0L && hours >= 24L) {
            CustomRetentionRange.Unit.DAYS
        } else {
            CustomRetentionRange.Unit.HOURS
        }
        customValueState = if (customUnit == CustomRetentionRange.Unit.DAYS) {
            (hours / 24L).toString()
        } else {
            hours.toString()
        }
    }

    fun dismissCustomEditor() {
        customOpenState = false
        customErrorState = null
    }

    fun updateCustomValue(value: String) {
        // Digits only: the validator rejects everything else anyway, and filtering
        // here keeps the field from ever showing a value that cannot be stored.
        customValueState = value.filter { it.isDigit() }.take(MAX_CUSTOM_DIGITS)
        customErrorState = null
    }

    fun selectCustomUnit(unit: CustomRetentionRange.Unit) {
        customUnitState = unit
        customErrorState = null
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
            customErrorState = result.rejection
            return false
        }
        val stored = prefs.setRetentionMillisValidated(result.millis!!)
        retentionMillisState = stored
        customOpenState = false
        customErrorState = null
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
        applyingState = true
        applyResultState = null
        viewModelScope.launch {
            val outcome = runCatching {
                service.applyToExistingOtpMessages()
            }.getOrNull()
            applyingState = false
            applyResultState = outcome
            refreshDiagnostics()
        }
    }

    fun clearApplyResult() {
        applyResultState = null
    }

    private companion object {
        /** Longer than 30 days expressed in days, so the field cannot overflow. */
        const val MAX_CUSTOM_DIGITS = 5
    }
}
