package com.autonomousone.messages.security

import android.content.Context
import com.autonomousone.messages.BuildConfig
import com.autonomousone.messages.gateway.GatewayPreferences

/**
 * ADR-007 — single source for the GMweb server URL the app TRUSTS for
 * pairing. Pairing must never require manual Gateway configuration first:
 *
 *   GatewayPreferences.gmwebUrl (user-configured, if set)
 *   ?: BuildConfig.GATEWAY_BACKEND_URL (production fallback)
 *
 * Also owns CANONICAL origin comparison: scheme + host + effective port,
 * HTTPS mandatory (P0-5/§3). Path, trailing slash, casing and default
 * ports never break the comparison; a different scheme, host or port
 * always does.
 */
object PairingEndpointResolver {

    /** The trusted server URL (user setting wins, else build default). */
    fun trustedServerUrl(context: Context): String {
        val prefs = GatewayPreferences(context)
        return prefs.gmwebUrl
            .takeIf { it.isNotBlank() }
            ?: BuildConfig.GATEWAY_BACKEND_URL
    }

    /**
     * Canonical origin: lowercase scheme+host+effective port.
     * Returns null for non-HTTPS URLs (HTTPS is mandatory for pairing).
     */
    fun canonicalOrigin(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return null
        // Manual parse (java.net.URI chokes on some hosts); pure Kotlin so
        // the logic is unit-testable without the Android framework.
        val schemeEnd = trimmed.indexOf("://")
        val scheme: String
        val rest: String
        if (schemeEnd > 0) {
            scheme = trimmed.substring(0, schemeEnd).lowercase()
            rest = trimmed.substring(schemeEnd + 3)
        } else {
            scheme = "https"
            rest = trimmed
        }
        if (scheme != "https") return null
        val authority = rest.substringBefore('/').substringBefore('?')
        val hostPort = authority.substringAfter('@', authority) // drop userinfo
        val host: String
        val port: Int
        val colon = hostPort.lastIndexOf(':')
        if (colon > 0 && hostPort.indexOf(':') == colon) {
            host = hostPort.substring(0, colon).lowercase()
            port = hostPort.substring(colon + 1).toIntOrNull() ?: return null
        } else if (hostPort.contains(":")) {
            return null // IPv6 literal — not expected for pairing origins
        } else {
            host = hostPort.lowercase()
            port = 443
        }
        if (host.isBlank()) return null
        val effectivePort = if (port == 443) 443 else port
        return "https://$host:$effectivePort"
    }

    /**
     * True when [qrOrigin] is the SAME canonical HTTPS origin as
     * [trustedServerUrl]. Different scheme/host/effective port → false.
     */
    fun originMatches(context: Context, qrOrigin: String): Boolean {
        val trusted = canonicalOrigin(trustedServerUrl(context)) ?: return false
        val scanned = canonicalOrigin(qrOrigin) ?: return false
        return trusted == scanned
    }
}
