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
    enum class DeliveryEvidence { DELIVERED, TEMPORARY, FAILED, UNKNOWN }

    /**
     * Maps the TP-Status from a 3GPP SMS-STATUS-REPORT (TS 23.040 §9.2.3.15).
     * The two high group bits define completed, temporary, permanent, and
     * temporary-but-no-longer-retrying states. Unknown/vendor values remain
     * UNKNOWN instead of inventing a delivery verdict.
     */
    fun classify3gppTpStatus(status: Int): DeliveryEvidence = when (status) {
        in 0x00..0x1f -> DeliveryEvidence.DELIVERED
        in 0x20..0x3f -> DeliveryEvidence.TEMPORARY
        in 0x40..0x7f -> DeliveryEvidence.FAILED
        else -> DeliveryEvidence.UNKNOWN
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
        partCount: Int
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

            // Partial positive evidence cannot claim whole-message delivery.
            dlvPartsDone > 0 -> Telephony.Sms.STATUS_NONE

            // ── still collecting multipart SENT callbacks ──
            sentSeen < partCount -> Telephony.Sms.STATUS_PENDING

            // ── every part reported, but at least one is only ambiguous:
            //    never a success tick ──
            sentUnconfirmedParts > 0 -> Telephony.Sms.STATUS_PENDING

            // ── fully sent and confirmed ──
            else -> Telephony.Sms.STATUS_NONE
        }
    }

    /**
     * The durable [SendState] for the same evidence. Kept next to [nextStatus]
     * so the provider status, the durable state machine and the UI overlay can
     * never disagree about what the device actually knows.
     */
    fun aggregateSendState(
        sentConfirmedParts: Int,
        sentUnconfirmedParts: Int,
        sentFailedParts: Int,
        dlvPartsDone: Int,
        partCount: Int,
        dispatched: Boolean
    ): SendState = when {
        partCount > 0 && dlvPartsDone >= partCount -> SendState.DELIVERED
        sentFailedParts > 0 -> SendState.FAILED
        sentUnconfirmedParts > 0 -> SendState.SEND_UNCONFIRMED
        partCount > 0 && sentConfirmedParts >= partCount -> SendState.SENT_CONFIRMED
        dispatched -> SendState.DISPATCHED
        else -> SendState.QUEUED
    }
}
