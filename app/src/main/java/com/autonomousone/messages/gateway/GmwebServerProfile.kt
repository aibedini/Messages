package com.autonomousone.messages.gateway

import java.net.URI

/**
 * Why a pasted server address was rejected. Each value maps to one sentence of UI copy, so
 * the user is told what to fix rather than shown a generic "invalid URL".
 */
enum class GmwebInputError {
    /** Nothing was entered. */
    BLANK,

    /** Not parseable as a URL at all. */
    MALFORMED,

    /** No scheme, e.g. `gmweb.okgfx.ir`. The scheme is required, never assumed. */
    MISSING_SCHEME,

    /** `http://` — the control plane and the pull bridge are HTTPS-only, with no exception. */
    INSECURE_SCHEME,

    /** `https://` with no host. */
    MISSING_HOST,

    /** `https://user:pass@host` — credentials must never be embedded in a stored URL. */
    EMBEDDED_CREDENTIALS,

    /** A query string is not part of a server address. */
    HAS_QUERY,

    /** A fragment is not part of a server address. */
    HAS_FRAGMENT,

    /**
     * A path that is not one of the known GMweb UI routes.
     *
     * Rejected rather than silently dropped on purpose: if GMweb is genuinely deployed under a
     * sub-path, silently stripping it would produce 404s that look like a server fault. Saying
     * so is the honest answer.
     */
    UNSUPPORTED_PATH
}

sealed interface GmwebServerNormalization {
    data class Valid(val profile: GmwebServerProfile) : GmwebServerNormalization
    data class Invalid(val error: GmwebInputError) : GmwebServerNormalization
}

/**
 * THE single GMweb server, derived from ONE user-supplied address.
 *
 * ── THE ARCHITECTURE THIS REPLACES ───────────────────────────────────────────
 * The app used to hold TWO independent server values: `gmwebUrl` drove the pull bridge, and
 * `backendUrl` drove the control plane (enrollment, events, heartbeat, trust). `backendUrl`
 * ALSO defaulted to a domain COMPILED INTO THE APK. So a user could point the delivery bridge
 * at their own GMweb while the control plane quietly kept talking to the baked-in one — the
 * phone pulling its send-requests from one server and uploading its events to another, with
 * nothing in the UI saying so.
 *
 * There is now ONE origin. Every GMweb route is DERIVED from it, and no host or domain is
 * compiled into the app:
 *
 * ```text
 *                 ONE CONFIG
 *          https://gmweb.okgfx.ir
 *                     |
 *         +-----------+-----------+
 *         |                       |
 *    Control Plane          SMS Pull Bridge
 *    /api/v1/agent/...      /gateway/...
 * ```
 *
 * (Kotlin block comments NEST, so a literal `/` followed by `*` cannot appear in this file's
 * comments — the route families are written with an ellipsis above for that reason.)
 *
 * The user may paste the PANEL url they actually have (`…/app`) — that is the address they
 * know — and the origin is derived from it. The panel URL is kept only for the "Open panel"
 * button.
 *
 * Pure and Android-free, so every normalization rule is unit-tested rather than discovered in
 * production.
 */
data class GmwebServerProfile(
    /** `https://gmweb.okgfx.ir` — scheme + host (+ port when non-default). No trailing slash. */
    val origin: String,
    val host: String,
    val port: Int = DEFAULT_HTTPS_PORT
) {

    /** `host` alone for the default port, `host:port` otherwise. Safe to display. */
    val displayHost: String
        get() = if (port == DEFAULT_HTTPS_PORT) host else "$host:$port"

    /** The panel the user knows, for the "Open panel" button. */
    val dashboardUrl: String get() = "$origin/app"

    // ── Derived protocol routes: paths are constants, never user input ──────
    val healthUrl: String get() = "$origin/health"
    val gatewayPingUrl: String get() = "$origin/gateway/ping"
    val gatewayStatusUrl: String get() = "$origin/gateway/status"
    val gatewayPullUrl: String get() = "$origin/gateway/pull"
    val gatewayValidateUrl: String get() = "$origin/gateway/validate"
    val gatewayAckUrl: String get() = "$origin/gateway/ack"

    /** The control-plane base for the agent API (`/api/v1/agent`). */
    val agentApiBase: String get() = "$origin/api/v1/agent"

    /** Paths, kept as constants so a caller can sign a request over the exact same string. */
    val identityPath: String get() = "/api/v1/agent/identity"
    val eventsBatchPath: String get() = "/api/v1/agent/events/batch"

    /** Joins a `"/path"` onto [origin]. The ONE place a GMweb URL is assembled. */
    fun url(path: String): String {
        require(path.startsWith("/")) { "a GMweb path must start with '/': $path" }
        return origin + path
    }

    companion object {

        const val DEFAULT_HTTPS_PORT = 443

        /**
         * The GMweb UI routes that are safe to strip when deriving the origin, because they are
         * the panel, not the API. Anything else is rejected — see [GmwebInputError.UNSUPPORTED_PATH].
         */
        val KNOWN_UI_PATHS: Set<String> = setOf("/app", "/dashboard")

        /** Uppercase scheme, trailing slashes and a trailing UI route are all accepted. */
        fun normalize(input: String?): GmwebServerNormalization {
            val raw = input?.trim().orEmpty()
            if (raw.isEmpty()) return GmwebServerNormalization.Invalid(GmwebInputError.BLANK)

            val uri = try {
                URI(raw)
            } catch (_: Exception) {
                return GmwebServerNormalization.Invalid(GmwebInputError.MALFORMED)
            }

            val scheme = uri.scheme?.lowercase()
                ?: return GmwebServerNormalization.Invalid(GmwebInputError.MISSING_SCHEME)
            // HTTPS with no exception: this address carries a device key, a bearer token and
            // the signed agent identity.
            if (scheme != "https") {
                return GmwebServerNormalization.Invalid(GmwebInputError.INSECURE_SCHEME)
            }
            if (!uri.rawUserInfo.isNullOrBlank()) {
                return GmwebServerNormalization.Invalid(GmwebInputError.EMBEDDED_CREDENTIALS)
            }
            if (uri.rawQuery != null) {
                return GmwebServerNormalization.Invalid(GmwebInputError.HAS_QUERY)
            }
            if (uri.rawFragment != null) {
                return GmwebServerNormalization.Invalid(GmwebInputError.HAS_FRAGMENT)
            }

            val host = uri.host?.takeIf { it.isNotBlank() }?.lowercase()
                ?: return GmwebServerNormalization.Invalid(GmwebInputError.MISSING_HOST)

            val port = if (uri.port > 0) uri.port else DEFAULT_HTTPS_PORT

            // Strip ONLY the known panel routes. Any other path is refused rather than
            // silently dropped, so a sub-path deployment fails loudly instead of 404-ing later.
            val path = uri.path.orEmpty().trimEnd('/')
            if (path.isNotEmpty() && path !in KNOWN_UI_PATHS) {
                return GmwebServerNormalization.Invalid(GmwebInputError.UNSUPPORTED_PATH)
            }

            val origin = if (port == DEFAULT_HTTPS_PORT) {
                "https://$host"
            } else {
                "https://$host:$port"
            }
            return GmwebServerNormalization.Valid(
                GmwebServerProfile(origin = origin, host = host, port = port)
            )
        }

        /** Convenience for the many call sites that only need the profile, or null. */
        fun profileOf(input: String?): GmwebServerProfile? =
            (normalize(input) as? GmwebServerNormalization.Valid)?.profile
    }
}
