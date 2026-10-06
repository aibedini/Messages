package com.autonomousone.messages.sms

import android.telephony.SmsManager

/**
 * Did the radio actually take the message, refuse it, or leave us guessing?
 *
 * The distinction is the whole point of the SMS reliability work: a missing or generic result must NOT
 * be turned into "failed" (a retry could then duplicate a message that really left the phone), and a
 * definite refusal must NOT be shown as a vague modem fault.
 */
enum class SendEvidence {
    /** Positive evidence that the radio accepted and sent this segment. */
    CONFIRMED,

    /** Positive evidence of refusal before acceptance — a retry cannot duplicate anything. */
    REJECTED,

    /** No usable evidence: `GENERIC_FAILURE`, a vendor code, or a missing callback. */
    AMBIGUOUS
}

/** What a retry would mean for this outcome. */
enum class RetrySafety {
    /** Nothing was accepted; retrying cannot duplicate. */
    SAFE,

    /** A retry may duplicate a message the network already has. Say so explicitly. */
    POSSIBLE_DUPLICATE,

    /** Wait for the cooldown/gate first — the transport asked us to slow down. */
    WAIT,

    /** Not applicable (success). */
    NOT_APPLICABLE
}

/**
 * The stable, persistable failure vocabulary.
 *
 * These names are written to durable storage and shown in diagnostics; they are NOT user-facing prose
 * (see [SmsFailurePresentation] for that). Deliberately granular: collapsing a rate limit, a radio-off
 * and a modem error into one "modem failure" is what made the original incident undiagnosable.
 *
 * NOTE ON BALANCE: there is no portable Android result for "insufficient prepaid balance". A carrier
 * that refuses for balance reasons looks like [CARRIER_REJECTED] or [CARRIER_FAILURE_UNKNOWN] here, and
 * the UI must not claim a balance problem as fact (mission §10/§59).
 */
enum class SmsTransportFailure(val persistable: String) {
    NO_SERVICE("NO_SERVICE"),
    RADIO_OFF("RADIO_OFF"),
    RADIO_UNAVAILABLE("RADIO_UNAVAILABLE"),
    NULL_PDU("NULL_PDU"),

    SIM_UNAVAILABLE("SIM_UNAVAILABLE"),
    NO_DEFAULT_SMS_SUBSCRIPTION("NO_DEFAULT_SMS_SUBSCRIPTION"),

    QUEUE_LIMIT_EXCEEDED("QUEUE_LIMIT_EXCEEDED"),
    RIL_RATE_LIMITED("RIL_RATE_LIMITED"),
    RIL_RETRY_REQUIRED("RIL_RETRY_REQUIRED"),

    NETWORK_REJECTED("NETWORK_REJECTED"),
    NETWORK_NOT_READY("NETWORK_NOT_READY"),
    NETWORK_ERROR("NETWORK_ERROR"),

    MODEM_ERROR("MODEM_ERROR"),
    MODEM_INVALID_STATE("MODEM_INVALID_STATE"),
    SYSTEM_ERROR("SYSTEM_ERROR"),
    INTERNAL_ERROR("INTERNAL_ERROR"),
    NO_RESOURCES("NO_RESOURCES"),

    INVALID_SMSC("INVALID_SMSC"),
    INVALID_DESTINATION("INVALID_DESTINATION"),
    INVALID_SMS_FORMAT("INVALID_SMS_FORMAT"),
    ENCODING_ERROR("ENCODING_ERROR"),

    FDN_RESTRICTED("FDN_RESTRICTED"),
    SHORT_CODE_NOT_ALLOWED("SHORT_CODE_NOT_ALLOWED"),

    OPERATION_NOT_ALLOWED("OPERATION_NOT_ALLOWED"),
    ACCESS_BARRED("ACCESS_BARRED"),
    BLOCKED_DUE_TO_CALL("BLOCKED_DUE_TO_CALL"),

    DISPATCH_EXCEPTION("DISPATCH_EXCEPTION"),

    CARRIER_REJECTED("CARRIER_REJECTED"),
    CARRIER_FAILURE_UNKNOWN("CARRIER_FAILURE_UNKNOWN"),

    SEND_CALLBACK_TIMEOUT("SEND_CALLBACK_TIMEOUT"),
    DELIVERY_REPORT_TIMEOUT("DELIVERY_REPORT_TIMEOUT"),
    DELIVERY_UNKNOWN("DELIVERY_UNKNOWN")
}

