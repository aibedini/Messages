package com.autonomousone.messages.sms

import android.app.Activity
import android.telephony.SmsManager

/**
 * NOTE ON THIS FILE'S NAME: a `SendState` enum used to live here (QUEUED / DISPATCHING /
 * DISPATCHED / SEND_UNCONFIRMED / SENT_CONFIRMED / DELIVERED / FAILED, with a monotonic `rank` and
 * `advance`). It was removed rather than wired, because nothing could consume it: no UI reads those
 * states, nothing persisted one, and `SmsStatusPolicy.aggregateSendState` — its only producer — had
 * no production caller, while its KDoc claimed a durable state machine and a UI overlay that did
 * not exist. The durable per-part evidence it claimed to add already exists as
 * `send_segments.callbackState`, and `SmsStatusPolicy.nextStatus` is the single derivation from it.
 * `theCallbackEvidenceToStatusDerivationHasExactlyOneDefinition` fails if a second derivation
 * reappears. What remains below is live: `SentPartVerdict` and `SmsSendPolicy` classify per-part
 * callbacks for `SmsStatusReceiver`, and `SmsSendFailure` codes are persisted to the segment ledger.
 */

/**
 * Typed, PERSISTABLE send-failure reason.
 *
 * Only stable codes are persisted ([code]); user-facing text is generated
 * separately from string resources, so no translated UI string is ever stored
 * as state.
 */
sealed interface SmsSendFailure {
    val code: String

    /** Nothing is registered on the network right now. */
    data object NoService : SmsSendFailure { override val code = "NO_SERVICE" }

    /** Airplane mode / radio powered down. */
    data object RadioOff : SmsSendFailure { override val code = "RADIO_OFF" }

    /** The PDU could not be built — usually nothing to send or a bad encoding. */
    data object NullPdu : SmsSendFailure { override val code = "NULL_PDU" }

    /** SIM missing, not ready, or the subscription is gone. */
    data object SimUnavailable : SmsSendFailure { override val code = "SIM_UNAVAILABLE" }

    /**
     * No line was named and the platform reports NO default SMS subscription (mission §25).
     *
     * Distinct from [SimUnavailable]: the SIM may be present and healthy, but nothing designates it
     * as the default, so there is no honest line to send on. The mission forbids picking SIM 1.
     */
    data object NoDefaultSubscription : SmsSendFailure {
        override val code = "NO_DEFAULT_SMS_SUBSCRIPTION"
    }

    /** SMSC address rejected by the modem. */
    data object InvalidSmsc : SmsSendFailure { override val code = "INVALID_SMSC" }

    /** Destination rejected (malformed number / short-code policy). */
    data object InvalidDestination : SmsSendFailure { override val code = "INVALID_DESTINATION" }

    /** Fixed-dialling-number restriction blocked the send. */
    data object FdnRestricted : SmsSendFailure { override val code = "FDN_RESTRICTED" }

    /** Per-hour / per-window send limit enforced by telephony. */
    data object LimitExceeded : SmsSendFailure { override val code = "LIMIT_EXCEEDED" }

    // ── the rate-limit family (the codes the reliability incident is made of) ──
    //
    // These already existed as raw ints and were logged as unknowns (`CUSTOM_106`). They are the
    // difference between "the radio asked us to slow down" and "the carrier refused the message",
    // which is the whole diagnosis this phone needed.

    /** `RESULT_ERROR_LIMIT_EXCEEDED`: Android's own SMS queue refused the submit. */
    data object QueueLimitExceeded : SmsSendFailure { override val code = "QUEUE_LIMIT_EXCEEDED" }

    /** `RESULT_RIL_REQUEST_RATE_LIMITED`: the RADIO rejected the request as too frequent. */
    data object RilRateLimited : SmsSendFailure { override val code = "RIL_RATE_LIMITED" }

    /** `RESULT_RIL_SMS_SEND_FAIL_RETRY`: the radio explicitly asks for a retry. */
    data object RilRetryRequired : SmsSendFailure { override val code = "RIL_RETRY_REQUIRED" }

    // ── network / carrier level ──────────────────────────────────────────────

    /** The network or carrier refused the message. NEVER inferred as a balance problem. */
    data object NetworkRejected : SmsSendFailure { override val code = "NETWORK_REJECTED" }

    /** The network is not registered/ready for SMS yet. */
    data object NetworkNotReady : SmsSendFailure { override val code = "NETWORK_NOT_READY" }

    /** A network-level error of unknown acceptance. */
    data object NetworkError : SmsSendFailure { override val code = "NETWORK_ERROR" }

    /** The carrier refused, with no portable Android code explaining why. */
    data object CarrierRejected : SmsSendFailure { override val code = "CARRIER_REJECTED" }

    /**
     * A generic failure / vendor code: we cannot tell whether the SMSC took the message.
     *
     * Distinct from [CarrierRejected] because the retry advice differs — nothing here proves the
     * message was refused, so a resend could duplicate it.
     */
    data object CarrierFailureUnknown : SmsSendFailure { override val code = "CARRIER_FAILURE_UNKNOWN" }

    // ── radio / modem / system ──────────────────────────────────────────────

    /** Radio present but not available to telephony right now. */
    data object RadioUnavailable : SmsSendFailure { override val code = "RADIO_UNAVAILABLE" }

