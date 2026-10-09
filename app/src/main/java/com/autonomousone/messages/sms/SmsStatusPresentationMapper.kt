package com.autonomousone.messages.sms

/**
 * THE UI-facing send state of one outgoing message.
 *
 * Every surface that shows an outgoing SMS — the chat bubble, message details, the conversation list —
 * reads THIS, never a raw `Telephony.Sms.STATUS_*`, result code or `SmsSendFailure` on its own. Letting
 * each Compose component interpret the raw evidence independently is how "could not confirm" quietly
 * became "Not sent" in one place and "Sent" in another.
 *
 * Transport and delivery are deliberately separate states (mission §4/§34):
 *
 * ```text
 * SENDING             the logical send has not reached terminal SENT evidence
 * SENT                every required SENT part confirmed  (NOT delivered)
 * DELIVERED           positive delivery report evidence
 * NOT_SENT            a definite TRANSPORT rejection — the phone never got it out
 * NOT_DELIVERED       a definite NEGATIVE carrier report — the phone sent it, the network did not deliver
 * SEND_STATUS_UNKNOWN ambiguous transport outcome — the SMS may have left the phone
 * DELIVERY_UNKNOWN    send confirmed, delivery evidence never became conclusive
 * ```
 *
 * ## Why [NOT_SENT] and [NOT_DELIVERED] are two states and not one
 *
 * They are the same sentence to a reader and opposite facts to a user. "Not sent" means the handset
 * never handed the message to the network — the fix might be service, a bad number, or a rate limit.
 * "Not delivered" means the handset DID send it and the carrier reported it could not be delivered —
 * the fix is on the network or the recipient's side, and the user may have been billed. Collapsing them
 * into one state tells the user the wrong thing about what happened and what to do next, so they get
 * separate states, separate glyphs and separate copy.
 */
enum class SmsUiState {
    SENDING,
    SENT,
    DELIVERED,
    NOT_SENT,
    NOT_DELIVERED,
    SEND_STATUS_UNKNOWN,
    DELIVERY_UNKNOWN;

    /** Terminal states never change again without new evidence. */
    val isTerminal: Boolean get() = this != SENDING

    /**
     * True when Home needs to draw attention to this state. Success states stay quiet: a list where
     * every row shouts "Sent" is noise.
     */
    val needsAttention: Boolean
        get() = this == NOT_SENT || this == NOT_DELIVERED ||
            this == SEND_STATUS_UNKNOWN || this == SENDING

    companion object {
        /**
         * Parse a persisted name, degrading an unknown or NEWER value instead of throwing.
         *
         * v26 stored delivery failures as `NOT_SENT` because there was no separate state. Those rows
         * are NOT reinterpreted: a stored `NOT_SENT` stays a transport failure, which is what it meant
         * when it was written. Guessing that an old value "probably meant" delivery would retroactively
         * rewrite history the app never recorded.
         */
        fun from(raw: String?): SmsUiState? =
            entries.firstOrNull { it.name == raw }
    }
}

/**
 * What one message's durable/runtime evidence says, in the smallest shape the mapper needs.
 *
 * [failureCode] is a [SmsSendFailure.code] when known; [telephonyStatus] is the provider's own
 * `Telephony.Sms.STATUS_*` value, used only as fallback evidence for historical rows that predate the
 * app's own transport state.
 */
data class SmsStatusEvidence(
    val isOutgoing: Boolean,
    val telephonyStatus: Int = 0,
    val failureCode: String? = null,
    val hasSentConfirmation: Boolean = false,
    val hasDeliveryConfirmation: Boolean = false,
    val hasDeliveryFailure: Boolean = false,
    val deliveryUnknown: Boolean = false
)

/**
 * Everything a surface needs to render a state, with NO Compose types in it: the domain decides
 * meaning, the UI decides how it looks.
 */
data class SmsStatusPresentation(
    val state: SmsUiState,
    val label: String,
    val detail: String?,
    val canRetry: Boolean,
    val duplicateRisk: Boolean,
    /** Optional short cause, e.g. under "Not sent". Never a raw code. */
    val causeLabel: String? = null
)

/**
 * The ONE mapping from evidence to user-visible state.
 *
 * Copy rules that are load-bearing:
 *  - an ambiguous outcome is NEVER "Not sent" (the message may have left the phone);
 *  - a missing delivery report is NEVER a failure;
 *  - a balance problem is never asserted (Android has no portable result for it).
 */
object SmsStatusPresentationMapper {

