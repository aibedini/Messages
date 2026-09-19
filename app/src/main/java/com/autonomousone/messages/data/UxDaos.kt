package com.autonomousone.messages.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * v3.4.0 UX DAOs.
 *
 * Every query here obeys the ACTIVE-UI contract:
 *
 *   RAW MIRROR : `messages` — everything the provider gave us. Used by the sync
 *                engine, the integrity audit and the repair queue. It must NEVER
 *                be filtered by user state, or provider reconciliation would
 *                "repair" rows the user deliberately trashed.
 *   ACTIVE UI  : raw row MINUS individually-trashed state MINUS rows hidden by
 *                an active thread tombstone. What Home, search, starred, media
 *                and the conversation window read.
 *   TRASH      : rows whose user state (or tombstone snapshot) marks them as
 *                deleted, newest first.
 *
 * The tombstone predicate is NOT re-typed in these annotations: it is
 * concatenated from [MessageCutoff], so a change to the cutoff semantics can
 * never make the SQL and the Kotlin mirror disagree.
 */

/** ACTIVE-UI fragment: individually-trashed rows excluded (alias `m`). */
private const val NOT_INDIVIDUALLY_TRASHED = MessageCutoff.NOT_INDIVIDUALLY_TRASHED_SQL

/** ACTIVE-UI fragment: rows hidden by a thread tombstone excluded (alias `m`). */
private const val NOT_HIDDEN_BY_TOMBSTONE = MessageCutoff.NOT_HIDDEN_BY_TOMBSTONE_SQL

/** Both halves of the ACTIVE-UI contract, composed once. */
private const val ACTIVE_MESSAGE_FILTER = MessageCutoff.ACTIVE_MESSAGE_FILTER_SQL

@Dao
interface ConversationPreferenceDao {

    @Query("SELECT * FROM conversation_preferences WHERE threadId = :threadId LIMIT 1")
    suspend fun get(threadId: Long): ConversationPreferenceEntity?

    @Query("SELECT * FROM conversation_preferences WHERE threadId = :threadId LIMIT 1")
    fun observe(threadId: Long): Flow<ConversationPreferenceEntity?>

    /** Threads the user bookmarked as unread (Home badge source). */
    @Query("SELECT threadId FROM conversation_preferences WHERE manualUnread = 1")
    suspend fun manuallyUnreadThreadIds(): List<Long>

    @Query("SELECT threadId FROM conversation_preferences WHERE manualUnread = 1")
    fun observeManuallyUnreadThreadIds(): Flow<List<Long>>

    @Query("SELECT threadId FROM conversation_preferences WHERE spam = 1")
    suspend fun spamThreadIds(): List<Long>

    @Query("SELECT threadId FROM conversation_preferences WHERE spam = 1")
    fun observeSpamThreadIds(): Flow<List<Long>>

    /** Threads muted RIGHT NOW; expiry is a comparison, never a scheduled job. */
    @Query(
        "SELECT threadId FROM conversation_preferences " +
            "WHERE mutedUntil = :forever OR (mutedUntil > 0 AND mutedUntil > :now)"
    )
    suspend fun mutedThreadIds(now: Long, forever: Long): List<Long>

    @Query(
        "SELECT threadId FROM conversation_preferences " +
            "WHERE mutedUntil = :forever OR (mutedUntil > 0 AND mutedUntil > :now)"
    )
    fun observeMutedThreadIds(now: Long, forever: Long): Flow<List<Long>>

    @Query("SELECT threadId FROM conversation_preferences WHERE customNotificationChannel = 1")
    suspend fun customizedChannelThreadIds(): List<Long>

    @Query("SELECT * FROM conversation_preferences WHERE threadId IN (:threadIds)")
    suspend fun forThreads(threadIds: List<Long>): List<ConversationPreferenceEntity>

