package com.autonomousone.messages.sync

import org.json.JSONObject

/**
 * What the user did with one AI suggestion (XR-GA-017), as the wire contract GMweb consumes.
 *
 * The four actions are the contract's (`select|edit|reject|report`), and only `edited` carries a body:
 * the user rewrote the draft before sending it. Keeping the payload this small is what makes the event
 * auditable without shipping a second copy of the conversation.
 *
 * `sentMessageRef` is intentionally nullable and left null by this build's UI. The provider assigns a
 * message id only AFTER a send completes, and reading it would mean reaching into the send pipeline
 * (`SmsSender` / `SendSimPolicy` / lane) that XR-GA-017 must not touch. The edited text and the anchor
 * message still let GMweb attribute the outcome, and a future sender-side hook can fill the reference
 * without changing this contract.
 */
data class SuggestionFeedback(
    val threadId: Long,
    /** The sender's own id from `suggestions[].id`. Never synthesized. */
    val suggestionId: String,
    val action: Action,
    /** Canonical message identity of the inbound message the suggestion answered. */
    val forMessageRef: String? = null,
    /** Present only for [Action.EDITED]: the draft as the user actually sent it. */
    val editedText: String? = null,
    /** Present when this build can prove which message the edit became. */
    val sentMessageRef: String? = null,
    /** The generation the card was delivered with; lets GMweb correlate late feedback. */
    val conversationGeneration: Int = 0,
    val atMs: Long = System.currentTimeMillis()
) {

    enum class Action(val wire: String) {
        /** Tapped into the composer, text untouched. */
        SELECTED("selected"),

        /** The user changed the draft before sending it. */
        EDITED("edited"),

        /** The cards were dismissed without being used. */
        REJECTED("rejected"),

        /** The user reported the suggestion as wrong ("گزارش پیشنهاد بد"). */
        REPORTED("reported")
    }

    /** The plaintext body, before the outbox encrypts it. Pure so the shape is unit-tested. */
    fun payloadJson(): String = JSONObject()
        .put("suggestionId", suggestionId)
        .put("action", action.wire)
        .put("atMs", atMs)
        .apply {
            if (!forMessageRef.isNullOrBlank()) put("forMessageRef", forMessageRef)
            if (!editedText.isNullOrBlank()) put("editedText", editedText)
            if (!sentMessageRef.isNullOrBlank()) put("sentMessageRef", sentMessageRef)
            if (conversationGeneration > 0) put("conversationGeneration", conversationGeneration)
        }
        .toString()
}
