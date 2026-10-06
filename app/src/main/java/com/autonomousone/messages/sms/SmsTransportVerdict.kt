package com.autonomousone.messages.sms

import android.telephony.SmsManager

/**
 * Did the radio actually take the message, refuse it, or leave us guessing?
 *
 * The distinction is the point of the SMS reliability work: a generic or missing result must NOT become
 * "failed" (a retry could then duplicate a message that really left the phone), and a definite refusal
 * must NOT be hidden as a vague modem fault.
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

    /** A retry may duplicate a message the network already has. The UI must say so. */
    POSSIBLE_DUPLICATE,

    /** Wait for the throttle cooldown first — the transport asked us to slow down. */
    WAIT,

    /** Not applicable (success). */
    NOT_APPLICABLE
}

/**
 * The ONE interpretation of an Android transport result.
 *
 * The failure vocabulary is the CANONICAL [SmsSendFailure] type from `SendState.kt` — there is exactly
 * one failure taxonomy in this app, and this object decides which entry of it a raw result means. The
 * extra information layered here (evidence, retry safety, whether to throttle) is what the transport
 * gate, the status receiver and the UI need in order to behave differently for a rate limit versus a
 * carrier refusal versus an unknown outcome.
 */
data class SmsTransportVerdict(
    /** Null on success. A [SmsSendFailure] instance, so its [SmsSendFailure.code] is persistable. */
    val failure: SmsSendFailure?,
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

    /** The stable, persistable code (`null` on success). */
    val failureCode: String? get() = failure?.code
}

object SmsTransportClassifier {

