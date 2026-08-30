package com.autonomousone.messages.sms

import android.provider.Telephony

/**
 * v2.6.13: pure status-transition policy for SMS modem callbacks.
 *
 * Separated from [SmsStatusReceiver] so the delivery-report semantics are
 * unit-testable without Android. The contract (matches Google Messages):
 *
 *  1. A SENT-phase failure is AUTHORITATIVE — the modem refused the submit,
 *     the message never left the device → [Telephony.Sms.STATUS_FAILED],
 *     sticky forever.
 *
 *  2. All SENT parts OK → the message IS sent. Its floor is
 *     [Telephony.Sms.STATUS_NONE] (single tick).
 *
 *  3. DELIVERED callbacks can only UPGRADE the status (SENT → Delivered).
 *     A delivery-report failure on one part of a multipart message is a
 *     carrier-side reporting gap — the submit was already accepted and sent.
 *     It must NEVER downgrade the message to FAILED. (This was the field
 *     bug: a multi-part Persian SMS on a delivery-reports-enabled SIM got
 *     the red failed mark although the recipient received it.)
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
        // ── 1. authoritative send failure ────────────────────────────────
        phase == Phase.SENT && !partOk -> Telephony.Sms.STATUS_FAILED
        sentFailSticky -> Telephony.Sms.STATUS_FAILED

        // ── 2. still sending: not every SENT part confirmed yet ─────────
        sentPartsDone < partCount -> Telephony.Sms.STATUS_PENDING

        // ── 3. fully SENT: delivery can only upgrade ─────────────────────
        // (delivered-phase failures land here: floor stays SENT)
        dlvPartsDone < partCount -> Telephony.Sms.STATUS_NONE

        // ── 4. everything delivered ──────────────────────────────────────
        else -> Telephony.Sms.STATUS_COMPLETE
    }

    /** True when a DELIVERED-part failure was recorded (diagnostics only). */
    fun isDeliveryGap(partOk: Boolean, phase: Phase): Boolean =
        phase == Phase.DELIVERED && !partOk
}
