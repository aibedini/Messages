package com.autonomousone.messages.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * PR-01 durability foundation for the Messaging Platform (docs/adr/ADR-001..003,
 * TechSpec Phase 1). Five additive tables:
 *
 *  - [RemoteConversationMapEntity]  provider threadId ↔ opaque conversation UUID.
 *      The ONLY place this mapping exists; GMweb ever sees the UUID (TechSpec §12).
 *  - [GatewayEventOutboxEntity]     durable cloud outbox — no critical event lives
 *      in RAM (Rule 4); every outgoing webhook/event is committed here first.
 *  - [RemoteCommandEntity]          durable command inbox — commands are stored and
 *      deduped BEFORE execution (exactly-once, Rule: INSERT OR IGNORE + unique idempotency key).
 *  - [RemoteCommandExecutionEntity] one row per execution attempt; a command is never
 *      executed twice without two rows existing.
 *  - [SyncCursorEntity]             per-direction cursors (upload ACK watermark,
 *      command inbox cursor, trust log) so reconnects resume without rescans.
 *
 * CRYPTO-FRIENDLY PAYLOAD SCHEMA (PR-01 contract): payload columns are
 * `ciphertext` + `encoding` + `schemaVersion` + `cryptoVersion`. In PR-01
 * cryptoVersion=0 means "JSON plaintext bytes"; from Phase 7 the same columns
 * carry an opaque encrypted envelope and business code never learns its layout
 * (ADR-002). NOTHING in this file may assume the payload is readable text.
 */

/** Provider threadId ↔ opaque conversation UUID mapping (Android-only truth). */
@Entity(
    tableName = "remote_conversation_map",
    indices = [Index(value = ["threadId"], unique = true)]
)
data class RemoteConversationMapEntity(
    /** Opaque UUID shared with GMweb — never derived from the phone number. */
    @PrimaryKey val conversationId: String,
    /** Local telephony thread id (messages/conversations tables). */
    val threadId: Long,
    val createdAt: Long
)

