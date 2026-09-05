package com.autonomousone.messages.data

import java.util.Base64
import java.util.UUID
import org.json.JSONObject

/**
 * PR-02: cloud event construction (TechSpec §67 event types). Lives in `data`
 * because it BUILDS Room rows (gateway_event_outbox); the network side is
 * EventUploader's business.
 *
 * ENVELOPE-ONLY on purpose: every event row stores
 * `ciphertext + encoding + schemaVersion + cryptoVersion` (the PR-01
 * crypto-friendly payload contract). In PR-02 the envelope carries JSON
 * plaintext with cryptoVersion=0 — REAL E2EE arrives in Phase 7 (ADR-002)
 * and MUST NOT change this file's public shape; business code never learns
 * the envelope layout.
 *
 * Identity rules (TechSpec §13):
 *  - `payload.messageId` = STABLE message UUID derived from the durable
 *    provider row identity `source:providerId:createdAtMs` — NEVER from
 *    body/timestamp alone (that was the old WebhookEngine bug).
 *  - `eventUuid` = DETERMINISTIC per-event UUID (kind + row identity + date)
 *    so the outbox unique index turns REBUILDS of the same event into free
 *    dedupe instead of "row already shipped, revision silently dropped".
 *
 * PII policy (ADR-001/002): sender/body live INSIDE the payload envelope
 * bytes (encrypted in Phase 7) — never as envelope columns.
 */
object GatewayEventFactory {

    /** Wire event type constants (TechSpec §67). */
    object Types {
        const val CONVERSATION_UPSERTED = "CONVERSATION_UPSERTED"
        const val MESSAGE_CREATED = "MESSAGE_CREATED"
        const val MESSAGE_UPDATED = "MESSAGE_UPDATED"
        const val MESSAGE_STATUS_CHANGED = "MESSAGE_STATUS_CHANGED"
        const val MESSAGE_DELETED = "MESSAGE_DELETED"
        const val THREAD_READ = "THREAD_READ"
        const val DEVICE_STATUS_CHANGED = "DEVICE_STATUS_CHANGED"
        const val SIM_STATE_CHANGED = "SIM_STATE_CHANGED"
    }

    object Encoding {
        const val JSON = "application/json"
        const val ENVELOPE_V1 = "envelope.v1"
    }

    /** Stable message identity shared by every event of one provider row. */
    fun messageIdFor(source: String, providerId: Long, createdAtMs: Long): String =
        UUID.nameUUIDFromBytes("$source:$providerId:$createdAtMs".toByteArray()).toString()

    /** Deterministic per-event UUID — dedupes redelivery of the SAME event. */
    fun eventUuidFor(eventType: String, source: String, providerId: Long, dateMs: Long): String =
        UUID.nameUUIDFromBytes("evt:$eventType:$source:$providerId:$dateMs".toByteArray()).toString()

    /**
     * Build an outbox row whose `ciphertext` column holds the envelope JSON:
     * `{"ciphertextB64":…,"encoding":…,"schemaVersion":1,"cryptoVersion":0}`.
     * cryptoVersion=0 means inner bytes are UTF-8 JSON (PR-02); Phase 7 swaps
     * in AEAD ciphertext bytes with cryptoVersion≥1 and zero schema change.
     */
    fun outboxRow(
        eventUuid: String,
        eventType: String,
        conversationId: String,
        payloadJson: String,
        createdAt: Long = System.currentTimeMillis()
    ): GatewayEventOutboxEntity {
        val inner = payloadJson.toByteArray(Charsets.UTF_8)
        val envelope = JSONObject()
            .put("ciphertextB64", Base64.getEncoder().encodeToString(inner))
            .put("encoding", Encoding.JSON)
            .put("schemaVersion", 1)
            .put("cryptoVersion", 0)
        return GatewayEventOutboxEntity(
            eventUuid = eventUuid,
            eventType = eventType,
            aggregateId = conversationId,
            ciphertext = envelope.toString().toByteArray(Charsets.UTF_8),
            encoding = Encoding.ENVELOPE_V1,
            schemaVersion = 1,
            cryptoVersion = 0,
            createdAt = createdAt
        )
    }

    /** Inverse of [outboxRow]'s envelope — used by the uploader wire path. */
    fun decodePayloadEnvelope(envelopeBytes: ByteArray): String {
        val envelope = JSONObject(String(envelopeBytes, Charsets.UTF_8))
        val cryptoVersion = envelope.optInt("cryptoVersion", -1)
        val encoding = envelope.optString("encoding", "")
        require(cryptoVersion == 0 && encoding == Encoding.JSON) {
            "unsupported payload envelope (cryptoVersion=$cryptoVersion, encoding=$encoding)"
        }
        return String(
            Base64.getDecoder().decode(envelope.getString("ciphertextB64")),
            Charsets.UTF_8
        )
    }

    /** Transport checks metadata only for encrypted bytes; never decrypt here. */
    fun validateForTransport(event: GatewayEventOutboxEntity) {
        require(event.encoding == Encoding.ENVELOPE_V1 && event.schemaVersion == 1)
        require(event.eventUuid.isNotBlank() && event.eventType.isNotBlank())
        require(event.cryptoVersion >= 0 && event.ciphertext.isNotEmpty())
        if (event.cryptoVersion == 0) decodePayloadEnvelope(event.ciphertext)
    }

    // ── Event builders (PII inside the payload bytes, never the envelope) ──

    fun messageCreated(
        source: String,
        providerId: Long,
        conversationId: String,
        direction: String,
        body: String,
        dateMs: Long,
        status: Int,
        address: String = ""
    ): GatewayEventOutboxEntity {
        val payload = JSONObject()
            .put("messageId", messageIdFor(source, providerId, dateMs))
            .put("direction", direction)
            .put("body", body)
            .put("dateMs", dateMs)
            .put("status", status)
            .put("address", address)
        return outboxRow(
            eventUuidFor(Types.MESSAGE_CREATED, source, providerId, dateMs),
            Types.MESSAGE_CREATED,
            conversationId,
            payload.toString()
        )
    }

    fun messageStatusChanged(
        source: String,
        providerId: Long,
        conversationId: String,
        status: Int,
        dateMs: Long
    ): GatewayEventOutboxEntity {
        val payload = JSONObject()
            .put("messageId", messageIdFor(source, providerId, dateMs))
            .put("status", status)
        return outboxRow(
            eventUuidFor("${Types.MESSAGE_STATUS_CHANGED}:$status", source, providerId, dateMs),
            Types.MESSAGE_STATUS_CHANGED,
            conversationId,
            payload.toString()
        )
    }

    fun messageDeleted(
        source: String,
        providerId: Long,
        conversationId: String,
        dateMs: Long
    ): GatewayEventOutboxEntity {
        val payload = JSONObject().put("messageId", messageIdFor(source, providerId, dateMs))
        return outboxRow(
            eventUuidFor(Types.MESSAGE_DELETED, source, providerId, dateMs),
            Types.MESSAGE_DELETED,
            conversationId,
            payload.toString()
        )
    }

    fun threadRead(conversationId: String): GatewayEventOutboxEntity {
        val payload = JSONObject()
            .put("conversationId", conversationId)
            .put("readAtMs", System.currentTimeMillis())
        return outboxRow(
            UUID.randomUUID().toString(),
            Types.THREAD_READ,
            conversationId,
            payload.toString()
        )
    }
}
