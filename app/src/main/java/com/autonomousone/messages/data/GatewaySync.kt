package com.autonomousone.messages.data

import androidx.room.Dao
import androidx.room.ColumnInfo
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
        Index("state", "priority", "nextAttemptAt"),
        Index("historySource", "historyGeneration", "historyOrdinal"),
        Index("aggregateId")
    ]
)
data class GatewayEventOutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventUuid: String,
    val eventType: String,
    /** Opaque aggregate reference (conversation UUID / message UUID). */
    val aggregateId: String,
    /** Opaque state identity and ordering metadata; never contains provider IDs or PII. */
    val messageId: String = "",
    val revision: Long = 1,
    val sortKey: Long = 0,
    val priority: String = PRIORITY_REALTIME,
    val historySource: String = "",
    val historyGeneration: Long = 0,
    val historyOrdinal: Long = 0,
    val historyDate: Long = 0,
    val historyProviderId: Long = 0,
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
    val ackedAt: Long = 0,
    /** Safe failure metadata. Existing rows migrate with null = unavailable. */
    val failureCategory: String? = null,
    val failureHttpStatus: Int? = null,
    val lastAttemptAt: Long? = null,
    val deadLetteredAt: Long? = null,
    val failureAppVersion: String? = null,

    // ── Outbox V2 (schema 19) ───────────────────────────────────────────────
    /**
     * Where this event came from: REALTIME | HISTORY | RECONCILIATION | COMMAND_RESULT |
     * STATUS_UPDATE (mission §10).
     *
     * Nullable because rows written before schema 19 genuinely have no recorded origin, and
     * guessing one would be inventing history.
     */
    val source: String? = null,
    /**
     * The lease owner while the row is IN_FLIGHT (mission §12).
     *
     * Null whenever the row is not in flight. A row whose lease has expired is returned to
     * PENDING by [GatewayEventOutboxDao.recoverStaleLeases], which is what stops a crash between
     * claim and upload from stranding a row forever.
     */
    val leaseId: String? = null,
    /** When the current lease was taken. The age of this is what "stale" is measured from. */
    val inFlightSince: Long? = null,
    /** The upload batch that last carried this row, for correlating a failure to a batch. */
    val batchId: String? = null,
    /**
     * The LAST HTTP status and structured outcome, recorded on every retry — not only on
     * dead-lettering. Without these, "why is this row stuck?" has no answer on the row itself.
     */
    val lastHttpStatus: Int? = null,
    val lastErrorCode: String? = null,
    val lastErrorMessageSafe: String? = null,
    /**
     * Which key encrypted the payload, so rotation can be handled without re-reading the
     * envelope (mission §42). Left null until the enqueue path can supply it.
     */
    val keyRef: String? = null
) {
    companion object {
        const val PRIORITY_REALTIME = "REALTIME"
        const val PRIORITY_BACKFILL = "BACKFILL"

        /**
         * Delivery priorities (mission §13).
         *
         * `BACKFILL` **is** the mission's HISTORY level: the stored value is kept because it
         * appears in @Query literals and in the enqueue path, and renaming it would be a wide
         * mechanical change whose failure mode is a silently empty result set.
         *
         * The levels matter because they decide who waits. Before this existed there were two
         * values and a strict REALTIME-before-everything ordering with a 200-row candidate cap,
         * so a device with sustained realtime traffic NEVER uploaded history — a 360k-message
         * backfill could stall indefinitely (audit Blocker 11).
         */
        const val PRIORITY_COMMAND_RESULT = "COMMAND_RESULT"
        const val PRIORITY_STATUS_UPDATE = "STATUS_UPDATE"
        const val PRIORITY_RECONCILIATION = "RECONCILIATION"

        /**
         * Which flow produced an event (mission §10).
         *
         * Distinct from [priority], which says how URGENT it is. They usually coincide, but not
         * always: a status update is a STATUS_UPDATE in origin yet may be REALTIME in urgency.
         * Keeping them apart is what lets a diagnostic answer "where did this come from?" without
         * inferring it from timing.
         */
        const val SOURCE_REALTIME = "REALTIME"
        const val SOURCE_HISTORY = "HISTORY"
        const val SOURCE_RECONCILIATION = "RECONCILIATION"
        const val SOURCE_COMMAND_RESULT = "COMMAND_RESULT"
        const val SOURCE_STATUS_UPDATE = "STATUS_UPDATE"

        val EVENT_SOURCES: List<String> = listOf(
            SOURCE_REALTIME, SOURCE_HISTORY, SOURCE_RECONCILIATION,
            SOURCE_COMMAND_RESULT, SOURCE_STATUS_UPDATE
        )

        /** Every priority, highest first. The order IS the weight order. */
        val DELIVERY_PRIORITIES: List<String> = listOf(
            PRIORITY_REALTIME,
            PRIORITY_COMMAND_RESULT,
            PRIORITY_STATUS_UPDATE,
            PRIORITY_BACKFILL,
            PRIORITY_RECONCILIATION
        )

        /** The mission's weights, for diagnostics and tests. */
        fun weightOf(priority: String): Int = when (priority) {
            PRIORITY_REALTIME -> 100
            PRIORITY_COMMAND_RESULT -> 90
            PRIORITY_STATUS_UPDATE -> 80
            PRIORITY_BACKFILL -> 20
            PRIORITY_RECONCILIATION -> 10
            // An unrecognised value is treated as middle-weight rather than last: something we do
            // not understand must not be starved, and must not outrank a user-visible message.
            else -> 50
        }

        /**
         * Foreground = work a person is waiting for. Background = catching up on the past.
         *
         * Fair batching guarantees the background group a share of every batch, which is what
         * stops history being starved by continuous realtime traffic.
         */
        val FOREGROUND_PRIORITIES: List<String> = listOf(
            PRIORITY_REALTIME, PRIORITY_COMMAND_RESULT, PRIORITY_STATUS_UPDATE
        )
        val BACKGROUND_PRIORITIES: List<String> = listOf(
            PRIORITY_BACKFILL, PRIORITY_RECONCILIATION
        )

        fun isBackground(priority: String): Boolean = priority in BACKGROUND_PRIORITIES

        // The SQL mirrors of the two lists above. Kept as constants so the DAO and a test can
        // share them; a test asserts every member of the Kotlin lists appears in both, which is
        // what stops the SQL and the model drifting apart.
        //
        // All three foreground values are named explicitly rather than leaning on ELSE: an
        // implicit fallback would silently mis-order the group if the list were ever reordered.
        const val FOREGROUND_PRIORITIES_SQL =
            "'" + PRIORITY_REALTIME + "', '" + PRIORITY_COMMAND_RESULT + "', '" + PRIORITY_STATUS_UPDATE + "'"
        const val BACKGROUND_PRIORITIES_SQL =
            "'" + PRIORITY_BACKFILL + "', '" + PRIORITY_RECONCILIATION + "'"
        const val FOREGROUND_ORDER_SQL =
            "CASE priority " +
                "WHEN '" + PRIORITY_REALTIME + "' THEN 0 " +
                "WHEN '" + PRIORITY_COMMAND_RESULT + "' THEN 1 " +
                "WHEN '" + PRIORITY_STATUS_UPDATE + "' THEN 2 ELSE 3 END"
        const val BACKGROUND_ORDER_SQL =
            "CASE priority " +
                "WHEN '" + PRIORITY_BACKFILL + "' THEN 0 " +
                "WHEN '" + PRIORITY_RECONCILIATION + "' THEN 1 ELSE 2 END"

        /**
         * ACKED retention (mission §19).
         *
         * Deletes acknowledged rows older than the retention window — EXCEPT any history row that
         * is not yet behind its source's persisted acked watermark.
         *
         * That exception is the whole point and it is easy to miss. `advanceHistoryAckWatermarks`
         * walks ACKED history rows forward from `ackedContiguousOrdinal` and stops at the first
         * gap; if a row beyond the watermark were deleted, the walk would break immediately and
         * the watermark could NEVER advance again — history would sit at CATCHING_UP forever while
         * every event was in fact acknowledged. So a row is removable only when
         * `historyOrdinal <= ackedContiguousOrdinal`, i.e. the watermark has already moved past
         * it and nothing will ever read it again.
         *
         * `historyGeneration = 0` identifies non-history rows (realtime, command results, status
         * updates, reconciliation), which no watermark depends on. `ackedAt > 0` keeps rows
         * written before `ackedAt` existed, because their age cannot be established.
         *
         * Nothing is deleted for being old alone, nothing non-terminal is touched, and
         * DEAD_LETTER rows are never removed — they are the record that something failed.
         */
        const val PURGE_ACKED_SQL =
            "DELETE FROM gateway_event_outbox " +
                "WHERE state = 'ACKED' AND ackedAt > 0 AND ackedAt < :olderThan " +
                "AND (historyGeneration = 0 OR EXISTS (" +
                "SELECT 1 FROM cloud_history_checkpoint c " +
                "WHERE c.source = gateway_event_outbox.historySource " +
                "AND c.generation = gateway_event_outbox.historyGeneration " +
                "AND gateway_event_outbox.historyOrdinal <= c.ackedContiguousOrdinal))"

        /**
         * The failure codes a successful enrollment actually repairs (mission §15/§17).
         *
         * These name a broken CREDENTIAL or a missing IDENTITY — the two things `/identity` fixes.
         * They are the only dead letters the post-enrollment rescue may touch.
         *
         * `DEVICE_REVOKED` is deliberately absent, and this is the important omission: a 403 is the
         * server withdrawing authorization, not a credential that went stale. Resurrecting those
         * rows on every heartbeat-driven re-enrollment would upload work for a device the server
         * has withdrawn, which is exactly the "replication must stop, not retry" rule the code
         * itself carries. Contract failures (`INVALID_EVENT_SCHEMA`, `PAYLOAD_TOO_LARGE`,
         * `SERVER_4XX`) are absent for a different reason: the server has already judged the
         * EVENT, and a new credential does not change that verdict.
         *
         * Shared as a constant so the SQL test executes the shipped predicate and the source guard
         * can prove this is the list the query uses.
         */
        const val RESCUE_ENROLLMENT_CODES_SQL =
            "'AUTH_REQUIRED', 'AUTH_EXPIRED', 'IDENTITY_NOT_REGISTERED', 'CRYPTO_KEY_UNAVAILABLE'"

        /**
         * Post-enrollment rescue of dead letters (PR-11), narrowed and re-budgeted.
         *
         * WHY THE PREDICATE IS NOT JUST `state = 'DEAD_LETTER'`: the original hotfix reset EVERY
         * dead letter ever recorded, on every successful enrollment. Because a 401 now re-enrolls
         * the device (`ControlPlaneAuthPolicy`), that ran on every auth-recovery cycle, and it
         * resurrected rows the server had permanently rejected — 400/422 contract failures whose
         * verdict a new credential cannot change. Those rows are guaranteed to fail again, but
         * first they occupy claim slots in a batch that is bounded at 100 events / 512 KB, so each
         * doomed row displaces a live event. The reset also wiped `lastErrorCode`,
         * `failureHttpStatus`, `deadLetteredAt` and `lastErrorMessageSafe` — the columns that
         * answer "why is this event stuck?" — turning a permanent, explained failure into an
         * unexplained one. A rescue that erases the reason is worse than no rescue.
         *
         * WHY `attemptCount = 0`: `EventUploader` tests `exhausted(attemptCount)` BEFORE
         * `markRetry`/`markDead` increment it, so a dead letter sits at 26 or more. Leaving the
         * count untouched (as this query originally did, documenting it as a feature) meant a
         * rescued row was ALREADY over `MAX_ATTEMPTS`, so its very next batch failure — anything,
         * including a transient network blip — dead-lettered it again with zero retries. The
         * rescue was therefore a no-op for its own stated case. Zeroing the count grants the row
         * the full budget it is owed under a credential that demonstrably just worked.
         *
         * This cannot loop without bound: it runs only after a successful enrollment, which is
         * itself evidence that the credential was accepted, and only for codes a fresh credential
         * can repair. A row that exhausts again under a still-broken credential dead-letters
         * normally and is left alone by the existing rule.
         *
         * `nextAttemptAt = 0` makes the rescued rows immediately claimable instead of waiting out a
         * stale backoff of up to five minutes.
         *
         * Rows the predicate does NOT match keep their state AND their recorded reason, which is
         * what makes a permanently rejected event diagnosable on the row itself.
         */
        const val RESCUE_ENROLLMENT_SQL =
            "UPDATE gateway_event_outbox SET state = 'PENDING', attemptCount = 0, " +
                "nextAttemptAt = 0, failureCategory = NULL, failureHttpStatus = NULL, " +
                "deadLetteredAt = NULL, failureAppVersion = NULL, lastErrorCode = NULL, " +
                "lastErrorMessageSafe = NULL, leaseId = NULL, inFlightSince = NULL " +
                "WHERE state = 'DEAD_LETTER' AND (" +
                "lastErrorCode IN (" + RESCUE_ENROLLMENT_CODES_SQL + ") " +
                // Rows dead-lettered by a build that predates lastErrorCode still explain
                // themselves through the transport category and status.
                "OR (lastErrorCode IS NULL AND (" +
                "failureHttpStatus = 401 OR failureCategory IN ('HTTP_AUTH', 'TLS'))))"

        /**
         * One-time v3 rollout recovery (see the DAO).
         *
         * Filtered by crypto VERSION rather than by failure reason, which is deliberate and is the
         * one place that differs from [RESCUE_ENROLLMENT_SQL]: the cohort is "events emitted by a
         * protocol whose server contract was not live yet", so the version is the useful selector —
         * the reason the server gave was a symptom of the missing contract, not information about
         * the event. It is scoped to a single run by `cryptoV3DeadLettersRecovered`, which is what
         * keeps a version-shaped predicate from becoming a standing rule.
         *
         * `attemptCount = 0` for exactly the reason given on [RESCUE_ENROLLMENT_SQL], and it was
         * missing here too: `EventUploader` consults `exhausted(attemptCount)` BEFORE the
         * increment, so a dead letter is already past `MAX_ATTEMPTS` and its first post-rescue
         * failure would dead-letter it again with zero retries. Because this rescue consumes a
         * one-time flag, leaving the count alone made it permanently useless rather than merely
         * wasteful: the flag says "already recovered", so the rows could never be rescued again.
         */
        const val RESET_CRYPTO_DEAD_LETTER_SQL =
            "UPDATE gateway_event_outbox SET state = 'PENDING', attemptCount = 0, " +
                "nextAttemptAt = 0, failureCategory = NULL, failureHttpStatus = NULL, " +
                "deadLetteredAt = NULL, failureAppVersion = NULL, lastErrorCode = NULL, " +
                "lastErrorMessageSafe = NULL, leaseId = NULL, inFlightSince = NULL " +
                "WHERE state = 'DEAD_LETTER' AND cryptoVersion >= :minCryptoVersion"

        /**
         * Attach history ordinal metadata to a row that already exists (mission §33).
         *
         * `historyGeneration = 0` is the "no history metadata yet" marker. The guard means ordinals
         * are written ONCE and never rewritten: re-stamping a row with a new ordinal would leave
         * the old ordinal missing from the sequence, and `advanceHistoryAckWatermarks` stops at
         * the first gap — so a rewrite would stall the ack watermark permanently.
         *
         * Shared as a constant so a test can execute the real statement.
         */
        const val STAMP_HISTORY_SQL =
            "UPDATE gateway_event_outbox SET historySource = :source, " +
                "historyGeneration = :generation, historyOrdinal = :ordinal, " +
                "historyDate = :date, historyProviderId = :providerId " +
                "WHERE eventUuid = :eventUuid AND historyGeneration = 0"

        /**
         * The outbox state machine (mission §11).
         *
         * `PENDING` is the mission's READY — a row waiting to be claimed. `SENDING` is IN_FLIGHT:
         * claimed under a lease. `RETRY_WAIT` is a row that has FAILED and is waiting out its
         * backoff, which is a different fact from never having been tried: without it, that
         * distinction only exists implicitly in `nextAttemptAt`, where nothing can query or
         * display it.
         *
         * The storage names for READY/IN_FLIGHT are kept as they were because they appear in a
         * dozen @Query literals, and a rename would be a large mechanical change whose failure
         * mode is a silently empty result set. The mapping lives in one place — the sync layer's
         * `SyncDiagnosticsCollector` — and is asserted by tests.
         */
        const val STATE_PENDING = "PENDING"
        const val STATE_SENDING = "SENDING"
        const val STATE_RETRY_WAIT = "RETRY_WAIT"
        const val STATE_ACKED = "ACKED"
        const val STATE_DEAD_LETTER = "DEAD_LETTER"

        /** The two states a row can be claimed from. */
        const val CLAIMABLE_STATES_SQL = "'PENDING', 'RETRY_WAIT'"

        /** Everything still owned by the outbox: not yet ACKed and not dead. */
        const val OUTSTANDING_STATES_SQL = "'PENDING', 'RETRY_WAIT', 'SENDING'"

        /**
         * The exact-match predicate for "is this row under key `:keyId`?" (mission §42).
         *
         * A key id sits INSIDE a `live|history` value, so the obvious `LIKE :keyId || '%'` also matches
         * a different key whose id merely starts the same way — a silent over-count that would have a
         * rotation re-encrypt rows it never claimed to touch. Matching on the delimiter makes a prefix
         * a non-match by construction.
         *
         * The `:keyId <> ''` guard is not decoration: without it an empty argument matches a row whose
         * `keyRef` is the empty string, and "the key called nothing" is not a key. `EventKeyRef.encode`
         * cannot produce one and `parse` refuses one, so the guard exists to make the query safe even
         * if some future writer does.
         *
         * Shared with the SQL test so the tested predicate IS the shipped one.
         */
        const val KEY_REF_MATCH_SQL =
            "(:keyId <> '' AND (" +
                "keyRef = :keyId OR " +
                "keyRef LIKE :keyId || '|%' OR " +
                "keyRef LIKE '%|' || :keyId))"

        /**
         * The history walk's read (mission §19/§21).
         *
         * `ORDER BY historyOrdinal` is load-bearing, not cosmetic: the walk stops at the first
         * non-contiguous row, so an unordered read would stop at an arbitrary place and freeze the
         * frontier. The `> :afterOrdinal` bound is what makes it resumable.
         */
        const val HISTORY_AFTER_SQL =
            "SELECT * FROM gateway_event_outbox WHERE historySource = :source " +
                "AND historyGeneration = :generation AND historyOrdinal > :afterOrdinal " +
                "ORDER BY historyOrdinal LIMIT :limit"
    }

    // ByteArray members force explicit equals/hashCode (data-class contract).
    override fun equals(other: Any?): Boolean = other is GatewayEventOutboxEntity && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

data class GatewayEventDiagnosticCount(
    val eventType: String,
    val state: String,
    val cryptoVersion: Int,
    @ColumnInfo(name = "count") val count: Int
)

/**
 * The outbox as counts per lifecycle state (mission §56).
 *
 * AGGREGATE ONLY: no ids, no payloads. The state names are the Room ones (`PENDING`/`SENDING`/
 * `ACKED`/`DEAD_LETTER`), not the mission's `READY`/`IN_FLIGHT`/`RETRY_WAIT` vocabulary, because
 * this maps the storage and the mapping to the mission's state machine belongs in the sync layer
 * where it can be tested.
 *
 * `SUM` over an empty table is NULL and Room reads it as 0, which is why these are non-null.
 */
data class GatewayOutboxStateSummary(
    @ColumnInfo(name = "readyCount") val readyCount: Int,
    @ColumnInfo(name = "retryWaitCount") val retryWaitCount: Int,
    @ColumnInfo(name = "inFlightCount") val inFlightCount: Int,
    @ColumnInfo(name = "ackedCount") val ackedCount: Int,
    @ColumnInfo(name = "deadLetterCount") val deadLetterCount: Int,
    /** Oldest `createdAt` among PENDING/SENDING rows, or null when nothing is waiting. */
    @ColumnInfo(name = "oldestPendingCreatedAt") val oldestPendingCreatedAt: Long?
)

/** The command inbox as counts per state (mission §56). Aggregate only. */
data class RemoteCommandSummary(
    @ColumnInfo(name = "received") val received: Int,
    @ColumnInfo(name = "claimed") val claimed: Int,
    @ColumnInfo(name = "executing") val executing: Int,
    @ColumnInfo(name = "completed") val completed: Int,
    @ColumnInfo(name = "failed") val failed: Int,
    @ColumnInfo(name = "expired") val expired: Int,
    @ColumnInfo(name = "lastReceivedAt") val lastReceivedAt: Long?
)

/**
 * An AGGREGATE row about dead-lettered outbox events (v3.4.7).
 *
 * Deliberately carries no payload: the point is to answer "are these historical leftovers or an
 * active defect?" without reading, logging or deleting a single event.
 *
 * Rows created before schema v18 have no failure metadata. New rows retain a safe category,
 * optional HTTP status and attempt timestamps; aggregate readers report older values as
 * unavailable rather than inventing them.
 */
data class DeadLetterBreakdownRow(
    val eventType: String,
    val cryptoVersion: Int,
    val priority: String,
    @ColumnInfo(name = "count") val count: Int,
    @ColumnInfo(name = "minAttempts") val minAttempts: Int,
    @ColumnInfo(name = "maxAttempts") val maxAttempts: Int,
    @ColumnInfo(name = "firstCreatedAt") val firstCreatedAt: Long,
    @ColumnInfo(name = "lastCreatedAt") val lastCreatedAt: Long,
    @ColumnInfo(name = "metadataCount") val metadataCount: Int = 0,
    @ColumnInfo(name = "lastDeadLetteredAt") val lastDeadLetteredAt: Long? = null
)

data class DeadLetterSummary(
    val count: Int = 0,
    val metadataCount: Int = 0,
    val lastDeadLetteredAt: Long? = null,
    val newLastHour: Int = 0,
    val newLast24Hours: Int = 0
)

/**
 * How many not-yet-uploaded events sit under one key (mission §42).
 *
 * `keyRef` is the canonical `live` or `live|history` form. Nothing sensitive: a key id is a UUID, and
 * no ciphertext is selected.
 */
data class OutboxKeyRefCount(
    @ColumnInfo(name = "keyRef") val keyRef: String?,
    @ColumnInfo(name = "count") val count: Int
)

@Dao
interface GatewayEventOutboxDao {
    /** IGNORE: re-enqueueing a committed eventUuid is a no-op, not a duplicate. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(event: GatewayEventOutboxEntity): Long

    @Query("SELECT id FROM gateway_event_outbox WHERE eventUuid = :eventUuid")
    suspend fun idOf(eventUuid: String): Long?

    /**
     * Which of these event ids already have a durable row, in ONE query (mission §35).
     *
     * The verification sweep asks this per page. The single-row [idOf] is correct for one row and
     * ruinous for a page: a point query per row turns a 360k-row sweep into 360k queries, and the
     * difference between an indexed range scan plus one lookup and 360k lookups is the difference
     * between a background pass and a battery complaint.
     *
     * Returns only the ids that exist, so a caller can build the existing-set directly.
     */
    @Query("SELECT eventUuid FROM gateway_event_outbox WHERE eventUuid IN (:eventUuids)")
    suspend fun existingEventIds(eventUuids: List<String>): List<String>

    @Query(
        "SELECT * FROM gateway_event_outbox " +
            "WHERE state IN (" + GatewayEventOutboxEntity.CLAIMABLE_STATES_SQL + ") " +
            "AND nextAttemptAt <= :now " +
            "ORDER BY CASE priority WHEN 'REALTIME' THEN 0 ELSE 1 END, id LIMIT :limit"
    )
    suspend fun claimable(now: Long, limit: Int): List<GatewayEventOutboxEntity>

    /**
     * Claimable rows in one fair-batching group (mission §13).
     *
     * The two groups are fetched separately so that a flood of realtime work cannot push history
     * out of the candidate window entirely. Fetching per group — rather than one global
     * `ORDER BY ... LIMIT` — is what makes the fairness possible at all: with a single 200-row
     * window, 200 due realtime rows meant no background row was ever seen, let alone sent.
     */
    @Query(
        "SELECT * FROM gateway_event_outbox " +
            "WHERE state IN (" + GatewayEventOutboxEntity.CLAIMABLE_STATES_SQL + ") " +
            "AND nextAttemptAt <= :now AND priority IN (" + GatewayEventOutboxEntity.FOREGROUND_PRIORITIES_SQL + ") " +
            "ORDER BY " + GatewayEventOutboxEntity.FOREGROUND_ORDER_SQL + ", id LIMIT :limit"
    )
    suspend fun claimableForeground(now: Long, limit: Int): List<GatewayEventOutboxEntity>

    @Query(
        "SELECT * FROM gateway_event_outbox " +
            "WHERE state IN (" + GatewayEventOutboxEntity.CLAIMABLE_STATES_SQL + ") " +
            "AND nextAttemptAt <= :now AND priority IN (" + GatewayEventOutboxEntity.BACKGROUND_PRIORITIES_SQL + ") " +
            "ORDER BY " + GatewayEventOutboxEntity.BACKGROUND_ORDER_SQL + ", id LIMIT :limit"
    )
    suspend fun claimableBackground(now: Long, limit: Int): List<GatewayEventOutboxEntity>

    /**
     * Claim rows under a lease (mission §12).
     *
     * Sets `leaseId` and `inFlightSince` together with the state, so "is this row in flight?" and
     * "since when?" can never disagree. Claimable from PENDING or RETRY_WAIT — a RETRY_WAIT row
     * whose due time has arrived is ready again.
     */
    @Query(
        "UPDATE gateway_event_outbox SET state = 'SENDING', leaseId = :leaseId, " +
            "inFlightSince = :at, lastAttemptAt = :at, batchId = :batchId " +
            "WHERE id IN (:ids) AND state IN (" + GatewayEventOutboxEntity.CLAIMABLE_STATES_SQL + ")"
    )
    suspend fun markSending(ids: List<Long>, leaseId: String, batchId: String, at: Long): Int

    /** Partial ACK (LOCK 13): only the reported eventUuid moves to ACKED. Releases the lease. */
    @Query(
        "UPDATE gateway_event_outbox SET state = 'ACKED', serverSequence = :serverSequence, " +
            "ackedAt = :ackedAt, leaseId = NULL, inFlightSince = NULL, " +
            "lastHttpStatus = :httpStatus, lastErrorCode = NULL, lastErrorMessageSafe = NULL " +
            "WHERE eventUuid = :eventUuid AND state = 'SENDING'"
    )
    suspend fun markAcked(
        eventUuid: String,
        serverSequence: Long,
        ackedAt: Long,
        httpStatus: Int?
    ): Int

    /**
     * A retryable failure: RETRY_WAIT with attemptCount+1, a due time, and the reason recorded.
     *
     * The reason is stored on the row rather than only logged, because "why is this row still
     * here?" is a question the row itself must be able to answer.
     */
    @Query(
        "UPDATE gateway_event_outbox SET state = 'RETRY_WAIT', attemptCount = attemptCount + 1, " +
            "nextAttemptAt = :nextAttemptAt, lastAttemptAt = :lastAttemptAt, " +
            "lastHttpStatus = :httpStatus, lastErrorCode = :errorCode, " +
            "lastErrorMessageSafe = :errorMessage, leaseId = NULL, inFlightSince = NULL " +
            "WHERE eventUuid = :eventUuid AND state = 'SENDING'"
    )
    suspend fun markRetry(
        eventUuid: String,
        nextAttemptAt: Long,
        lastAttemptAt: Long,
        httpStatus: Int?,
        errorCode: String,
        errorMessage: String?
    ): Int

    /** Permanent schema/auth reject — never silently dropped (health alert reads this state). */
    @Query(
        "UPDATE gateway_event_outbox SET state = 'DEAD_LETTER', " +
            "attemptCount = attemptCount + 1, failureCategory = :failureCategory, " +
            "failureHttpStatus = :httpStatus, lastAttemptAt = :at, deadLetteredAt = :at, " +
            "failureAppVersion = :appVersion, lastHttpStatus = :httpStatus, " +
            "lastErrorCode = :errorCode, lastErrorMessageSafe = :errorMessage, " +
            "leaseId = NULL, inFlightSince = NULL " +
            "WHERE eventUuid = :eventUuid AND state = 'SENDING'"
    )
    suspend fun markDead(
        eventUuid: String,
        failureCategory: String,
        httpStatus: Int?,
        at: Long,
        appVersion: String,
        errorCode: String,
        errorMessage: String?
    ): Int

    /**
     * Return leases that have expired (mission §12), so a crash between claim and upload cannot
     * strand a row in SENDING forever.
     *
     * `inFlightSince IS NULL` is included deliberately: a row left SENDING by a build that
     * predates leases has no timestamp to age out, and would otherwise be stuck permanently.
     *
     * This replaces the previous unbounded `resetSendingToPending`, which requeued EVERY SENDING
     * row regardless of age — including rows another claimant was actively uploading.
     */
    @Query(
        "UPDATE gateway_event_outbox SET state = 'PENDING', leaseId = NULL, inFlightSince = NULL " +
            "WHERE state = 'SENDING' AND (inFlightSince IS NULL OR inFlightSince < :staleBefore)"
    )
    suspend fun recoverStaleLeases(staleBefore: Long): Int

    /**
     * PR-11 hotfix: rescue rows dead-lettered by an enrollment race — a batch signed BEFORE
     * `/identity` completed, or while the Keystore was briefly unavailable, is a 401 the server
     * would accept once the credential exists. Called once right after a successful enroll.
     *
     * The predicate, the retry re-budget and the reasons `DEVICE_REVOKED` and contract failures are
     * excluded all live on [GatewayEventOutboxEntity.RESCUE_ENROLLMENT_SQL]. It is a shared
     * constant so the SQL test executes the shipped statement rather than a copy of it.
     */
    @Query(GatewayEventOutboxEntity.RESCUE_ENROLLMENT_SQL)
    suspend fun resetDeadLetterToPending(): Int

    /**
     * One-time v3 rollout recovery; retry only events emitted by the new protocol.
     *
     * Why the predicate is version-shaped and why the retry budget is restored are both on
     * [GatewayEventOutboxEntity.RESET_CRYPTO_DEAD_LETTER_SQL].
     */
    @Query(GatewayEventOutboxEntity.RESET_CRYPTO_DEAD_LETTER_SQL)
    suspend fun resetCryptoDeadLetterToPending(minCryptoVersion: Int): Int

    /**
     * ACKED retention (mission §19): remove acknowledged rows older than [olderThan].
     *
     * The predicate — including the watermark exception that keeps history from stalling — is
     * documented on [GatewayEventOutboxEntity.PURGE_ACKED_SQL]. It is the only DELETE anywhere
     * near this table, and it can only ever match ACKED rows.
     */
    @Query(GatewayEventOutboxEntity.PURGE_ACKED_SQL)
    suspend fun purgeAckedBefore(olderThan: Long): Int

    /**
     * Attaches history-ordinal metadata to a row that ALREADY exists (mission §33).
     *
     * The history sweep and realtime detection now compute the same `eventUuid` for one message,
     * so when realtime won the race the sweep finds an existing row. It must not simply skip it:
     * `advanceHistoryAckWatermarks` walks ACKED history rows from `ackedContiguousOrdinal` and
     * stops at the first gap, so a row with no ordinal breaks that walk and the watermark can
     * never advance again — history would report CATCHING_UP forever while every event was in fact
     * acknowledged.
     *
     * `AND historyGeneration = 0` is the other half of that safety. Ordinals are written ONCE and
     * never rewritten: re-stamping a row with a new ordinal would leave the old ordinal missing
     * from the sequence, which stalls the same walk in a different way. A row that already carries
     * history metadata therefore matches nothing here, and the caller leaves the checkpoint alone.
     */
    @Query(GatewayEventOutboxEntity.STAMP_HISTORY_SQL)
    suspend fun stampHistoryMetadata(
        eventUuid: String,
        source: String,
        generation: Long,
        ordinal: Long,
        date: Long,
        providerId: Long
    ): Int

    @Query("SELECT COUNT(*) FROM gateway_event_outbox WHERE state IN ("+ GatewayEventOutboxEntity.OUTSTANDING_STATES_SQL +")")
    suspend fun pendingDepth(): Int

    /**
     * Rows currently claimed by an in-flight batch.
     *
     * `pendingDepth()` deliberately counts PENDING and SENDING together, which is the right
     * question for "is there work left?" but the wrong one for health: a row that has been
     * claimed for minutes is a STUCK upload, and the status card has to be able to say so.
     * Pending-only depth is `pendingDepth() - sendingDepth()`.
     */
    @Query("SELECT COUNT(*) FROM gateway_event_outbox WHERE state = 'SENDING'")
    suspend fun sendingDepth(): Int

    @Query("SELECT COUNT(*) FROM gateway_event_outbox WHERE priority = 'BACKFILL' AND state IN ("+ GatewayEventOutboxEntity.OUTSTANDING_STATES_SQL +")")
    suspend fun pendingBackfillDepth(): Int

    @Query("SELECT COUNT(*) FROM gateway_event_outbox WHERE priority = 'REALTIME' AND state IN ("+ GatewayEventOutboxEntity.OUTSTANDING_STATES_SQL +")")
    suspend fun pendingRealtimeDepth(): Int

    @Query("SELECT COUNT(*) FROM gateway_event_outbox WHERE state = 'DEAD_LETTER'")
    suspend fun deadLetterDepth(): Int

    /**
     * Every key that not-yet-uploaded events are encrypted under, with counts (mission §42).
     *
     * Grouped rather than filtered by a key id, deliberately: the caller asking "is a rotation in
     * progress?" does not know the id in advance, and more than one group among OUTSTANDING rows is
     * exactly the answer. Rows with no `keyRef` — signed key grants, and rows written before the
     * column was populated — are grouped under NULL rather than hidden, so the count cannot be
     * mistaken for the whole outstanding population.
     */
    @Query(
        "SELECT keyRef AS keyRef, COUNT(*) AS count FROM gateway_event_outbox " +
            "WHERE state IN (" + GatewayEventOutboxEntity.OUTSTANDING_STATES_SQL + ") " +
            "GROUP BY keyRef ORDER BY count DESC, keyRef ASC"
    )
    suspend fun outstandingKeyRefCounts(): List<OutboxKeyRefCount>

    /**
     * How many OUTSTANDING events are encrypted under [keyId], in either position (mission §42).
     *
     * The predicate is [GatewayEventOutboxEntity.KEY_REF_MATCH_SQL]; see it for why the match is
     * delimiter-based rather than a prefix, and why an empty key id is refused.
     */
    @Query(
        "SELECT COUNT(*) FROM gateway_event_outbox WHERE state IN (" +
            GatewayEventOutboxEntity.OUTSTANDING_STATES_SQL + ") AND " +
            GatewayEventOutboxEntity.KEY_REF_MATCH_SQL
    )
    suspend fun outstandingUnderKey(keyId: String): Int

    /**
     * Aggregate-only view of the dead-letter population (v3.4.7).
     *
     * READ-ONLY, and DEAD_LETTER rows are never deleted by anything. (The one DELETE in this DAO
     * is [purgeAckedBefore], which can only match state = 'ACKED'.) A dead-letter backlog of
     * a few hundred says nothing on its own — it may be a historical cohort from a rollout that
     * the server later accepted, or an active defect — and the way to tell is to look at the
     * SHAPE (which event types, which crypto versions, how many attempts, over what window)
     * before touching anything.
     *
     * No body and no ciphertext is selected, so nothing sensitive can reach a report.
     */
    @Query(
        """
        SELECT eventType AS eventType,
               cryptoVersion AS cryptoVersion,
               priority AS priority,
               COUNT(*) AS count,
               MIN(attemptCount) AS minAttempts,
               MAX(attemptCount) AS maxAttempts,
               MIN(createdAt) AS firstCreatedAt,
               MAX(createdAt) AS lastCreatedAt,
               SUM(CASE WHEN deadLetteredAt IS NOT NULL THEN 1 ELSE 0 END) AS metadataCount,
               MAX(deadLetteredAt) AS lastDeadLetteredAt
        FROM gateway_event_outbox
        WHERE state = 'DEAD_LETTER'
        GROUP BY eventType, cryptoVersion, priority
        ORDER BY count DESC, eventType ASC
        """
    )
    suspend fun deadLetterBreakdown(): List<DeadLetterBreakdownRow>

    @Query(
        "SELECT COUNT(*) AS count, " +
            "SUM(CASE WHEN deadLetteredAt IS NOT NULL THEN 1 ELSE 0 END) AS metadataCount, " +
            "MAX(deadLetteredAt) AS lastDeadLetteredAt, " +
            "SUM(CASE WHEN deadLetteredAt >= :lastHour THEN 1 ELSE 0 END) AS newLastHour, " +
            "SUM(CASE WHEN deadLetteredAt >= :last24Hours THEN 1 ELSE 0 END) AS newLast24Hours " +
            "FROM gateway_event_outbox WHERE state = 'DEAD_LETTER'"
    )
    suspend fun deadLetterSummary(lastHour: Long, last24Hours: Long): DeadLetterSummary

    @Query(
        "SELECT eventType, state, cryptoVersion, COUNT(*) AS count FROM gateway_event_outbox " +
            "GROUP BY eventType, state, cryptoVersion ORDER BY eventType, state, cryptoVersion"
    )
    suspend fun diagnosticCounts(): List<GatewayEventDiagnosticCount>

    @Query("SELECT COALESCE(MAX(serverSequence), 0) FROM gateway_event_outbox WHERE state = 'ACKED'")
    suspend fun maxAckedServerSequence(): Long

    @Query(
        "SELECT COALESCE(SUM(LENGTH(ciphertext)), 0) FROM gateway_event_outbox " +
            "WHERE state IN (" + GatewayEventOutboxEntity.OUTSTANDING_STATES_SQL + ")"
    )
    suspend fun pendingBytes(): Long

    /**
     * The outbox as counts per lifecycle state, plus the age of the oldest waiting event.
     *
     * Read-only and aggregate-only: no payload, no id, nothing that could carry content.
     * Distinguishes READY from RETRY_WAIT (`PENDING` before and after its due time) because
     * "waiting to be claimed" and "waiting out a backoff" are different facts, and `sendingDepth`
     * counts the in-flight rows that a crash-recovery pass will requeue.
     *
     * `oldestPendingCreatedAt` is what answers "is the queue draining?" — a count alone looks
     * identical whether it is moving or stuck.
     */
    @Query(
        "SELECT " +
            "SUM(CASE WHEN state = 'PENDING' AND nextAttemptAt <= :now THEN 1 ELSE 0 END) AS readyCount, " +
            "SUM(CASE WHEN state = 'RETRY_WAIT' THEN 1 ELSE 0 END) AS retryWaitCount, " +
            "SUM(CASE WHEN state = 'SENDING' THEN 1 ELSE 0 END) AS inFlightCount, " +
            "SUM(CASE WHEN state = 'ACKED' THEN 1 ELSE 0 END) AS ackedCount, " +
            "SUM(CASE WHEN state = 'DEAD_LETTER' THEN 1 ELSE 0 END) AS deadLetterCount, " +
            "MIN(CASE WHEN state IN (" + GatewayEventOutboxEntity.OUTSTANDING_STATES_SQL +
                ") THEN createdAt END) AS oldestPendingCreatedAt " +
            "FROM gateway_event_outbox"
    )
    suspend fun stateSummary(now: Long): GatewayOutboxStateSummary

    /**
     * The history rows at or after a frontier, in ordinal order (mission §19/§21).
     *
     * A shared constant so a JVM SQL test runs the SHIPPED text rather than a retyped copy: the
     * ordering and the `historyOrdinal > :afterOrdinal` bound ARE the walk's correctness, and a test
     * that retypes them keeps passing while the DAO changes under it.
     */
    @Query(GatewayEventOutboxEntity.HISTORY_AFTER_SQL)
    suspend fun historyAfter(
        source: String,
        generation: Long,
        afterOrdinal: Long,
        limit: Int
    ): List<GatewayEventOutboxEntity>

    @Query(
        "SELECT COUNT(*) FROM gateway_event_outbox WHERE historySource = :source " +
            "AND historyGeneration = :generation AND state = 'DEAD_LETTER'"
    )
    suspend fun historyDeadLetters(source: String, generation: Long): Int
}

@Entity(tableName = "cloud_history_checkpoint")
data class CloudHistoryCheckpointEntity(
    @PrimaryKey val source: String,
    val generation: Long,
    val producerCursorDate: Long,
    val producerCursorProviderId: Long,
    val nextOrdinal: Long,
    val ackedContiguousOrdinal: Long,
    val ackedCursorDate: Long,
    val ackedCursorProviderId: Long,
    val sourceExhausted: Boolean,
    val updatedAt: Long,
)

@Dao
interface CloudHistoryCheckpointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: CloudHistoryCheckpointEntity)

    @Query("SELECT * FROM cloud_history_checkpoint WHERE source = :source")
    suspend fun get(source: String): CloudHistoryCheckpointEntity?

    @Query("SELECT * FROM cloud_history_checkpoint ORDER BY source")
    suspend fun all(): List<CloudHistoryCheckpointEntity>
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
    val state: String = STATE_RECEIVED,

    // ── Command V2 (schema 20) ──────────────────────────────────────────────
    /**
     * How many times execution has been attempted (mission §45/§48).
     *
     * `@ColumnInfo(defaultValue = "0")` is load-bearing, not decoration: a Kotlin default is NOT a
     * SQL default, so without it Room generates `attemptCount INTEGER NOT NULL` with no DEFAULT —
     * and `ALTER TABLE ... ADD COLUMN ... NOT NULL` with no default is rejected by SQLite outright,
     * so every existing command row would fail to migrate. Declaring the default makes the entity
     * and the migration agree by construction.
     */
    @ColumnInfo(defaultValue = "0")
    val attemptCount: Int = 0,
    /**
     * The lease owner while a command is CLAIMED (mission §48).
     *
     * `leaseExpiresAt` is what makes a command recoverable: without it, a process death between
     * claim and execution left the row stuck forever and a redelivery got no ACK, so GMweb's ledger
     * hung (`docs/gateway-replication-audit.md`, Blocker 15).
     */
    val leaseId: String? = null,
    val leaseExpiresAt: Long? = null,
    val claimedAt: Long? = null,
    val executedAt: Long? = null,
    val completedAt: Long? = null,
    /** The canonical id of the event carrying this command's result. */
    val resultEventId: String? = null,
    /** A structured `SyncErrorCode` name, so a failure is machine-readable. */
    val lastErrorCode: String? = null,
    /** The web's optimistic-bubble key, preserved end to end (mission §49). */
    val clientMessageId: String? = null
) {
    companion object {
        const val STATE_RECEIVED = "RECEIVED"
        const val STATE_ACCEPTED = "ACCEPTED"
        const val STATE_EXECUTING = "EXECUTING"
        const val STATE_COMPLETED = "COMPLETED"
        const val STATE_FAILED = "FAILED"
        const val STATE_EXPIRED = "EXPIRED"

        /** States that will never change again. */
        val TERMINAL_STATES = listOf(STATE_COMPLETED, STATE_FAILED, STATE_EXPIRED)

        /** States a drain still has to resolve, in the order a drain should consider them. */
        val NON_TERMINAL_STATES = listOf(STATE_RECEIVED, STATE_ACCEPTED, STATE_EXECUTING)

        /**
         * How long a claim may be held before the command is presumed abandoned (mission §48).
         *
         * Five minutes, matching the outbox lease. Long enough for a slow send, short enough that a
         * process death does not strand a command for the life of the install.
         */
        const val DEFAULT_LEASE_TIMEOUT_MS = 5 * 60_000L
    }

    override fun equals(other: Any?): Boolean = other is RemoteCommandEntity && other.commandId == commandId
    override fun hashCode(): Int = commandId.hashCode()
}

