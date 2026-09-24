package com.autonomousone.messages.sync

import com.autonomousone.messages.gateway.health.GatewayFailureKind

/**
 * Structured, machine-readable reason a replication step is not proceeding.
 *
 * Business state must never be an arbitrary string: anything that decides whether, when or why
 * an event moves has to be one of these values, so it can be counted, compared, asserted on and
 * exported without a human reading prose. Today the app answers "why is nothing syncing?" with
 * raw server response bodies, `"dispatch_failed"`, and a `DEGRADED` verdict — none of which name
 * a cause (see `docs/gateway-replication-audit.md`, Blockers 2 and 6).
 *
 * Division of labour with the existing taxonomies:
 *
 *  - [GatewayFailureKind] stays the **transport** taxonomy: what the last HTTP/TLS attempt did.
 *  - [SyncErrorCode] is the **outcome** taxonomy: what is stopping replication now, including
 *    causes that never reach the network at all (missing permission, missing grant, no key).
 *  - [ReplicationBlocker] is the small set of those causes that currently block, and it is the
 *    one thing a diagnostic must name.
 *
 * The names are the mission's, deliberately, so diagnostics and support conversations use one
 * vocabulary.
 */
enum class SyncErrorCode {

    // ── Configuration ────────────────────────────────────────────────────────

    /** The gateway is switched off by the user. Not a fault. */
    GATEWAY_DISABLED,

    /** No GMweb origin is stored, so there is nowhere to replicate to. */
    CONFIG_MISSING_SERVER_URL,

    // ── Identity and authorization ───────────────────────────────────────────

    /** The device has never completed identity enrollment. */
    IDENTITY_NOT_REGISTERED,

    /** The control plane needs credentials we do not have. */
    AUTH_REQUIRED,

    /** Credentials existed and are no longer valid. */
    AUTH_EXPIRED,

    /** The server has revoked this device. Replication must stop, not retry. */
    DEVICE_REVOKED,

    // ── Transport ────────────────────────────────────────────────────────────

    /** No validated internet. A wait, not an error. */
    NETWORK_UNAVAILABLE,

    /** A connection was made but the peer did not answer in time. */
    NETWORK_TIMEOUT,

    /** A 4xx that is not otherwise classified. */
    SERVER_4XX,

    /** A 5xx: the server failed. Retryable. */
    SERVER_5XX,

    /** HTTP 429. Retryable, and honour Retry-After where the server sends it. */
    RATE_LIMITED,

    // ── Payload ──────────────────────────────────────────────────────────────

    /** The event failed a local schema/envelope rule and can never be accepted. */
    INVALID_EVENT_SCHEMA,

    /** The event exceeds the server's size limit on its own, so no batch split can help. */
    PAYLOAD_TOO_LARGE,

    // ── Crypto ───────────────────────────────────────────────────────────────

    /**
     * The key material required to encrypt is unavailable (Keystore unavailable, key never
     * provisioned, or the CKE wrap key is gone).
     *
     * Added to the mission's minimum list because without it a missing device key has no honest
     * code — it would have to borrow [HISTORY_KEY_MISSING] and misdescribe itself. Today this
     * condition is invisible: `DeviceIdentity.isEnrolled()` is never called, so it surfaces only
     * as a signing failure that is retried forever (audit Blockers 6 and 19).
     *
     * This is never satisfied by falling back to plaintext. It is a hard block.
     */
    CRYPTO_KEY_UNAVAILABLE,

    // ── History ──────────────────────────────────────────────────────────────

    /** No authorized consumer holds FULL_HISTORY, so backfill has no destination. */
    HISTORY_GRANT_MISSING,

    /** The key needed to encrypt history is not available. Never falls back to plaintext. */
    HISTORY_KEY_MISSING,

    // ── Telephony ────────────────────────────────────────────────────────────

    /** READ_SMS (or the MMS equivalent) is not granted. */
    TELEPHONY_PERMISSION_MISSING,

    /** The provider could not be read even though permission appears granted. */
    TELEPHONY_QUERY_FAILED,

    // ── Send ─────────────────────────────────────────────────────────────────

    /** The requested SIM is not present or not active. Must never fall back silently. */
    SIM_NOT_AVAILABLE,

    /** The platform refused the send. */
    SMS_SEND_FAILED,

    // ── Command execution ────────────────────────────────────────────────────

    /**
     * A command was claimed and its lease expired, so the app CANNOT TELL whether it ran.
     *
     * This is the honest code for the crash window between claiming a `SEND_SMS` command and
     * recording its outcome: the message may have reached the radio, or it may never have been
     * handed over. It is deliberately NOT [SMS_SEND_FAILED] — that would assert the platform
     * refused the send, which is precisely what is unknown — and it is deliberately NOT
     * transient, because the one thing that must never happen here is an automatic retry
     * (mission §78: no remotely requested send may cause the same SMS to be sent twice).
     *
     * A human resolves it: the row is reported terminal so GMweb replaces the optimistic bubble
     * with a retryable failure, and a retry arrives as a NEW command id, so no duplicate is
     * possible by construction.
     */
    COMMAND_INTERRUPTED_AFTER_SUBMIT,

