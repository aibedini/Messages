package com.autonomousone.messages.sms

import android.provider.Telephony

/**
 * Pure status-transition policy for SMS modem callbacks.
 *
 * Separated from [SmsStatusReceiver] so the transport semantics are
 * unit-testable without Android. The contract is evidence-based and monotonic:
 *
 *  1. A SENT callback resolves its part into a [SentPartVerdict]:
 *     CONFIRMED (RESULT_OK), UNCONFIRMED (ambiguous/vendor result) or FAILED
 *     (a definite, actionable refusal). The raw modem result is diagnostic data
 *     and never poisons provider status by itself.
 *
 *  2. All SENT callbacks received → the message is Sent/unknown, UNLESS at least
 *     one part is FAILED (→ Failed) or only UNCONFIRMED (→ Pending, i.e. NOT a
 *     success tick). This is what stops a dead SIM / no-credit submit from
 *     rendering as a normal single tick.
 *
 *  3. DELIVERED callbacks are evidence and can only UPGRADE status —
 *     a successful report for every part produces Delivered, and it outranks
 *     older failure evidence. Only a parsed permanent TP-Status can produce
 *     Failed; a missing/malformed callback remains unknown and never downgrades.
 *
 *  4. All DELIVERED parts OK → [Telephony.Sms.STATUS_COMPLETE].
 */
object SmsStatusPolicy {

    enum class Phase { SENT, DELIVERED }

    /**
     * The evidence classes the persistence and projection layers act on.
     *
     * [NOT_CONFIRMED] was added because the old four values could not express the truth about
     * TP-Status `0x01`/`0x02`. The network accepted the message and then declined to confirm delivery;
     * calling that DELIVERED is a false success, and calling it FAILED tells the user something the
     * network never said. A distinct class is the only honest option, and it is genuinely necessary
     * rather than convenient.
     */
    enum class DeliveryEvidence { DELIVERED, NOT_CONFIRMED, TEMPORARY, FAILED, UNKNOWN }

    /**
     * Maps the TP-Status from a 3GPP SMS-STATUS-REPORT (TS 23.040 §9.2.3.15).
     *
     * Delegates to [SmsTpStatus], the single decoder, so this and every other surface agree. The
     * previous implementation treated all of `0x00..0x1F` as delivered, which reported confirmed
     * delivery for `0x01` ("forwarded, delivery unconfirmed") and `0x02` ("replaced by the SC").
     */
    fun classify3gppTpStatus(status: Int): DeliveryEvidence = when (SmsTpStatus.decode(status).evidence) {
        SmsTpStatus.Evidence.DELIVERED -> DeliveryEvidence.DELIVERED
        SmsTpStatus.Evidence.NOT_CONFIRMED -> DeliveryEvidence.NOT_CONFIRMED
        SmsTpStatus.Evidence.TEMPORARY -> DeliveryEvidence.TEMPORARY
        SmsTpStatus.Evidence.FAILED -> DeliveryEvidence.FAILED
        SmsTpStatus.Evidence.UNKNOWN -> DeliveryEvidence.UNKNOWN
    }

    /** Android documents 2 << 16 as the CDMA "received" status. */
    fun classify3gpp2Status(status: Int): DeliveryEvidence =
        if (status == (2 shl 16)) DeliveryEvidence.DELIVERED
        else DeliveryEvidence.UNKNOWN

    /**
     * Provider status for the message, derived from per-part evidence.
     *
     * @param sentConfirmedParts  parts whose SENT callback returned RESULT_OK
     * @param sentUnconfirmedParts parts whose SENT callback was ambiguous
     * @param sentFailedParts     parts whose SENT callback was a definite refusal
     * @param dlvPartsDone        distinct DELIVERED parts confirmed OK so far
     * @param partCount           total parts of the message
     */
    fun nextStatus(
        sentConfirmedParts: Int,
        sentUnconfirmedParts: Int,
        sentFailedParts: Int,
        dlvPartsDone: Int,
        dlvPartsPending: Int,
        dlvPartsFailed: Int,
        partCount: Int,
        /**
         * Parts the network explicitly reported as NOT CONFIRMED (`0x01`/`0x02`).
         *
         * Defaults to 0 so callers that predate this evidence keep compiling, but a caller that HAS this
         * evidence must pass it: without it those parts look unreported and the message stays PENDING,
         * waiting for a report the carrier has already sent its last word on.
         */
        dlvPartsNotConfirmed: Int = 0
    ): Int {
        val sentSeen = sentConfirmedParts + sentUnconfirmedParts + sentFailedParts
        return when {
            // ── network delivery evidence is authoritative and monotonic ──
            dlvPartsDone > 0 && dlvPartsDone >= partCount -> Telephony.Sms.STATUS_COMPLETE

            // A parsed TP-Status is authoritative network evidence. For a logical
            // multipart SMS, one permanently failed part means the whole body was
            // not delivered intact.
            dlvPartsFailed > 0 -> Telephony.Sms.STATUS_FAILED

            // ── a DEFINITE per-part refusal is an actionable send failure ──
            // No service, radio off, null PDU: the message did not leave the
            // device and the user must see it (Part 25 regression).
            sentFailedParts > 0 -> Telephony.Sms.STATUS_FAILED

            dlvPartsPending > 0 -> Telephony.Sms.STATUS_PENDING

            // ── the network's final word is "not confirmed" ──
            // A terminal answer, so the message is no longer waiting on anything. It is deliberately NOT
            // STATUS_FAILED (the network never said the message failed) and NOT STATUS_COMPLETE (nothing
            // confirmed it arrived). STATUS_NONE is the provider's own "no positive delivery report",
            // which is exactly what this is.
            dlvPartsDone > 0 || dlvPartsNotConfirmed > 0 -> Telephony.Sms.STATUS_NONE

            // ── still collecting multipart SENT callbacks ──
            sentSeen < partCount -> Telephony.Sms.STATUS_PENDING

            // ── every part reported, but at least one is only ambiguous:
            //    never a success tick ──
            sentUnconfirmedParts > 0 -> Telephony.Sms.STATUS_PENDING

            // ── fully sent and confirmed ──
            else -> Telephony.Sms.STATUS_NONE
        }
    }
}