@Dao
interface RemoteCommandDao {
    /** Returns rowId for a NEW command, -1 when commandId/idempotencyKey is a redelivery. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(command: RemoteCommandEntity): Long

    /**
     * Atomic single-owner claim under a lease (mission §48).
     *
     * Only one caller can move RECEIVED→ACCEPTED, and the lease records WHO holds it and until
     * when. `attemptCount` increments here, so a claim that is never completed is still counted.
     */
    @Query(
        "UPDATE remote_commands SET state = 'ACCEPTED', leaseId = :leaseId, " +
            "leaseExpiresAt = :leaseExpiresAt, claimedAt = :at, attemptCount = attemptCount + 1 " +
            "WHERE commandId = :commandId AND state = 'RECEIVED'"
    )
    suspend fun markAcceptedIfReceived(
        commandId: String,
        leaseId: String,
        leaseExpiresAt: Long,
        at: Long
    ): Int

    /**
     * Return commands whose lease has expired to RECEIVED (mission §48).
     *
     * A process death mid-execution leaves a command CLAIMED. Without this it stays claimed
     * forever, and every redelivery is answered with silence — so GMweb's ledger never resolves.
     *
     * This only makes the row claimable again; it does NOT decide that the send did not happen.
     * Duplicate protection belongs to execution (mission §47), not to lease recovery.
     */
    @Query(
        "UPDATE remote_commands SET state = 'RECEIVED', leaseId = NULL, leaseExpiresAt = NULL " +
            "WHERE state IN ('ACCEPTED', 'EXECUTING') " +
            "AND leaseExpiresAt IS NOT NULL AND leaseExpiresAt < :now"
    )
    suspend fun reclaimExpiredLeases(now: Long): Int

