package com.autonomousone.messages.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

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

    /** Newest message per thread — used to rebuild conversations in one pass. */
    @Query(
        """
        SELECT * FROM messages m
        WHERE m.date = (
            SELECT MAX(m2.date) FROM messages m2
            WHERE m2.threadId = m.threadId
        )
        GROUP BY m.threadId
        """
    )
    suspend fun newestPerThread(): List<MessageEntity>

    @Query("SELECT MAX(date) FROM messages WHERE source = :source")
    suspend fun newestDateFor(source: String): Long?

    @Upsert
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(messages: List<MessageEntity>): List<Long>

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int

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
            threadId, normalizedAddress, rawAddress, snippet, lastMessageDate, unreadCount
        )
        VALUES (
            :threadId, :normalizedAddress, :rawAddress, :snippet, :lastMessageDate, :unreadCount
        )
        ON CONFLICT(threadId) DO UPDATE SET
            normalizedAddress = excluded.normalizedAddress,
            rawAddress = excluded.rawAddress,
            snippet = excluded.snippet,
            lastMessageDate = excluded.lastMessageDate,
            unreadCount = excluded.unreadCount
        """
    )
    suspend fun upsertPreservingFlags(
        threadId: Long,
        normalizedAddress: String,
        rawAddress: String,
        snippet: String,
        lastMessageDate: Long,
        unreadCount: Int
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
