package com.autonomousone.messages.gateway

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

class GatewayPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREF_NAME = "sms_gateway_prefs"
        private const val KEY_ENABLED = "gateway_enabled"
        private const val KEY_PORT = "gateway_port"
        private const val KEY_API_KEY = "gateway_api_key"
        private const val KEY_WEBHOOK_URL = "gateway_webhook_url"
        private const val KEY_AUTO_START = "gateway_auto_start"
        const val DEFAULT_PORT = 8080
    }

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var apiKey: String
        get() {
            val existing = prefs.getString(KEY_API_KEY, null)
            if (!existing.isNullOrBlank()) {
                return existing
            }
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
}
