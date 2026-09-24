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

    /** SMSC address rejected by the modem. */
    data object InvalidSmsc : SmsSendFailure { override val code = "INVALID_SMSC" }

    /** Destination rejected (malformed number / short-code policy). */
    data object InvalidDestination : SmsSendFailure { override val code = "INVALID_DESTINATION" }

    /** Fixed-dialling-number restriction blocked the send. */
    data object FdnRestricted : SmsSendFailure { override val code = "FDN_RESTRICTED" }

    /** Per-hour / per-window send limit enforced by telephony. */
    data object LimitExceeded : SmsSendFailure { override val code = "LIMIT_EXCEEDED" }

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
        when (resultCode) {
            RESULT_OK_CODE -> SentPartVerdict.CONFIRMED
            SmsManager.RESULT_ERROR_NO_SERVICE,
            SmsManager.RESULT_ERROR_RADIO_OFF,
            SmsManager.RESULT_ERROR_NULL_PDU -> SentPartVerdict.FAILED
            else -> SentPartVerdict.UNCONFIRMED
        }

    /**
     * Typed reason for a part, or null when the part is confirmed (or merely
     * ambiguous — ambiguity is a STATE, not a failure reason).
     */
    fun hardFailure(resultCode: Int, errorCode: Int? = null): SmsSendFailure? =
        when (resultCode) {
            RESULT_OK_CODE -> null
            SmsManager.RESULT_ERROR_NO_SERVICE -> SmsSendFailure.NoService
            SmsManager.RESULT_ERROR_RADIO_OFF -> SmsSendFailure.RadioOff
            SmsManager.RESULT_ERROR_NULL_PDU -> SmsSendFailure.NullPdu
            else -> null
        }

    /** Reason recorded for an ambiguous part, for diagnosis only. */
    fun ambiguousReason(resultCode: Int, errorCode: Int? = null): SmsSendFailure =
        SmsSendFailure.ModemFailure(resultCode, errorCode)
}
