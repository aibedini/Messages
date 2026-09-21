package com.autonomousone.messages.gateway.health

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** Which part of one HTTP exchange a timeout happened in. */
enum class GatewayRequestPhase {
    /** Resolving the host name. */
    DNS,

    /** Establishing the TCP connection. */
    CONNECT,

    /** TLS handshake. */
    TLS,

    /** Writing the request. */
    WRITE,

    /** Waiting for the response. */
    READ,

    /** The phase is not known; classification falls back to the message text. */
    UNKNOWN
}

/**
 * WHY a gateway exchange failed, in terms a user can act on.
 *
 * The whole point of this enum is that "Disconnected" is useless. A wrong API key, a
 * wrong URL, a stale certificate and an offline phone are four different problems with
 * four different fixes, and the app can tell them apart because the JVM already did:
 * `UnknownHostException`, `ConnectException`, `SSLPeerUnverifiedException` and HTTP 401
 * are distinct outcomes of a distinct code path.
 *
 * Pure and Android-free so the classification is unit-tested rather than eyeballed
 * through a logcat.
 */
enum class GatewayFailureKind {

    /** No failure recorded. */
    NONE,

    /** The host name did not resolve: wrong URL, or DNS is blocked/offline. */
    DNS,

    /** The name resolved but nothing accepted the TCP connection: wrong port, or down. */
    TCP_CONNECT,

    /** TLS handshake or certificate validation failed — including a hostname mismatch. */
    TLS,

    /** HTTP 401: the device/API key was rejected. Retrying changes nothing. */
    HTTP_AUTH,

    /** HTTP 403: the device is recognised but not authorised for this route. */
    HTTP_FORBIDDEN,

    /**
     * HTTP 400: the SERVER could not parse or accept the REQUEST itself.
     *
     * This is emphatically NOT an authentication rejection, and conflating the two was a real
     * defect: a perfectly working device was reported as having its key rejected because the
     * diagnostic's own request did not match the server's contract. A 400 means the app and
     * the server disagree about the request SHAPE — an app bug or a version mismatch — and it
     * gets its own kind precisely so the UI can say that instead of blaming the credential.
     */
    HTTP_BAD_REQUEST,

    /** HTTP 405: the route exists, but not for this method. Also a contract mismatch. */
    HTTP_METHOD_NOT_ALLOWED,

    /** HTTP 404: the URL is wrong, or the gateway route is not mounted. */
    HTTP_NOT_FOUND,

    /** HTTP 409: a state/protocol conflict with the server. */
    HTTP_CONFLICT,

    /** HTTP 429: the server is rate limiting this device. */
    HTTP_RATE_LIMITED,

    /** HTTP 5xx: the server itself failed. */
    HTTP_SERVER,

    /** The connection was established but the server did not answer in time. */
    READ_TIMEOUT,

    /** The request body could not be written in time. */
    WRITE_TIMEOUT,

    /** The device has no validated internet at all. Not an error — a wait. */
    NETWORK_OFFLINE,

    /** A response arrived but could not be understood: incompatible API or protocol. */
    INVALID_RESPONSE,

    /** The server answered and the payload failed a local validation rule. */
    VALIDATION_FAILED,

    /** Anything else. Deliberately last resort, never a bucket for the above. */
    UNKNOWN;

    /** True when retrying the SAME request can plausibly succeed. */
    val isTransient: Boolean
        get() = when (this) {
            DNS, TCP_CONNECT, READ_TIMEOUT, WRITE_TIMEOUT, HTTP_SERVER,
            HTTP_RATE_LIMITED, NETWORK_OFFLINE, HTTP_CONFLICT, UNKNOWN -> true
            // A rejected key, a wrong URL, a wrong route, a bad certificate, a malformed API
            // and a request the server cannot parse do not heal by waiting.
            NONE, TLS, HTTP_AUTH, HTTP_FORBIDDEN, HTTP_BAD_REQUEST,
            HTTP_METHOD_NOT_ALLOWED, HTTP_NOT_FOUND,
            INVALID_RESPONSE, VALIDATION_FAILED -> false
        }

    /**
     * True when this is the DEVICE's fault rather than the network's — the distinction the
     * status card needs, because these are the cases where "Reconnect" cannot help and the
     * user has to change something.
     *
     * [HTTP_BAD_REQUEST] and [HTTP_METHOD_NOT_ALLOWED] are deliberately EXCLUDED: they are the
     * APP's fault (a request that does not match the server's contract), and telling the user
     * to check their key or their address for those would send them to fix the wrong thing.
     */
    val needsConfigurationChange: Boolean
        get() = this == HTTP_AUTH || this == HTTP_FORBIDDEN ||
            this == HTTP_NOT_FOUND || this == TLS || this == INVALID_RESPONSE

