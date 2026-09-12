package com.autonomousone.messages.security

import com.autonomousone.messages.data.GatewayEventOutboxEntity
import org.json.JSONObject
import java.util.Base64

/** Production upload allowlist. Plaintext canonical rows may exist only before encryption. */
object EventCryptoPolicy {
    const val MAX_BATCH_EVENTS = 100
    const val MAX_DECODED_BATCH_BYTES = 512 * 1024
    private const val MAX_CONTENT_BYTES = 64 * 1024
    private const val MAX_CONTROL_BYTES = 128 * 1024

    val contentVersions = mapOf(
        "MESSAGE_CREATED" to setOf(1, 2, 3),
        "MESSAGE_UPDATED" to setOf(1, 2, 3),
        "MESSAGE_STATUS_CHANGED" to setOf(1, 2, 3),
        "MESSAGE_DELETED" to setOf(1, 2, 3),
        "CONVERSATION_UPSERT" to setOf(3),
        "CONVERSATION_UPSERTED" to setOf(3),
        "CONVERSATION_DELETED" to setOf(3),
        "THREAD_READ" to setOf(1, 2, 3),
        "CONTACTS_SNAPSHOT" to setOf(1, 2),
        "CONTACTS_CHANGED" to setOf(1, 2),
    )
    val keyVersions = mapOf(
        "KEY_GRANT" to setOf(1),
        "CONTACTS_KEY_GRANT" to setOf(1),
        "KEYRING_ENTRY" to setOf(2),
        "HISTORY_KEY_GRANT" to setOf(3),
    )
    val nonContentVersions = mapOf(
        "DEVICE_STATUS_CHANGED" to setOf(0, 1),
        "SIM_STATE_CHANGED" to setOf(0, 1),
    )

    fun isContentBearing(type: String) = type in contentVersions

    fun validateForUpload(event: GatewayEventOutboxEntity) {
        val allowed = contentVersions[event.eventType]
            ?: keyVersions[event.eventType]
            ?: nonContentVersions[event.eventType]
            ?: throw IllegalArgumentException("unknown event type")
        require(event.cryptoVersion in allowed) {
            if (isContentBearing(event.eventType)) "encrypted payload required" else "unsupported crypto version"
        }
        require(event.schemaVersion == 1)
        require(event.encoding == if (event.cryptoVersion == 0) "envelope.v1" else "envelope.v${event.cryptoVersion}")
        require(event.eventUuid.length in 1..128 && event.aggregateId.length <= 256 && event.messageId.length <= 128)
        val max = if (isContentBearing(event.eventType)) MAX_CONTENT_BYTES else MAX_CONTROL_BYTES
        require(event.ciphertext.isNotEmpty() && event.ciphertext.size <= max)
        if (isContentBearing(event.eventType)) validateEncryptedEnvelope(event)
    }

    private fun validateEncryptedEnvelope(event: GatewayEventOutboxEntity) {
        val envelope = JSONObject(String(event.ciphertext, Charsets.UTF_8))
        require(envelope.getInt("v") == event.cryptoVersion && envelope.getString("kind") == "message")
        require(envelope.getString("eventId") == event.eventUuid)
        require(envelope.getString("type") == event.eventType)
        require(envelope.getString("conversationId") == event.aggregateId)
        val fields = if (event.cryptoVersion == 3) listOf(
            "iv", "ciphertext", "historyWrapIv", "historyWrappedDek", "liveWrapIv", "liveWrappedDek"
        ) else listOf("iv", "ciphertext", "wrapIv", "wrappedDek")
        for (field in fields) {
            val decoded = Base64.getDecoder().decode(envelope.getString(field))
            require(decoded.size >= if (field.contains("iv", ignoreCase = true)) 12 else 16)
        }
    }
}
