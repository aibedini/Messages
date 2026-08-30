package com.autonomousone.messages.sms

import android.provider.Telephony
import android.telephony.SmsManager

/**
 * Pure status-transition policy for SMS modem callbacks.
 *
 * Separated from [SmsStatusReceiver] so the delivery-report semantics are
 * unit-testable without Android. The contract (matches Google Messages / AOSP):
 *
 *  1. An authoritative SENT-phase failure is FAILED. The receiver classifies
 *     GENERIC_FAILURE as ambiguous on the affected carrier/RIL path because
 *     field evidence shows those UCS-2 messages can still be delivered.
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
     * RESULT_ERROR_GENERIC_FAILURE is not authoritative on the affected
     * carrier/RIL path: UCS-2 messages can be delivered despite this result.
     * Keep this exception phase-specific; a failed delivery report remains a
     * reporting gap and all concrete SENT error codes remain failures.
     */
    fun isAmbiguousSentFailure(phase: Phase, resultCode: Int): Boolean =
        phase == Phase.SENT && resultCode == SmsManager.RESULT_ERROR_GENERIC_FAILURE

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

        // ── 1. no delivery evidence: an authoritative SENT failure stands ──
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
