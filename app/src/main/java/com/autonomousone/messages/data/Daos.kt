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
     * Partial write for repair/read-mirror paths that must NOT clobber
     * user-owned projection state — an upsert here would reset pinned and
     * archived to false and drop a pinned thread off the top of Home.
     */
    @Query(
        """
        UPDATE conversations SET
            normalizedAddress = :normalizedAddress,
            rawAddress = :rawAddress,
            snippet = :snippet,
            lastMessageDate = :lastMessageDate,
            unreadCount = :unreadCount
        WHERE threadId = :threadId
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
}
