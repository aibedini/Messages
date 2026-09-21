package com.autonomousone.messages.gateway

import org.json.JSONArray
import org.json.JSONObject

/**
 * THE agent liveness request, shared by every caller that pings the control plane.
 *
 * ── WHY THIS FILE EXISTS ─────────────────────────────────────────────────────
 * The diagnostic AUTH probe hand-rolled its own body and got an HTTP 400 from GMweb while the
 * heartbeat — posting to the *same* path with the *same* credentials — was succeeding every
 * minute. The two bodies were not the same request:
 *
 * ```text
 * heartbeat (worked)   appVersion, batteryLevel, networkType, timestamp, events, sourceDeviceId
 * auth probe (400)     appVersion, timestamp, diagnostic,  events, sourceDeviceId
 *                      └── extra unknown field, two required ones missing
 * ```
 *
 * So the diagnostic was measuring its OWN malformed request and reporting the result as a fact
 * about the device's credential. A 400 means "the server could not accept this request", and
 * the fix is not to re-classify it — it is to send the request that is known to work.
 *
 * ONE builder now, so the two can never drift apart again; a test pins the key set, and a
 * source guard fails if a caller stops going through here.
 *
 * The body carries no message content: an EMPTY `events` array is a pure liveness ping that the
 * server ingests as `{accepted:[],duplicates:0}` without touching a sequence.
 */
object AgentLivenessRequest {

    /** The control-plane liveness route. Same path for heartbeat and for the diagnostic check. */
    const val EVENTS_PATH = "/api/v1/agent/events/batch"

    const val KEY_APP_VERSION = "appVersion"
    const val KEY_BATTERY_LEVEL = "batteryLevel"
    const val KEY_NETWORK_TYPE = "networkType"
    const val KEY_TIMESTAMP = "timestamp"
    const val KEY_EVENTS = "events"
    const val KEY_SOURCE_DEVICE_ID = "sourceDeviceId"

    /**
     * The exact key set every liveness request must carry.
     *
     * Deliberately declared rather than inferred: an extra convenience field is precisely what
     * broke this, so the set is asserted by a test and additions are a conscious act.
     */
    val KEYS: Set<String> = setOf(
        KEY_APP_VERSION,
        KEY_BATTERY_LEVEL,
        KEY_NETWORK_TYPE,
        KEY_TIMESTAMP,
        KEY_EVENTS,
        KEY_SOURCE_DEVICE_ID
    )

    /**
     * The liveness body.
     *
     * @param batteryLevel -1 when unknown, which is what the heartbeat has always sent.
     * @param networkType `mobile` / `wifi` / `unknown` — the server's own vocabulary, kept as
     *   the heartbeat has always spelled it.
     */
    fun body(
        appVersion: String,
        batteryLevel: Int,
        networkType: String,
        timestamp: Long,
        sourceDeviceId: String
    ): JSONObject = JSONObject()
        .put(KEY_APP_VERSION, appVersion)
        .put(KEY_BATTERY_LEVEL, batteryLevel)
        .put(KEY_NETWORK_TYPE, networkType)
        .put(KEY_TIMESTAMP, timestamp)
        .put(KEY_EVENTS, JSONArray())
        .put(KEY_SOURCE_DEVICE_ID, sourceDeviceId)
}
