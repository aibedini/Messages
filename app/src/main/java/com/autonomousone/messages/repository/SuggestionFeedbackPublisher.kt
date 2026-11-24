package com.autonomousone.messages.repository

import android.content.Context
import com.autonomousone.messages.data.GatewayEventFactory
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.security.ConversationKeyRepository
import com.autonomousone.messages.sync.SuggestionFeedback
import com.autonomousone.messages.utils.DiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Delivers `SUGGESTION_FEEDBACK` through the EXISTING encrypted outbox (XR-GA-017).
 *
 * ## Why not a new transport
 *
 * Device→server traffic already has exactly one durable path: build an event row, encrypt it with the
 * account keyring, insert it into `gateway_event_outbox`, and let [com.autonomousone.messages.gateway
 * .EventUploader] own delivery, retry, dead-lettering and the upload policy gate. A suggestion feedback
 * is just another content-bearing event (its `editedText` IS user text), so it reuses that path: the
 * same encryption, the same allowlist ([com.autonomousone.messages.security.EventCryptoPolicy]), the
 * same backoff. Anything else would be a second delivery mechanism with none of that scrutiny — and
 * "no new network endpoints" is a hard constraint of the feature.
 *
 * ## Why fire-and-forget with an app-scoped coroutine
 *
 * The tap that produces the feedback is a UI event; the durable write is a Room insert. Tying the write
 * to the composition (`rememberCoroutineScope`) would cancel it the instant the user navigates away —
 * exactly when they are most likely to have tapped. A process-scoped job means the row is committed
 * even if the screen is finished, and a failed write is reported to diagnostics instead of crashing a
 * conversation. Feedback is best-effort by design: it must never block or fail a user's send.
 */
class SuggestionFeedbackPublisher private constructor(context: Context) {

    companion object {
        @Volatile
        private var instance: SuggestionFeedbackPublisher? = null

        fun get(context: Context): SuggestionFeedbackPublisher =
            instance ?: synchronized(this) {
                instance ?: SuggestionFeedbackPublisher(context.applicationContext).also {
                    instance = it
                }
            }
    }

    private val appContext = context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Enqueue one feedback event. Returns immediately; the durable write happens on the IO scope. */
    fun publish(feedback: SuggestionFeedback) {
        scope.launch {
            runCatching { write(feedback) }
                .onFailure { DiagnosticLog.event("GM_SUGGEST", "feedback failed action=${feedback.action.wire}") }
        }
    }

    /**
     * The durable write, exposed for tests and for a caller that wants to await the result.
     *
     * @return true only when the encrypted row is actually in `gateway_event_outbox`.
     */
    suspend fun write(feedback: SuggestionFeedback): Boolean = withContext(Dispatchers.IO) {
        val db = MessagesDatabase.get(appContext)
        // The conversation UUID is the only identity GMweb has (TechSpec §12); a thread without one
        // gets it minted here exactly as sync does, so feedback for a conversation the web already
        // knows about carries the SAME id rather than a fresh one.
        val conversationId = GatewaySyncRepository(db).ensureConversationIdForThread(feedback.threadId)

        // A hidden conversation is hidden for every web-bound event, feedback included: the user
        // asked that neither the conversation nor its contact appear on the web at all.
        val prefs = com.autonomousone.messages.messaging.MessagingPreferences(appContext)
        val conversation = db.conversationDao().byThread(feedback.threadId)
        if (prefs.isHiddenFromWebConversation(conversationId) ||
            (conversation != null && prefs.isHiddenFromWebAddress(conversation.rawAddress))
        ) {
            DiagnosticLog.event("GM_SUGGEST", "feedback suppressed: hidden from web")
            return@withContext false
        }

        val event = GatewayEventFactory.suggestionFeedback(
            conversationId = conversationId,
            payloadJson = feedback.payloadJson(),
            atMs = feedback.atMs
        )
        // Content-bearing: encrypted with the account keyring, never stored or uploaded as plaintext.
        val encrypted = ConversationKeyRepository(db).encrypt(event, category = "")
        val inserted = db.gatewayEventOutboxDao().insertOrIgnore(encrypted) != -1L
        DiagnosticLog.event(
            "GM_SUGGEST",
            "feedback action=${feedback.action.wire} queued=$inserted thread=${feedback.threadId}"
        )
        inserted
    }
}
