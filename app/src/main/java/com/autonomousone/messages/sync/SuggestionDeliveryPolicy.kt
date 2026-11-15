package com.autonomousone.messages.sync

/**
 * When a delivered `SUGGESTIONS_READY` payload may become a visible card (XR-GA-017).
 *
 * Pure on purpose. Three rules decide the whole feature, and each has a failure mode that is silent
 * if it lives inside the poller's executor:
 *
 * ```text
 * expired            the AI answered about a conversation that has moved on
 * stale generation   the payload was built from an older view of the thread
 * superseded anchor  a NEWER inbound message has arrived since the payload was built
 * ```
 *
 * The anchor rule is the authoritative one. `conversation_generation` is a cheap upstream filter and
 * the definitions on the two sides of the wire need not agree numerically; "is [forMessageRef] still
 * the newest inbound message on this device?" is a fact this device can prove from its own mirror.
 * Both are checked, in that order, so a payload that is wrong for either reason is refused rather
 * than rendered.
 *
 * The vocabulary is shared with the executor through [Outcome] so a test can assert the DECISION and
 * the poller cannot reinterpret it.
 */
object SuggestionDeliveryPolicy {

    /**
     * How many of the newest inbound messages an anchor may name.
     *
     * A suggestion is only useful for the message it answers, and the only message that can still be
     * the "last inbound" is within a handful of the newest rows. 50 covers a burst of messages
     * arriving while the command was in flight, without ever walking a long thread.
     */
    const val ANCHOR_WINDOW = 50

    /**
     * One inbound message the sender might have meant by `for_message_ref`.
     *
     * [canonicalId] is the stable identity Android publishes (`messageIdFor`); [aliases] are the
     * human-readable composite forms (`sms:123`) accepted so an integration that echoes a provider
     * identity is not rejected over representation. Both identify the SAME row.
     */
    data class Anchor(
        val canonicalId: String,
        val aliases: List<String>
    )

    sealed interface Outcome {
        /** The payload is fresh: persist it against [anchorMessageId] (the canonical identity). */
        data class Accept(val anchorMessageId: String) : Outcome

        /** Refuse, with the stable code the ledger and GMweb will see. */
        data class Reject(val code: SyncErrorCode, val detail: String) : Outcome
    }

    /**
     * Decide whether a validated payload may be stored.
     *
     * @param anchors the thread's newest inbound messages, NEWEST FIRST.
     */
    fun decide(
        commandGeneration: Int,
        currentGeneration: Int,
        expiresAtMs: Long,
        nowMs: Long,
        forMessageRef: String,
        anchors: List<Anchor>
    ): Outcome {
        // 1. Expiry is absolute: a card that cannot be shown must not be stored, or the next open of
        //    the conversation would have to re-prove the same fact before every render.
        if (expiresAtMs <= nowMs) {
            return Outcome.Reject(SyncErrorCode.SUGGESTIONS_EXPIRED, "expired")
        }

        // 2. Generation is one-directional by design: only a STRICTLY older generation is refused.
        //    A device whose mirror is behind must never reject a fresh suggestion, and equality is the
        //    normal case (the command answers the newest message the phone itself published).
        if (commandGeneration < currentGeneration) {
            return Outcome.Reject(SyncErrorCode.SUGGESTIONS_STALE, "generation")
        }

        // 3. The anchor must be the newest inbound message. This is the rule the contract states
        //    directly, and it holds even when the two sides disagree about what a generation counts.
        val index = anchors.indexOfFirst { anchor ->
            anchor.canonicalId == forMessageRef || anchor.aliases.any { it == forMessageRef }
        }
        if (index < 0) {
            return Outcome.Reject(SyncErrorCode.SUGGESTIONS_UNKNOWN_MESSAGE, "anchor_unknown")
        }
        if (index != 0) {
            return Outcome.Reject(SyncErrorCode.SUGGESTIONS_STALE, "anchor_superseded")
        }
        return Outcome.Accept(anchors[0].canonicalId)
    }

    /**
     * True when a card anchored at [anchorMessageId] is still showable for the current thread view.
     *
     * The UI runs the same three rules against the latest composed window; it is the same function so
     * the screen and the executor can never disagree about what "stale" means.
     */
    fun isShowable(
        anchorMessageId: String,
        newestInboundMessageId: String?,
        expiresAtMs: Long,
        nowMs: Long
    ): Boolean = anchorMessageId.isNotBlank() &&
        anchorMessageId == newestInboundMessageId &&
        expiresAtMs > nowMs
}