/**
 * The ONE interpretation of a transport result.
 *
 * Everything that needs to judge an SMS outcome — the status receiver, the queue, the diagnostics
 * screen and the failure copy — reads this instead of re-deriving meaning from a raw int. Duplicated
 * classification is how `GENERIC_FAILURE` came to be treated as a definite failure in one place and as
 * an unknown in another.
 */
data class SmsTransportVerdict(
    /** Null on success. */
    val failure: SmsTransportFailure?,
    val evidence: SendEvidence,
    val retrySafety: RetrySafety,
    /** True when the transport explicitly asked us to slow down. */
    val shouldThrottle: Boolean,
    /** The raw code, kept for durable evidence. */
    val resultCode: Int,
    val resultCodeName: String,
    val radioErrorCode: Int?
) {
    val isSuccess: Boolean get() = failure == null
}

object SmsTransportClassifier {

    /**
     * Classify a SENT-callback result code, with the optional vendor `errorCode` the platform includes
     * for generic/RIL failures.
     *
     * The generic result is deliberately [SendEvidence.AMBIGUOUS]: it tells us nothing about whether
     * the SMSC took the message, so a retry decision there must be a user decision, never an automatic
     * resend.
     */
    fun classify(resultCode: Int, radioErrorCode: Int? = null): SmsTransportVerdict {
        val name = SmsResultCodes.name(resultCode)
        fun verdict(
            failure: SmsTransportFailure?,
            evidence: SendEvidence,
            retry: RetrySafety,
            throttle: Boolean = false
        ) = SmsTransportVerdict(
            failure = failure,
            evidence = evidence,
            retrySafety = retry,
            shouldThrottle = throttle,
            resultCode = resultCode,
            resultCodeName = name,
            radioErrorCode = radioErrorCode
        )

        return when (resultCode) {
            android.app.Activity.RESULT_OK ->
                verdict(null, SendEvidence.CONFIRMED, RetrySafety.NOT_APPLICABLE)

            // ── throttling: the radio said "too many requests" ────────────────
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED ->
                verdict(SmsTransportFailure.QUEUE_LIMIT_EXCEEDED, SendEvidence.REJECTED, RetrySafety.WAIT, throttle = true)
            SmsManager.RESULT_RIL_REQUEST_RATE_LIMITED ->
                verdict(SmsTransportFailure.RIL_RATE_LIMITED, SendEvidence.REJECTED, RetrySafety.WAIT, throttle = true)

            // ── the radio asked for a retry, explicitly ──────────────────────
            SmsManager.RESULT_RIL_SMS_SEND_FAIL_RETRY ->
                verdict(SmsTransportFailure.RIL_RETRY_REQUIRED, SendEvidence.REJECTED, RetrySafety.WAIT)

            // ── definite, pre-acceptance refusals (retry is safe) ────────────
            SmsManager.RESULT_ERROR_NO_SERVICE ->
                verdict(SmsTransportFailure.NO_SERVICE, SendEvidence.REJECTED, RetrySafety.SAFE)
            SmsManager.RESULT_ERROR_RADIO_OFF ->
                verdict(SmsTransportFailure.RADIO_OFF, SendEvidence.REJECTED, RetrySafety.SAFE)
            SmsManager.RESULT_ERROR_NULL_PDU ->
                verdict(SmsTransportFailure.NULL_PDU, SendEvidence.REJECTED, RetrySafety.SAFE)
            SmsManager.RESULT_RIL_RADIO_NOT_AVAILABLE ->
                verdict(SmsTransportFailure.RADIO_UNAVAILABLE, SendEvidence.REJECTED, RetrySafety.SAFE)
            SmsManager.RESULT_RIL_SIM_ABSENT ->
                verdict(SmsTransportFailure.SIM_UNAVAILABLE, SendEvidence.REJECTED, RetrySafety.SAFE)
            SmsManager.RESULT_RIL_INVALID_SMSC_ADDRESS ->
                verdict(SmsTransportFailure.INVALID_SMSC, SendEvidence.REJECTED, RetrySafety.SAFE)
            SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE ->
                verdict(SmsTransportFailure.FDN_RESTRICTED, SendEvidence.REJECTED, RetrySafety.SAFE)
            SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED,
            SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED ->
                verdict(SmsTransportFailure.SHORT_CODE_NOT_ALLOWED, SendEvidence.REJECTED, RetrySafety.SAFE)

            // ── network / carrier level ──────────────────────────────────────
            SmsManager.RESULT_RIL_NETWORK_REJECT ->
                verdict(SmsTransportFailure.NETWORK_REJECTED, SendEvidence.REJECTED, RetrySafety.WAIT)
            SmsManager.RESULT_RIL_NETWORK_NOT_READY ->
                verdict(SmsTransportFailure.NETWORK_NOT_READY, SendEvidence.REJECTED, RetrySafety.WAIT)
            SmsManager.RESULT_RIL_NETWORK_ERR ->
                verdict(SmsTransportFailure.NETWORK_ERROR, SendEvidence.AMBIGUOUS, RetrySafety.POSSIBLE_DUPLICATE)
            SmsManager.RESULT_RIL_ACCESS_BARRED ->
                verdict(SmsTransportFailure.ACCESS_BARRED, SendEvidence.REJECTED, RetrySafety.SAFE)

            // ── modem / system ──────────────────────────────────────────────
            SmsManager.RESULT_RIL_MODEM_ERR ->
                verdict(SmsTransportFailure.MODEM_ERROR, SendEvidence.AMBIGUOUS, RetrySafety.POSSIBLE_DUPLICATE)
            SmsManager.RESULT_RIL_INVALID_MODEM_STATE, SmsManager.RESULT_RIL_INVALID_STATE ->
                verdict(SmsTransportFailure.MODEM_INVALID_STATE, SendEvidence.AMBIGUOUS, RetrySafety.WAIT)
            SmsManager.RESULT_RIL_SYSTEM_ERR, SmsManager.RESULT_RIL_INTERNAL_ERR ->
                verdict(SmsTransportFailure.SYSTEM_ERROR, SendEvidence.AMBIGUOUS, RetrySafety.POSSIBLE_DUPLICATE)
            SmsManager.RESULT_RIL_NO_MEMORY, SmsManager.RESULT_RIL_NO_RESOURCES ->
                verdict(SmsTransportFailure.NO_RESOURCES, SendEvidence.REJECTED, RetrySafety.WAIT)
            SmsManager.RESULT_RIL_CANCELLED ->
                verdict(SmsTransportFailure.OPERATION_NOT_ALLOWED, SendEvidence.REJECTED, RetrySafety.SAFE)
            SmsManager.RESULT_RIL_OPERATION_NOT_ALLOWED, SmsManager.RESULT_RIL_REQUEST_NOT_SUPPORTED ->
                verdict(SmsTransportFailure.OPERATION_NOT_ALLOWED, SendEvidence.REJECTED, RetrySafety.SAFE)
            SmsManager.RESULT_RIL_BLOCKED_DUE_TO_CALL,
            SmsManager.RESULT_RIL_SIMULTANEOUS_SMS_AND_CALL_NOT_ALLOWED ->
                verdict(SmsTransportFailure.BLOCKED_DUE_TO_CALL, SendEvidence.REJECTED, RetrySafety.WAIT)

            // ── malformed content ───────────────────────────────────────────
            SmsManager.RESULT_RIL_INVALID_SMS_FORMAT ->
                verdict(SmsTransportFailure.INVALID_SMS_FORMAT, SendEvidence.REJECTED, RetrySafety.SAFE)
            SmsManager.RESULT_RIL_ENCODING_ERR ->
                verdict(SmsTransportFailure.ENCODING_ERROR, SendEvidence.REJECTED, RetrySafety.SAFE)
            SmsManager.RESULT_RIL_INVALID_ARGUMENTS ->
                verdict(SmsTransportFailure.INVALID_DESTINATION, SendEvidence.REJECTED, RetrySafety.SAFE)

            // ── generic: we genuinely do not know ───────────────────────────
            //
            // NOT a definite failure. `GENERIC_FAILURE` is what a carrier refusing for balance looks
            // like from Android, and it is also what a vendor-specific modem error looks like. The
            // honest answer is "carrier/unknown", and the retry path must warn about duplicates.
            SmsManager.RESULT_ERROR_GENERIC_FAILURE ->
                verdict(SmsTransportFailure.CARRIER_FAILURE_UNKNOWN, SendEvidence.AMBIGUOUS, RetrySafety.POSSIBLE_DUPLICATE)

            else ->
                verdict(SmsTransportFailure.CARRIER_FAILURE_UNKNOWN, SendEvidence.AMBIGUOUS, RetrySafety.POSSIBLE_DUPLICATE)
        }
    }
}
