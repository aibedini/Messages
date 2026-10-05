package com.autonomousone.messages.gateway

import android.content.Context
import android.provider.Telephony
import org.json.JSONObject
import java.security.MessageDigest

/** Durable, body-free bridge between a modem status callback and its GMweb task. */
object GatewayDeliveryReports {
    private const val PREFS = "gmweb_delivery_reports_v1"
    private const val MAP_PREFIX = "map_"
    private const val MAP_CREATED_PREFIX = "map_created_"
    private const val REPORT_PREFIX = "report_"
    private const val QUARANTINE_PREFIX = "quarantine_"
    private const val MAP_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    private val lock = Any()

    /**
     * One durable carrier verdict, body-free.
     *
     * The last five fields are ADDITIVE: a report persisted by an older build simply lacks them and
     * decodes with the defaults below (see [pending]), so no existing queued report is lost or has to
     * be migrated. `eventId` remains `sha256(requestId|status)`, so every retry of the same verdict
     * sends byte-identical identity and GMweb can deduplicate it.
     */
    data class Report(
        val eventId: String,
        val requestId: String,
        val status: String,
        val occurredAt: Long,
        /** 0-based index of the part whose callback produced this aggregate verdict. */
        val segmentIndex: Int = 0,
        val segmentCount: Int = 1,
        /** The subscription that actually carried the message, when it was known at callback time. */
        val subscriptionId: Int? = null,
        /** The native modem result code (SENT callback), when this verdict came from a SENT result. */
        val carrierResultCode: Int? = null,
        /** True only when EVERY expected part has carrier delivery evidence. */
        val allSegmentsDelivered: Boolean = false,
        /** When the device received the callback — distinct from when the carrier produced it. */
        val receivedAtDevice: Long = occurredAt
    ) {
        fun json(): JSONObject = JSONObject()
            .put("eventId", eventId)
            .put("requestId", requestId)
            .put("status", status)
            .put("occurredAt", occurredAt)
            // ── additive, backward-safe ──────────────────────────────────────
            .put("eventType", EVENT_TYPE_CARRIER_DELIVERY)
            .put("segmentIndex", segmentIndex)
            .put("segmentCount", segmentCount)
            .put("allSegmentsDelivered", allSegmentsDelivered)
            .put("receivedAtDevice", receivedAtDevice)
            .putOpt("subscriptionId", subscriptionId)
            .putOpt("carrierResultCode", carrierResultCode)
    }

    /** The one event type this endpoint reports. */
    const val EVENT_TYPE_CARRIER_DELIVERY = "carrier_delivery"

