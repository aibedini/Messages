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
        private const val LEGACY_ACCESS = "access_token"
        private const val LEGACY_REFRESH = "refresh_token"
    }
}

object EveChatServerUrl {

    sealed interface Result {
        data class Ok(val url: String, val insecure: Boolean) : Result
        data class Invalid(val reason: String) : Result
    }

    /** Accepts what people actually type: no scheme, trailing slashes, a pasted `/api/v1`. */
    fun normalize(input: String): Result {
        var text = input.trim().trimEnd('/')
        if (text.isEmpty()) return Result.Invalid("آدرس سرور را وارد کنید")
        if (!text.contains("://")) text = "https://$text"
        val uri = runCatching { java.net.URI(text) }.getOrNull()
            ?: return Result.Invalid("آدرس سرور معتبر نیست")
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return Result.Invalid("فقط آدرس http یا https پذیرفته می‌شود")
        if (uri.host.isNullOrBlank()) return Result.Invalid("آدرس سرور معتبر نیست")
        var path = (uri.rawPath ?: "").trimEnd('/')
        if (path.endsWith("/api/v1")) path = path.removeSuffix("/api/v1")
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return Result.Ok("$scheme://${uri.host.lowercase()}$port$path", insecure = scheme == "http")
    }
}
