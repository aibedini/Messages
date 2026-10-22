package com.autonomousone.messages.evechat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the access/refresh token pair.
 *
 * Refresh is single-flight: when several requests and the socket hit 401 at once, one refresh
 * runs and the rest reuse its result. A rejected refresh token ends the session through
 * [onSessionExpired] instead of looping.
 */
class EveChatAuth(
    private val prefs: EveChatPreferences,
    private val api: EveChatApiClient,
    private val onSessionExpired: suspend () -> Unit
) {
    private val mutex = Mutex()

    @Volatile
    private var cached: Pair<String, String>? = null

    suspend fun accessToken(): String? = tokens()?.first

    suspend fun store(pair: TokenPair): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val ok = prefs.writeTokens(pair.accessToken, pair.refreshToken)
            cached = if (ok) pair.accessToken to pair.refreshToken else null
            ok
        }
    }

    fun forget() {
        cached = null
    }

    suspend fun <T> authorized(block: suspend (token: String) -> ApiResult<T>): ApiResult<T> {
        val token = accessToken() ?: return ApiResult.Err(ApiError(ApiError.Kind.UNAUTHORIZED))
        val first = block(token)
        if (first !is ApiResult.Err || first.error.kind != ApiError.Kind.UNAUTHORIZED) return first
        val fresh = refresh(staleToken = token) ?: return first
        return block(fresh)
    }

    /** @return a valid access token, or null when the session is over (or the network is down). */
    suspend fun refresh(staleToken: String?): String? {
        var expired = false
        val result = mutex.withLock {
            val current = tokens()
            if (current == null) {
                expired = true
                return@withLock null
            }
            if (staleToken != null && current.first != staleToken) return@withLock current.first
            when (val r = api.refresh(current.second)) {
                is ApiResult.Ok -> {
                    val ok = withContext(Dispatchers.IO) { prefs.writeTokens(r.value.accessToken, r.value.refreshToken) }
                    cached = r.value.accessToken to r.value.refreshToken
                    if (!ok) EveChatLog.w("auth", "refreshed token could not be persisted")
                    EveChatLog.i("auth", "access token refreshed")
                    r.value.accessToken
                }
                is ApiResult.Err -> {
                    val k = r.error.kind
                    expired = k == ApiError.Kind.UNAUTHORIZED || k == ApiError.Kind.FORBIDDEN || k == ApiError.Kind.CLIENT
                    EveChatLog.w("auth", "token refresh failed", null, "kind" to k, "expired" to expired)
                    null
                }
            }
        }
        if (expired) onSessionExpired()
        return result
    }

    private suspend fun tokens(): Pair<String, String>? {
        cached?.let { return it }
        return withContext(Dispatchers.IO) { prefs.readTokens() }?.also { cached = it }
    }
}
