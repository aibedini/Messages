package com.autonomousone.messages.gateway.health

import com.autonomousone.messages.utils.PhoneToken
import java.net.URI

/**
 * The configured GMweb endpoint, parsed once so every layer agrees about what "the
 * server" is.
 *
 * The host matters because the TLS check is a HOSTNAME check: a certificate issued for
 * `gmweb.example.com` is not valid for `203.0.113.10`, and the app must say so rather than
 * silently accepting it or silently failing. Everything here is Android-free so the parsing
 * rules are unit-tested.
 */
data class GatewayEndpoint(
    val scheme: String,
    val host: String,
    val port: Int,
    val path: String = ""
) {
    /** HTTPS only. A plaintext control plane is rejected before it is ever dialled. */
    val secure: Boolean get() = scheme.equals("https", ignoreCase = true)

    /** True when the host is a literal address rather than a name that needs DNS. */
    val isLiteralAddress: Boolean
        get() = IPV4.matches(host) || host.contains(':')

    /** Host with the port only when it is not the scheme default. Never the full URL. */
    val displayHost: String
        get() = if (port == defaultPort) host else "$host:$port"

    val defaultPort: Int
        get() = if (secure) 443 else 80

    companion object {

        private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

        /** Parses a configured URL, or null when it is unusable. */
        fun parse(url: String): GatewayEndpoint? {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return null
            return try {
                val uri = URI(trimmed)
                val scheme = uri.scheme?.takeIf { it.isNotBlank() } ?: return null
                val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
                val secure = scheme.equals("https", ignoreCase = true)
                val port = if (uri.port > 0) uri.port else if (secure) 443 else 80
                GatewayEndpoint(
                    scheme = scheme.lowercase(),
                    host = host,
                    port = port,
                    path = uri.path.orEmpty()
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Turns an arbitrary failure message into something safe to SHOW and to keep.
 *
 * Two rules, both of them promises:
 *
 *  1. no user content: a long digit run is an address or an identifier, so it is replaced
 *     by the same deterministic token the diagnostics log already uses, never printed;
 *  2. one bounded line: a detail is a caption, not a stack trace, so newlines collapse and
 *     the result is capped.
 */
object GatewayHealthText {

    /** Longest detail kept. */
    const val MAX_LENGTH = 160

    private val longDigitRun = Regex("(?<![\\dA-Za-z])\\+?\\d{7,15}(?!\\d)")

    fun safeDetail(raw: String?): String? {
        val collapsed = raw
            ?.replace('\n', ' ')
            ?.replace('\r', ' ')
            ?.replace('\t', ' ')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val redacted = longDigitRun.replace(collapsed) { match ->
            "id#" + PhoneToken.of(match.value)
        }
        return if (redacted.length <= MAX_LENGTH) redacted else redacted.take(MAX_LENGTH - 1) + "…"
    }
}
