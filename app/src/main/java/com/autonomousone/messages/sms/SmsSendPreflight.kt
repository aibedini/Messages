package com.autonomousone.messages.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.autonomousone.messages.messaging.SimManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stable, machine-readable reasons the gateway cannot attempt an SMS right now.
 *
 * Machine code and human sentence are kept apart on purpose: GMweb keys off
 * these constants, an operator reads the log. One condition has exactly one
 * code — there is deliberately no `sim_missing`/`sim_not_found`/`no_sim` family
 * spelling the same state three ways.
 */
object SmsBlockReason {
    /** `Manifest.permission.SEND_SMS` is not granted. */
    const val PERMISSION_DENIED = "permission_denied"

    /** The app does not hold the platform SMS role, so the radio path is unavailable. */
    const val NOT_DEFAULT_SMS_APP = "not_default_sms_app"

    /** The device reports no active subscription at all. */
    const val NO_ACTIVE_SUBSCRIPTION = "no_active_subscription"

    /** An explicit SIM was requested and it is not in the device's active list. */
    const val SIM_UNAVAILABLE = "sim_unavailable"

    /** The local send queue is not running, so a task could never be drained. */
    const val QUEUE_NOT_RUNNING = "queue_not_running"

    /** The HTTP gateway is not listening. */
    const val GATEWAY_NOT_RUNNING = "gateway_not_running"
}

/**
 * One active subscription, as much of it as GMweb and diagnostics need.
 *
 * Deliberately does NOT carry IMSI/ICCID/phone number: a stable Android
 * `subscriptionId` plus slot and carrier labels is all that is required to pick
 * a line, and identifiers that are not needed must not travel.
 */
data class SmsSubscription(
    val subscriptionId: Int,
    val slotIndex: Int,
    val displayName: String,
    val carrierName: String
)

/**
 * Answer to "can this device attempt an SMS right now, and if not, why not".
 *
 * **The defect this exists for.** `/ready` answered
 * `isListening && isDefaultSmsApp() && EveSmsQueue.isRunning`. A device with
 * `SEND_SMS` revoked — or with the selected SIM removed — reported
 * `{"status":"ready"}` while every send was guaranteed to fail. A readiness
 * probe that cannot see the permission it needs is not a probe.
 *
 * Two readiness levels are kept apart so the existing public contract is not
 * silently redefined:
 *
 *  - [gatewayReady] — the bridge infrastructure is alive (HTTP + queue).
 *  - [sendReady]    — the device can *attempt* an SMS: gateway and queue up,
 *                     `SEND_SMS` granted, SMS role held, and a usable SIM
 *                     resolved (an explicitly selected SIM must itself be active).
 *
 * [evaluate] is pure so the decision table is unit-testable on the JVM;
 * [inspect] is the only Android-touching entry point.
 */