    /** Commands a drain still has to resolve, oldest first (mission §46). */
    @Query(
        "SELECT * FROM remote_commands WHERE state IN ('RECEIVED', 'ACCEPTED', 'EXECUTING') " +
            "ORDER BY receivedAt ASC LIMIT :limit"
    )
    suspend fun nonTerminal(limit: Int): List<RemoteCommandEntity>

    @Query("SELECT COUNT(*) FROM remote_commands WHERE state IN ('RECEIVED', 'ACCEPTED', 'EXECUTING')")
    suspend fun nonTerminalDepth(): Int

    /** Records a terminal outcome with its structured reason. */
    @Query(
        "UPDATE remote_commands SET state = :state, completedAt = :at, lastErrorCode = :errorCode, " +
            "leaseId = NULL, leaseExpiresAt = NULL WHERE commandId = :commandId"
    )
    suspend fun markFinished(
        commandId: String,
        state: String,
        at: Long,
        errorCode: String?
    ): Int

    /** Stamps the execution start, so "how long did this take?" has an answer. */
    @Query("UPDATE remote_commands SET state = 'EXECUTING', executedAt = :at WHERE commandId = :commandId")
    suspend fun markExecuting(commandId: String, at: Long): Int

    /** Stamps the event that carries this command's result. */
    @Query("UPDATE remote_commands SET resultEventId = :eventId WHERE commandId = :commandId")
    suspend fun setResultEventId(commandId: String, eventId: String): Int

