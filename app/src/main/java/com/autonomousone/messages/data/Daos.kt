package com.autonomousone.messages.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("""
        SELECT * FROM messages
        WHERE source = :source AND (date < :beforeDate OR (date = :beforeDate AND providerId < :beforeId))
        ORDER BY date DESC, providerId DESC LIMIT :limit
    """)
    suspend fun cloudHistoryPage(source: String, beforeDate: Long, beforeId: Long, limit: Int): List<MessageEntity>

    /** Newest-first window of one conversation (the hot read path). */
    @Query(
        """
        SELECT * FROM messages
        WHERE threadId = :threadId
        ORDER BY date DESC, providerId DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun pageForThread(threadId: Long, limit: Int, offset: Int): List<MessageEntity>

    /**
     * Hot-path newest window for instant-open. Same indexed scan as
     * pageForThread but with no OFFSET clause — the conversation open path
     * always wants offset 0, and an OFFSET of 0 makes SQLite planners (and
     * reviewers) ask unnecessary questions. Kept separate so a future
     * migration/rebuild that genuinely pages can still use pageForThread.
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE threadId = :threadId
        ORDER BY date DESC, providerId DESC
        LIMIT :limit
        """
    )
    suspend fun newestWindowForThread(threadId: Long, limit: Int): List<MessageEntity>

    /** Reactive tail for the open conversation (Room invalidation drives UI). */
    @Query(
        """
        SELECT * FROM messages
        WHERE threadId = :threadId
        ORDER BY date DESC, providerId DESC
        LIMIT :limit
        """
    )
    fun observeThread(threadId: Long, limit: Int): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT * FROM messages
        WHERE normalizedAddress = :address
        ORDER BY date DESC, providerId DESC
        LIMIT :limit
        """
    )
    suspend fun newestForAddress(address: String, limit: Int): List<MessageEntity>

    /**
     * Newest message per thread — used to rebuild conversations in one pass.
     * The old MAX(date)+GROUP BY form picked an ARBITRARY row among equal-date
     * ties (a send and its delivery receipt share a timestamp constantly);
     * the correlated rowid subquery makes the pick deterministic: date, then
     * direction (outgoing type=2 wins over incoming), then provider id.
     */
    @Query(
        """
        SELECT m.*
        FROM messages m
        WHERE m.rowid = (
            SELECT m2.rowid
            FROM messages m2
            WHERE m2.threadId = m.threadId
            ORDER BY m2.date DESC, m2.source DESC, m2.providerId DESC
            LIMIT 1
        )
        """
    )
    suspend fun newestPerThread(): List<MessageEntity>

    /** Deterministic single-thread newest row for projection rebuilds. */
    @Query(
        """
        SELECT *
        FROM messages
        WHERE threadId = :threadId
        ORDER BY date DESC, source DESC, providerId DESC
        LIMIT 1
        """
    )
    suspend fun newestForThread(threadId: Long): MessageEntity?

    @Query("SELECT MAX(date) FROM messages WHERE source = :source")
    suspend fun newestDateFor(source: String): Long?

    @Upsert
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(messages: List<MessageEntity>): List<Long>

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM messages WHERE source = :source")
    suspend fun countBySource(source: String): Int

    @Query("DELETE FROM messages WHERE threadId = :threadId")
    suspend fun deleteThread(threadId: Long)

    @Query("UPDATE messages SET read = 1 WHERE threadId = :threadId AND read = 0")
    suspend fun markThreadRead(threadId: Long)

    // ── NEW: SQL COUNT for unread (replaces O(n) in-memory scan) ──────────

    /** O(unread_count) via partial index, not O(total_messages_in_thread). */
    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE threadId = :threadId AND read = 0 AND type = 1
        """
    )
    suspend fun countUnread(threadId: Long): Int

    /**
     * PHASE 5: every thread's unread count in ONE aggregate query.
     *
     * The full projection rebuild used to call countUnread() once per
     * conversation (3N read queries for N conversations). This replaces N of
     * those with 1, so a rebuild's READ query count no longer grows with the
     * number of conversations - only its writes do, which is unavoidable.
     *
     * The predicate is deliberately identical to [countUnread].
     */
    @Query(
        """
        SELECT threadId AS threadId, COUNT(*) AS unreadCount
        FROM messages
        WHERE read = 0 AND type = 1
        GROUP BY threadId
        """
    )
    suspend fun unreadCountsByThread(): List<ThreadUnreadCount>

    /** Find a message by composite key (source, providerId) for delta calculation. */
    @Query(
        """
        SELECT * FROM messages
        WHERE source = :source AND providerId = :providerId
        LIMIT 1
        """
    )
    suspend fun findByKey(source: String, providerId: Long): MessageEntity?

    /** Delete a single message by composite key. */
    @Query(
        """
        DELETE FROM messages
        WHERE source = :source AND providerId = :providerId
        """
    )
    suspend fun deleteBySourceAndId(source: String, providerId: Long)
}

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations ORDER BY lastMessageDate DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE threadId = :threadId")
    suspend fun byThread(threadId: Long): ConversationEntity?

    @Query("SELECT * FROM conversations ORDER BY lastMessageDate DESC")
    suspend fun all(): List<ConversationEntity>

    @Upsert
    suspend fun upsert(conversation: ConversationEntity)

    /**
     * Sync-engine rebuild path: overwrites every projection field INCLUDING
     * pinned/archived (the coordinator passes the current repository state).
     */
    @Upsert
    suspend fun upsertFull(conversation: ConversationEntity)

    /**
     * The single write path for a conversation projection touched by an exact
     * mutation. TRUE upsert:
     *   - INSERT when the thread is brand-new (a first message just landed and
     *     Home has never seen this conversation — the realtime path must not
     *     depend on a later full rebuild to make it visible);
     *   - UPDATE on conflict — and critically ONLY the projected fields, so
     *     user-owned `pinned` / `archived` flags are never clobbered.
     */
    @Query(
        """
        INSERT INTO conversations (
            threadId, normalizedAddress, rawAddress, snippet, lastMessageDate, unreadCount,
            lastMessageType, pinned, archived
        )
        VALUES (
            :threadId, :normalizedAddress, :rawAddress, :snippet, :lastMessageDate, :unreadCount,
            :lastMessageType, :pinnedOnInsert, :archivedOnInsert
        )
        ON CONFLICT(threadId) DO UPDATE SET
            normalizedAddress = excluded.normalizedAddress,
            rawAddress = excluded.rawAddress,
            snippet = CASE
                WHEN excluded.lastMessageDate >= conversations.lastMessageDate
                    THEN excluded.snippet
                ELSE conversations.snippet END,
            lastMessageDate = MAX(excluded.lastMessageDate, conversations.lastMessageDate),
            lastMessageType = CASE
                WHEN excluded.lastMessageDate >= conversations.lastMessageDate
                    THEN excluded.lastMessageType
                ELSE conversations.lastMessageType END,
            unreadCount = excluded.unreadCount
        """
    )
    suspend fun upsertPreservingFlags(
        threadId: Long,
        normalizedAddress: String,
        rawAddress: String,
        snippet: String,
        lastMessageDate: Long,
        unreadCount: Int,
        /**
         * SQLite requires a value for every NOT NULL column without a DEFAULT.
         * The shipped conversations schema declares pinned/archived NOT NULL
         * with NO default, so omitting them here fails the whole INSERT with
         * "NOT NULL constraint failed: conversations.pinned" — which is exactly
         * the realtime path that must make a new conversation visible at once.
         * These are INSERT-only: the ON CONFLICT branch never touches them, so
         * user-owned state survives every subsequent upsert.
         */
        pinnedOnInsert: Boolean,
        archivedOnInsert: Boolean,
        lastMessageType: Int = 1
    )

    /**
     * AUTHORITATIVE projection replace for every REBUILD path (exact delete,
     * thread repair, integrity repair).
     *
     * [upsertPreservingFlags] is deliberately MONOTONIC (lastMessageDate =
     * MAX(excluded, existing)) because that is correct for the realtime insert
     * fast path: a new message may only advance a conversation. It is WRONG for a
     * rebuild, because deleting the newest message must be able to roll the
     * conversation BACKWARDS:
     *
     *   projection = C @ 12:00 ; delete C ; remaining newest = B @ 11:00
     *   MAX(11:00, 12:00) keeps 12:00 and the deleted snippet stays on Home.
     *
     * This variant writes snippet / lastMessageDate / lastMessageType
     * unconditionally, and still never touches the user-owned pinned / archived
     * flags.
     */
    @Query(
        """
        INSERT INTO conversations (
            threadId, normalizedAddress, rawAddress, snippet, lastMessageDate, unreadCount,
            lastMessageType, pinned, archived
        )
        VALUES (
            :threadId, :normalizedAddress, :rawAddress, :snippet, :lastMessageDate, :unreadCount,
            :lastMessageType, :pinnedOnInsert, :archivedOnInsert
        )
        ON CONFLICT(threadId) DO UPDATE SET
            normalizedAddress = excluded.normalizedAddress,
            rawAddress = excluded.rawAddress,
            snippet = excluded.snippet,
            lastMessageDate = excluded.lastMessageDate,
            lastMessageType = excluded.lastMessageType,
            unreadCount = excluded.unreadCount
        """
    )
    suspend fun replaceProjectionPreservingFlags(
        threadId: Long,
        normalizedAddress: String,
        rawAddress: String,
        snippet: String,
        lastMessageDate: Long,
        unreadCount: Int,
        /** See [upsertPreservingFlags]: NOT NULL, no SQL default, insert-only. */
        pinnedOnInsert: Boolean,
        archivedOnInsert: Boolean,
        lastMessageType: Int = 1
    )

    @Query("UPDATE conversations SET unreadCount = 0 WHERE threadId = :threadId")
    suspend fun markRead(threadId: Long)

    @Query("UPDATE conversations SET archived = :archived WHERE threadId = :threadId")
    suspend fun setArchived(threadId: Long, archived: Boolean)

    @Query("UPDATE conversations SET pinned = :pinned WHERE threadId = :threadId")
    suspend fun setPinned(threadId: Long, pinned: Boolean)

    @Query("DELETE FROM conversations WHERE threadId = :threadId")
    suspend fun delete(threadId: Long)
}

