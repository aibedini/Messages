package com.autonomousone.messages.evechat

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class EveChatPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "") ?: ""
        set(value) = prefs.edit { putString(KEY_SERVER_URL, value.trimEnd('/')) }

    var accessToken: String
        get() = prefs.getString(KEY_ACCESS_TOKEN, "") ?: ""
        set(value) = prefs.edit { putString(KEY_ACCESS_TOKEN, value) }

    var refreshToken: String
        get() = prefs.getString(KEY_REFRESH_TOKEN, "") ?: ""
        set(value) = prefs.edit { putString(KEY_REFRESH_TOKEN, value) }

    var agentEmail: String
        get() = prefs.getString(KEY_AGENT_EMAIL, "") ?: ""
        set(value) = prefs.edit { putString(KEY_AGENT_EMAIL, value) }

    val isLoggedIn: Boolean
        get() = accessToken.isNotBlank() && serverUrl.isNotBlank()

    fun clear() {
        prefs.edit {
            remove(KEY_ACCESS_TOKEN)
            remove(KEY_REFRESH_TOKEN)
            remove(KEY_AGENT_EMAIL)
        }
    }

    companion object {
        private const val PREFS_NAME = "evechat_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_AGENT_EMAIL = "agent_email"
    }
}
