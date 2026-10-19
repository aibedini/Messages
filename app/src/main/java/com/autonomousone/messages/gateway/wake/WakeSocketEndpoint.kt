package com.autonomousone.messages.gateway.wake

import java.net.URI

/**
 * The WSS origin derived from the ONE user-configured GMweb origin (STAGE-4 §1).
 *
 * There is no compiled-in production host (`app/build.gradle.kts` records that decision), so the
 * socket address is derived by scheme substitution and nothing else. `ws://` is rejected
 * unconditionally: plaintext is not a setting and not a debug flag (§2).
 */
data class WakeSocketEndpoint(
    val url: String,
    val host: String,
) {
    init {
        require(url.startsWith("wss://")) { "wake socket endpoint must be wss://" }
    }

    companion object {
        /** The whole cross-repo path contract. It is signed without a query string. */
        const val PATH = "/gateway/wake"

        fun fromServerOrigin(raw: String): WakeSocketEndpoint? {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("https://", ignoreCase = true)) return null
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
            if (!uri.scheme.equals("https", ignoreCase = true)) return null
            // A wss:// origin is accepted only when it is the scheme-swapped form of an HTTPS
            // origin; ws:// and http:// never reach this branch.
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
            if (uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
            if (!uri.path.isNullOrBlank() && uri.path != "/") return null
            val authority = if (uri.port > 0 && uri.port != 443) "$host:${uri.port}" else host
            return WakeSocketEndpoint(url = "wss://$authority$PATH", host = host)
        }
    }
}
