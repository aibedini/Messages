package com.autonomousone.messages.messaging

import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * v3.4.0 FEATURE 11 — the "Undo Send" preference
 * (Settings → Messaging → Sending).
 *
 * ── Default is OFF, and OFF must be indistinguishable from v3.3.6 ────────────
 * A delay of 0 means "no delay at all": the composer calls [SmsSender] on the
 * same code path it always has, no ledger row is written and no WorkManager job
 * is enqueued. The delay feature is therefore opt-in in the strict sense — a
 * user who never opens this screen cannot be affected by it, and the default
 * install keeps the byte-for-byte behaviour of the previous release.
 *
 * ── Why a dedicated class rather than a field on MessagingPreferences ────────
 * Same convention, separate owner: the undo-send ledger, the worker and the
 * composer all need this value, and keeping it in its own small class means the
 * workstream that owns Send Delay is the only writer of its key. It deliberately
 * uses the SAME SharedPreferences FILE name as [MessagingPreferences] so there
 * is still one messaging preference store on the device, not two.
 */
class SendDelayPreferences(context: Context) {

    private val prefs = context.getSharedPreferences(MessagingPreferences.PREF_NAME, Context.MODE_PRIVATE)

    /** Current delay in milliseconds. 0 = OFF. */
    var delayMillis: Int
        get() = coerceMillis(prefs.getInt(KEY_DELAY_MILLIS, OFF_DELAY_MILLIS))
        set(value) = prefs.edit()
            .putInt(KEY_DELAY_MILLIS, coerceMillis(value))
            .apply()

    /** True when the composer must hold messages before sending. */
    val isEnabled: Boolean get() = delayMillis > 0

    /**
     * Observe the setting.
     *
     * `callbackFlow` is used rather than a process-global bus because the value
     * can also change from a restore/backup, and because the composer only needs
     * a value while it is on screen — the listener is removed when the collector
     * goes away, so a long-lived screen does not leak a preference listener.
     */
    fun delayMillisFlow(): Flow<Int> = callbackFlow {
        trySend(delayMillis)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY_DELAY_MILLIS) trySend(delayMillis)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    companion object {
        /**
         * The delay key. Not `private`: [MessagingPreferences] does not read it,
         * but a future backup/restore allow-list may need the exact key name, and
         * a typo there would silently lose the setting.
         */
        const val KEY_DELAY_MILLIS = "undo_send_delay_millis"

        /** OFF — the default. */
        const val OFF_DELAY_MILLIS = 0

        /**
         * The selectable presets, in display order: OFF / 3 / 5 / 10 / 30 s.
         *
         * A preset LIST rather than a free-form number: undo is a race against a
         * deadline the user is watching, and an arbitrary value (7 seconds, say)
         * has no usable affordance. The database and the scheduler accept any
         * positive value, so this list is the UI contract, not a hard limit.
         */
        val PRESET_SECONDS: List<Int> = listOf(0, 3, 5, 10, 30)

        val PRESET_MILLIS: List<Int> = PRESET_SECONDS.map { it * 1000 }

        /**
         * Normalises a stored/entered value:
         *
         *  * anything non-positive (or unknown) becomes OFF, so a corrupt
         *    preference can never delay a message by a negative amount;
         *  * anything above [MAX_DELAY_MILLIS] is clamped, so a bad migrated
         *    value cannot park a message for a week without the user noticing.
         *
         * The presets all survive this unchanged — the clamp exists only so that
         * an out-of-band value cannot produce an unbounded delay.
         */
        fun coerceMillis(value: Int): Int = when {
            value <= 0 -> OFF_DELAY_MILLIS
            value > MAX_DELAY_MILLIS -> MAX_DELAY_MILLIS
            else -> value
        }

        /** Longest delay the UI can produce (30 s) with headroom for a future preset. */
        const val MAX_DELAY_MILLIS = 60_000

        /** Seconds for display; 0 means OFF. */
        fun secondsOf(value: Int): Int = coerceMillis(value) / 1000

        /** Millis of a preset, validated. */
        fun millisOfSeconds(seconds: Int): Int = coerceMillis(seconds * 1000)

        /** True when [millis] is one of the offered presets. */
        fun isPreset(millis: Int): Boolean = coerceMillis(millis) in PRESET_MILLIS
    }
}