    @Upsert
    suspend fun upsert(preference: ConversationPreferenceEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(preference: ConversationPreferenceEntity): Long

    // ── Field-scoped writers ────────────────────────────────────────────────
    // Each INSERTs a row of pure defaults when absent, then patches ONLY its own
    // columns on conflict. "Mark unread" therefore can never clear a mute, and a
    // mute can never clear a category override or a spam report.

    @Query(
        """
        INSERT INTO conversation_preferences
            (threadId, manualUnread, mutedUntil, customNotificationChannel,
             categoryOverride, spam, spamReportedAt, spamBlockedByReport, updatedAt)
        VALUES (:threadId, :manualUnread, 0, 0, NULL, 0, 0, 0, :now)
        ON CONFLICT(threadId) DO UPDATE SET
            manualUnread = excluded.manualUnread,
            updatedAt = excluded.updatedAt
        """
    )
    suspend fun setManualUnread(threadId: Long, manualUnread: Boolean, now: Long)

    @Query(
        """
        INSERT INTO conversation_preferences
            (threadId, manualUnread, mutedUntil, customNotificationChannel,
             categoryOverride, spam, spamReportedAt, spamBlockedByReport, updatedAt)
        VALUES (:threadId, 0, :mutedUntil, 0, NULL, 0, 0, 0, :now)
        ON CONFLICT(threadId) DO UPDATE SET
            mutedUntil = excluded.mutedUntil,
            updatedAt = excluded.updatedAt
        """
    )
    suspend fun setMutedUntil(threadId: Long, mutedUntil: Long, now: Long)

    @Query(
        """
        INSERT INTO conversation_preferences
            (threadId, manualUnread, mutedUntil, customNotificationChannel,
             categoryOverride, spam, spamReportedAt, spamBlockedByReport, updatedAt)
        VALUES (:threadId, 0, 0, 1, NULL, 0, 0, 0, :now)
        ON CONFLICT(threadId) DO UPDATE SET
            customNotificationChannel = 1,
            updatedAt = excluded.updatedAt
        """
    )
    suspend fun enableCustomNotificationChannel(threadId: Long, now: Long)

    /** null clears the override back to the automatic category. */
    @Query(
        """
        INSERT INTO conversation_preferences
            (threadId, manualUnread, mutedUntil, customNotificationChannel,
             categoryOverride, spam, spamReportedAt, spamBlockedByReport, updatedAt)
        VALUES (:threadId, 0, 0, 0, :category, 0, 0, 0, :now)
        ON CONFLICT(threadId) DO UPDATE SET
            categoryOverride = excluded.categoryOverride,
            updatedAt = excluded.updatedAt
        """
    )
    suspend fun setCategoryOverride(threadId: Long, category: String?, now: Long)

    @Query(
        """
        INSERT INTO conversation_preferences
            (threadId, manualUnread, mutedUntil, customNotificationChannel,
             categoryOverride, spam, spamReportedAt, spamBlockedByReport, updatedAt)
        VALUES (:threadId, 0, 0, 0, NULL, 1, :now, :blockedByReport, :now)
        ON CONFLICT(threadId) DO UPDATE SET
            spam = 1,
            spamReportedAt = excluded.spamReportedAt,
            spamBlockedByReport = excluded.spamBlockedByReport,
            updatedAt = excluded.updatedAt
        """
    )
    suspend fun markSpam(threadId: Long, blockedByReport: Boolean, now: Long)

    /**
     * Not-Spam. [clearBlockProvenance] is true ONLY when this spam report created
     * the block — a number the user had blocked manually before the report must
     * stay blocked, so its provenance flag survives.
     */
    @Query(
        """
        UPDATE conversation_preferences
        SET spam = 0,
            spamReportedAt = 0,
            spamBlockedByReport = CASE WHEN :clearBlockProvenance = 1
                THEN 0 ELSE spamBlockedByReport END,
            updatedAt = :now
        WHERE threadId = :threadId
        """
    )
    suspend fun clearSpam(threadId: Long, clearBlockProvenance: Boolean, now: Long)
}

@Dao
interface TrashedThreadDao {

    @Query("SELECT * FROM trashed_threads WHERE threadId = :threadId LIMIT 1")
    suspend fun get(threadId: Long): TrashedThreadEntity?