    /**
     * A command reached its expiry without ever being claimed, so it provably never executed.
     *
     * Distinct from [COMMAND_INTERRUPTED_AFTER_SUBMIT] because nothing is unknown: the
     * discriminating fact is `attemptCount == 0`, and reporting "we are not sure" about work that
     * provably never started is its own kind of dishonesty.
     */
    COMMAND_EXPIRED_BEFORE_CLAIM,

    /** Anything not covered above. Deliberately last resort. */
    UNKNOWN;

    /**
     * True when retrying the SAME work, unchanged, could plausibly succeed later.
     *
     * This is what separates "wait" from "a human must act": a rejected credential, a missing
     * permission and a missing key never heal by waiting, and treating them as transient is what
     * produced the infinite invisible retry of audit Blocker 6.
     */
    val isTransient: Boolean
        get() = when (this) {
            NETWORK_UNAVAILABLE, NETWORK_TIMEOUT, SERVER_5XX, RATE_LIMITED -> true
            GATEWAY_DISABLED, CONFIG_MISSING_SERVER_URL,
            IDENTITY_NOT_REGISTERED, AUTH_REQUIRED, AUTH_EXPIRED, DEVICE_REVOKED,
            SERVER_4XX, INVALID_EVENT_SCHEMA, PAYLOAD_TOO_LARGE,
            CRYPTO_KEY_UNAVAILABLE,
            HISTORY_GRANT_MISSING, HISTORY_KEY_MISSING,
            TELEPHONY_PERMISSION_MISSING, TELEPHONY_QUERY_FAILED,
            SIM_NOT_AVAILABLE, SMS_SEND_FAILED,
            COMMAND_INTERRUPTED_AFTER_SUBMIT, COMMAND_EXPIRED_BEFORE_CLAIM,
            UNKNOWN -> false
        }

    companion object {

        /**
         * The transport outcome as an outcome code.
         *
         * Kept here, next to the target enum, so there is exactly one place that decides what a
         * status or exception means in business terms — the alternative is each caller inventing
         * its own mapping, which is how `409` came to be "transient" to the classifier and fatal
         * to the uploader (audit Blocker 4).
         *
         * [httpStatus] is authoritative when present, because a response that arrived is better
         * evidence than an exception raised on the way.
         */
        fun fromFailureKind(kind: GatewayFailureKind, httpStatus: Int? = null): SyncErrorCode {
            httpStatus?.let { return fromHttpStatus(it) }
            return when (kind) {
                GatewayFailureKind.NONE -> UNKNOWN
                GatewayFailureKind.DNS,
                GatewayFailureKind.TCP_CONNECT,
                GatewayFailureKind.NETWORK_OFFLINE -> NETWORK_UNAVAILABLE
                GatewayFailureKind.READ_TIMEOUT,
                GatewayFailureKind.WRITE_TIMEOUT -> NETWORK_TIMEOUT
                GatewayFailureKind.TLS -> AUTH_REQUIRED
                GatewayFailureKind.HTTP_AUTH -> AUTH_REQUIRED
                GatewayFailureKind.HTTP_FORBIDDEN -> DEVICE_REVOKED
                GatewayFailureKind.VALIDATION_FAILED,
                GatewayFailureKind.INVALID_RESPONSE -> INVALID_EVENT_SCHEMA
                GatewayFailureKind.HTTP_BAD_REQUEST,
                GatewayFailureKind.HTTP_METHOD_NOT_ALLOWED,
                GatewayFailureKind.HTTP_NOT_FOUND,
                GatewayFailureKind.HTTP_CONFLICT,
                GatewayFailureKind.HTTP_SERVER,
                GatewayFailureKind.HTTP_RATE_LIMITED,
                GatewayFailureKind.UNKNOWN -> UNKNOWN
            }
        }

        /**
         * The outcome code for an HTTP status.
         *
         * Only 401 is an authentication problem and only 403 is an authorization one; every
         * other 4xx is a contract failure about the REQUEST, which must never be reported as a
         * rejected credential (the exact defect v3.4.7 fixed on the device).
         */
        fun fromHttpStatus(status: Int): SyncErrorCode = when {
            status == 429 -> RATE_LIMITED
            status == 401 -> AUTH_REQUIRED
            status == 403 -> DEVICE_REVOKED
            status == 413 -> PAYLOAD_TOO_LARGE
            status == 422 -> INVALID_EVENT_SCHEMA
            status in 400..499 -> SERVER_4XX
            status in 500..599 -> SERVER_5XX
            else -> UNKNOWN
        }
    }
}
