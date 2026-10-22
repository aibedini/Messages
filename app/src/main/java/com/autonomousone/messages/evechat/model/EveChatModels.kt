package com.autonomousone.messages.evechat.model

import org.json.JSONArray
import org.json.JSONObject

data class EveChatAgent(
    val id: String,
    val name: String,
    val email: String,
    val avatarUrl: String? = null,
    val role: String = "agent",
    val isOnline: Boolean = false
) {
    companion object {
        fun fromJson(j: JSONObject) = EveChatAgent(
            id = j.getString("id"),
            name = j.optString("name", ""),
            email = j.optString("email", ""),
            avatarUrl = j.optString("avatar_url", null).takeIf { !it.isNullOrBlank() },
            role = j.optString("role", "agent"),
            isOnline = j.optBoolean("is_online", false)
        )
    }
}

data class EveChatVisitor(
    val id: String,
    val name: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val country: String? = null,
    val lastSeenAt: String? = null
) {
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() }
            ?: phone?.takeIf { it.isNotBlank() }
            ?: email?.takeIf { it.isNotBlank() }
            ?: "بازدیدکننده"

    companion object {
        fun fromJson(j: JSONObject) = EveChatVisitor(
            id = j.getString("id"),
            name = j.optString("name", null).takeIf { !it.isNullOrBlank() },
            email = j.optString("email", null).takeIf { !it.isNullOrBlank() },
            phone = j.optString("phone", null).takeIf { !it.isNullOrBlank() },
            country = j.optString("country", null).takeIf { !it.isNullOrBlank() },
            lastSeenAt = j.optString("last_seen_at", null).takeIf { !it.isNullOrBlank() }
        )
    }
}

data class EveChatConversation(
    val id: String,
    val visitorId: String,
    val assignedAgentId: String? = null,
    val status: String = "pending",
    val channel: String = "web",
    val subject: String? = null,
    val unreadAgent: Int = 0,
    val lastMessageAt: String? = null,
    val createdAt: String = "",
    val visitor: EveChatVisitor? = null,
    val agent: EveChatAgent? = null,
    val lastMessagePreview: String? = null
) {
    companion object {
        fun fromJson(j: JSONObject): EveChatConversation {
            val visitor = j.optJSONObject("visitor")?.let { EveChatVisitor.fromJson(it) }
            val agent = j.optJSONObject("agent")?.let { EveChatAgent.fromJson(it) }
            return EveChatConversation(
                id = j.getString("id"),
                visitorId = j.optString("visitor_id", ""),
                assignedAgentId = j.optString("assigned_agent_id", null).takeIf { !it.isNullOrBlank() },
                status = j.optString("status", "pending"),
                channel = j.optString("channel", "web"),
                subject = j.optString("subject", null).takeIf { !it.isNullOrBlank() },
                unreadAgent = j.optInt("unread_agent", 0),
                lastMessageAt = j.optString("last_message_at", null).takeIf { !it.isNullOrBlank() },
                createdAt = j.optString("created_at", ""),
                visitor = visitor,
                agent = agent
            )
        }
    }
}

data class EveChatMessage(
    val id: String,
    val conversationId: String,
    val senderType: String,
    val senderId: String? = null,
    val senderName: String? = null,
    val contentType: String = "text",
    val content: String = "",
    val attachments: List<String> = emptyList(),
    val isInternal: Boolean = false,
    val isRead: Boolean = false,
    val createdAt: String = "",
    val clientMessageId: String? = null,
    val sendState: SendState = SendState.Sent
) {
    enum class SendState { Sending, Sent, Failed }

    companion object {
        fun fromJson(j: JSONObject): EveChatMessage {
            val attachments = mutableListOf<String>()
            j.optJSONArray("attachments")?.let { arr ->
                for (i in 0 until arr.length()) attachments.add(arr.getString(i))
            }
            return EveChatMessage(
                id = j.getString("id"),
                conversationId = j.optString("conversation_id", ""),
                senderType = j.optString("sender_type", ""),
                senderId = j.optString("sender_id", "").takeIf { it.isNotBlank() },
                senderName = j.optString("sender_name", "").takeIf { it.isNotBlank() },
                contentType = j.optString("content_type", "text"),
                content = j.optString("content", ""),
                attachments = attachments,
                isInternal = j.optBoolean("is_internal", false),
                isRead = j.optBoolean("is_read", false),
                createdAt = j.optString("created_at", ""),
                clientMessageId = j.optString("client_message_id", "").takeIf { it.isNotBlank() }
            )
        }
    }
}

data class EveChatTicket(
    val id: String,
    val title: String,
    val status: String = "open",
    val priority: String = "medium",
    val assignedAgentId: String? = null,
    val tags: List<String> = emptyList(),
    val createdAt: String = ""
) {
    companion object {
        fun fromJson(j: JSONObject): EveChatTicket {
            val tags = mutableListOf<String>()
            j.optJSONArray("tags")?.let { arr ->
                for (i in 0 until arr.length()) tags.add(arr.getString(i))
            }
            return EveChatTicket(
                id = j.getString("id"),
                title = j.optString("title", ""),
                status = j.optString("status", "open"),
                priority = j.optString("priority", "medium"),
                assignedAgentId = j.optString("assigned_agent_id", null).takeIf { !it.isNullOrBlank() },
                tags = tags,
                createdAt = j.optString("created_at", "")
            )
        }
    }
}

data class PagedList<T>(
    val data: List<T>,
    val total: Int,
    val limit: Int,
    val offset: Int
)
