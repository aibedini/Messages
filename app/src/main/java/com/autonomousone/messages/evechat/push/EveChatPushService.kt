package com.autonomousone.messages.evechat.push

import com.autonomousone.messages.evechat.EveChatLog
import com.autonomousone.messages.evechat.EveChatSession
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * FCM entry point. Runs on Firebase's worker thread; the handling budget is short, so the
 * targeted sync is bounded and anything left over is handed to WorkManager.
 */
class EveChatPushService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        EveChatSession.get(this).push.onNewToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val push = EveChatPush.parse(message.data)
        if (push == null) {
            EveChatLog.d("push", "ignored non-EveChat FCM message", "keys" to message.data.keys.size)
            return
        }
        val deprioritized = message.priority < message.originalPriority
        EveChatLog.i(
            "push", "received",
            "type" to push.type,
            "conv" to EveChatLog.id(push.conversationId),
            "latencyMs" to (System.currentTimeMillis() - message.sentTime).takeIf { message.sentTime > 0 },
            "deprioritized" to deprioritized
        )
        if (deprioritized) {
            EveChatLog.w("push", "FCM lowered message priority; app may be battery-restricted")
        }
        val session = EveChatSession.get(this)
        val handled = runBlocking { withTimeoutOrNull(HANDLE_BUDGET_MS) { session.handlePush(push) } }
        if (handled == null) {
            EveChatLog.w("push", "handling exceeded budget; deferring", null, "conv" to EveChatLog.id(push.conversationId))
            session.onPushTimedOut(push)
        }
    }

    /** FCM dropped messages it could not deliver in time; only a full sync can recover them. */
    override fun onDeletedMessages() {
        EveChatLog.w("push", "FCM reported deleted messages; requesting full sync")
        EveChatSession.get(this).requestBackgroundSync()
    }

    private companion object {
        const val HANDLE_BUDGET_MS = 8_000L
    }
}
