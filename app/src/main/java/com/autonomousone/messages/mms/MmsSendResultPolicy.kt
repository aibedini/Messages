package com.autonomousone.messages.mms

import android.app.Activity
import android.telephony.SmsManager

/**
 * What the platform's MMS stack reported for one `sendMultimediaMessage` (mission §70 spirit).
 *
 * **The defect this exists for.** `MmsSender.triggerSend` passed a **null** `PendingIntent` as the
 * send-result callback. The platform then had nowhere to deliver the outcome, so:
 *
 *  - a failed MMS stayed in `MESSAGE_BOX_OUTBOX` for ever, looking to the user like a message that is
 *    still being sent;
 *  - nothing in the app, and therefore nothing in the gateway, ever learned the difference between an
 *    MMS that left the device and one that never did.
 *
 * That is the "silently lost" shape the mission's first invariant forbids, on the one message type
 * where the payload is a photo or a voice note rather than text.
 *
 * Pure, and deliberately not `SmsSendFailure`: those codes are written to the per-SMS segment ledger
 * keyed by an SMS provider row id, and an MMS row id is a different key space
 * (`content://mms/<id>`). Reusing them would let an MMS outcome land on an SMS row that happens to
 * share the number — so MMS carries its own vocabulary and its own record, and never touches that
 * ledger.
 */
enum class MmsSendVerdict {
    /** `RESULT_OK`: the platform's MMS stack accepted the request. */
    SENT,

    /**
     * A condition that can clear on its own — no data network, a timeout, a retryable HTTP result.
     *
     * Worth telling the user about, because "try again when you have data" is an action.
     */
    RETRYABLE,

    /**
     * A configuration or subscription problem: the same attempt cannot succeed unchanged.
     *
     * Distinct from [RETRYABLE] because "wait and try again" is the wrong advice for a wrong APN.
     */
    PERMANENT,

    /**
     * An unrecognised result code.
     *
     * Never collapsed into [SENT]. A send whose outcome the app cannot interpret is an unknown
     * outcome, and reporting it as delivered would be the exact lie this whole path was fixed for.
     */
    UNKNOWN;

    val isFailure: Boolean get() = this == RETRYABLE || this == PERMANENT || this == UNKNOWN
}

/** A machine-readable outcome: a verdict plus a stable code string for logs and reports. */
data class MmsSendOutcome(val verdict: MmsSendVerdict, val code: String) {
    val isTransient: Boolean get() = verdict == MmsSendVerdict.RETRYABLE
}

object MmsSendResultPolicy {

    /**
     * Classify the code the platform delivered to the send-result `PendingIntent`.
     *
     * The switch is exhaustive over the SDK's `MMS_ERROR_*` constants. Anything else — including a
     * bare `0` that is not `RESULT_OK`, and any future code — is [MmsSendVerdict.UNKNOWN] rather than
     * a guess.
     */
    fun classify(resultCode: Int): MmsSendOutcome = when (resultCode) {
        Activity.RESULT_OK -> MmsSendOutcome(MmsSendVerdict.SENT, "MMS_SENT")

        // Waiting helps: the network is the problem, not the request.
        SmsManager.MMS_ERROR_NO_DATA_NETWORK ->
            MmsSendOutcome(MmsSendVerdict.RETRYABLE, "MMS_NO_DATA_NETWORK")
        SmsManager.MMS_ERROR_DATA_DISABLED ->
            MmsSendOutcome(MmsSendVerdict.RETRYABLE, "MMS_DATA_DISABLED")
        SmsManager.MMS_ERROR_IO_ERROR ->
            MmsSendOutcome(MmsSendVerdict.RETRYABLE, "MMS_IO_ERROR")
        SmsManager.MMS_ERROR_HTTP_FAILURE ->
            MmsSendOutcome(MmsSendVerdict.RETRYABLE, "MMS_HTTP_FAILURE")
        SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS ->
            MmsSendOutcome(MmsSendVerdict.RETRYABLE, "MMS_UNABLE_CONNECT")
        SmsManager.MMS_ERROR_RETRY ->
            MmsSendOutcome(MmsSendVerdict.RETRYABLE, "MMS_RETRY")

        // Waiting does not help: something about how the send is configured is wrong.
        SmsManager.MMS_ERROR_INVALID_APN ->
            MmsSendOutcome(MmsSendVerdict.PERMANENT, "MMS_INVALID_APN")
        SmsManager.MMS_ERROR_CONFIGURATION_ERROR ->
            MmsSendOutcome(MmsSendVerdict.PERMANENT, "MMS_CONFIGURATION_ERROR")
        SmsManager.MMS_ERROR_INVALID_SUBSCRIPTION_ID ->
            MmsSendOutcome(MmsSendVerdict.PERMANENT, "MMS_INVALID_SUBSCRIPTION_ID")
        SmsManager.MMS_ERROR_INACTIVE_SUBSCRIPTION ->
            MmsSendOutcome(MmsSendVerdict.PERMANENT, "MMS_INACTIVE_SUBSCRIPTION")
        SmsManager.MMS_ERROR_MMS_DISABLED_BY_CARRIER ->
            MmsSendOutcome(MmsSendVerdict.PERMANENT, "MMS_DISABLED_BY_CARRIER")
        SmsManager.MMS_ERROR_UNSPECIFIED ->
            MmsSendOutcome(MmsSendVerdict.UNKNOWN, "MMS_UNSPECIFIED")

        else -> MmsSendOutcome(MmsSendVerdict.UNKNOWN, "MMS_UNKNOWN_RESULT:$resultCode")
    }

    /**
     * The line a diagnostic shows, without any recipient or content.
     *
     * The MMS row id is a local provider row id, not a phone number, so it is safe to log and it is
     * what makes a support conversation about one stuck picture possible.
     */
    fun describe(mmsId: Long, outcome: MmsSendOutcome): String =
        "mms=$mmsId code=${outcome.code} verdict=${outcome.verdict.name}"
}