@Dao
interface SyncStateDao {

    @Query("SELECT * FROM sync_state WHERE source = :source")
    suspend fun forSource(source: String): SyncStateEntity?

    @Upsert
    suspend fun upsert(state: SyncStateEntity)

    // ── Targeted watermark updates ─────────────────────────────────────────
    // NEVER read-modify-write the whole entity inside the backfill loop: a
    // stale copy written back at the end stomps every cursor advanced during
    // the run (the v2.6.2 bug). Each update touches exactly one field group.

    @Query(
        "UPDATE sync_state SET newestDate = :date, newestId = :id, " +
            "lastReconcileAt = :now WHERE source = :source " +
            "AND (newestDate < :date OR (newestDate = :date AND newestId < :id))"
    )
    suspend fun advanceNewest(source: String, date: Long, id: Long, now: Long)

    @Query(
        "UPDATE sync_state SET oldestDate = :date, oldestId = :id, " +
            "lastReconcileAt = :now WHERE source = :source " +
            "AND (oldestDate > :date OR (oldestDate = :date AND oldestId > :id))"
    )
    suspend fun advanceOldest(source: String, date: Long, id: Long, now: Long)

    /** Set ONLY after the conversations projection has been rebuilt. */
    @Query("UPDATE sync_state SET initialWindowReady = 1, lastReconcileAt = :now WHERE source = :source")
    suspend fun markInitialWindowReady(source: String, now: Long)

