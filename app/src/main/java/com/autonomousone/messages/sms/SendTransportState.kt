package com.autonomousone.messages.sms

import android.provider.Telephony
import com.autonomousone.messages.data.MessageEntity

/**
 * The APP-OWNED transport verdict of one outgoing message — the durable fact that survives process
 * death, reboot and app upgrade.
 *
 * ## Why this is not the provider's `status` column
 *
 * `Telephony.Sms.STATUS_*` is Telephony's own delivery-state field, and it is genuinely ambiguous for
 * our purposes: `STATUS_PENDING` (64) means BOTH "every part reported but ambiguous" and "no callback
 * has arrived yet". Those are opposite answers — one is finished and unknowable, the other is still in
 * flight — and a restart that collapses them tells the user the wrong thing. `SmsStatusPolicy` still
 * derives the provider status correctly at callback time; this enum records what the transport
 * actually told us, so the two can never be confused for one another again.
 *
 * Transport and delivery stay separate on purpose (mission §1/§4): this enum is the SUBMIT half, and
 * [SmsStatusEvidence.hasDeliveryConfirmation] is the DELIVERY half. Neither is inferred from the
 * other.
 *
 * ## Why it is a String in the entity
 *
 * Stored by [name] through the same converter discipline as [com.autonomousone.messages.data
 * .SegmentCallbackState], so a value written by a NEWER build cannot crash an older one: an
 * unrecognised name degrades to [UNKNOWN] rather than throwing.
 */
enum class SendTransportState {

    /**
     * The native call was accepted and no terminal verdict has been recorded yet.
     *
     * This is written BEFORE the physical submit, so a process killed mid-submit leaves SENDING
     * rather than a fabricated outcome. That is the honest answer: we do not know yet.
     */
    SENT_PENDING,

    /** Every required SENT part came back RESULT_OK. NOT delivery — see the delivery evidence. */
    SENT_CONFIRMED,

    /**
     * The transport outcome is ambiguous: a vendor/generic result or a callback that never arrived.
     *
     * The message MAY have left the phone, so this is never presented as a failure and never silently
     * resent. Before this state existed, an ambiguous outcome was indistinguishable from "still
     * sending" after a restart, and the user was told the send was in progress for ever.
     */
    SENT_AMBIGUOUS,

    /** A definite, actionable rejection: the message did not leave the device. */
    NOT_SENT,

    /** A row that has never been through this app's transport path (historical or inbound). */
    UNKNOWN;

    companion object {
        /** Parse a persisted name, degrading an unknown/newer value to [UNKNOWN] instead of throwing. */
        fun from(raw: String?): SendTransportState =
            entries.firstOrNull { it.name == raw } ?: UNKNOWN
    }
}

/**
 * The ONE derivation from app-owned transport state to the evidence the presentation mapper reads.
 *
 * This exists so there is exactly ONE place that decides what a persisted state MEANS, and the copy
 * rules stay in [SmsStatusPresentationMapper] where they already are. A second interpretation in a
 * Compose component is precisely how "could not confirm" became "Not sent" on one screen and "Sent"
 * on another.
 */
object SendStateDerivation {
    /**
     * Evidence for one outgoing message.
     *
     * The mapping is deliberately total and lossless:
     *
     * ```text
     * SENT_CONFIRMED  -> hasSentConfirmation (DELIVERY evidence decides Sent vs Delivery unknown)
     * SENT_AMBIGUOUS  -> failureCode = the recorded cause, which classifies as AMBIGUOUS
     *                    => SEND_STATUS_UNKNOWN, never "Not sent"
     * NOT_SENT        -> failureCode = the recorded definite cause => NOT_SENT
     * SENT_PENDING    -> no evidence yet => SENDING
     * UNKNOWN         -> no app-owned state => provider status is the only fallback
     * ```
     *
     * An ambiguous state with NO recorded code still reports ambiguity: the code is for the cause
     * label, and its absence must not upgrade a "maybe sent" into a "sent".
     */
    fun evidenceFor(
        message: MessageEntity,
        isOutgoing: Boolean,
        hasDeliveryConfirmation: Boolean = false,
        hasDeliveryFailure: Boolean = false,
        /**
         * True only when there is POSITIVE evidence that a delivery report was expected and did not
         * arrive conclusively.
         *
         * Deliberately NOT "no delivery confirmation yet": `SmsStatusPresentationMapper` treats this
         * as a claim about the carrier, and defaulting it to true would report `DELIVERY_UNKNOWN` for
         * every ordinary message — turning "we have not checked" into "the carrier never told us".
         * Absence of evidence is not evidence of absence, so the default is false and the caller must
         * assert it.
         */
        deliveryUnknown: Boolean = false
    ): SmsStatusEvidence {
        val transport = SendTransportState.from(message.sendTransportState)
        val code = message.sendFailureCode?.takeIf { it.isNotBlank() }
        return SmsStatusEvidence(
            isOutgoing = isOutgoing,
            telephonyStatus = message.status,
            failureCode = when (transport) {
                // An ambiguous send with no recorded cause still needs a code that classifies as
                // ambiguous, or the mapper would fall through to the provider status.
                SendTransportState.SENT_AMBIGUOUS -> code ?: SmsSendFailure.SendCallbackTimeout.code
                // A definite refusal with no recorded cause is still a refusal.
                SendTransportState.NOT_SENT -> code ?: SmsSendFailure.CarrierRejected.code
                SendTransportState.SENT_CONFIRMED -> null
                SendTransportState.SENT_PENDING -> null
                SendTransportState.UNKNOWN -> code
            },
            hasSentConfirmation = transport == SendTransportState.SENT_CONFIRMED,
            hasDeliveryConfirmation = hasDeliveryConfirmation,
            hasDeliveryFailure = hasDeliveryFailure,
            deliveryUnknown = deliveryUnknown
        )
    }
}

