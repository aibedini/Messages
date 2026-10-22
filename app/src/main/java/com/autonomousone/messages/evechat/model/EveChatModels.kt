package com.autonomousone.messages.evechat.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object ConvStatus {
    const val PENDING = "pending"
    const val ACTIVE = "active"
    const val RESOLVED = "resolved"
    const val CLOSED = "closed"

    val OPEN = listOf(PENDING, ACTIVE)
    val DONE = listOf(RESOLVED, CLOSED)

    fun isOpen(status: String) = status == PENDING || status == ACTIVE
}

object SenderType {
    const val VISITOR = "visitor"
    const val AGENT = "agent"
    const val BOT = "bot"
    const val SYSTEM = "system"
}

data class EveChatAgent(
    val id: String,
    val name: String,
    val email: String,
    val role: String = "agent"
) {
    companion object {
        fun fromJson(j: JSONObject) = EveChatAgent(
            id = j.getString("id"),
            name = j.optStringOrNull("name") ?: "",
            email = j.optStringOrNull("email") ?: "",
            role = j.optStringOrNull("role") ?: "agent"
        )
    }
}

@Entity(tableName = "conversations")
data class EveChatConversation(
    @PrimaryKey val id: String,
    val visitorId: String,
    val visitorName: String? = null,
    val visitorEmail: String? = null,
    val visitorPhone: String? = null,
    val assignedAgentId: String? = null,
    val assignedAgentName: String? = null,
    val status: String = ConvStatus.PENDING,
    val channel: String = "web",
    val subject: String? = null,
    val unreadAgent: Int = 0,
    val lastMessageAt: Long? = null,
    val createdAt: Long = 0,
    val lastMessagePreview: String? = null,
    val lastMessageSenderType: String? = null,
    val historyComplete: Boolean = false
) {
    val displayName: String
        get() = visitorName ?: visitorPhone ?: visitorEmail ?: "بازدیدکننده"

    val activityAt: Long
        get() = lastMessageAt ?: createdAt

    companion object {
        /** Accepts both the REST conversation view and the flattened `conversation.new` payload. */
        fun fromJson(j: JSONObject): EveChatConversation {
            val visitor = j.optJSONObject("visitor")
            val agent = j.optJSONObject("agent")
            return EveChatConversation(
                id = j.getString("id"),
                visitorId = j.optStringOrNull("visitor_id") ?: visitor?.optStringOrNull("id") ?: "",
                visitorName = visitor?.let { v ->
                    v.optStringOrNull("name") ?: v.optStringOrNull("eve_mobile")
                },
                visitorEmail = visitor?.optStringOrNull("email"),
                visitorPhone = visitor?.optStringOrNull("phone"),
                assignedAgentId = j.optStringOrNull("assigned_agent_id"),
                assignedAgentName = agent?.optStringOrNull("name"),
                status = j.optStringOrNull("status") ?: ConvStatus.PENDING,
                channel = j.optStringOrNull("channel") ?: "web",
                subject = j.optStringOrNull("subject"),
                unreadAgent = j.optInt("unread_agent", 0),
                lastMessageAt = EveChatTime.parse(j.optStringOrNull("last_message_at")),
                createdAt = EveChatTime.parse(j.optStringOrNull("created_at")) ?: 0L
            )
        }
    }
}

enum class SendState { SENT, PENDING, FAILED }

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversationId", "createdAt"]),
        Index(value = ["clientMessageId"], unique = true)
    ]
)
data class EveChatMessage(
    @PrimaryKey val id: String,
    val conversationId: String,
    val clientMessageId: String? = null,
    val senderType: String,
    val senderId: String? = null,
    val senderName: String? = null,
    val contentType: String = "text",
    val content: String = "",
    val attachments: List<String> = emptyList(),
    val isInternal: Boolean = false,
    val isRead: Boolean = false,
    val createdAt: Long = 0,
    val sendState: SendState = SendState.SENT,
    val attempts: Int = 0,
    val lastError: String? = null
) {
    val isOutgoing: Boolean get() = senderType == SenderType.AGENT
    val isSystem: Boolean get() = senderType == SenderType.SYSTEM || senderType == SenderType.BOT

    companion object {
        const val LOCAL_PREFIX = "local-"

        fun fromJson(j: JSONObject): EveChatMessage {
            val attachments = mutableListOf<String>()
            j.optJSONArray("attachments")?.let { arr ->
                for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let(attachments::add)
            }
            return EveChatMessage(
                id = j.getString("id"),
                conversationId = j.optStringOrNull("conversation_id") ?: "",
                clientMessageId = j.optStringOrNull("client_message_id"),
                senderType = j.optStringOrNull("sender_type") ?: SenderType.SYSTEM,
                senderId = j.optStringOrNull("sender_id"),
                senderName = j.optStringOrNull("sender_name"),
                contentType = j.optStringOrNull("content_type") ?: "text",
                content = j.optStringOrNull("content") ?: "",
                attachments = attachments,
                isInternal = j.optBoolean("is_internal", false),
                isRead = j.optBoolean("is_read", false),
                createdAt = EveChatTime.parse(j.optStringOrNull("created_at")) ?: System.currentTimeMillis()
            )
        }
    }
}

/** `conversation.updated` carries only the fields that changed. */
data class ConversationPatch(
    val conversationId: String,
    val status: String? = null,
    val assignedAgentId: String? = null,
    val assignedAgentName: String? = null,
    val lastMessageAt: Long? = null,
    val unreadAgent: Int? = null,
    val senderType: String? = null
)

data class PagedList<T>(val items: List<T>, val total: Int)

object EveChatTime {
    private val sqlLayout = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** Server times are UTC; returns epoch millis, or null for blank/unknown input. */
    fun parse(value: String?): Long? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        text.toLongOrNull()?.let { return it }
        return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching {
                LocalDateTime.parse(text.take(19).replace('T', ' '), sqlLayout)
                    .toInstant(ZoneOffset.UTC).toEpochMilli()
            }.getOrNull()
    }
}

/** org.json returns the literal "null" for JSON null with optString; this does not. */
fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).trim().takeIf { it.isNotEmpty() }
}
