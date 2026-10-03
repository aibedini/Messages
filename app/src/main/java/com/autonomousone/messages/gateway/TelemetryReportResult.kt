package com.autonomousone.messages.gateway

/**
 * What ONE telemetry POST attempt actually produced.
 *
 * **Why a Boolean was not enough.** `report()` returned true/false, "success" was written when the
 * call did not throw, and a signed request that never opened a socket was indistinguishable from a
 * server rejection — which is why a week of zero `POST /api/v1/agent/device-telemetry` requests could
 * not be explained. Every attempt now names its stage, and a remote refresh command can hand GMweb
 * the same answer it reports internally.
 */
sealed interface TelemetryReportResult {

    /** The server answered 2xx. Nothing weaker counts as success. */
    data class Success(
        val httpStatus: Int,
        val attemptedAt: Long,
        val completedAt: Long,
        val trigger: TelemetryTrigger
    ) : TelemetryReportResult

    data class Failure(
        val code: TelemetryFailureCode,
        val httpStatus: Int? = null,
        /** Safe, non-sensitive detail: a failure kind or an HTTP status. Never a body or a secret. */
        val detail: String? = null
    ) : TelemetryReportResult

    val succeeded: Boolean get() = this is Success
}

/**
 * The only failure vocabulary telemetry has, and the exact string each one becomes in a command ACK.
 *
 * Stable by contract: these strings are what GMweb stores against the command it sent, so renaming
 * one silently changes the meaning of history.
 */
enum class TelemetryFailureCode(val commandCode: String) {

    /** No live reporter exists in this process (the gateway service is not running). */
    NOT_RUNNING("TELEMETRY_NOT_RUNNING"),

    /** The gateway is switched off (or consent is absent) by the user. */
    GATEWAY_DISABLED("TELEMETRY_GATEWAY_DISABLED"),

    /** This install has never completed identity enrollment, so it cannot sign. */
    IDENTITY_NOT_REGISTERED("TELEMETRY_IDENTITY_NOT_REGISTERED"),

    /** No GMweb origin is configured: there is nowhere to send the report. */
    NO_GMWEB_ORIGIN("TELEMETRY_NO_GMWEB_ORIGIN"),

    /** The Keystore could not sign, so the request was aborted before it was sent (fail closed). */
    SIGNING_FAILED("TELEMETRY_SIGNING_FAILED"),

    /** The server answered, and the answer was not 2xx. */
    HTTP_ERROR("TELEMETRY_HTTP_ERROR"),

    /** No response at all: DNS, TCP, TLS, socket read. */
    TRANSPORT_ERROR("TELEMETRY_TRANSPORT_ERROR"),

    /** The caller's deadline passed while the POST was still in flight. */
    TIMEOUT("TELEMETRY_TIMEOUT");

    companion object {
        /** The code for an eligibility reason, or null when the input means "eligible". */
        fun forEligibility(reason: String?): TelemetryFailureCode? = when (reason) {
            null -> null
            TelemetryEligibility.GATEWAY_DISABLED -> GATEWAY_DISABLED
            TelemetryEligibility.IDENTITY_NOT_REGISTERED -> IDENTITY_NOT_REGISTERED
            TelemetryEligibility.NO_GMWEB_ORIGIN -> NO_GMWEB_ORIGIN
            // An eligibility reason this build does not know cannot be reported as success.
            else -> TRANSPORT_ERROR
        }
    }
}
