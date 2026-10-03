package com.autonomousone.messages.gateway

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** What one transport attempt returned, in the only categories telemetry can act on. */
internal sealed interface TelemetryPostOutcome {
    data class Accepted(val httpStatus: Int) : TelemetryPostOutcome
    data class Rejected(val httpStatus: Int) : TelemetryPostOutcome

    /** The request was aborted before it was sent because it could not be signed. */
    data object SigningFailed : TelemetryPostOutcome

    /** The configured origin is not HTTPS, so the request was never attempted. */
    data object InsecureUrl : TelemetryPostOutcome

    data class TransportError(val detail: String?) : TelemetryPostOutcome
}

/**
 * The one place a telemetry POST is performed.
 *
 * Android-free and transport-agnostic on purpose: the rules below are the ones that keep telemetry
 * honest, and they are asserted by tests with a fake transport instead of being inferred from a
 * device that reported nothing for a week.
 *
 *  1. **Eligibility first, and named.** A blocked attempt returns the exact reason
 *     (`GATEWAY_DISABLED` / `IDENTITY_NOT_REGISTERED` / `NO_GMWEB_ORIGIN`) rather than a silent
 *     `false`. The gate stays enforced — it is not bypassed, only explained.
 *  2. **At most ONE POST in flight.** Every path — the 60-second heartbeat, a lifecycle trigger and
 *     a web-requested refresh — goes through [perform], which holds one mutex. A remote refresh that
 *     arrives while a report is running waits for it and then performs exactly one fresh report; it
 *     never starts a second request alongside it, and it never creates a second reporter.
 *  3. **Success means 2xx and nothing else.** A trigger is not success, signing is not success, and
 *     opening a socket is not success.
 */
internal class TelemetryReporter(
    private val eligibility: () -> TelemetryEligibility,
    private val deviceId: () -> String,
    private val payload: suspend (TelemetryTrigger) -> JSONObject,
    private val transport: TelemetryTransport,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** One mutex for the whole process: there is one reporter, and it does one POST at a time. */
    private val reportMutex = Mutex()

    /**
     * Run one report attempt.
     *
     * NEVER throws: every outcome is a [TelemetryReportResult]. A caller that has to report to GMweb
     * (the remote refresh command) must not be able to mistake an exception for a completed report.
     */
    suspend fun perform(trigger: TelemetryTrigger): TelemetryReportResult = reportMutex.withLock {
        val gate = eligibility()
        if (!gate.eligible) {
            val code = TelemetryFailureCode.forEligibility(gate.reason) ?: TelemetryFailureCode.NOT_RUNNING
            return@withLock TelemetryReportResult.Failure(code = code, detail = gate.reason)
        }
        val attemptedAt = clock()
        val body = try {
            payload(trigger)
        } catch (e: Exception) {
            // A payload that cannot be built is a LOCAL failure, and it gets its own code: calling it
            // a transport error told a reader "your network is broken" about a phone whose command
            // channel was answering HTTP 200 on the same host. The exception CLASS is kept (a
            // class name carries no payload, no number and no secret) so the on-device card can name
            // the failing operation instead of guessing.
            return@withLock TelemetryReportResult.Failure(
                code = TelemetryFailureCode.PAYLOAD_BUILD_FAILED,
                detail = "payload_build_failed:" + e.javaClass.simpleName
            )
        }
        return@withLock when (val outcome = transport.post(body, deviceId())) {
            is TelemetryPostOutcome.Accepted -> TelemetryReportResult.Success(
                httpStatus = outcome.httpStatus,
                attemptedAt = attemptedAt,
                completedAt = clock(),
                trigger = trigger
            )
            is TelemetryPostOutcome.Rejected -> TelemetryReportResult.Failure(
                code = TelemetryFailureCode.HTTP_ERROR,
                httpStatus = outcome.httpStatus
            )
            TelemetryPostOutcome.SigningFailed -> TelemetryReportResult.Failure(
                code = TelemetryFailureCode.SIGNING_FAILED
            )
            TelemetryPostOutcome.InsecureUrl -> TelemetryReportResult.Failure(
                code = TelemetryFailureCode.INSECURE_URL
            )
            is TelemetryPostOutcome.TransportError -> TelemetryReportResult.Failure(
                code = TelemetryFailureCode.TRANSPORT_ERROR,
                detail = outcome.detail
            )
        }
    }
}

/** The wire. One method, so a test can drive every branch without Android or a socket. */
internal fun interface TelemetryTransport {
    suspend fun post(payload: JSONObject, deviceId: String): TelemetryPostOutcome
}