    @Query("SELECT * FROM trashed_threads WHERE threadId = :threadId LIMIT 1")
    fun observe(threadId: Long): Flow<TrashedThreadEntity?>

    @Query("SELECT * FROM trashed_threads")
    suspend fun all(): List<TrashedThreadEntity>

    /** Recently Deleted screen: newest deletion first. */
    @Query("SELECT * FROM trashed_threads ORDER BY deletedAt DESC")
    fun observeAll(): Flow<List<TrashedThreadEntity>>

    @Query("SELECT * FROM trashed_threads ORDER BY deletedAt DESC")
    suspend fun allNewestFirst(): List<TrashedThreadEntity>

    @Query("SELECT threadId FROM trashed_threads")
    suspend fun trashedThreadIds(): List<Long>

    /** Bounded due set for the purge worker — index-backed on purgeAt. */
    @Query("SELECT * FROM trashed_threads WHERE purgeAt <= :now ORDER BY purgeAt ASC LIMIT :limit")
    suspend fun dueForPurge(now: Long, limit: Int): List<TrashedThreadEntity>

    @Query("SELECT MIN(purgeAt) FROM trashed_threads")
    suspend fun earliestPurgeAt(): Long?

    @Upsert
    suspend fun upsert(tombstone: TrashedThreadEntity)

    /** Restore: the provider still holds every row, so no re-insert is needed. */
    @Query("DELETE FROM trashed_threads WHERE threadId = :threadId")
    suspend fun delete(threadId: Long)

    @Query("DELETE FROM trashed_threads WHERE threadId IN (:threadIds)")
    suspend fun deleteAll(threadIds: List<Long>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(tombstone: TrashedThreadEntity): Long
}

/**
 * A starred message joined with its content, for the global Starred browser and
 * the Conversation-Info counter. Deliberately carries the composite identity so
 * the UI can jump to the EXACT row (SMS 100 vs MMS 100).
 */
data class StarredMessageRow(
    val source: String,
    val providerId: Long,
    val threadId: Long,
    val body: String,
    val date: Long,
    val rawAddress: String,
    val normalizedAddress: String,
    val starredAt: Long
)

@Dao
interface MessageUserStateDao {

    @Query(
        "SELECT * FROM message_user_state WHERE source = :source AND providerId = :providerId LIMIT 1"
    )
    suspend fun get(source: String, providerId: Long): MessageUserStateEntity?

    @Query(
        "SELECT * FROM message_user_state WHERE source = :source AND providerId = :providerId LIMIT 1"
    )
    fun observe(source: String, providerId: Long): Flow<MessageUserStateEntity?>

    @Query("SELECT * FROM message_user_state")
    suspend fun all(): List<MessageUserStateEntity>

    @Upsert
    suspend fun upsert(state: MessageUserStateEntity)

    // ─ Field-scoped writers ────────────────────────────────────────────────
    // The insert branch carries pure defaults; the conflict branch patches ONLY
    // its own columns. Starring therefore never un-trashes a row, and trashing
    // never loses a star (which is what protects a starred OTP from cleanup).

    @Query(
        """
        INSERT INTO message_user_state
            (source, providerId, threadId, starred, starredAt, trashedAt, purgeAt,
             keepFromOtpCleanup, updatedAt)
        VALUES (:source, :providerId, :threadId, :starred, :starredAt, 0, 0, 0, :now)
        ON CONFLICT(source, providerId) DO UPDATE SET
            starred = excluded.starred,
            starredAt = excluded.starredAt,
            updatedAt = excluded.updatedAt
        """
    )
    suspend fun setStarred(
        source: String,
        providerId: Long,
        threadId: Long,
        starred: Boolean,
        starredAt: Long,
        now: Long
    )