    /**
     * Classify a SENT-callback result code, with the optional vendor `errorCode` the platform includes
     * for generic/RIL failures.
     *
     * The generic result is deliberately [SendEvidence.AMBIGUOUS]: it tells us nothing about whether the
     * SMSC took the message, so a retry there must be a user decision, never an automatic resend.
     */
    fun classify(resultCode: Int, radioErrorCode: Int? = null): SmsTransportVerdict {
        val name = SmsResultCodes.name(resultCode)
        fun verdict(
            failure: SmsSendFailure?,
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

            // ── throttling: the two codes that mean "slow down" ──────────────
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED ->
                verdict(SmsSendFailure.QueueLimitExceeded, SendEvidence.REJECTED, RetrySafety.WAIT, throttle = true)
            SmsManager.RESULT_RIL_REQUEST_RATE_LIMITED ->
                verdict(SmsSendFailure.RilRateLimited, SendEvidence.REJECTED, RetrySafety.WAIT, throttle = true)

            // ── the radio explicitly asks for a retry ───────────────────────
            SmsManager.RESULT_RIL_SMS_SEND_FAIL_RETRY ->
                verdict(SmsSendFailure.RilRetryRequired, SendEvidence.REJECTED, RetrySafety.WAIT)

            // ── definite pre-acceptance refusals (a retry cannot duplicate) ──
            SmsManager.RESULT_ERROR_NO_SERVICE ->
                verdict(SmsSendFailure.NoService, SendEvidence.REJECTED, RetrySafety.SAFE)
            SmsManager.RESULT_ERROR_RADIO_OFF ->
                verdict(SmsSendFailure.RadioOff, SendEvidence.REJECTED, RetrySafety.SAFE)
            SmsManager.RESULT_ERROR_NULL_PDU ->
                verdict(SmsSendFailure.NullPdu, SendEvidence.REJECTED, RetrySafety.SAFE)
            SmsManager.RESULT_RIL_RADIO_NOT_AVAILABLE ->
                verdict(SmsSendFailure.RadioUnavailable, SendEvidence.REJECTED, RetrySafety.SAFE)
            SmsManager.RESULT_RIL_SIM_ABSENT ->
                verdict(SmsSendFailure.SimUnavailable, SendEvidence.REJECTED, RetrySafety.SAFE)
            SmsManager.RESULT_RIL_INVALID_SMSC_ADDRESS ->
                verdict(SmsSendFailure.InvalidSmsc, SendEvidence.REJECTED, RetrySafety.SAFE)
            SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE ->
                verdict(SmsSendFailure.FdnRestricted, SendEvidence.REJECTED, RetrySafety.SAFE)
            SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED,
            SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED ->
                verdict(SmsSendFailure.ShortCodeNotAllowed, SendEvidence.REJECTED, RetrySafety.SAFE)

            // ── network / carrier level ─────────────────────────────────────
            SmsManager.RESULT_RIL_NETWORK_REJECT ->
                verdict(SmsSendFailure.NetworkRejected, SendEvidence.REJECTED, RetrySafety.WAIT)
            SmsManager.RESULT_RIL_NETWORK_NOT_READY ->
                verdict(SmsSendFailure.NetworkNotReady, SendEvidence.REJECTED, RetrySafety.WAIT)
            SmsManager.RESULT_RIL_NETWORK_ERR ->
                verdict(SmsSendFailure.NetworkError, SendEvidence.AMBIGUOUS, RetrySafety.POSSIBLE_DUPLICATE)
            SmsManager.RESULT_RIL_ACCESS_BARRED ->
                verdict(SmsSendFailure.AccessBarred, SendEvidence.REJECTED, RetrySafety.SAFE)

            // ── modem / system ──────────────────────────────────────────────
            SmsManager.RESULT_RIL_MODEM_ERR ->
                verdict(SmsSendFailure.ModemFailure(resultCode, radioErrorCode), SendEvidence.AMBIGUOUS, RetrySafety.POSSIBLE_DUPLICATE)
            SmsManager.RESULT_RIL_INVALID_MODEM_STATE, SmsManager.RESULT_RIL_INVALID_STATE ->
                verdict(SmsSendFailure.ModemInvalidState, SendEvidence.AMBIGUOUS, RetrySafety.WAIT)
            SmsManager.RESULT_RIL_SYSTEM_ERR, SmsManager.RESULT_RIL_INTERNAL_ERR ->
                verdict(SmsSendFailure.SystemError, SendEvidence.AMBIGUOUS, RetrySafety.POSSIBLE_DUPLICATE)
            SmsManager.RESULT_RIL_NO_MEMORY, SmsManager.RESULT_RIL_NO_RESOURCES ->
                verdict(SmsSendFailure.NoResources, SendEvidence.REJECTED, RetrySafety.WAIT)
            SmsManager.RESULT_RIL_CANCELLED,
            SmsManager.RESULT_RIL_OPERATION_NOT_ALLOWED,
            SmsManager.RESULT_RIL_REQUEST_NOT_SUPPORTED ->
                verdict(SmsSendFailure.OperationNotAllowed, SendEvidence.REJECTED, RetrySafety.SAFE)
            SmsManager.RESULT_RIL_BLOCKED_DUE_TO_CALL,
            SmsManager.RESULT_RIL_SIMULTANEOUS_SMS_AND_CALL_NOT_ALLOWED ->
                verdict(SmsSendFailure.BlockedDueToCall, SendEvidence.REJECTED, RetrySafety.WAIT)

            // ── malformed content ──────────────────────────────────────────
            SmsManager.RESULT_RIL_INVALID_SMS_FORMAT ->
                verdict(SmsSendFailure.InvalidSmsFormat, SendEvidence.REJECTED, RetrySafety.SAFE)
            SmsManager.RESULT_RIL_ENCODING_ERR ->
                verdict(SmsSendFailure.EncodingError, SendEvidence.REJECTED, RetrySafety.SAFE)
            SmsManager.RESULT_RIL_INVALID_ARGUMENTS ->
                verdict(SmsSendFailure.InvalidDestination, SendEvidence.REJECTED, RetrySafety.SAFE)

            // ── generic: we genuinely do not know ──────────────────────────
            //
            // NOT a definite failure. `GENERIC_FAILURE` is what a carrier refusing for balance looks
            // like from Android, and it is also what a vendor-specific modem error looks like. The
            // honest answer is "unknown", and a retry must warn about duplicates.
            //
            // It keeps the PRE-EXISTING `ModemFailure(resultCode, errorCode)` shape (rather than the
            // newer CarrierFailureUnknown) so the raw numbers stay on the persisted reason exactly as
            // the segment ledger already stored them; only genuinely unrecognised vendor codes fall
            // through to the newer code below.
            SmsManager.RESULT_ERROR_GENERIC_FAILURE ->
                verdict(
                    SmsSendFailure.ModemFailure(resultCode, radioErrorCode),
                    SendEvidence.AMBIGUOUS,
                    RetrySafety.POSSIBLE_DUPLICATE
                )

            else ->
                verdict(SmsSendFailure.CarrierFailureUnknown, SendEvidence.AMBIGUOUS, RetrySafety.POSSIBLE_DUPLICATE)
        }
    }
}
