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

    data class Report(
        val eventId: String,
        val requestId: String,
        val status: String,
        val occurredAt: Long
    ) {
        fun json(): JSONObject = JSONObject()
            .put("eventId", eventId)
            .put("requestId", requestId)
            .put("status", status)
            .put("occurredAt", occurredAt)
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
    fun recordFinal(context: Context, rowId: Long, providerStatus: Int, at: Long): Boolean {
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
            if (preferences.contains(key)) return@synchronized true
            preferences.edit()
                .putString(key, Report(eventId, requestId, status, at).json().toString())
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
                    Report(obj.getString("eventId"), obj.getString("requestId"),
                        obj.getString("status"), obj.getLong("occurredAt"))
                }.getOrNull()
            }
            .sortedBy { it.occurredAt }
            .take(limit.coerceIn(1, 100))
            .toList()
    }

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
