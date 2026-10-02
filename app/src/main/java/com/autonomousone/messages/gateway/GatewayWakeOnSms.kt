package com.autonomousone.messages.gateway

import android.content.Context
import android.util.Log

/**
 * Starts the gateway because an SMS just arrived — the one moment the phone KNOWS someone is trying
 * to reach it.
 *
 * **The defect this exists for.** Nothing on the receive path started the sync stack. The observer
 * relay, the provider-repair scheduler and the event uploader are all created in
 * `GatewayService.onCreate`, and the app has no periodic scheduler for the gateway (its own KDoc says
 * so): the foreground service is revived only by the user opening the app, by boot, or by its
 * restart watchdog. So a message that arrived while the service was down was written to the provider
 * — the app being the default SMS app means the row exists — and then sat there until the user
 * opened the app. That is precisely the reported "a new SMS does not show up in GMweb until I
 * refresh", and its discriminator is that the UPLOAD was never late; nothing was listening.
 *
 * `SMS_DELIVER` is one of the platform's documented exemptions from the background
 * foreground-service start restriction, so this is a legal start in the case that matters most. The
 * non-default path (`SMS_RECEIVED`) is not exempt, so it may be refused — and a refusal is handled,
 * not swallowed: [GatewayService.startGateway] defers to WorkManager.
 *
 * Only ever starts something the user already asked for: the desired flag and consent are checked
 * here AND again inside `startGateway` (GatewayAccessPolicy). An SMS can never enable the gateway.
 */
object GatewayWakeOnSms {

    private const val TAG = "GM_PRESENCE"

    /**
     * @return true when a start was requested. False means "nothing to do" (the gateway is off by
     *   user choice, consent is absent, or the service is already running), never a failure.
     */
    fun maybeStart(context: Context): Boolean {
        val prefs = GatewayPreferences(context)
        if (!prefs.gatewayDesiredEnabled || !prefs.hasGatewayConsent) return false
        if (GatewayService.isServiceRunning) return false
        val requested = GatewayService.startGateway(
            context,
            GatewayForegroundStartPolicy.StartReason.USER_OR_APP
        )
        Log.i(TAG, "incoming SMS woke the gateway requested=$requested")
        com.autonomousone.messages.utils.DiagnosticLog.event(
            "GM_PRESENCE",
            "wake_on_sms requested=$requested"
        )
        return requested
    }
}
