package com.autonomousone.messages.mms

/**
 * What happened to an MMS send request.
 *
 * `MmsSender` used to return a bare `Boolean`, and **every** caller discarded it — including the
 * conversation screen, which added an optimistic bubble and emitted an "outgoing sent" event
 * unconditionally. So a refusal was invisible: the user saw the photo as sent, and the app had already
 * decided it was not.
 *
 * [Queued] is deliberately not called "sent": it means the request row exists and was handed to the
 * platform. Whether the message left the device is only known later, at
 * [MmsStatusReceiver] — and keeping those two facts apart is the same discipline the SMS path uses.
 */
sealed interface MmsSendResult {

    /** The request row was created and handed to the platform; the outcome arrives asynchronously. */
    data class Queued(val mmsId: Long) : MmsSendResult

    /**
     * The message provably will not be sent.
     *
     * [code] is stable and machine-readable (`mms_empty_payload`, `mms_payload_too_large`, ...) so a
     * log line, a REST response and a support conversation name the same thing; [reason] is the
     * sentence a person can act on. No recipient and no content appears in either.
     */
    data class Rejected(val code: String, val reason: String) : MmsSendResult

    val queued: Boolean get() = this is Queued
}