    /**
     * Backfill the web's optimistic-bubble key on a row ingested before it was captured (§49).
     *
     * Conditional on the column being empty, deliberately: a key this device already recorded is
     * the one it committed to, and a later redelivery must never be able to replace it. That makes
     * this safe to call on every redelivery, which is what lets a row ingested by an older build
     * heal instead of waiting out its 24h expiry.
     */
    @Query(
        "UPDATE remote_commands SET clientMessageId = :clientMessageId " +
            "WHERE commandId = :commandId AND (clientMessageId IS NULL OR clientMessageId = '')"
    )
    suspend fun setClientMessageIdIfMissing(commandId: String, clientMessageId: String): Int

    /** Guarded transition — the WHERE clause enforces the legal-from set. */
    @Query(
        "UPDATE remote_commands SET state = :state " +
            "WHERE commandId = :commandId AND state IN (:fromStates)"
    )
    suspend fun markState(commandId: String, state: String, fromStates: List<String>): Int

    @Query("SELECT * FROM remote_commands WHERE commandId = :commandId")
    suspend fun get(commandId: String): RemoteCommandEntity?

    /** Redelivery path for the unified send queue: surface the existing row. */
    @Query("SELECT * FROM remote_commands WHERE idempotencyKey = :idempotencyKey LIMIT 1")
    suspend fun getByIdempotencyKey(idempotencyKey: String): RemoteCommandEntity?