    /**
     * True when this Telephony row belongs to a GMweb/Gateway task.
     *
     * Read-only probe used by the SEND path: a gateway-originated message must always ask the modem
     * for a delivery report, even if the user turned delivery reports off for their own messages —
     * otherwise GMweb would never learn the carrier's verdict for the send it requested.
     */
    fun isGatewayOriginated(context: Context, rowId: Long): Boolean {
        if (rowId <= 0L) return false
        return synchronized(lock) { prefs(context).contains(MAP_PREFIX + rowId) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Called before native submission; commit is synchronous so process death cannot erase identity. */
    fun remember(context: Context, rowId: Long, gatewayRequestId: String?): Boolean {
        if (rowId <= 0 || gatewayRequestId.isNullOrBlank()) return false
        return synchronized(lock) {
            val preferences = prefs(context)
            val now = System.currentTimeMillis()
            val editor = preferences.edit()
            preferences.all.keys.asSequence().filter { it.startsWith(MAP_CREATED_PREFIX) }.forEach { key ->
                val created = preferences.getLong(key, 0L)
                if (created <= 0L || now - created > MAP_RETENTION_MS) {
                    val suffix = key.removePrefix(MAP_CREATED_PREFIX)
                    editor.remove(key).remove(MAP_PREFIX + suffix)
                }
            }
            editor.putString(MAP_PREFIX + rowId, gatewayRequestId)
                .putLong(MAP_CREATED_PREFIX + rowId, now).commit()
        }
    }

    /** Persist only definitive carrier evidence. Neither SENT nor temporary/unknown implies delivery. */
    fun recordFinal(
        context: Context,
        rowId: Long,
        providerStatus: Int,
        at: Long,
        /**
         * Part metadata and native result, all optional so an older caller keeps compiling and an
         * older persisted report keeps decoding.
         */
        segmentIndex: Int = 0,
        segmentCount: Int = 1,
        subscriptionId: Int? = null,
        carrierResultCode: Int? = null
    ): Boolean {
        val status = when (providerStatus) {
            Telephony.Sms.STATUS_COMPLETE -> "delivered"
            Telephony.Sms.STATUS_FAILED -> "failed"
            else -> return false
        }
        return synchronized(lock) {
            val preferences = prefs(context)
            val requestId = preferences.getString(MAP_PREFIX + rowId, null) ?: return@synchronized false
            val eventId = eventId(requestId, status)
            val key = REPORT_PREFIX + eventId
            // Already queued (or already quarantined) — the same verdict twice is one event.
            if (preferences.contains(key)) return@synchronized true
            preferences.edit()
                .putString(
                    key,
                    Report(
                        eventId = eventId,
                        requestId = requestId,
                        status = status,
                        occurredAt = at,
                        segmentIndex = segmentIndex,
                        segmentCount = segmentCount,
                        subscriptionId = subscriptionId,
                        carrierResultCode = carrierResultCode,
                        // Only a `delivered` aggregate verdict means every expected part has carrier
                        // evidence: `failed` can be one refused part of a multipart message.
                        allSegmentsDelivered = status == "delivered",
                        receivedAtDevice = at
                    ).json().toString()
                )
                .remove(MAP_PREFIX + rowId)
                .remove(MAP_CREATED_PREFIX + rowId)
                .commit()
        }
    }

    fun pending(context: Context, limit: Int = 20): List<Report> = synchronized(lock) {
        prefs(context).all.asSequence()
            .filter { (key, _) -> key.startsWith(REPORT_PREFIX) }
            .mapNotNull { (_, value) ->
                runCatching {
                    val obj = JSONObject(value as String)
                    val status = obj.getString("status")
                    Report(
                        eventId = obj.getString("eventId"),
                        requestId = obj.getString("requestId"),
                        status = status,
                        occurredAt = obj.getLong("occurredAt"),
                        // A report written before these fields existed decodes to the safe defaults
                        // rather than crashing the uploader for every legacy row.
                        segmentIndex = obj.optInt("segmentIndex", 0),
                        segmentCount = obj.optInt("segmentCount", 1),
                        subscriptionId = obj.optIntOrNull("subscriptionId"),
                        carrierResultCode = obj.optIntOrNull("carrierResultCode"),
                        allSegmentsDelivered = obj.optBoolean(
                            "allSegmentsDelivered",
                            status == "delivered"
                        ),
                        receivedAtDevice = obj.optLong("receivedAtDevice", obj.optLong("occurredAt", 0L))
                    )
                }.getOrNull()
            }
            .sortedBy { it.occurredAt }
            .take(limit.coerceIn(1, 100))
            .toList()
    }

    /** Absent, JSON-null and non-numeric all mean "not reported" — never a fabricated 0. */
    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    fun acknowledge(context: Context, eventId: String): Boolean = synchronized(lock) {
        prefs(context).edit().remove(REPORT_PREFIX + eventId).commit()
    }

    /** Keep definitive modem evidence for operator review when GMweb has no task ledger row. */
    fun quarantineUnknownRequest(context: Context, eventId: String): Boolean = synchronized(lock) {
        val preferences = prefs(context)
        val report = preferences.getString(REPORT_PREFIX + eventId, null) ?: return@synchronized false
        preferences.edit()
            .putString(QUARANTINE_PREFIX + eventId, report)
            .remove(REPORT_PREFIX + eventId)
            .commit()
    }

    fun quarantinedCount(context: Context): Int = synchronized(lock) {
        prefs(context).all.keys.count { it.startsWith(QUARANTINE_PREFIX) }
    }

    internal fun isUnknownRequestResponse(statusCode: Int, errorCode: String?): Boolean =
        statusCode == 404 && errorCode == "unknown_request_id"

    internal fun eventId(requestId: String, status: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$requestId|$status".toByteArray(Charsets.UTF_8))
        return "dlr_" + bytes.take(16).joinToString("") { "%02x".format(it) }
    }
}
