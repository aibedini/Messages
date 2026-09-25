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

    /**
     * The `senderDeviceId` of a command this device queued for itself.
     *
     * Also the marker the drain uses to leave such a row alone: it is not a remote instruction, so
     * draining it (and ACKing its id back to GMweb) would be answering a request nobody made — and if a
     * future executor accepted these rows, it would re-send a message this device had already handed to
     * the radio.
     */
    const val LOCAL_SOURCE_DEVICE_ID = "android-local"

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
        sourceDeviceId: String = LOCAL_SOURCE_DEVICE_ID,
        clientMessageId: String? = null,
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
        // §49: recorded in BOTH places on purpose. The column is the authority a drain reads, and
        // the payload copy is what a row enqueued by an older build would have had. Without the
        // column, a local-pipeline send interrupted between enqueue and dispatch would be re-driven
        // by the drain with no bubble key at all.
        if (!clientMessageId.isNullOrBlank()) payload.put("clientMessageId", clientMessageId)
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
            idempotencyKey = idempotencyKey,
            clientMessageId = clientMessageId?.takeIf { it.isNotBlank() },
            // Recorded, because the parameter used to be accepted and then DROPPED. It is the only
            // marker that distinguishes a row this device queued for itself from one the control plane
            // sent, and the command drain keys off exactly that: a local row must never be drained or
            // ACKed as though GMweb had asked for it (see CommandDrainPolicy).
            senderDeviceId = sourceDeviceId,
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

    /**
     * PR-10: execute a REMOTE-ingested SEND_SMS command (SecureCommandPoller
     * hands over rows it freshly ingested from GMweb). The command row is
     * already durable (RECEIVED); this drains it through the SAME guarded
     * lifecycle the local funnel uses — ACCEPTED → (directSend via the
     * existing executor path) → COMPLETED/FAILED — and returns true when the
     * terminal state was reported (the poller then skips its own ack).
     *
     * Payload contract (v1, cryptoVersion=0): plain JSON
     * `{type:"SEND_SMS", phone, body, subscriptionId?}`.
     */
    suspend fun executeIngested(
        cmd: com.autonomousone.messages.data.RemoteCommandEntity,
        repo: com.autonomousone.messages.repository.GatewaySyncRepository
    ): Boolean {
        require(cmd.type == "SEND_SMS") { "executeIngested only drains SEND_SMS" }
        val accepted = repo.markCommandAcceptedIfReceived(cmd.commandId)
        if (!accepted) return false // someone else already moved it — not ours

        val payload = try {
            org.json.JSONObject(String(cmd.ciphertext, Charsets.UTF_8))
        } catch (e: Exception) {
            repo.finishCommandFrom(
                commandId = cmd.commandId,
                state = com.autonomousone.messages.data.RemoteCommandEntity.STATE_FAILED,
                // A payload this device cannot parse: the reason is recorded rather than only thrown,
                // so the row explains itself even if the throw is swallowed a layer up.
                errorCode = com.autonomousone.messages.sync.SyncErrorCode.UNKNOWN.name,
                fromStates = listOf(
                    com.autonomousone.messages.data.RemoteCommandEntity.STATE_ACCEPTED,
                    com.autonomousone.messages.data.RemoteCommandEntity.STATE_EXECUTING
                )
            )
            throw IllegalArgumentException("corrupt SEND_SMS payload for ${cmd.commandId}", e)
        }
        val phone = payload.optString("phone")
        val body = payload.optString("body")
        val subId = if (payload.has("subscriptionId") && !payload.isNull("subscriptionId")) {
            payload.optInt("subscriptionId")
        } else null
        require(phone.isNotBlank() && body.isNotBlank()) {
            "SEND_SMS payload missing phone/body (${cmd.commandId})"
        }

        repo.markCommandState(
            cmd.commandId, RemoteCommandEntity.STATE_EXECUTING,
            listOf(RemoteCommandEntity.STATE_ACCEPTED)
        )
        val smsSender = SmsSender(com.autonomousone.messages.Holders.appContext)
        // §49: the row is the authority, the payload is a legacy fallback.
        //
        // There are two places the same key could come from, and a rule with two sources is a rule
        // that will disagree with itself. The row is written once at intake and never changes; the
        // payload is whatever the sender put there, including rows ingested by builds that did not
        // capture the key. Preferring the row and falling back to the payload keeps a single
        // precedence rule instead of two independent reads.
        val bubbleKey = cmd.clientMessageId
            ?: payload.optString("clientMessageId").takeIf { it.isNotBlank() }
        val outcome = smsSender.sendWithOutcome(
            phone = phone,
            text = body,
            subscriptionIdOverride = subId,
            smscOverride = null,
            showToast = false,
            originCommandId = cmd.commandId,
            clientMessageId = bubbleKey,
        )
        val terminal = if (outcome is SmsSender.SendOutcome.Accepted)
            RemoteCommandEntity.STATE_COMPLETED else RemoteCommandEntity.STATE_FAILED
        repo.finishCommandFrom(
            commandId = cmd.commandId,
            state = terminal,
            errorCode = if (outcome is SmsSender.SendOutcome.Accepted) null
            else com.autonomousone.messages.sync.SyncErrorCode.SMS_SEND_FAILED.name,
            fromStates = listOf(
                RemoteCommandEntity.STATE_EXECUTING,
                RemoteCommandEntity.STATE_ACCEPTED
            )
        )
        return true
    }
}