@Dao
interface RemoteConversationMapDao {
    /** Idempotent mapping create — returns -1 when the UUID/thread already mapped. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(map: RemoteConversationMapEntity): Long

    @Query("SELECT * FROM remote_conversation_map WHERE conversationId = :conversationId")
    suspend fun getByConversationId(conversationId: String): RemoteConversationMapEntity?

    @Query("SELECT * FROM remote_conversation_map WHERE threadId = :threadId")
    suspend fun getByThreadId(threadId: Long): RemoteConversationMapEntity?

    @Query("SELECT * FROM remote_conversation_map ORDER BY createdAt")
    fun observeAll(): Flow<List<RemoteConversationMapEntity>>
}

/**
 * Durable cloud outbox for gateway events (TechSpec §15). Written in the SAME
 * Room transaction as the message row it describes (PR-02 wires the incoming
 * dispatcher); upload workers claim, ACK, retry or dead-letter rows.
 */
@Entity(
    tableName = "gateway_event_outbox",
    indices = [
        // Dedupe on eventUuid: a re-committed event can never double-queue.
        Index(value = ["eventUuid"], unique = true),
        // Claim query shape: due rows first, FIFO within a state.
        Index("state", "nextAttemptAt"),
        Index("aggregateId")
    ]
)
data class GatewayEventOutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventUuid: String,
    val eventType: String,
    /** Opaque aggregate reference (conversation UUID / message UUID). */
    val aggregateId: String,
    /** Device-local monotonic queue order (= insert order via autoincrement id). */
    val sequenceLocal: Long = 0,
    /** Opaque payload bytes — NEVER parsed by business code (see file KDoc). */
    val ciphertext: ByteArray,
    val encoding: String,
    val schemaVersion: Int,
    /** 0 = plaintext JSON payload (PR-01); ≥1 = encrypted envelope (Phase 7). */
    val cryptoVersion: Int = 0,
    val createdAt: Long,
    val attemptCount: Int = 0,
    val nextAttemptAt: Long = 0,
    val state: String = STATE_PENDING,
    val serverSequence: Long = 0,
    val ackedAt: Long = 0
) {
    companion object {
        const val STATE_PENDING = "PENDING"
        const val STATE_SENDING = "SENDING"
        const val STATE_ACKED = "ACKED"
        const val STATE_DEAD_LETTER = "DEAD_LETTER"
    }

    // ByteArray members force explicit equals/hashCode (data-class contract).
    override fun equals(other: Any?): Boolean = other is GatewayEventOutboxEntity && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

@Dao
interface GatewayEventOutboxDao {
    /** IGNORE: re-enqueueing a committed eventUuid is a no-op, not a duplicate. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(event: GatewayEventOutboxEntity): Long

    @Query("SELECT id FROM gateway_event_outbox WHERE eventUuid = :eventUuid")
    suspend fun idOf(eventUuid: String): Long?

    @Query(
        "SELECT * FROM gateway_event_outbox " +
            "WHERE state IN ('PENDING', 'SENDING') AND nextAttemptAt <= :now " +
            "ORDER BY id LIMIT :limit"
    )
    suspend fun claimable(now: Long, limit: Int): List<GatewayEventOutboxEntity>

    @Query(
        "UPDATE gateway_event_outbox SET state = 'SENDING' " +
            "WHERE id IN (:ids) AND state = 'PENDING'"
    )
    suspend fun markSending(ids: List<Long>): Int

    /** Partial ACK (LOCK 13): only the reported eventUuid moves to ACKED. */
    @Query(
        "UPDATE gateway_event_outbox SET state = 'ACKED', serverSequence = :serverSequence, " +
            "ackedAt = :ackedAt WHERE eventUuid = :eventUuid AND state = 'SENDING'"
    )
    suspend fun markAcked(eventUuid: String, serverSequence: Long, ackedAt: Long): Int

    /** Transport failure → back to PENDING with attemptCount+1 and a due time. */
    @Query(
        "UPDATE gateway_event_outbox SET state = 'PENDING', attemptCount = attemptCount + 1, " +
            "nextAttemptAt = :nextAttemptAt WHERE eventUuid = :eventUuid AND state = 'SENDING'"
    )
    suspend fun markRetry(eventUuid: String, nextAttemptAt: Long): Int

    /** Permanent schema/auth reject — never silently dropped (health alert reads this state). */
    @Query("UPDATE gateway_event_outbox SET state = 'DEAD_LETTER' WHERE eventUuid = :eventUuid")
    suspend fun markDead(eventUuid: String): Int

    /** Process-death recovery: crash between claim and upload/ACK leaves SENDING rows. */
    @Query("UPDATE gateway_event_outbox SET state = 'PENDING' WHERE state = 'SENDING'")
    suspend fun resetSendingToPending(): Int

    @Query("SELECT COUNT(*) FROM gateway_event_outbox WHERE state IN ('PENDING', 'SENDING')")
    suspend fun pendingDepth(): Int

    @Query(
        "SELECT COALESCE(SUM(LENGTH(ciphertext)), 0) FROM gateway_event_outbox " +
            "WHERE state IN ('PENDING', 'SENDING')"
    )
    suspend fun pendingBytes(): Long
}

/**
 * Durable remote command inbox (TechSpec §17/§18). Commands are verified then
 * COMMITTED here BEFORE any ACK to GMweb, and executed exactly once via
 * [RemoteCommandExecutionEntity] rows.
 */
@Entity(
    tableName = "remote_commands",
    indices = [
        // Idempotency (LOCK 4 / TechSpec §49): same idempotency key can never
        // enqueue twice — a redelivered command is a no-op INSERT.
        Index(value = ["idempotencyKey"], unique = true),
        Index("state", "receivedAt")
    ]
)
data class RemoteCommandEntity(
    @PrimaryKey val commandId: String,
    val type: String,
    /** Opaque payload bytes — decrypt/verify happens in later PRs, never here. */
    val ciphertext: ByteArray,
    val encoding: String,
    val schemaVersion: Int,
    val cryptoVersion: Int = 0,
    /** Client signature bytes, stored opaque until PR-08 verification lands. */
    val signature: ByteArray = ByteArray(0),
    val senderDeviceId: String = "",
    val issuedAt: Long = 0,
    val receivedAt: Long,
    val expiresAt: Long = 0,
    val nonce: String = "",
    val idempotencyKey: String,
    val state: String = STATE_RECEIVED
) {
    companion object {
        const val STATE_RECEIVED = "RECEIVED"
        const val STATE_ACCEPTED = "ACCEPTED"
        const val STATE_EXECUTING = "EXECUTING"
        const val STATE_COMPLETED = "COMPLETED"
        const val STATE_FAILED = "FAILED"
        const val STATE_EXPIRED = "EXPIRED"
    }

    override fun equals(other: Any?): Boolean = other is RemoteCommandEntity && other.commandId == commandId
    override fun hashCode(): Int = commandId.hashCode()
}

@Dao
interface RemoteCommandDao {
    /** Returns rowId for a NEW command, -1 when commandId/idempotencyKey is a redelivery. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(command: RemoteCommandEntity): Long

    /** Atomic single-owner claim: only one caller can move RECEIVED→ACCEPTED. */
    @Query("UPDATE remote_commands SET state = 'ACCEPTED' WHERE commandId = :commandId AND state = 'RECEIVED'")
    suspend fun markAcceptedIfReceived(commandId: String): Int

    /** Guarded transition — the WHERE clause enforces the legal-from set. */
    @Query(
        "UPDATE remote_commands SET state = :state " +
            "WHERE commandId = :commandId AND state IN (:fromStates)"
    )
    suspend fun markState(commandId: String, state: String, fromStates: List<String>): Int

    @Query("SELECT * FROM remote_commands WHERE commandId = :commandId")
    suspend fun get(commandId: String): RemoteCommandEntity?

    @Query("SELECT COUNT(*) FROM remote_commands WHERE state = 'RECEIVED'")
    suspend fun inboxDepth(): Int

    /** Expire commands that were never picked up before their expires_at. */
    @Query(
        "UPDATE remote_commands SET state = 'EXPIRED' " +
            "WHERE state = 'RECEIVED' AND expiresAt > 0 AND expiresAt < :now"
    )
    suspend fun expireStale(now: Long): Int
}

/** One row per execution attempt — the audit trail that makes exactly-once provable. */
@Entity(
    tableName = "remote_command_executions",
    indices = [Index("commandId")]
)
data class RemoteCommandExecutionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val commandId: String,
    val attempt: Int,
    val startedAt: Long,
    val finishedAt: Long = 0,
    val result: String = ""
)

