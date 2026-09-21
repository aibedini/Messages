package com.autonomousone.messages.gateway

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.telephony.TelephonyManager

/**
 * The device facts a liveness request carries, in ONE place.
 *
 * Extracted from [HeartbeatManager] so the diagnostic probe can send a byte-identical request
 * without duplicating the Android plumbing — duplicating it is how the probe came to send a
 * different body and collect a 400 that it then misreported as a credential rejection.
 */
class AgentDeviceFacts(private val context: Context) {

    /**
     * Battery percentage, or -1 when unknown.
     *
     * The sticky battery broadcast is read with a null receiver, which is the documented way to
     * poll the current value without registering anything.
     */
    fun batteryLevel(): Int = try {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) level * 100 / scale else -1
    } catch (_: Exception) {
        -1
    }

    /**
     * The server's own vocabulary: `mobile` or `unknown`.
     *
     * `unknown` is not a failure — it is what the heartbeat has always sent unless the cellular
     * radio is the active data path, and changing that wording would change the contract the
     * server already accepts.
     */
    fun networkType(): String = try {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        when {
            tm == null -> "unknown"
            tm.dataState == TelephonyManager.DATA_CONNECTED -> "mobile"
            else -> "unknown"
        }
    } catch (_: Exception) {
        "unknown"
    }
}
