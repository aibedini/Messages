package com.autonomousone.messages.sms

import android.provider.Telephony

/**
 * v2.6.14: pure status-transition policy for SMS modem callbacks.
 *
 * Separated from [SmsStatusReceiver] so the delivery-report semantics are
 * unit-testable without Android. The contract (matches Google Messages / AOSP):
 *
 *  1. A SENT-phase failure is PROVISIONAL — the radio often reports
 *     GENERIC_FAILURE on messages the SMSC actually accepted (observed with
 *     UCS-2 Persian SMS on IR-MCI). It shows FAILED (with the Resend affordance)
 *     until proven otherwise.
 *
 *  2. All SENT parts OK → the message IS sent. Its floor is
 *     [Telephony.Sms.STATUS_NONE] (single tick).
 *
 *  3. DELIVERED callbacks are evidence and can only UPGRADE status —
 *     a successful delivery report for ANY part REFUTES an earlier SENT
 *     failure (the network reached the handset; the earlier radio error was
 *     a lie) and lifts the row at least to SENT, to Delivered once every
 *     part has reported. A delivery-report failure on one part of a
 *     multipart message is a carrier reporting gap and never downgrades
 *     a fully-sent message to FAILED.
 *
 *  4. All DELIVERED parts OK → [Telephony.Sms.STATUS_COMPLETE].
 */
object SmsStatusPolicy {

    enum class Phase { SENT, DELIVERED }

    /**
     * @param phase          which callback is being processed now
     * @param partOk         resultCode == RESULT_OK for THIS callback
     * @param sentFailSticky a SENT part failed earlier for this row
     * @param dlvFailSticky  a DELIVERED part failed earlier for this row
     *                       (diagnostics only; never authoritative)
     * @param sentPartsDone  distinct SENT parts confirmed OK so far (incl. now)
     * @param dlvPartsDone   distinct DELIVERED parts confirmed OK so far (incl. now)
     * @param partCount      total parts of the message
     */
    fun nextStatus(
        phase: Phase,
        partOk: Boolean,
        sentFailSticky: Boolean,
        dlvFailSticky: Boolean,
        sentPartsDone: Int,
        dlvPartsDone: Int,
        partCount: Int
    ): Int = when {
        // ── 3a. delivery evidence: the message reached the handset. A
        //      successful DELIVERED report outranks every SENT error. ──
        dlvPartsDone > 0 && dlvPartsDone >= partCount -> Telephony.Sms.STATUS_COMPLETE
        dlvPartsDone > 0 -> Telephony.Sms.STATUS_NONE  // refutes FAILED; floor SENT

        // ── 1. no delivery evidence: the SENT verdict stands (provisionally) ──
        phase == Phase.SENT && !partOk -> Telephony.Sms.STATUS_FAILED
        sentFailSticky -> Telephony.Sms.STATUS_FAILED

        // ── 2. still sending: not every SENT part confirmed yet ─────────
        sentPartsDone < partCount -> Telephony.Sms.STATUS_PENDING

        // ── fully sent; partial/gapped delivery reports keep the single tick ──
        else -> Telephony.Sms.STATUS_NONE
    }

    /** True when a DELIVERED-part failure was recorded (diagnostics only). */
    fun isDeliveryGap(partOk: Boolean, phase: Phase): Boolean =
        phase == Phase.DELIVERED && !partOk
}