data class SmsSendPreflight(
    val gatewayRunning: Boolean,
    val queueRunning: Boolean,
    val defaultSmsApp: Boolean,
    val sendSmsPermission: Boolean,
    /** False when the device withheld the subscription list (no READ_PHONE_STATE). */
    val subscriptionsKnown: Boolean,
    val activeSubscriptions: List<SmsSubscription>,
    /** The line the user/GMweb selected, or null when no line was chosen. */
    val selectedSubscriptionId: Int?,
    val blockingReasons: List<String>
) {

    /** HTTP + queue only: the bridge is alive, independent of telephony state. */
    val gatewayReady: Boolean
        get() = blockingReasons.none {
            it == SmsBlockReason.GATEWAY_NOT_RUNNING || it == SmsBlockReason.QUEUE_NOT_RUNNING
        }

    /**
     * True when the requested line is known to be usable.
     *
     * With no explicit selection the platform default is the user's intent, so
     * availability is not something this probe can contradict. When the device
     * withheld the list, the honest answer is "not contradicted", not "absent".
     */
    val selectedSubscriptionAvailable: Boolean
        get() = selectedSubscriptionId == null ||
            !subscriptionsKnown ||
            activeSubscriptions.any { it.subscriptionId == selectedSubscriptionId }

    /** No hard blocker remains: an SMS may be attempted. */
    val sendReady: Boolean get() = blockingReasons.isEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        put("status", if (sendReady) "ready" else "not_ready")
        if (!sendReady) put("error", "not_ready")
        put("ready", sendReady)
        put("gatewayRunning", gatewayRunning)
        // Alias kept because the pre-existing not-ready body used this name.
        put("serverRunning", gatewayRunning)
        put("gatewayReady", gatewayReady)
        put("queueRunning", queueRunning)
        put("defaultSmsApp", defaultSmsApp)
        put("sendSmsPermission", sendSmsPermission)
        put("subscriptionsKnown", subscriptionsKnown)
        put("activeSubscriptions", JSONArray().apply {
            activeSubscriptions.forEach { sim ->
                put(JSONObject()
                    .put("subscriptionId", sim.subscriptionId)
                    .put("slotIndex", sim.slotIndex)
                    .put("displayName", sim.displayName)
                    .put("carrierName", sim.carrierName)
                    .put("active", true))
            }
        })
        put("selectedSubscriptionId", selectedSubscriptionId ?: JSONObject.NULL)
        put("selectedSubscriptionAvailable", selectedSubscriptionAvailable)
        put("sendReady", sendReady)
        put("blockingReasons", JSONArray().apply { blockingReasons.forEach { put(it) } })
    }

    companion object {

        /** `Manifest.permission.SEND_SMS`, checked through the runtime permission API. */
        fun hasSendSmsPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
                PackageManager.PERMISSION_GRANTED

        /**
         * The whole decision table. Pure: no Android types, no I/O.
         *
         * @param telephonyStateKnown whether the device's active-subscription list
         *   could actually be read. When it could not, SIM availability is
         *   unknown and must NOT be reported as a blocker — refusing every send
         *   because the app could not look would be worse than the failure it
         *   guards against. The manager-level fail-closed check in
         *   [SendSimPolicy] still refuses a PROVEN mismatch at send time.
         */
        fun evaluate(
            gatewayRunning: Boolean,
            queueRunning: Boolean,
            defaultSmsApp: Boolean,
            sendSmsPermission: Boolean,
            activeSubscriptions: List<SmsSubscription>,
            telephonyStateKnown: Boolean,
            selectedSubscriptionId: Int?
        ): SmsSendPreflight {
            val reasons = ArrayList<String>(4)
            if (!gatewayRunning) reasons += SmsBlockReason.GATEWAY_NOT_RUNNING
            if (!queueRunning) reasons += SmsBlockReason.QUEUE_NOT_RUNNING
            if (!sendSmsPermission) reasons += SmsBlockReason.PERMISSION_DENIED
            if (!defaultSmsApp) reasons += SmsBlockReason.NOT_DEFAULT_SMS_APP
            if (telephonyStateKnown) {
                if (activeSubscriptions.isEmpty()) {
                    reasons += SmsBlockReason.NO_ACTIVE_SUBSCRIPTION
                } else if (selectedSubscriptionId != null &&
                    activeSubscriptions.none { it.subscriptionId == selectedSubscriptionId }
                ) {
                    reasons += SmsBlockReason.SIM_UNAVAILABLE
                }
            }
            return SmsSendPreflight(
                gatewayRunning = gatewayRunning,
                queueRunning = queueRunning,
                defaultSmsApp = defaultSmsApp,
                sendSmsPermission = sendSmsPermission,
                subscriptionsKnown = telephonyStateKnown,
                activeSubscriptions = activeSubscriptions,
                selectedSubscriptionId = selectedSubscriptionId,
                blockingReasons = reasons
            )
        }

        /** Reads the live device state for `/ready`. The one Android entry point. */
        fun inspect(
            context: Context,
            gatewayRunning: Boolean,
            queueRunning: Boolean,
            defaultSmsApp: Boolean,
            selectedSubscriptionId: Int?
        ): SmsSendPreflight {
            val simManager = SimManager(context)
            val known = simManager.hasReadPhoneState()
            val active = if (known) {
                simManager.getActiveSims().map {
                    SmsSubscription(
                        subscriptionId = it.subscriptionId,
                        slotIndex = it.slotIndex,
                        displayName = it.displayName,
                        carrierName = it.carrierName
                    )
                }
            } else {
                emptyList()
            }
            return evaluate(
                gatewayRunning = gatewayRunning,
                queueRunning = queueRunning,
                defaultSmsApp = defaultSmsApp,
                sendSmsPermission = hasSendSmsPermission(context),
                activeSubscriptions = active,
                telephonyStateKnown = known,
                selectedSubscriptionId = selectedSubscriptionId
            )
        }
    }
}
