package com.autonomousone.messages.evechat.push

import org.json.JSONObject

/**
 * An EveChat FCM data message (contract: docs/evechat-push.md).
 *
 * A push is a hint, not the source of truth: the app re-reads the conversation from the server
 * and notifies from what it fetched. [title]/[body] are only used when that fetch is impossible.
 */
data class EveChatPush(
    val version: Int,
    val type: Type,
    val conversationId: String?,
    val messageId: String?,
    val agentId: String?,
    val sentAt: Long?,
    val title: String?,
    val body: String?
) {
    enum class Type { MESSAGE, NEW_CONVERSATION, CONVERSATION_UPDATED, SYNC }

    companion object {
        const val SUPPORTED_VERSION = 1
        private const val MAX_TEXT = 300

        /** @return null when the message is not an EveChat push at all. */
        fun parse(data: Map<String, String>): EveChatPush? {
            if (data["source"] != "evechat") return null
            val rawType = data["type"]?.trim().orEmpty()
            if (rawType.isEmpty()) return null
            val version = data["v"]?.toIntOrNull() ?: 1
            val known = when (rawType) {
                "message.received" -> Type.MESSAGE
                "conversation.new" -> Type.NEW_CONVERSATION
                "conversation.updated", "conversation.resolved", "agent.assigned" -> Type.CONVERSATION_UPDATED
                else -> Type.SYNC
            }
            // A newer server may change field meaning; the only safe reaction is a full sync.
            val type = if (version > SUPPORTED_VERSION) Type.SYNC else known
            return EveChatPush(
                version = version,
                type = type,
                conversationId = data["conversation_id"].clean(),
                messageId = data["message_id"].clean(),
                agentId = data["agent_id"].clean(),
                sentAt = data["sent_at"]?.toLongOrNull(),
                title = data["title"].clean()?.take(MAX_TEXT),
                body = data["body"].clean()?.take(MAX_TEXT)
            )
        }

        private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/** The body of `POST /api/v1/push/devices`. */
data class PushDeviceRegistration(
    val deviceId: String,
    val token: String,
    val appVersion: String,
    val appBuild: Int,
    val osVersion: String,
    val deviceModel: String,
    val locale: String,
    val timezone: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("device_id", deviceId)
        .put("platform", "android")
        .put("provider", "fcm")
        .put("token", token)
        .put("app_version", appVersion)
        .put("app_build", appBuild)
        .put("os_version", osVersion)
        .put("device_model", deviceModel)
        .put("locale", locale)
        .put("timezone", timezone)
}

/** What the server last accepted for this device. */
data class PushRegistrationRecord(
    val token: String,
    val server: String,
    val agentId: String,
    val appBuild: Int,
    val registeredAt: Long
) {
    fun toJson(): String = JSONObject()
        .put("token", token)
        .put("server", server)
        .put("agent_id", agentId)
        .put("app_build", appBuild)
        .put("registered_at", registeredAt)
        .toString()

    companion object {
        fun fromJson(text: String?): PushRegistrationRecord? = text?.let {
            runCatching {
                val j = JSONObject(it)
                PushRegistrationRecord(
                    token = j.getString("token"),
                    server = j.getString("server"),
                    agentId = j.getString("agent_id"),
                    appBuild = j.getInt("app_build"),
                    registeredAt = j.getLong("registered_at")
                )
            }.getOrNull()
        }
    }
}

object PushRegistrationPolicy {
    /** FCM recommends refreshing server-side tokens periodically so stale ones can be pruned. */
    const val REFRESH_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

    /** How long a server that answered "no push endpoint" is left alone before asking again. */
    const val UNSUPPORTED_RETRY_MS = 24L * 60 * 60 * 1000

    fun needsRegistration(
        record: PushRegistrationRecord?,
        token: String?,
        server: String,
        agentId: String?,
        appBuild: Int,
        now: Long
    ): Boolean {
        if (record == null) return true
        if (token != null && token != record.token) return true
        if (record.server != server) return true
        if (agentId != null && agentId != record.agentId) return true
        if (record.appBuild != appBuild) return true
        return now - record.registeredAt >= REFRESH_INTERVAL_MS
    }

    /** True for answers that mean the server has no push endpoint at all (not a transient failure). */
    fun isUnsupported(httpStatus: Int?): Boolean = httpStatus == 404 || httpStatus == 405 || httpStatus == 501
}