    @Query(
        """
        INSERT INTO message_user_state
            (source, providerId, threadId, starred, starredAt, trashedAt, purgeAt,
             keepFromOtpCleanup, updatedAt)
        VALUES (:source, :providerId, :threadId, 0, 0, :trashedAt, :purgeAt, 0, :now)
        ON CONFLICT(source, providerId) DO UPDATE SET
            trashedAt = excluded.trashedAt,
            purgeAt = excluded.purgeAt,
            updatedAt = excluded.updatedAt
        """
    )
    suspend fun markTrashed(
        source: String,
        providerId: Long,
        threadId: Long,
        trashedAt: Long,
        purgeAt: Long,
        now: Long
    )

    /** Restore one message: trashedAt/purgeAt return to 0, star/keep survive. */
    @Query(
        """
        UPDATE message_user_state
        SET trashedAt = 0, purgeAt = 0, updatedAt = :now
        WHERE source = :source AND providerId = :providerId
        """
    )
    suspend fun restore(source: String, providerId: Long, now: Long)

    @Query(
        """
        INSERT INTO message_user_state
            (source, providerId, threadId, starred, starredAt, trashedAt, purgeAt,
             keepFromOtpCleanup, updatedAt)
        VALUES (:source, :providerId, :threadId, 0, 0, 0, 0, :keep, :now)
        ON CONFLICT(source, providerId) DO UPDATE SET
            keepFromOtpCleanup = excluded.keepFromOtpCleanup,
            updatedAt = excluded.updatedAt
        """
    )
    suspend fun setKeepFromOtpCleanup(
        source: String,
        providerId: Long,
        threadId: Long,
        keep: Boolean,
        now: Long
    )

    @Query("SELECT COUNT(*) FROM message_user_state WHERE threadId = :threadId AND starred = 1")
    suspend fun countStarredInThread(threadId: Long): Int

    @Query("SELECT COUNT(*) FROM message_user_state WHERE threadId = :threadId AND starred = 1")
    fun observeStarredCountInThread(threadId: Long): Flow<Int>

    /**
     * Global starred browser. ACTIVE-UI filtered: a starred message inside a
     * trashed conversation is in Trash, not in Starred.
     */
    @Query(
        """
        SELECT m.source AS source, m.providerId AS providerId, m.threadId AS threadId,
               m.body AS body, m.date AS date, m.rawAddress AS rawAddress,
               m.normalizedAddress AS normalizedAddress, us.starredAt AS starredAt
        FROM message_user_state us
        JOIN messages m ON m.source = us.source AND m.providerId = us.providerId
        WHERE us.starred = 1 AND $ACTIVE_MESSAGE_FILTER
        ORDER BY m.date DESC, m.source DESC, m.providerId DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun starredPage(limit: Int, offset: Int): List<StarredMessageRow>

    @Query(
        """
        SELECT m.source AS source, m.providerId AS providerId, m.threadId AS threadId,
               m.body AS body, m.date AS date, m.rawAddress AS rawAddress,
               m.normalizedAddress AS normalizedAddress, us.starredAt AS starredAt
        FROM message_user_state us
        JOIN messages m ON m.source = us.source AND m.providerId = us.providerId
        WHERE us.starred = 1 AND us.threadId = :threadId AND $ACTIVE_MESSAGE_FILTER
        ORDER BY m.date DESC, m.source DESC, m.providerId DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun starredPageInThread(
        threadId: Long,
        limit: Int,
        offset: Int
    ): List<StarredMessageRow>

    /** Individually trashed messages due for permanent purge (index-backed). */
    @Query(
        "SELECT * FROM message_user_state WHERE purgeAt > 0 AND purgeAt <= :now " +
            "ORDER BY purgeAt ASC LIMIT :limit"
    )
    suspend fun dueForPurge(now: Long, limit: Int): List<MessageUserStateEntity>

    @Query("SELECT MIN(purgeAt) FROM message_user_state WHERE purgeAt > 0")
    suspend fun earliestPurgeAt(): Long?

    /** Currently trashed messages inside one conversation (Trash detail). */
    @Query(
        "SELECT * FROM message_user_state WHERE threadId = :threadId AND trashedAt > 0 " +
            "ORDER BY trashedAt DESC"
    )
    suspend fun trashedInThread(threadId: Long): List<MessageUserStateEntity>

    /**
     * Explicit orphan cleanup — the replacement for an FK CASCADE (see
     * [MessageUserStateEntity]). Only ever called for identities the provider has
     * PROVEN gone, never on a provider refresh.
     */
    @Query(
        """
        DELETE FROM message_user_state
        WHERE NOT EXISTS (
            SELECT 1 FROM messages m
            WHERE m.source = message_user_state.source
              AND m.providerId = message_user_state.providerId
        )
        """
    )
    suspend fun deleteOrphans(): Int
}

/**
 * One message fed to the local classifier. Carries `type` so the BACKFILL can
 * honour the "incoming only" rule for OTP cleanup without a second query.
 */
data class MessageForClassification(
    val source: String,
    val providerId: Long,
    val threadId: Long,
    val body: String,
    val date: Long,
    val rawAddress: String,
    val messageType: Int
)

/** Category histogram for one conversation (bounded by the enum size). */
data class ThreadCategoryCount(
    val category: String,
    val count: Int
)

@Dao
interface MessageClassificationDao {

