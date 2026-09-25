package com.autonomousone.messages.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.autonomousone.messages.gateway.GatewayForegroundStartPolicy
import com.autonomousone.messages.gateway.GatewayPreferences
import com.autonomousone.messages.gateway.GatewayService

/**
 * Re-arms the gateway after a phone reboot. The user's intent
 * (gatewayDesiredEnabled) survives in SharedPreferences; this receiver
 * simply replays ACTION_START so ConnectionSupervisor reconciles from
 * scratch (bind server, heartbeat, poller, sync) — no manual step.
 *
 * Consent is re-checked inside GatewayService.startGateway; a revoked
 * consent means the start is silently dropped (and the pref cleared).
 *
 * THE REASON IS PASSED DELIBERATELY. On Android 15+ (this app targets 36) a boot receiver may NOT
 * launch a `dataSync` foreground service. This receiver used to call the same start path as the
 * user-driven one, so the reboot re-arm asked for a type the platform forbids at boot and the start
 * threw. Because the gateway has no periodic scheduler, the practical effect was a gateway that
 * stayed dead after every reboot until the user opened the app. The reason now travels with the
 * start so `startForegroundNotification` can choose a type that is legal from a boot receiver.
 */
class BootGatewayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = GatewayPreferences(context)
        if (!prefs.gatewayDesiredEnabled || !prefs.hasGatewayConsent) {
            Log.d(TAG, "Boot: gateway not desired (enabled=${prefs.gatewayDesiredEnabled} consent=${prefs.hasGatewayConsent}) — skip")
            return
        }
        Log.i(TAG, "Boot: restarting gateway (user intent persisted)")
        GatewayService.startGateway(
            context,
            GatewayForegroundStartPolicy.StartReason.BOOT
        )
    }

    companion object { private const val TAG = "BOOT_GW" }
}