    /** The modem is in a state that cannot accept a submit. */
    data object ModemInvalidState : SmsSendFailure { override val code = "MODEM_INVALID_STATE" }

    /** A phone-side system/internal failure while submitting. */
    data object SystemError : SmsSendFailure { override val code = "SYSTEM_ERROR" }

    /** Telephony ran out of memory/resources for the request. */
    data object NoResources : SmsSendFailure { override val code = "NO_RESOURCES" }

    // ── content / policy ────────────────────────────────────────────────────

    /** The PDU format was rejected for this destination/network. */
    data object InvalidSmsFormat : SmsSendFailure { override val code = "INVALID_SMS_FORMAT" }

    /** The body could not be encoded for this network. */
    data object EncodingError : SmsSendFailure { override val code = "ENCODING_ERROR" }

    /** Short codes are not permitted on this SIM. */
    data object ShortCodeNotAllowed : SmsSendFailure { override val code = "SHORT_CODE_NOT_ALLOWED" }

    /** Telephony refused to perform the operation at all. */
    data object OperationNotAllowed : SmsSendFailure { override val code = "OPERATION_NOT_ALLOWED" }

    /** The carrier has barred this line from sending. */
    data object AccessBarred : SmsSendFailure { override val code = "ACCESS_BARRED" }

    /** The SIM cannot send SMS while a call is active. */
    data object BlockedDueToCall : SmsSendFailure { override val code = "BLOCKED_DUE_TO_CALL" }

    // ── evidence that never arrived ─────────────────────────────────────────

    /**
     * SUBMITTED but no SENT callback arrived in time.
     *
     * Deliberately NOT a failure: we cannot prove what happened, and a blind retry could duplicate a
     * message that really left the phone.
     */
    data object SendCallbackTimeout : SmsSendFailure { override val code = "SEND_CALLBACK_TIMEOUT" }

    /** SENT confirmed, but no delivery report arrived. Not a failure either. */
    data object DeliveryReportTimeout : SmsSendFailure { override val code = "DELIVERY_REPORT_TIMEOUT" }

    /** SENT confirmed, delivery evidence never became conclusive. */
    data object DeliveryUnknown : SmsSendFailure { override val code = "DELIVERY_UNKNOWN" }

    /** The API call itself threw — nothing reached telephony. */
    data class DispatchRejected(val errorCode: Int?) : SmsSendFailure {
        override val code = "DISPATCH_REJECTED"
    }

    /** A modem error nobody classified for certain. Ambiguous, never fatal. */
    data class ModemFailure(val resultCode: Int, val errorCode: Int?) : SmsSendFailure {
        override val code = "MODEM_FAILURE"
    }
}

/** Per-part verdict derived from one SENT callback. */
enum class SentPartVerdict {
    /** RESULT_OK for exactly this part. */
    CONFIRMED,

    /** Ambiguous/vendor result: could still have been accepted by the SMSC. */
    UNCONFIRMED,

    /** Definite, actionable refusal for exactly this part. */
    FAILED
}

/**
 * Pure classifier for SENT-callback result codes.
 *
 * Deliberately NOT "non-OK == FAILED": on real devices/carriers
 * RESULT_ERROR_GENERIC_FAILURE is returned for submits the SMSC accepted and
 * delivered (observed with UCS-2/Persian bodies). Blindly failing those was the
 * previous false-failure bug; blindly succeeding them is the current
 * false-success bug (a dead SIM with no credit shows a single tick).
 *
 * The split is therefore by ACTIONABILITY:
 *   hard failure  -> a definite reason the user can act on (FAILED, visible)
 *   ambiguous     -> SEND_UNCONFIRMED (visible as not-confirmed, retry offered)
 *   RESULT_OK     -> CONFIRMED
 */
object SmsSendPolicy {

    /** Activity.RESULT_OK — the radio accepted this part. */
    const val RESULT_OK_CODE: Int = Activity.RESULT_OK

    fun classifySentResult(resultCode: Int, errorCode: Int? = null): SentPartVerdict =
        when (SmsTransportClassifier.classify(resultCode, errorCode).evidence) {
            SendEvidence.CONFIRMED -> SentPartVerdict.CONFIRMED
            SendEvidence.REJECTED -> SentPartVerdict.FAILED
            SendEvidence.AMBIGUOUS -> SentPartVerdict.UNCONFIRMED
        }

    /**
     * Typed reason for a part, or null when the part is confirmed (or merely
     * ambiguous — ambiguity is a STATE, not a failure reason).
     *
     * Single source: [SmsTransportClassifier] owns the result-code interpretation (including the whole
     * API 30+ RIL family), so this cannot drift from what the diagnostics log and the transport gate
     * believe about the same code.
     */
    fun hardFailure(resultCode: Int, errorCode: Int? = null): SmsSendFailure? {
        val verdict = SmsTransportClassifier.classify(resultCode, errorCode)
        return if (verdict.evidence == SendEvidence.REJECTED) verdict.failure else null
    }

    /** Reason recorded for an ambiguous part, for diagnosis only. */
    fun ambiguousReason(resultCode: Int, errorCode: Int? = null): SmsSendFailure =
        SmsTransportClassifier.classify(resultCode, errorCode).failure
            ?: SmsSendFailure.ModemFailure(resultCode, errorCode)
}