    @Query(
        "SELECT * FROM message_classification WHERE source = :source AND providerId = :providerId LIMIT 1"
    )
    suspend fun get(source: String, providerId: Long): MessageClassificationEntity?

    @Query("SELECT * FROM message_classification WHERE threadId = :threadId")
    suspend fun forThread(threadId: Long): List<MessageClassificationEntity>

    @Upsert
    suspend fun upsert(classification: MessageClassificationEntity)

    @Upsert
    suspend fun upsertAll(classifications: List<MessageClassificationEntity>)

    @Query("SELECT category, COUNT(*) AS count FROM message_classification WHERE threadId = :threadId GROUP BY category")
    suspend fun categoryCountsForThread(threadId: Long): List<ThreadCategoryCount>

    @Query("SELECT COUNT(*) FROM message_classification WHERE threadId = :threadId AND isOtp = 1")
    suspend fun countOtpInThread(threadId: Long): Int

    /**
     * Bounded due set for the OTP cleanup worker — index-backed on
     * otpDeleteEligibleAt. ONE unique worker is scheduled for MIN of this column,
     * so this is never a table scan and never one job per OTP.
     */
    @Query(
        "SELECT * FROM message_classification WHERE otpDeleteEligibleAt > 0 " +
            "AND otpDeleteEligibleAt <= :now ORDER BY otpDeleteEligibleAt ASC LIMIT :limit"
    )
    suspend fun dueForOtpCleanup(now: Long, limit: Int): List<MessageClassificationEntity>

    @Query("SELECT MIN(otpDeleteEligibleAt) FROM message_classification WHERE otpDeleteEligibleAt > 0")
    suspend fun earliestOtpEligibleAt(): Long?

    /** Schedules (or unschedules with 0) the cleanup deadline for one message. */
    @Query(
        "UPDATE message_classification SET otpDeleteEligibleAt = :eligibleAt " +
            "WHERE source = :source AND providerId = :providerId"
    )
    suspend fun setOtpDeleteEligibleAt(source: String, providerId: Long, eligibleAt: Long)

    /**
     * Checkpointed backfill batch.
     *
     * KEYSET paging, never OFFSET: the cursor is the (date, source, providerId)
     * tuple of the last classified row, which is exactly the canonical ordering
     * index. `afterProviderId = Long.MAX_VALUE` starts a fresh sweep. Deep OFFSET
     * on a 360K-row table would re-walk every skipped row on every batch.
     */
    @Query(
        """
        SELECT m.source AS source, m.providerId AS providerId, m.threadId AS threadId,
               m.body AS body, m.date AS date, m.rawAddress AS rawAddress,
               m.type AS messageType
        FROM messages m
        WHERE NOT EXISTS (
            SELECT 1 FROM message_classification c
            WHERE c.source = m.source AND c.providerId = m.providerId
        )
          AND (m.date < :afterDate
               OR (m.date = :afterDate AND (m.source < :afterSource
                   OR (m.source = :afterSource AND m.providerId < :afterProviderId))))
        ORDER BY m.date DESC, m.source DESC, m.providerId DESC
        LIMIT :limit
        """
    )
    suspend fun unclassifiedBatch(
        afterDate: Long,
        afterSource: String,
        afterProviderId: Long,
        limit: Int
    ): List<MessageForClassification>

