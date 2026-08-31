package com.autonomousone.messages.repository

import androidx.room.withTransaction
import com.autonomousone.messages.data.GatewayEventOutboxDao
import com.autonomousone.messages.data.GatewayEventOutboxEntity
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.RemoteCommandDao
import com.autonomousone.messages.data.RemoteCommandEntity
import com.autonomousone.messages.data.RemoteConversationMapDao
import com.autonomousone.messages.data.RemoteConversationMapEntity
import com.autonomousone.messages.data.SyncCursorDao
import com.autonomousone.messages.data.SyncCursorEntity
import kotlin.math.min
import kotlin.random.Random

/**
 * PR-01 boundary over the durability tables (docs/adr/ADR-001..003). Deliberately
 * dumb: NO crypto, NO networking — upload/poll workers (PR-02/PR-09/PR-10) call
 * these suspend functions. The transactional claim below is the process-death
 * guarantee: a worker that dies between claim and upload leaves the row SENDING,
 * and [recoverSending] flips it back to PENDING on next start (Rule 4 / §16).
 */
class GatewaySyncRepository(
    private val db: MessagesDatabase,
    private val outboxDao: GatewayEventOutboxDao = db.gatewayEventOutboxDao(),
    private val commandDao: RemoteCommandDao = db.remoteCommandDao(),
    private val mapDao: RemoteConversationMapDao = db.remoteConversationMapDao(),
    private val cursorDao: SyncCursorDao = db.syncCursorDao()
) {

    /** LOCK 13 upload policy — pure math so the JVM tests can pin it. */
    object Policy {
        const val MAX_BATCH_EVENTS = 100
        const val MAX_BATCH_BYTES = 512 * 1024

        /** Full-jitter exponential backoff: uniform in [0, min(cap, base·2^attempt)). */
        const val BACKOFF_BASE_MS = 2_000L
        const val BACKOFF_CAP_MS = 5 * 60_000L

        fun backoffDelayMs(attempt: Int, random: Random): Long {
            val ceiling = min(BACKOFF_CAP_MS, BACKOFF_BASE_MS shl attempt.coerceIn(0, 20))
            return if (ceiling <= 1) 0 else random.nextLong(0, ceiling)
        }

        /** One upload batch: first [MAX_BATCH_EVENTS] rows up to [MAX_BATCH_BYTES] payload bytes. */
        data class Batch(val events: List<GatewayEventOutboxEntity>, val bytes: Long)

        fun selectBatch(candidates: List<GatewayEventOutboxEntity>): Batch {
            var bytes = 0L
            val events = ArrayList<GatewayEventOutboxEntity>(min(MAX_BATCH_EVENTS, candidates.size))
            for (event in candidates) {
                if (events.size == MAX_BATCH_EVENTS) break
                val size = event.ciphertext.size
                if (events.isNotEmpty() && bytes + size > MAX_BATCH_BYTES) break
                bytes += size
                events.add(event)
            }
            return Batch(events, bytes)
        }
    }

    /** Idempotent enqueue — re-committing the same eventUuid is a no-op. */
    suspend fun enqueueEvent(event: GatewayEventOutboxEntity): Boolean =
        outboxDao.insertOrIgnore(event) != -1L

    /** Transactional claim: mark exactly the selected batch SENDING, atomically. */
    suspend fun claimBatch(now: Long): List<GatewayEventOutboxEntity> =
        db.withTransaction {
            val selected = Policy.selectBatch(outboxDao.claimable(now, Policy.MAX_BATCH_EVENTS * 2)).events
            if (selected.isNotEmpty()) {
                outboxDao.markSending(selected.map { it.id })
            }
            selected
        }

    /** Partial ACK support (LOCK 13): each reported eventUuid advances alone. */
    /** Partial ACK (LOCK 13): only the reported eventUuid moves to ACKED. */
    suspend fun onAcked(eventUuid: String, serverSequence: Long, ackedAt: Long): Int =
        outboxDao.markAcked(eventUuid, serverSequence, ackedAt)

    suspend fun onRetry(eventUuid: String, attempt: Int, random: Random, now: Long) {
        outboxDao.markRetry(eventUuid, now + Policy.backoffDelayMs(attempt, random))
    }

    /** Permanent reject (schema/auth) — visible as DEAD_LETTER, never silently dropped. */
    suspend fun onDeadLetter(eventUuid: String) {
        outboxDao.markDead(eventUuid)
    }

    /** Startup recovery for the crash window between claim and ACK. */
    suspend fun recoverSending(): Int = outboxDao.resetSendingToPending()

    suspend fun pendingDepth(): Int = outboxDao.pendingDepth()
    suspend fun pendingBytes(): Long = outboxDao.pendingBytes()

    /** True = newly ingested; false = redelivery (exactly-once by unique index). */
    suspend fun ingestCommand(command: RemoteCommandEntity): Boolean =
        commandDao.insertOrIgnore(command) != -1L

    suspend fun markCommandAcceptedIfReceived(commandId: String): Boolean =
        commandDao.markAcceptedIfReceived(commandId) == 1

    /** Guarded lifecycle transition used by the send executor (PR-03). */
    suspend fun markCommandState(commandId: String, state: String, fromStates: List<String>): Boolean =
        commandDao.markState(commandId, state, fromStates) == 1

    /** Durable row lookup for honest redelivery ACKs (PR-10, §58). */
    suspend fun getCommand(commandId: String): RemoteCommandEntity? = commandDao.get(commandId)

    suspend fun mapOrGet(threadId: Long, conversationId: String): RemoteConversationMapEntity {
        mapDao.getByThreadId(threadId)?.let { return it }
        mapDao.insertOrIgnore(RemoteConversationMapEntity(conversationId, threadId, System.currentTimeMillis()))
        return mapDao.getByThreadId(threadId)!!
    }

    suspend fun cursor(direction: String): SyncCursorEntity? = cursorDao.get(direction)

    suspend fun saveCursor(cursor: SyncCursorEntity) = cursorDao.upsert(cursor)

    /**
     * TechSpec §12: the provider threadId ↔ opaque conversation UUID mapping
     * lives ONLY on Android (remote_conversation_map). Idempotent — returns
     * the existing mapping when present.
     */
    suspend fun ensureConversationIdForThread(threadId: Long): String {
        mapDao.getByThreadId(threadId)?.let { return it.conversationId }
        val conversationId = java.util.UUID.randomUUID().toString()
        mapDao.insertOrIgnore(
            RemoteConversationMapEntity(conversationId, threadId, System.currentTimeMillis())
        )
        // Lost an insert race? The winner's row is the mapping — read it back.
        return mapDao.getByThreadId(threadId)?.conversationId ?: conversationId
    }
}

