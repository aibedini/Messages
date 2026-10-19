package com.autonomousone.messages.gateway.wake

import okhttp3.CertificatePinner
import java.util.Base64

/**
 * User-configured certificate pins for the WSS origin (STAGE-4 §2.1).
 *
 * Two pins are supported on purpose: a single pin plus a certificate renewal is a guaranteed
 * outage. The pins live beside `gmwebServerOrigin` in preferences, because a compiled-in pin would
 * contradict the no-compiled-in-server decision.
 *
 * If the owner configured no pin the socket runs unpinned and the diagnostic card says so. A pin
 * mismatch is reported as a transport failure and falls back to polling; it is never silently
 * bypassed.
 */
data class WakeCertificatePins(
    val current: String? = null,
    val next: String? = null,
) {
    val configured: Boolean get() = normalized().isNotEmpty()

    /**
     * True when the owner typed something that is not a 32-byte SHA-256 pin. A typo must fail
     * closed rather than silently leave one pin (or none) active: a silently dropped rotation pin is
     * how a renewal becomes an outage.
     */
    val invalidInput: Boolean
        get() = listOf(current, next).any { raw ->
            !raw.isNullOrBlank() && normalize(raw) == null
        }

    /** Normalizes both pins to OkHttp's `sha256/<base64>` spelling, dropping duplicates. */
    fun normalized(): List<String> =
        listOfNotNull(normalize(current), normalize(next)).distinct()

    /**
     * The pinner to install on the WebSocket client, or null when the owner configured nothing.
     *
     * Unpinned is an explicit, reported state, not a fallback that pretends to be secure.
     */
    fun certificatePinner(host: String): CertificatePinner? {
        val pins = normalized()
        if (pins.isEmpty()) return null
        val builder = CertificatePinner.Builder()
        pins.forEach { builder.add(host, it) }
        return builder.build()
    }

    companion object {
        /**
         * Accepts the forms a human may paste: `sha256/<base64>`, `sha256:<base64>`, or a bare
         * base64 SHA-256. Returns null for anything that is not a 32-byte SHA-256 digest.
         */
        fun normalize(raw: String?): String? {
            val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val withoutPrefix = value
                .removePrefix("sha256/")
                .removePrefix("sha256:")
                .trim()
            val bytes = runCatching { Base64.getDecoder().decode(withoutPrefix) }.getOrNull()
                ?: return null
            if (bytes.size != 32) return null
            return "sha256/" + Base64.getEncoder().encodeToString(bytes)
        }
    }
}
