package com.autonomousone.messages.gateway

import android.content.Context
import android.content.SharedPreferences
import com.autonomousone.messages.BuildConfig
import java.util.UUID

class GatewayPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREF_NAME = "sms_gateway_prefs"
        // ── LAN server keys (existing) ──
        private const val KEY_ENABLED = "gateway_enabled"
        private const val KEY_PORT = "gateway_port"
        private const val KEY_API_KEY = "gateway_api_key"
        private const val KEY_WEBHOOK_URL = "gateway_webhook_url"
        private const val KEY_AUTO_START = "gateway_auto_start"
        // ── Cloud backend keys (new) ──
        private const val KEY_BACKEND_URL = "cloud_backend_url"
        private const val KEY_GATEWAY_ID = "cloud_gateway_id"
        private const val KEY_GATEWAY_TOKEN = "cloud_gateway_token"
        private const val KEY_LAST_HEARTBEAT = "cloud_last_heartbeat"
        private const val KEY_IS_REGISTERED = "cloud_is_registered"
        // ── Idempotency store ──
        private const val KEY_SENT_EVENT_IDS = "cloud_sent_event_ids"
        private const val MAX_EVENT_IDS = 500

        const val DEFAULT_PORT = 8080
    }

    // ── LAN server (existing unchanged) ──

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var apiKey: String
        get() {
            val existing = prefs.getString(KEY_API_KEY, null)
            if (!existing.isNullOrBlank()) return existing
            val newKey = generateApiKey()
            prefs.edit().putString(KEY_API_KEY, newKey).apply()
            return newKey
        }
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    var webhookUrl: String
        get() = prefs.getString(KEY_WEBHOOK_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WEBHOOK_URL, value).apply()

    var autoStartOnBoot: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    fun generateNewApiKey(): String {
        val newKey = generateApiKey()
        apiKey = newKey
        return newKey
    }

    private fun generateApiKey(): String {
        return "gw_" + UUID.randomUUID().toString().replace("-", "").take(16)
    }

    // ── Cloud backend (new) ──

    /**
     * Production backend URL.
     * Default from BuildConfig (set to https://gaitway.autonomousone.in at build time).
     * Persisted after first set so the admin can update it remotely without a new APK.
     */
    var backendUrl: String
        get() = prefs.getString(KEY_BACKEND_URL, null)
            ?: BuildConfig.GATEWAY_BACKEND_URL
        set(value) = prefs.edit().putString(KEY_BACKEND_URL, value.trimEnd('/')).apply()

    /** The public gateway ID returned by the backend on registration. */
    var gatewayId: String
        get() = prefs.getString(KEY_GATEWAY_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GATEWAY_ID, value).apply()

    /**
     * The raw bearer token issued by the backend.
     * Stored in SharedPreferences (MODE_PRIVATE).
     * For production, consider migrating to EncryptedSharedPreferences (Jetpack Security).
     */
    var gatewayToken: String
        get() = prefs.getString(KEY_GATEWAY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GATEWAY_TOKEN, value).apply()

    var lastHeartbeatAt: Long
        get() = prefs.getLong(KEY_LAST_HEARTBEAT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_HEARTBEAT, value).apply()

    var isRegistered: Boolean
        get() = prefs.getBoolean(KEY_IS_REGISTERED, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_REGISTERED, value).apply()

    fun clearCloudCredentials() {
        prefs.edit()
            .remove(KEY_GATEWAY_ID)
            .remove(KEY_GATEWAY_TOKEN)
            .putBoolean(KEY_IS_REGISTERED, false)
            .apply()
    }

    // ── Idempotency: track sent event IDs ──

    fun hasEventBeenSent(eventId: String): Boolean {
        return getSentEventIds().contains(eventId)
    }

    fun markEventSent(eventId: String) {
        val ids = getSentEventIds().toMutableSet()
        ids.add(eventId)
        // Keep only the newest MAX_EVENT_IDS entries (simple LRU by removing oldest)
        val trimmed = if (ids.size > MAX_EVENT_IDS) {
            ids.drop(ids.size - MAX_EVENT_IDS).toSet()
        } else ids
        prefs.edit().putStringSet(KEY_SENT_EVENT_IDS, trimmed).apply()
    }

    private fun getSentEventIds(): Set<String> {
        return prefs.getStringSet(KEY_SENT_EVENT_IDS, emptySet()) ?: emptySet()
    }
}
