package com.autonomousone.messages.sms

import com.autonomousone.messages.data.GatewayEventOutboxEntity
import com.autonomousone.messages.data.RemoteCommandEntity
import java.util.UUID

/**
 * PR-03: THE single durable queue for outgoing SMS (TechSpec §19/§40/§93/§94).
 *
 * Every send source — Android Composer, Notification Reply, Web/PWA command,
 * Scheduled send, Gateway/EVE API — lands here as a REMOTE_COMMAND row
 * (durable, idempotent). Nothing calls SmsManager outside this pipeline.
 * The executor drains in order; [SmsSender.sendOrEnqueue] transparently
 * redirects legacy callers into enqueue mode (temporary compatibility layer,
 * removed once every call site is moved onto the pipeline surface).
 *
 * Command lifecycle: REMOTE queue row (RECEIVED) → drain (ACCEPTED→EXECUTING)
 * → SmsManager hand-off → COMPLETED/FAILED row + MESSAGE_STATUS_CHANGED event.
 * Exactly-once comes from PR-01's unique idempotencyKey + execution rows.
 */
object GatewayOutgoingPipeline {

    /**
     * PR-03 rollout flag: when FALSE every legacy entry point keeps its exact
     * current behaviour (direct send, no queue); when TRUE every send funnels
     * through the durable remote_commands queue. Flip to TRUE only after
     * green process-death tests; Phase 2 removes the flag entirely.
     */
    @Volatile
    var ENQUEUE_ALL_SENDS: Boolean = false

    fun newIdempotencyKey(): String = UUID.randomUUID().toString()

    /** Pure decision record — JVM-testable without Android (ponytail: tiny). */
    data class Plan(
        val commandId: String,
        val idempotencyKey: String,
        val conversationId: String,
        val messageUuid: String,
        val body: String,
        val subscriptionId: Int?
    )

    /**
     * Enqueue one SEND_SMS command durably. Returns the durable command id
     * (never null unless storage itself failed, which throws — no silent
     * fire-and-forget, Rule 4). Redelivery with the same [idempotencyKey]
     * is a no-op returning the EXISTING command id (exactly-once).
     */
    suspend fun enqueueSendSms(
        phone: String,
        body: String,
        threadId: Long,
        subscriptionId: Int? = null,
        idempotencyKey: String = newIdempotencyKey(),
        sourceDeviceId: String = "android-local"
    ): Plan {
        val db = com.autonomousone.messages.data.MessagesDatabase.get(
            com.autonomousone.messages.Holders.appContext
        )
        val repo = com.autonomousone.messages.repository.GatewaySyncRepository(db)
        val conversationId = repo.ensureConversationIdForThread(threadId)
        val commandId = UUID.randomUUID().toString()
        val messageUuid = UUID.randomUUID().toString()
        val payload = org.json.JSONObject()
            .put("type", "SEND_SMS")
            .put("phone", phone)
            .put("body", body)
            .put("threadId", threadId)
            .put("messageId", messageUuid)
        if (subscriptionId != null) payload.put("subscriptionId", subscriptionId)
        val now = System.currentTimeMillis()
        val row = RemoteCommandEntity(
            commandId = commandId,
            type = "SEND_SMS",
            ciphertext = payload.toString().toByteArray(Charsets.UTF_8),
            encoding = "application/json",
            schemaVersion = 1,
            cryptoVersion = 0,
            receivedAt = now,
            issuedAt = now,
            // §93: no short timeout may delete a queued command — 24h floor,
            // matched by the GMweb command expiry when Phase 3 lands.
            expiresAt = now + 24L * 3600_000,
            idempotencyKey = idempotencyKey
        )
        val inserted = repo.ingestCommand(row)
        if (!inserted) {
            // Redelivery: the existing row wins; surface its id (exactly-once).
            val existing = db.remoteCommandDao().getByIdempotencyKey(idempotencyKey)
            if (existing != null) {
                return Plan(
                    existing.commandId, idempotencyKey, conversationId, messageUuid, body, subscriptionId
                )
            }
        }
        return Plan(commandId, idempotencyKey, conversationId, messageUuid, body, subscriptionId)
    }
}