@Dao
interface RemoteCommandExecutionDao {
    @Insert
    suspend fun insert(execution: RemoteCommandExecutionEntity): Long

    @Query(
        "UPDATE remote_command_executions SET finishedAt = :finishedAt, result = :result WHERE id = :id"
    )
    suspend fun finish(id: Long, finishedAt: Long, result: String)

    @Query("SELECT COUNT(*) FROM remote_command_executions WHERE commandId = :commandId")
    suspend fun countFor(commandId: String): Int
}

/**
 * Per-direction sync cursor (upload ACK watermark, command inbox cursor, trust
 * log cursor). Separate from `sync_state` (telephony watermarks) on purpose:
 * gateway cursors advance with SERVER sequences, not provider dates.
 */
@Entity(tableName = "sync_cursors")
data class SyncCursorEntity(
    @PrimaryKey val direction: String,
    val lastSequence: Long = 0,
    val lastServerAck: Long = 0,
    val updatedAt: Long = 0
) {
    companion object {
        const val DIRECTION_EVENT_UPLOAD = "eventUpload"
        const val DIRECTION_COMMAND_INBOX = "commandInbox"
        const val DIRECTION_TRUST_LOG = "trustLog"
    }
}

@Dao
interface SyncCursorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cursor: SyncCursorEntity)

    @Query("SELECT * FROM sync_cursors WHERE direction = :direction")
    suspend fun get(direction: String): SyncCursorEntity?
}