    @Query("SELECT COUNT(*) FROM message_classification")
    suspend fun classifiedCount(): Int

    @Query("DELETE FROM message_classification WHERE source = :source AND providerId = :providerId")
    suspend fun delete(source: String, providerId: Long)

    /**
     * Explicit orphan cleanup (see [MessageUserStateEntity] for why there is no
     * FK CASCADE). Only for identities the provider has PROVEN gone.
     */
    @Query(
        """
        DELETE FROM message_classification
        WHERE NOT EXISTS (
            SELECT 1 FROM messages m
            WHERE m.source = message_classification.source
              AND m.providerId = message_classification.providerId
        )
        """
    )
    suspend fun deleteOrphans(): Int
}

@Dao
interface ConversationClassificationDao {

    @Query("SELECT * FROM conversation_classification WHERE threadId = :threadId LIMIT 1")
    suspend fun get(threadId: Long): ConversationClassificationEntity?

    @Query("SELECT * FROM conversation_classification")
    suspend fun all(): List<ConversationClassificationEntity>

    @Upsert
    suspend fun upsert(classification: ConversationClassificationEntity)

    @Query("SELECT threadId FROM conversation_classification WHERE category = :category")
    suspend fun threadIdsForCategory(category: String): List<Long>

    @Query("SELECT threadId FROM conversation_classification WHERE category = :category")
    fun observeThreadIdsForCategory(category: String): Flow<List<Long>>

    @Query("DELETE FROM conversation_classification WHERE threadId = :threadId")
    suspend fun delete(threadId: Long)
}

@Dao
interface MessageAssetDao {

    /**
     * Idempotent ingest: [MessageAssetEntity.assetKey] is deterministic, so
     * re-reading the same MMS part or the same link UPDATES instead of inserting
     * a duplicate. Two identical URLs in two different messages keep two keys.
     */
    @Upsert
    suspend fun upsertAll(assets: List<MessageAssetEntity>)

    @Upsert
    suspend fun upsert(asset: MessageAssetEntity)

    @Query("SELECT * FROM message_assets WHERE assetKey = :assetKey LIMIT 1")
    suspend fun get(assetKey: String): MessageAssetEntity?

    /** Paged tab body: newest first, indexed on (threadId, date). */
    @Query(
        "SELECT * FROM message_assets WHERE threadId = :threadId AND kind = :kind " +
            "ORDER BY date DESC, assetKey DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun pageByKind(
        threadId: Long,
        kind: String,
        limit: Int,
        offset: Int
    ): List<MessageAssetEntity>

    @Query(
        "SELECT COUNT(*) FROM message_assets WHERE threadId = :threadId AND kind = :kind"
    )
    suspend fun countByKind(threadId: Long, kind: String): Int

    /** Per-message lookup so a bubble can show its own link/attachment chip. */
    @Query(
        "SELECT * FROM message_assets WHERE source = :source AND providerId = :providerId " +
            "ORDER BY kind ASC, value ASC"
    )
    suspend fun forMessage(source: String, providerId: Long): List<MessageAssetEntity>

    @Query("SELECT COUNT(*) FROM message_assets WHERE threadId = :threadId AND kind = :kind")
    fun observeCountByKind(threadId: Long, kind: String): Flow<Int>

    /** A deleted message must take its assets with it (explicit, no FK). */
    @Query("DELETE FROM message_assets WHERE source = :source AND providerId = :providerId")
    suspend fun deleteForMessage(source: String, providerId: Long)

    @Query(
        """
        DELETE FROM message_assets
        WHERE NOT EXISTS (
            SELECT 1 FROM messages m
            WHERE m.source = message_assets.source
              AND m.providerId = message_assets.providerId
        )
        """
    )
    suspend fun deleteOrphans(): Int
}