    @Query(
        "UPDATE sync_state SET historyBackfillComplete = 1, lastReconcileAt = :now WHERE source = :source"
    )
    suspend fun markHistoryComplete(source: String, now: Long)

    @Query("UPDATE sync_state SET lastReconcileAt = :now WHERE source = :source")

/**
 * Durable exact-repair queue. See [ProviderRepairEntity] for the invariants.
 *
 * Every mutation is scoped by generation so that two workers, or one worker and
 * a newer provider event, can never corrupt each other.
 */
@Dao
interface ProviderRepairDao {

    /**
     * Records work for an exact provider identity, or bumps its generation.
     *
     * Unbounded by design: a correctness queue must never evict.
     */
    @Query(
        "INSERT INTO provider_repair_queue " +
            "(source, providerId, generation, state, attempts, nextRetryAt, leaseUntil, " +
            "lastFailureReason, createdAt, updatedAt) " +
            "VALUES (:source, :providerId, 1, 'PENDING', 0, :now, 0, '', :now, :now) " +
            "ON CONFLICT(source, providerId) DO UPDATE SET " +
            "generation = provider_repair_queue.generation + 1, " +
            "state = 'PENDING', attempts = 0, nextRetryAt = :now, leaseUntil = 0, " +
            "updatedAt = :now"
    )
    suspend fun enqueue(source: String, providerId: Long, now: Long)

    /** Earliest retry among rows nobody currently owns - the timer wake time. */
    @Query("SELECT MIN(nextRetryAt) FROM provider_repair_queue WHERE state != 'IN_FLIGHT'")
    suspend fun minPendingRetryAt(): Long?

    /** Earliest lease expiry, so a crashed owner is recovered without an event. */
    @Query("SELECT MIN(leaseUntil) FROM provider_repair_queue WHERE state = 'IN_FLIGHT'")
    suspend fun minLeaseUntil(): Long?

