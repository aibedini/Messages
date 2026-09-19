package com.autonomousone.messages.messaging

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The two OTP-retention values the rest of the app depends on.
 *
 * Declared as an interface so the scheduling/cleanup ENGINE can be unit-tested
 * on the JVM without Android: [OtpRetentionPreferences] is the ONLY production
 * implementation, and it stays the only writer — a test double implements this
 * interface and never touches a real store.
 */
interface OtpRetentionSettings {
    /** Global switch. MUST default to false on a fresh install. */
    val enabled: Boolean

    /** Retention in millis, always a validated value (never 0, never absurd). */
    val retentionMillis: Long
}

/**
 * THE central preference store for GLOBAL OTP retention (v3.4.0 FEATURE 14).
 *
 * There is deliberately exactly ONE place that reads or writes these two
 * values, because they are read on three different threads (the receive path,
 * the cleanup worker, and the Settings UI) and a second copy of "is auto-delete
 * on?" is exactly how a disabled feature starts deleting messages.
 *
 * DEFAULTS ARE OFF: [enabled] is false on a fresh install, so no enrollment and
 * no cleanup happens until the user turns it on in Settings > Messaging.
 *
 * VALIDATION IS ENFORCED ON BOTH SIDES OF THE STORE:
 *  - the WRITE path ([retentionMillis] setter / [setRetentionMillisValidated])
 *    clamps into [CustomRetentionRange.MIN_HOURS]..[CustomRetentionRange.MAX_HOURS],
 *    so a caller that forgot to validate cannot persist 0 ("delete immediately")
 *    or ten years;
 *  - the READ path clamps again, so a value written by an older build or restored
 *    from a backup cannot reach the scheduler either.
 *
 * Writes use `apply()` like the rest of the app; the value the UI renders comes
 * from [stateFlow], which re-reads the store on every change.
 */
class OtpRetentionPreferences(context: Context) : OtpRetentionSettings {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Immutable snapshot of both values — what the UI renders. */
    data class State(
        val enabled: Boolean,
        val retentionMillis: Long
    ) {
        /** True when [retentionMillis] is one of the fixed presets. */
        val isPreset: Boolean get() = retentionMillis in CustomRetentionRange.PRESETS
    }

    /**
     * Global switch. **OFF by default.**
     *
     * Turning it on never cleans anything by itself: existing messages are only
     * enrolled by the explicit "Apply to existing OTP messages" action, and new
     * messages are enrolled from the moment they arrive.
     */
    override var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /**
     * How long an incoming OTP is kept before it is moved to Trash.
     *
     * Reads are CLAMPED rather than trusted: a value written by an older build
     * (or a restored backup) outside the supported window is reported as the
     * nearest valid retention instead of being handed to the scheduler.
     */
    override var retentionMillis: Long
        get() = clamp(prefs.getLong(KEY_RETENTION_MILLIS, DEFAULT_RETENTION_MILLIS))
        set(value) {
            val clamped = clamp(value)
            prefs.edit().putLong(KEY_RETENTION_MILLIS, clamped).apply()
        }

    /**
     * Stores a retention and reports the outcome.
     *
     * @return the value actually persisted. Equals [value] when it was legal;
     *         the clamped bound otherwise. Caller surfaces the difference as
     *         visible feedback — this never fails silently.
     */
    fun setRetentionMillisValidated(value: Long): Long {
        val clamped = clamp(value)
        prefs.edit().putLong(KEY_RETENTION_MILLIS, clamped).apply()
        return clamped
    }

    /** Convenience for the Custom editor: validates raw user input in one step. */
    fun setCustomRetention(value: String, unit: CustomRetentionRange.Unit): CustomRetentionRange.Result {
        val result = CustomRetentionRange.validate(value, unit)
        result.millis?.let { setRetentionMillisValidated(it) }
        return result
    }

    fun state(): State = State(enabled = enabled, retentionMillis = retentionMillis)

    /**
     * UI stream. `callbackFlow` is used because SharedPreferences has no Flow of
     * its own and a polling flow would keep a timer alive for a settings screen
     * the user opens twice a year. The listener is removed in [awaitClose], so
     * the collector never leaks a registered listener.
     */
    fun stateFlow(): Flow<State> = callbackFlow {
        // Emit the current value immediately so the UI never shows a blank row.
        trySend(state())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY_ENABLED || key == KEY_RETENTION_MILLIS) {
                trySend(state())
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    companion object {
        private const val PREFS_NAME = "otp_retention_prefs"
        private const val KEY_ENABLED = "otp_auto_delete_enabled"
        private const val KEY_RETENTION_MILLIS = "otp_retention_millis"

        /** 24 hours — the middle of the offered presets, and a sane default. */
        const val DEFAULT_RETENTION_MILLIS: Long = 24L * CustomRetentionRange.HOUR_MS

        fun clamp(value: Long): Long {
            val min = CustomRetentionRange.MIN_HOURS * CustomRetentionRange.HOUR_MS
            val max = CustomRetentionRange.MAX_HOURS * CustomRetentionRange.HOUR_MS
            return value.coerceIn(min, max)
        }
    }
}
