package com.autonomousone.messages.sms

import android.provider.Telephony

/**
 * Pure status-transition policy for SMS modem callbacks.
 *
 * Separated from [SmsStatusReceiver] so the delivery-report semantics are
 * unit-testable without Android. The contract is intentionally evidence-based:
 *
 *  1. A completed SENT callback resolves its part for UI aggregation. Its raw
 *     modem result remains diagnostic data and cannot poison provider status.
 *
 *  2. All SENT callbacks received → the message is Sent/unknown. Its floor is
 *     [Telephony.Sms.STATUS_NONE] (single tick).
 *
 *  3. DELIVERED callbacks are evidence and can only UPGRADE status —
 *     a successful report for every part produces Delivered. Only a parsed
 *     permanent TP-Status can produce Failed; a missing/malformed callback
 *     remains unknown and never downgrades Sent.
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
     * @param sentPartsDone  distinct SENT callbacks received so far (incl. now)
     * @param dlvPartsDone   distinct DELIVERED parts confirmed OK so far (incl. now)
     * @param partCount      total parts of the message
     */
    fun nextStatus(
        sentPartsDone: Int,
        dlvPartsDone: Int,
        dlvPartsPending: Int,
        dlvPartsFailed: Int,
        partCount: Int
    ): Int = when {
        // ── delivery evidence: every part reached the handset ──
        dlvPartsDone > 0 && dlvPartsDone >= partCount -> Telephony.Sms.STATUS_COMPLETE

        // A parsed TP-Status is authoritative network evidence. For a logical
        // multipart SMS, one permanently failed part means the whole body was
        // not delivered intact.
        dlvPartsFailed > 0 -> Telephony.Sms.STATUS_FAILED
        dlvPartsPending > 0 -> Telephony.Sms.STATUS_PENDING

        // Partial positive evidence cannot claim whole-message delivery.
        dlvPartsDone > 0 -> Telephony.Sms.STATUS_NONE

        // ── still collecting multipart SENT callbacks ──
        sentPartsDone < partCount -> Telephony.Sms.STATUS_PENDING

        // ── fully sent; partial/gapped delivery reports keep the single tick ──
        else -> Telephony.Sms.STATUS_NONE
    }

}