    @Query("SELECT COUNT(*) FROM provider_repair_queue")
    suspend fun count(): Int

    /** Non-claiming observation of due work. Claiming is separate (see [claim]). */
    @Query(
        "SELECT * FROM provider_repair_queue " +
            "WHERE state != 'IN_FLIGHT' AND nextRetryAt <= :now " +
            "ORDER BY nextRetryAt ASC LIMIT :limit"
    )
    suspend fun due(now: Long, limit: Int): List<ProviderRepairEntity>

    /**
     * Claims ONE row. Returns the number of rows updated.
     *
     * 0 means somebody else owns it, or a NEWER generation already replaced it:
     * in both cases this worker must do nothing at all.
     */
    @Query(
        "UPDATE provider_repair_queue SET state = 'IN_FLIGHT', leaseUntil = :leaseUntil, " +
            "updatedAt = :now WHERE source = :source AND providerId = :providerId " +
            "AND generation = :generation AND state != 'IN_FLIGHT'"
    )
    suspend fun claim(
        source: String,
        providerId: Long,
        generation: Long,
        leaseUntil: Long,
        now: Long
    ): Int

    /**
     * Is this worker still the owner of the generation it claimed?
     *
     * Checked immediately before a destructive mutation so that a read which has
     * been superseded by a newer provider event cannot delete a row.
     */
    @Query(
        "SELECT COUNT(*) FROM provider_repair_queue WHERE source = :source " +
            "AND providerId = :providerId AND generation = :generation " +
            "AND state = 'IN_FLIGHT'"
    )
    suspend fun stillOwned(source: String, providerId: Long, generation: Long): Int

    /** Success: the work is DONE. Scoped to the claimed generation. */
    @Query(
        "DELETE FROM provider_repair_queue WHERE source = :source " +
            "AND providerId = :providerId AND generation = :generation"
    )
    suspend fun ack(source: String, providerId: Long, generation: Long): Int

    /** Failure: keep the work, schedule a capped retry. Never dropped. */
    @Query(
        "UPDATE provider_repair_queue SET state = 'BACKOFF', attempts = attempts + 1, " +
            "nextRetryAt = :nextRetryAt, leaseUntil = 0, lastFailureReason = :reason, " +
            "updatedAt = :now WHERE source = :source AND providerId = :providerId " +
            "AND generation = :generation"
    )
    suspend fun nack(
        source: String,
        providerId: Long,
        generation: Long,
        nextRetryAt: Long,
        reason: String,
        now: Long
    ): Int

    /**
     * Crash recovery: a lease that expired means the owner died. The row goes
     * back to PENDING and becomes due immediately.
     *
     * Never touches the generation: the work itself is still the newest known
     * work for that identity.
     */
    @Query(
        "UPDATE provider_repair_queue SET state = 'PENDING', nextRetryAt = 0, " +
            "leaseUntil = 0, updatedAt = :now " +
            "WHERE state = 'IN_FLIGHT' AND leaseUntil <= :now"
    )
    suspend fun reclaimExpiredLeases(now: Long): Int

    /** Diagnostics: bounded snapshot, never used for a correctness decision. */
    @Query("SELECT * FROM provider_repair_queue ORDER BY nextRetryAt ASC LIMIT :limit")
    suspend fun snapshot(limit: Int): List<ProviderRepairEntity>

    @Query("SELECT COALESCE(SUM(attempts), 0) FROM provider_repair_queue")
    suspend fun totalAttempts(): Int
}

    suspend fun touchReconcile(source: String, now: Long)
}

/** Per-thread aggregate over the full-text index. */
data class ThreadHit(
    val threadId: Long,
    val matchCount: Int,
    val latestDate: Long
)

@Dao
interface MessageFtsDao {

    /**
     * Thread-level hits for a MATCH query, newest conversation first.
     * Runs entirely inside the FTS index + a rowid join — no full table scan.
     */
    @Query(
        """
        SELECT m.threadId AS threadId, COUNT(*) AS matchCount, MAX(m.date) AS latestDate
        FROM messages_fts
        JOIN messages m ON m.rowid = messages_fts.docid
        WHERE messages_fts MATCH :query
        GROUP BY m.threadId
        ORDER BY latestDate DESC
        LIMIT :limit
        """
    )
    suspend fun threadHits(query: String, limit: Int): List<ThreadHit>

    /** Total matching messages (for "N results" labeling). */
    @Query("SELECT COUNT(*) FROM messages_fts WHERE messages_fts MATCH :query")
    suspend fun countMatches(query: String): Int
}
