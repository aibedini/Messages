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
        const val CONVERSATION_DELETED = "CONVERSATION_DELETED"
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
        const val ENVELOPE_V2 = "envelope.v2"
        const val ENVELOPE_V3 = "envelope.v3"
    }

    /** Stable message identity shared by every event of one provider row. */
    fun messageIdFor(source: String, providerId: Long, createdAtMs: Long): String =
        UUID.nameUUIDFromBytes("$source:$providerId:$createdAtMs".toByteArray()).toString()

    /** Deterministic per-event UUID — dedupes redelivery of the SAME event. */
    fun eventUuidFor(eventType: String, source: String, providerId: Long, dateMs: Long): String =
        UUID.nameUUIDFromBytes("evt:v3:$eventType:$source:$providerId:$dateMs".toByteArray()).toString()

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
        createdAt: Long = System.currentTimeMillis(),
        source: String = GatewayEventOutboxEntity.SOURCE_REALTIME
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
            createdAt = createdAt,
            source = source
        )
    }

    /**
     * Which flow produced an event (mission §10), derived rather than passed by every caller.
     *
     * The ordering is deliberate: a send that originated from a web command is a COMMAND_RESULT
     * even though it also looks like ordinary realtime activity, because that is the fact an
     * operator is looking for when a web send misbehaves. A history sweep is HISTORY regardless of
     * how urgent it feels.
     */
    private fun sourceFor(priority: String, originCommandId: String?): String = when {
        originCommandId != null -> GatewayEventOutboxEntity.SOURCE_COMMAND_RESULT
        priority == GatewayEventOutboxEntity.PRIORITY_BACKFILL -> GatewayEventOutboxEntity.SOURCE_HISTORY
        priority == GatewayEventOutboxEntity.PRIORITY_RECONCILIATION ->
            GatewayEventOutboxEntity.SOURCE_RECONCILIATION
        else -> GatewayEventOutboxEntity.SOURCE_REALTIME
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
        com.autonomousone.messages.security.EventCryptoPolicy.validateForUpload(event)
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
        address: String = "",
        contactName: String? = null,
        read: Boolean = false,
        originCommandId: String? = null,
        clientMessageId: String? = null,
        revision: Long = System.currentTimeMillis(),
        priority: String = GatewayEventOutboxEntity.PRIORITY_REALTIME,
    ): GatewayEventOutboxEntity {
        val payload = JSONObject()
            .put("messageId", messageIdFor(source, providerId, dateMs))
            .put("direction", direction)
            .put("body", body)
            .put("dateMs", dateMs)
            .put("status", status)
            .put("address", address)
            .put("read", read)
        if (!contactName.isNullOrBlank()) payload.put("contactName", contactName)
        if (!originCommandId.isNullOrBlank()) payload.put("originCommandId", originCommandId)
        if (!clientMessageId.isNullOrBlank()) payload.put("clientMessageId", clientMessageId)
        // ONE identity for one logical message, whichever way it was discovered.
        //
        // This used to branch on priority, giving a history sweep its own `evt:replica-v4:` domain.
        // That meant the same provider row seen by both paths produced TWO rows with two
        // eventUuids and one shared payload.messageId — and the outbox's unique index cannot dedupe
        // across namespaces, so the §33 race could put one message in the outbox twice
        // (docs/gateway-replication-audit.md, Blocker 7). Mission §33 requires the same canonical
        // identity, and §21 permits changing this because the bug was demonstrated rather than
        // suspected.
        //
        // Existing rows carrying the old ids are unaffected: they keep their rows, and a replayed
        // history sweep now computes this id, finds nothing, and re-sends — which the server
        // answers as DUPLICATE, a case the uploader now acknowledges (mission §16) instead of
        // retrying forever.
        val eventId = eventUuidFor(Types.MESSAGE_CREATED, source, providerId, dateMs)
        return outboxRow(
            eventId,
            Types.MESSAGE_CREATED,
            conversationId,
            payload.toString(),
            source = sourceFor(priority, originCommandId)
        ).copy(
            messageId = messageIdFor(source, providerId, dateMs),
            revision = revision,
            sortKey = dateMs,
            priority = priority,
        )
    }

    /**
     * A status/read transition on an existing message (mission §49).
     *
     * [originCommandId] and [clientMessageId] travel here for the same reason they travel on
     * [messageCreated]: a web-requested send is optimistic on GMweb's side, and when it later turns
     * DELIVERED or FAILED the server has to be able to find the bubble it created. `messageCreated`
     * carried both from the start; this one carried neither, so a status change could never be tied
     * back to the web bubble — §49 was half-implemented in exactly that direction.
     *
     * Adding payload keys does NOT touch the event identity: [eventUuidFor] is derived from the
     * type, source, provider row and date only, and the caller overrides it with its own
     * `message-state:` key. Mission §33 requires one identity per (source, row, date, state), and a
     * status event's identity must not start depending on which send caused it.
     *
     * `source` deliberately stays [GatewayEventOutboxEntity.SOURCE_STATUS_UPDATE] and does not
     * switch to `COMMAND_RESULT`: a status change IS a status change regardless of what caused it,
     * and the causal link belongs in the payload rather than in the accounting bucket.
     */
    fun messageStatusChanged(
        source: String,
        providerId: Long,
        conversationId: String,
        status: Int,
        dateMs: Long,
        direction: String? = null,
        body: String? = null,
        address: String? = null,
        contactName: String? = null,
        read: Boolean = false,
        originCommandId: String? = null,
        clientMessageId: String? = null,
    ): GatewayEventOutboxEntity {
        val payload = JSONObject()
            .put("messageId", messageIdFor(source, providerId, dateMs))
            .put("status", status)
            .put("dateMs", dateMs)
            .put("read", read)
        if (direction != null) payload.put("direction", direction)
        if (body != null) payload.put("body", body)
        if (address != null) payload.put("address", address)
        if (!contactName.isNullOrBlank()) payload.put("contactName", contactName)
        if (!originCommandId.isNullOrBlank()) payload.put("originCommandId", originCommandId)
        if (!clientMessageId.isNullOrBlank()) payload.put("clientMessageId", clientMessageId)
        return outboxRow(
            eventUuidFor("${Types.MESSAGE_STATUS_CHANGED}:$status", source, providerId, dateMs),
            Types.MESSAGE_STATUS_CHANGED,
            conversationId,
            payload.toString(),
            source = GatewayEventOutboxEntity.SOURCE_STATUS_UPDATE
        ).copy(
            messageId = messageIdFor(source, providerId, dateMs),
            revision = System.currentTimeMillis(),
            sortKey = dateMs,
        )
    }

    /**
     * A message is gone.
     *
     * Deliberately does NOT carry `originCommandId`/`clientMessageId`, unlike the other three
     * members of the family. It was given them for one round and then removed: `MessageMutation`
     * .`Delete` carries neither, so no call site could populate them, and a parameter that is always
     * null is precisely the defect this round fixed one function over (`clientMessageId` on
     * `RemoteCommandEntity`). A correlation should be added here when a delete actually has one —
     * i.e. if a remotely requested delete command ever exists — not because the shape looks
     * symmetric.
     */
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
        ).copy(
            messageId = messageIdFor(source, providerId, dateMs),
            revision = System.currentTimeMillis(),
            sortKey = dateMs,
        )
    }

    fun threadRead(
        conversationId: String,
        revisionKey: String = "legacy"
    ): GatewayEventOutboxEntity {
        val payload = JSONObject()
            .put("conversationId", conversationId)
            .put("readAtMs", System.currentTimeMillis())
        return outboxRow(
            UUID.nameUUIDFromBytes("thread-read:$conversationId:$revisionKey".toByteArray()).toString(),
            Types.THREAD_READ,
            conversationId,
            payload.toString()
        )
    }

    fun conversationUpserted(
        conversationId: String,
        displayName: String?,
        address: String,
        lastMessagePreview: String,
        lastMessageDirection: String,
        lastMessageAt: Long,
        unreadCount: Int,
        pinned: Boolean,
        archived: Boolean,
        revision: Long = System.currentTimeMillis(),
        priority: String = GatewayEventOutboxEntity.PRIORITY_REALTIME,
    ): GatewayEventOutboxEntity {
        val payload = JSONObject()
            .put("conversationId", conversationId)
            .put("displayName", displayName ?: address)
            .put("address", address)
            .put("lastMessagePreview", lastMessagePreview)
            .put("lastMessageDirection", lastMessageDirection)
            .put("lastMessageAt", lastMessageAt)
            .put("unreadCount", unreadCount)
            .put("pinned", pinned)
            .put("archived", archived)
        return outboxRow(
            UUID.nameUUIDFromBytes("conversation:$conversationId:$revision".toByteArray()).toString(),
            Types.CONVERSATION_UPSERTED,
            conversationId,
            payload.toString(),
            source = sourceFor(priority, originCommandId = null),
        ).copy(revision = revision, sortKey = lastMessageAt, priority = priority)
    }

    fun conversationDeleted(conversationId: String, revision: Long = System.currentTimeMillis()) =
        outboxRow(
            UUID.nameUUIDFromBytes("conversation-delete:$conversationId:$revision".toByteArray()).toString(),
            Types.CONVERSATION_DELETED,
            conversationId,
            JSONObject().put("conversationId", conversationId).toString(),
        ).copy(revision = revision, sortKey = revision)
}