/**
 * Collapses the durable per-part ledger into ONE app-owned transport verdict.
 *
 * ## Why this is a pure function and not a `when` inside the receiver
 *
 * The receiver already derives the PROVIDER status (for delivery) and would have needed a second,
 * subtly different derivation for the app-owned verdict. Two derivations of the same evidence is how
 * the bubble and the list end up disagreeing about one message, so the transport verdict is computed
 * here, once, from the same `send_segments` rows — and is unit-testable without Android or a radio.
 *
 * ## Why the submission fact is an input
 *
 * [SendTransportState.SENT_PENDING] and [SendTransportState.NOT_SENT] are NOT distinguishable from
 * per-part callback states alone: "the radio took it and no callback has arrived" and "the submit was
 * refused and the refusal is recorded" can both present as a single PENDING part. The immutable
 * `submittedAt` fact is what separates them — it is written exactly once, by the native path, and only
 * when the call was accepted.
 *
 * @param callbackStates per-part modem verdicts, ordered by part index.
 * @param submittedPartsCount how many parts have a non-null `submittedAt` (the immutable fact).
 * @param partCount the logical message's part count.
 * @param failureCode the recorded failure code for the most severe part, if any.
 */
fun resolveSendTransportState(
    callbackStates: List<SegmentCallbackVerdict>,
    submittedPartsCount: Int,
    partCount: Int,
    failureCode: String? = null
): SendTransportState {
    val total = partCount.coerceAtLeast(1)
    val confirmed = callbackStates.count { it == SegmentCallbackVerdict.CONFIRMED }
    val ambiguous = callbackStates.count { it == SegmentCallbackVerdict.AMBIGUOUS }
    val failed = callbackStates.count { it == SegmentCallbackVerdict.FAILED }
    val reported = confirmed + ambiguous + failed

    return when {
        // A definite refusal is terminal and outranks everything: the message did not leave.
        failed > 0 -> SendTransportState.NOT_SENT

        // Ambiguity is terminal KNOWLEDGE ("the radio did not tell us") and outranks "still waiting",
        // because more waiting cannot turn an already-ambiguous part into a definite answer. This is
        // the ordering that makes an ambiguous outcome survive a restart instead of decaying back to
        // "Sending…" and inviting a duplicate send.
        ambiguous > 0 -> SendTransportState.SENT_AMBIGUOUS

        // Every part positively confirmed.
        confirmed >= total -> SendTransportState.SENT_CONFIRMED

        // Some parts confirmed and the rest still silent: the message is on its way, but "Sent" is not
        // yet true for the whole body.
        reported > 0 || submittedPartsCount > 0 -> SendTransportState.SENT_PENDING

        // A ledger ROW exists but carries no verdict and no submission fact. This is the callback-first
        // race: a callback reached the ledger before the immutable `submittedAt` write committed. A
        // callback only exists because a submit happened, so this is emphatically NOT a refusal — and
        // reading it as one would show "Not sent" for a message that is on its way.
        callbackStates.isNotEmpty() -> SendTransportState.SENT_PENDING

        // No ledger row at all: nothing was submitted and nothing was reported, so the send never
        // reached the radio. Note the distinction from the branch above — that one has evidence of a
        // submit, this one has none.
        else -> SendTransportState.NOT_SENT
    }
}

/**
 * The ledger's per-part verdict, restated here.
 *
 * This is a deliberate MAPPING BOUNDARY rather than a second source of truth: `send_segments` is the
 * data layer's table and the verdicts are its vocabulary, while the derivation above is transport
 * policy and lives with the rest of the send-state rules. Restating the four values keeps the policy
 * free of a data-layer dependency (the mapper and the receiver both sit above the ledger).
 *
 * The cost of a restatement is drift, so it is closed by a test that asserts this enum and
 * [com.autonomousone.messages.data.SegmentCallbackState] have identical names — a value added to one
 * and forgotten in the other fails there rather than silently becoming a PENDING verdict.
 */
enum class SegmentCallbackVerdict { PENDING, CONFIRMED, AMBIGUOUS, FAILED }
