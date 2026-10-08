package com.autonomousone.messages.gateway

import com.autonomousone.messages.messaging.SimDiscovery
import com.autonomousone.messages.messaging.SimDiscoveryResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * The parts of the telemetry payload whose MEANING is a rule, not a data fetch.
 *
 * Split out so the rules are asserted by tests rather than observed on a device:
 *
 *  - a missing `READ_PHONE_STATE` is reported as "SIM list unavailable, permission missing" — NOT as
 *    a transport failure and NOT as "no SIM". The report still travels, because "the phone is online
 *    and cannot list its SIMs" is exactly what GMweb needs to know;
 *  - zero active subscriptions is `available = true, items = []` — the question was ANSWERED;
 *  - a platform failure is `available = false` with its own reason, never an empty list;
 *  - the capability list is LOCAL BUILD EVIDENCE and is never derived from server input.
 */
object TelemetryPayloadSections {

    /** The encrypted command types this build can execute. Local evidence, never server-driven. */
    val COMMAND_TYPES: List<String> = listOf(
        "SEND_SMS",
        "MARK_THREAD_READ",
        "REFRESH_DEVICE_TELEMETRY"
    )

    /** The live agent device id — the SAME identity that signs the request and the body field. */
    /**
     * `smsSubscriptions`, from a FRESH discovery.
     *
     * ## What a remote reader gets, and what it must never get
     *
     * Each line carries its opaque `simRef` — the only SIM identifier that may cross this boundary —
     * plus display fields and liveness flags. **No ICCID, no IMSI and no SIM serial**, because those
     * are privileged identifiers that this app neither needs nor asks for (mission §28).
     *
     * `subscriptionId` is deliberately **not** published either. It is Android-local authority: it is
     * reassigned as SIMs are swapped, so a remote party holding one would be addressing a line by a
     * number whose meaning changes — and it is a tiny enumerable domain. `simRef` is the stable,
     * keyed replacement (see [com.autonomousone.messages.messaging.SimRef]). `defaultSubscriptionId`
     * is kept only because it is pre-existing contract for the LOCAL default-line indicator, not a
     * way to address a SIM.
     *
     * @param discovery the result of a discovery performed for THIS report. Never a cached list: a
     *   web-requested refresh exists precisely because a cached one can be stale.
     */
    fun smsSubscriptions(
        discovery: SimDiscoveryResult,
        permissionGranted: Boolean,
        defaultSubscriptionId: Int,
        lastChangedAt: Long?,
        safeLabel: (String) -> String
    ): JSONObject {
        val items = JSONArray()
        (discovery as? SimDiscoveryResult.Available)?.sims?.forEach { sim ->
            items.put(
                JSONObject()
                    .put("simRef", sim.simRef)
                    .put("slotIndex", sim.slotIndex)
                    .put("displayName", safeLabel(sim.displayName))
                    .put("carrierName", safeLabel(sim.carrierName))
                    .put("isDefaultSms", sim.isSystemDefault)
                    .put("isActive", true)
            )
        }
        return JSONObject()
            .put("available", discovery is SimDiscoveryResult.Available)
            .putOpt("reason", SimDiscovery.reasonOf(discovery))
            .put("permissionGranted", permissionGranted)
            .put("defaultSubscriptionId", defaultSubscriptionId)
            .putOpt("lastChangedAt", lastChangedAt)
            .put("items", items)
    }

    /** The command types this build can execute, advertised so GMweb can stop guessing. */
    fun capabilities(): JSONObject = JSONObject()
        .put("commandTypes", JSONArray(COMMAND_TYPES))

    /** `permissions`, reported separately: listing SIMs says nothing about being able to send. */
    fun permissions(
        readPhoneState: Boolean,
        sendSms: Boolean,
        defaultSmsRole: Boolean
    ): JSONObject = JSONObject()
        .put("readPhoneState", readPhoneState)
        .put("sendSms", sendSms)
        .put("defaultSmsRole", defaultSmsRole)
}
