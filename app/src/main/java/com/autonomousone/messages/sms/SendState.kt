package com.autonomousone.messages.sms

import android.app.Activity
import android.telephony.SmsManager

/**
 * Explicit, durable lifecycle of an OUTGOING message.
 *
 * The whole point of this type is that
 *
 *     SmsManager.sendTextMessage() returned without throwing
 *
 * means only [DISPATCHED] — Android telephony accepted the API call. It is NOT
 * carrier success, and it must never be rendered as one. Carrier success is
 * [SENT_CONFIRMED] (the radio reported RESULT_OK for every part) and delivery is
 * [DELIVERED] (a parsed positive SMS-STATUS-REPORT).
 *
 *   QUEUED -> DISPATCHING -> DISPATCHED
 *                              |- RESULT_OK .................. -> SENT_CONFIRMED
 *                              |- definite transport failure .. -> FAILED
 *                              '- ambiguous/vendor result ..... -> SEND_UNCONFIRMED
 *
 *   SENT_CONFIRMED  -+-> valid positive delivery report -> DELIVERED
 *   SEND_UNCONFIRMED-+
 *
 * [rank] encodes evidence strength so a stale or duplicate callback can never
 * downgrade a stronger verdict (a DELIVERED message is never pushed back to
 * "sent" or "unconfirmed" by a late report).
 */
enum class SendState(val rank: Int) {
    /** Persisted locally, no telephony call yet. */
    QUEUED(0),

    /** The send call is in flight on this device. */
    DISPATCHING(1),

    /** Telephony accepted the API call for every part. Nothing is confirmed. */
    DISPATCHED(2),

    /** Ambiguous modem/vendor verdict: neither confirmed nor provably failed. */
    SEND_UNCONFIRMED(3),

    /** RESULT_OK for every part: the radio accepted the submit. */
    SENT_CONFIRMED(4),

    /** A parsed positive delivery report for every part. */
    DELIVERED(5),

    /** A definite, actionable refusal (no service, radio off, invalid SIM/SMSC, ...). */
    FAILED(-1);

    val isTerminal: Boolean get() = this == DELIVERED || this == FAILED

    companion object {
        /**
         * Monotonic advance. FAILED is not a "stronger" state than DELIVERED —
         * real delivery evidence always wins — but nothing else may ever
         * downgrade a stronger verdict.
         */
        fun advance(current: SendState, next: SendState): SendState = when {
            next == DELIVERED -> DELIVERED
            current == DELIVERED -> DELIVERED
            current == FAILED -> FAILED
            next == FAILED -> FAILED
            next.rank >= current.rank -> next
            else -> current
        }

        fun fromName(raw: String?): SendState? =
            values().firstOrNull { it.name == raw }
    }
}

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