    @Query("SELECT COUNT(*) FROM remote_commands WHERE state = 'RECEIVED'")
    suspend fun inboxDepth(): Int

    /**
     * The command inbox as counts per state (mission §56).
     *
     * Read-only. `claimed` is ACCEPTED — the state a command reaches when a single owner has
     * taken it (`markAcceptedIfReceived`). `lastType` is a command TYPE such as `SEND_SMS`, never
     * a payload.
     */
    @Query(
        "SELECT " +
            "SUM(CASE WHEN state = 'RECEIVED' THEN 1 ELSE 0 END) AS received, " +
            "SUM(CASE WHEN state = 'ACCEPTED' THEN 1 ELSE 0 END) AS claimed, " +
            "SUM(CASE WHEN state = 'EXECUTING' THEN 1 ELSE 0 END) AS executing, " +
            "SUM(CASE WHEN state = 'COMPLETED' THEN 1 ELSE 0 END) AS completed, " +
            "SUM(CASE WHEN state = 'FAILED' THEN 1 ELSE 0 END) AS failed, " +
            "SUM(CASE WHEN state = 'EXPIRED' THEN 1 ELSE 0 END) AS expired, " +
            "MAX(receivedAt) AS lastReceivedAt " +
            "FROM remote_commands"
    )
    suspend fun summary(): RemoteCommandSummary

    @Query("SELECT type FROM remote_commands ORDER BY receivedAt DESC LIMIT 1")
    suspend fun lastCommandType(): String?

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
