package com.autonomousone.messages.evechat

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.autonomousone.messages.utils.SecureStore

/**
 * EveChat account state. Tokens are encrypted with the Android Keystore ([SecureStore]); only
 * non-secret metadata is stored in clear. Decrypting performs keystore IPC, so token reads are
 * for background threads only — [hasSession] answers "signed in?" without decrypting.
 */
class EveChatPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "") ?: ""
        set(value) = prefs.edit { putString(KEY_SERVER_URL, value.trimEnd('/')) }

    var agentEmail: String
        get() = prefs.getString(KEY_AGENT_EMAIL, "") ?: ""
        set(value) = prefs.edit { putString(KEY_AGENT_EMAIL, value) }

    var agentId: String
        get() = prefs.getString(KEY_AGENT_ID, "") ?: ""
        set(value) = prefs.edit { putString(KEY_AGENT_ID, value) }

    var agentName: String
        get() = prefs.getString(KEY_AGENT_NAME, "") ?: ""
        set(value) = prefs.edit { putString(KEY_AGENT_NAME, value) }

    /** Stable per install, never tied to hardware ids; survives sign-out so the server can upsert. */
    val deviceId: String
        @Synchronized get() = prefs.getString(KEY_DEVICE_ID, null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit(commit = true) { putString(KEY_DEVICE_ID, it) }
        }

    var fcmToken: String?
        get() = prefs.getString(KEY_FCM_TOKEN, null)
        set(value) = prefs.edit { if (value == null) remove(KEY_FCM_TOKEN) else putString(KEY_FCM_TOKEN, value) }

    var pushRecord: String?
        get() = prefs.getString(KEY_PUSH_RECORD, null)
        set(value) = prefs.edit { if (value == null) remove(KEY_PUSH_RECORD) else putString(KEY_PUSH_RECORD, value) }

    /** `<server>|<epoch ms>`: the server that reported no push support, and when to ask again. */
    var pushUnsupported: String?
        get() = prefs.getString(KEY_PUSH_UNSUPPORTED, null)
        set(value) = prefs.edit { if (value == null) remove(KEY_PUSH_UNSUPPORTED) else putString(KEY_PUSH_UNSUPPORTED, value) }

    var lastPushAt: Long
        get() = prefs.getLong(KEY_LAST_PUSH_AT, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_PUSH_AT, value) }

    val hasSession: Boolean
        get() = serverUrl.isNotBlank() &&
            (prefs.contains(KEY_ACCESS_ENC) || !prefs.getString(LEGACY_ACCESS, null).isNullOrBlank())

    fun readTokens(): Pair<String, String>? {
        migrateLegacyTokens()
        val access = prefs.getString(KEY_ACCESS_ENC, null)?.let(SecureStore::decrypt) ?: return null
        val refresh = prefs.getString(KEY_REFRESH_ENC, null)?.let(SecureStore::decrypt) ?: ""
        return access to refresh
    }

    /** @return false when the Keystore refused to encrypt; the caller must not treat that as signed in. */
    fun writeTokens(access: String, refresh: String): Boolean {
        val accessEnc = SecureStore.encrypt(access) ?: return false
        val refreshEnc = SecureStore.encrypt(refresh) ?: return false
        prefs.edit(commit = true) {
            putString(KEY_ACCESS_ENC, accessEnc)
            putString(KEY_REFRESH_ENC, refreshEnc)
            remove(LEGACY_ACCESS)
            remove(LEGACY_REFRESH)
        }
        return true
    }

    fun clearSession() {
        prefs.edit(commit = true) {
            remove(KEY_ACCESS_ENC)
            remove(KEY_REFRESH_ENC)
            remove(LEGACY_ACCESS)
            remove(LEGACY_REFRESH)
            remove(KEY_AGENT_ID)
            remove(KEY_AGENT_NAME)
            remove(KEY_FCM_TOKEN)
            remove(KEY_PUSH_RECORD)
            remove(KEY_PUSH_UNSUPPORTED)
            remove(KEY_LAST_PUSH_AT)
        }
    }

    /** Builds up to 3.7.0 stored tokens in plaintext. */
    private fun migrateLegacyTokens() {
        val access = prefs.getString(LEGACY_ACCESS, null)?.takeIf { it.isNotBlank() } ?: return
        val refresh = prefs.getString(LEGACY_REFRESH, null).orEmpty()
        if (writeTokens(access, refresh)) {
            EveChatLog.i("auth", "migrated plaintext tokens to keystore encryption")
        }
    }

    companion object {
        private const val PREFS_NAME = "evechat_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_AGENT_EMAIL = "agent_email"
        private const val KEY_AGENT_ID = "agent_id"
        private const val KEY_AGENT_NAME = "agent_name"
        private const val KEY_ACCESS_ENC = "access_token_enc"
        private const val KEY_REFRESH_ENC = "refresh_token_enc"
        private const val KEY_DEVICE_ID = "push_device_id"
        private const val KEY_FCM_TOKEN = "push_fcm_token"
        private const val KEY_PUSH_RECORD = "push_registration"
        private const val KEY_PUSH_UNSUPPORTED = "push_unsupported"
        private const val KEY_LAST_PUSH_AT = "push_last_received_at"
        private const val LEGACY_ACCESS = "access_token"
        private const val LEGACY_REFRESH = "refresh_token"
    }
}

object EveChatServerUrl {

    sealed interface Result {
        data class Ok(val url: String, val insecure: Boolean) : Result

        /** [reasonRes] is a string resource: this object has no Context and must not guess a language. */
        data class Invalid(@androidx.annotation.StringRes val reasonRes: Int) : Result
    }

    /** Accepts what people actually type: no scheme, trailing slashes, a pasted `/api/v1`. */
    fun normalize(input: String): Result {
        var text = input.trim().trimEnd('/')
        val required = com.autonomousone.messages.R.string.eve_server_required
        val invalid = com.autonomousone.messages.R.string.eve_server_invalid
        if (text.isEmpty()) return Result.Invalid(required)
        if (!text.contains("://")) text = "https://$text"
        val uri = runCatching { java.net.URI(text) }.getOrNull() ?: return Result.Invalid(invalid)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") {
            return Result.Invalid(com.autonomousone.messages.R.string.eve_server_scheme)
        }
        if (uri.host.isNullOrBlank()) return Result.Invalid(invalid)
        var path = (uri.rawPath ?: "").trimEnd('/')
        if (path.endsWith("/api/v1")) path = path.removeSuffix("/api/v1")
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return Result.Ok("$scheme://${uri.host.lowercase()}$port$path", insecure = scheme == "http")
    }
}
