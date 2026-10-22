package com.autonomousone.messages.evechat.network

import com.autonomousone.messages.evechat.model.ConversationPatch
import com.autonomousone.messages.evechat.model.EveChatConversation
import com.autonomousone.messages.evechat.model.EveChatMessage
import com.autonomousone.messages.evechat.model.EveChatTime
import com.autonomousone.messages.evechat.model.optStringOrNull
import org.json.JSONObject
import kotlin.random.Random

sealed interface SocketEvent {
    /** The socket is open and authenticated; anything missed while closed must be fetched. */
    data object Connected : SocketEvent
    data object Ping : SocketEvent
    data class Hello(val serverNowMs: Long?) : SocketEvent
    data class ConversationCreated(val conversation: EveChatConversation, val firstMessage: EveChatMessage?) : SocketEvent
    data class ConversationChanged(val patch: ConversationPatch) : SocketEvent
    data class MessageArrived(val message: EveChatMessage) : SocketEvent
    data class NoteArrived(val message: EveChatMessage) : SocketEvent
    data class MessageAccepted(val serverId: String, val clientId: String?) : SocketEvent
    data class Typing(val conversationId: String, val typing: Boolean) : SocketEvent
    data class ServerError(val code: String, val message: String) : SocketEvent
}

/**
 * Maps EveChat agent-socket frames (`{type, id?, data?, ts}`) to [SocketEvent]s.
 *
 * Shapes follow the server: `conversation.new` is a flattened conversation plus `visitor` and
 * `message`; `conversation.updated` / `conversation.resolved` / `agent.assigned` are partial
 * patches keyed by `conversation_id`; `note.added` wraps the note under `message`; the agent
 * flavour of `message.sent` is only an ack `{id, client_id}`. Unknown types return null.
 */
object EveChatEventParser {

    fun parse(text: String): SocketEvent? {
        val envelope = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val data = envelope.optJSONObject("data")
        return when (envelope.optString("type")) {
            "ping" -> SocketEvent.Ping
            "sync.response" -> SocketEvent.Hello(data?.optLong("now")?.takeIf { it > 0 })
            "conversation.new" -> data?.let {
                SocketEvent.ConversationCreated(
                    EveChatConversation.fromJson(it),
                    it.optJSONObject("message")?.let(EveChatMessage::fromJson)
                )
            }
            "conversation.updated" -> data?.let(::patch)?.let(SocketEvent::ConversationChanged)
            "conversation.resolved" -> data?.let(::patch)?.let { p ->
                SocketEvent.ConversationChanged(p.copy(status = p.status ?: "resolved"))
            }
            "agent.assigned" -> data?.let(::patch)?.let(SocketEvent::ConversationChanged)
            "message.received" -> data?.let { SocketEvent.MessageArrived(EveChatMessage.fromJson(it)) }
            "note.added" -> data?.let { d ->
                val msg = d.optJSONObject("message") ?: d
                SocketEvent.NoteArrived(EveChatMessage.fromJson(msg))
            }
            "message.sent" -> data?.optStringOrNull("id")?.let { id ->
                SocketEvent.MessageAccepted(id, data.optStringOrNull("client_id"))
            }
            "visitor.typing" -> data?.optStringOrNull("conversation_id")?.let { SocketEvent.Typing(it, true) }
            "visitor.typing.stop" -> data?.optStringOrNull("conversation_id")?.let { SocketEvent.Typing(it, false) }
            "error" -> SocketEvent.ServerError(
                data?.optStringOrNull("code") ?: "unknown",
                data?.optStringOrNull("message") ?: ""
            )
            else -> null
        }
    }

    private fun patch(d: JSONObject): ConversationPatch? {
        val id = d.optStringOrNull("conversation_id") ?: d.optStringOrNull("id") ?: return null
        return ConversationPatch(
            conversationId = id,
            status = d.optStringOrNull("status"),
            assignedAgentId = d.optStringOrNull("assigned_agent_id") ?: d.optStringOrNull("agent_id"),
            assignedAgentName = d.optStringOrNull("agent_name"),
            lastMessageAt = EveChatTime.parse(d.optStringOrNull("last_message_at")),
            unreadAgent = if (d.has("unread_agent") && !d.isNull("unread_agent")) d.optInt("unread_agent") else null,
            senderType = d.optStringOrNull("sender_type")
        )
    }

    fun command(type: String, data: JSONObject? = null): String =
        JSONObject().put("type", type).apply { data?.let { put("data", it) } }.toString()
}

/** Exponential backoff with jitter so a server restart is not met by every client at once. */
object ReconnectBackoff {
    private const val BASE_MS = 1_000L
    private const val CAP_MS = 30_000L

    fun delayFor(attempt: Int, random: Random = Random.Default): Long {
        if (attempt <= 0) return 0
        val exp = BASE_MS shl (attempt - 1).coerceAtMost(5)
        val ceiling = exp.coerceAtMost(CAP_MS)
        return ceiling / 2 + random.nextLong(ceiling / 2 + 1)
    }
}