    fun present(evidence: SmsStatusEvidence): SmsStatusPresentation {
        // Incoming messages have no send state to show.
        if (!evidence.isOutgoing) {
            return SmsStatusPresentation(
                state = SmsUiState.SENT,
                label = "",
                detail = null,
                canRetry = false,
                duplicateRisk = false
            )
        }

        // ── delivery evidence outranks transport, but only when it is evidence ──
        if (evidence.hasDeliveryFailure) {
            val copy = SmsFailurePresentation.deliveryFailedCopy(null)
            return SmsStatusPresentation(
                state = SmsUiState.NOT_DELIVERED,
                label = "Not delivered",
                detail = copy.detail,
                canRetry = true,
                duplicateRisk = false,
                causeLabel = "Carrier reports delivery failed"
            )
        }
        if (evidence.hasDeliveryConfirmation) {
            return SmsStatusPresentation(
                state = SmsUiState.DELIVERED,
                label = "Delivered",
                detail = null,
                canRetry = false,
                duplicateRisk = false
            )
        }

        // ── transport evidence ───────────────────────────────────────────────
        val failure = evidence.failureCode
        if (failure != null) {
            val evidenceKind = SmsTransportClassifier.evidenceForCode(failure)
            return when (evidenceKind) {
                SendEvidence.REJECTED -> {
                    val copy = SmsFailurePresentation.forCode(failure)
                    SmsStatusPresentation(
                        state = SmsUiState.NOT_SENT,
                        label = "Not sent",
                        detail = copy.detail,
                        canRetry = true,
                        duplicateRisk = copy.duplicateRisk,
                        causeLabel = copy.primary
                    )
                }
                else -> {
                    // AMBIGUOUS (generic/vendor/timeout): never presented as a definite failure, and
                    // the user is always warned that a retry may duplicate the message.
                    val copy = SmsFailurePresentation.forCode(failure)
                    SmsStatusPresentation(
                        state = SmsUiState.SEND_STATUS_UNKNOWN,
                        label = "Send status unknown",
                        detail = AMBIGUOUS_DETAIL,
                        canRetry = true,
                        duplicateRisk = true,
                        causeLabel = copy.primary
                    )
                }
            }
        }

        if (evidence.hasSentConfirmation) {
            return if (evidence.deliveryUnknown) {
                val copy = SmsFailurePresentation.forCode(SmsSendFailure.DeliveryUnknown.code)
                SmsStatusPresentation(
                    state = SmsUiState.DELIVERY_UNKNOWN,
                    label = "Delivery unknown",
                    detail = copy.detail,
                    canRetry = false,
                    duplicateRisk = false
                )
            } else {
                SmsStatusPresentation(
                    state = SmsUiState.SENT,
                    label = "Sent",
                    detail = null,
                    canRetry = false,
                    duplicateRisk = false
                )
            }
        }

        // ── historical rows with no app-owned transport state ────────────────
        //
        // The provider's own status is used only when it carries EVIDENCE. `STATUS_COMPLETE` is the
        // numeric 0, which is also the default of a freshly submitted row — so 0 is NOT read as
        // positive delivery, it is read as "no transport state recorded", which is honestly SENDING
        // rather than a success we cannot prove.
        return when (evidence.telephonyStatus) {
            // STATUS_FAILED with no app evidence: we cannot say whether it left the phone.
            128 -> SmsStatusPresentation(
                state = SmsUiState.SEND_STATUS_UNKNOWN,
                label = "Send status unknown",
                detail = AMBIGUOUS_DETAIL,
                canRetry = true,
                duplicateRisk = true
            )
            // STATUS_PENDING (64) / STATUS_NONE (32) and the ambiguous 0 default.
            else -> SmsStatusPresentation(SmsUiState.SENDING, "Sending…", null, false, false)
        }
    }

    /** What Home needs from a conversation's newest message. */
    fun conversationSummary(presentation: SmsStatusPresentation, isOutgoing: Boolean): String? =
        if (!isOutgoing) {
            null
        } else when (presentation.state) {
            SmsUiState.SENDING -> "Sending"
            SmsUiState.NOT_SENT -> presentation.causeLabel?.let { "Not sent · $it" } ?: "Not sent"
            SmsUiState.SEND_STATUS_UNKNOWN -> "Send status unknown"
            SmsUiState.DELIVERY_UNKNOWN -> "Delivery unknown"
            // Success stays quiet in the list.
            else -> null
        }

    /**
     * A compact leading marker for a conversation row, or null when the row should read normally.
     *
     * Separate from [conversationSummary] because the two answer different questions:
     *
     * ```text
     * conversationSummary -> the WORDS describing the state ("Not sent")
     * rowMarker           -> the GLYPH and words in a list row ("⚠ Not sent ·")
     * ```
     *
     * The glyph matters: a list where every row is text-only makes a failure indistinguishable from a
     * snippet at a glance. "Not sent" and "Not delivered" get DIFFERENT glyphs from "Sending", because
     * a failure the user must act on and a send in progress are not the same kind of information.
     *
     * A success state returns null, so a recovered conversation stops shouting on its own.
     */
    fun rowMarker(presentation: SmsStatusPresentation, isOutgoing: Boolean): String? {
        if (!isOutgoing) return null
        return when (presentation.state) {
            // A send in progress: a clock, not a warning. Nothing is wrong yet.
            SmsUiState.SENDING -> "◷ Sending"
            SmsUiState.NOT_SENT -> "⚠ Not sent"
            // Distinct from NOT_SENT on purpose: the carrier reported a delivery failure, which is a
            // different fact and a different remedy from the phone failing to send at all.
            SmsUiState.NOT_DELIVERED -> "⚠ Not delivered"
            SmsUiState.SEND_STATUS_UNKNOWN -> "⚠ Send status unknown"
            SmsUiState.DELIVERY_UNKNOWN -> "Delivery unknown"
            // Success stays quiet.
            else -> null
        }
    }

    const val AMBIGUOUS_DETAIL: String =
        "The mobile network did not confirm whether this message was sent. " +
            "Retrying may send it twice."
}