    /**
     * True when this failure is evidence that a CREDENTIAL was rejected.
     *
     * Only 401 and 403 qualify. This is the single source for "the key was rejected", so no
     * call site has to guess — a 400 from a malformed diagnostic request must never be
     * reported as a rejected key, which is exactly what happened.
     */
    val isAuthenticationRejection: Boolean
        get() = this == HTTP_AUTH || this == HTTP_FORBIDDEN

    /**
     * True when the server rejected the REQUEST rather than the credential: the app and the
     * server disagree about the contract (path, method, body shape or API version).
     */
    val isRequestContractMismatch: Boolean
        get() = this == HTTP_BAD_REQUEST || this == HTTP_METHOD_NOT_ALLOWED ||
            this == HTTP_NOT_FOUND || this == INVALID_RESPONSE

    companion object {

        /**
         * Classify a failed exchange.
         *
         * [httpStatus] is used when the exchange actually produced a response; [error] is
         * used when it did not. The ORDER matters: a 401 that also carried a
         * `SocketTimeoutException` while reading the error body is an AUTH problem, not a
         * timeout, because the status is the more specific fact.
         */
        fun classify(
            error: Throwable? = null,
            httpStatus: Int? = null,
            phase: GatewayRequestPhase = GatewayRequestPhase.UNKNOWN,
            networkValidated: Boolean = true
        ): GatewayFailureKind {
            httpStatus?.let { status ->
                fromHttpStatus(status)?.let { return it }
            }
            if (!networkValidated) return NETWORK_OFFLINE
            if (error == null) return NONE

            var cause: Throwable? = error
            var depth = 0
            while (cause != null && depth < 8) {
                fromThrowable(cause, phase)?.let { return it }
                cause = cause.cause
                depth++
            }

            // Fall back to the message only for the one case that cannot be told from the
            // type: a SocketTimeoutException carries no phase.
            val message = error.message.orEmpty().lowercase()
            if (message.contains("timed out") || message.contains("timeout")) {
                return when (phase) {
                    GatewayRequestPhase.CONNECT -> TCP_CONNECT
                    GatewayRequestPhase.WRITE -> WRITE_TIMEOUT
                    else -> READ_TIMEOUT
                }
            }
            return UNKNOWN
        }

        /** The failure an HTTP status alone implies, or null when the status is a success. */
        fun fromHttpStatus(status: Int): GatewayFailureKind? = when {
            status in 200..299 -> null
            status == 400 -> HTTP_BAD_REQUEST
            status == 401 -> HTTP_AUTH
            status == 403 -> HTTP_FORBIDDEN
            status == 404 -> HTTP_NOT_FOUND
            status == 405 -> HTTP_METHOD_NOT_ALLOWED
            status == 409 -> HTTP_CONFLICT
            status == 422 -> VALIDATION_FAILED
            status == 429 -> HTTP_RATE_LIMITED
            status in 500..599 -> HTTP_SERVER
            // Any other 4xx is the server refusing the request shape; retrying it unchanged
            // will not help, so it must NOT be reported as a transient 5xx-style failure.
            // It is a REQUEST problem, not a credential one.
            status in 400..499 -> HTTP_BAD_REQUEST
            else -> UNKNOWN
        }

        private fun fromThrowable(
            error: Throwable,
            phase: GatewayRequestPhase
        ): GatewayFailureKind? = when (error) {
            // The name never resolved. A wrong host, or DNS blocked on this network.
            is UnknownHostException -> DNS

            // Nothing accepted the connection.
            is ConnectException -> TCP_CONNECT
            is NoRouteToHostException -> TCP_CONNECT

            // Hostname/certificate mismatch first: it is a SUBCLASS of the handshake
            // failure, so checking the parent first would mislabel it.
            is SSLPeerUnverifiedException -> TLS
            is SSLHandshakeException -> TLS

            is SocketTimeoutException -> when (phase) {
                GatewayRequestPhase.CONNECT -> TCP_CONNECT
                GatewayRequestPhase.WRITE -> WRITE_TIMEOUT
                GatewayRequestPhase.TLS -> TLS
                else -> READ_TIMEOUT
            }

            is IOException -> null // keep walking the cause chain

            else -> null
        }
    }
}
