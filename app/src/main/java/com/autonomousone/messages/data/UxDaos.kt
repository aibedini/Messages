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
 * never make the SQL and the Kotlin mirror disagree. (The ACTIVE-UI reader
 * statements that use it live in [MessageUserStateSql] / [MessageAssetSql] for
 * the same reason: one named string per behaviour, so tests can execute the
 * production SQL instead of re-typing it.)
 */

@Dao
interface ConversationPreferenceDao {

    @Query("SELECT * FROM conversation_preferences WHERE threadId = :threadId LIMIT 1")
    suspend fun get(threadId: Long): ConversationPreferenceEntity?

    /**
     * BLOCKING read for the notification path only.
     *
     * `NotificationHelper.showSmsNotification` runs on a background thread (the
     * same thread that already does the provider contact lookup) and cannot be
     * suspend, so it needs a plain blocking query. NEVER call this from a Compose
     * main thread — that is exactly the work the architecture rules forbid
     * putting on the UI thread.
     */
    @Query("SELECT * FROM conversation_preferences WHERE threadId = :threadId LIMIT 1")
    fun getBlocking(threadId: Long): ConversationPreferenceEntity?

    @Query("SELECT * FROM conversation_preferences WHERE threadId = :threadId LIMIT 1")
    fun observe(threadId: Long): Flow<ConversationPreferenceEntity?>

    /** Threads the user bookmarked as unread (Home badge source). */
    @Query("SELECT threadId FROM conversation_preferences WHERE manualUnread = 1")
    suspend fun manuallyUnreadThreadIds(): List<Long>

    @Query("SELECT threadId FROM conversation_preferences WHERE manualUnread = 1")
    fun observeManuallyUnreadThreadIds(): Flow<List<Long>>

    /**
     * LIVE category overrides (threadId + override only). The override is read
     * at Home's read time so a stale copy can never beat a live user decision.
     */
    @Query("SELECT threadId, categoryOverride FROM conversation_preferences")
    fun observeCategoryOverrides(): Flow<List<ThreadCategoryOverride>>

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

    /**
     * WHY THIS SQL IS INLINE (and [SET_STARRED_SQL] still exists)
     * ---------------------------------------------------------
     * Room's KSP processor rejects an annotation argument that is a string
     * TEMPLATE (`@Query("$SOME_CONST")`): the assertion fails with "No property
     * named value was found in annotation Query" and takes the whole KSP/Room
     * code-generation step down with it. A `const val` used DIRECTLY
     * (`@Query(SOME_CONST)`) is a valid annotation constant, but a template is
     * not — so the production copy must be a literal here.
     *
     * The statement is therefore single-sourced in the other direction:
     * [MessageUserStateSqlTest] executes the REAL statements against a real
     * SQLite database built from Room's own schema, so the tested text is the
     * text that runs, and the identical literal below is asserted against the
     * constant by that test to make drift impossible to miss.
     */
    @Query(
        "INSERT INTO message_user_state " +
            "(source, providerId, threadId, starred, starredAt, trashedAt, purgeAt, " +
            "keepFromOtpCleanup, updatedAt) " +
            "VALUES (:source, :providerId, :threadId, :starred, :starredAt, 0, 0, 0, :now) " +
            "ON CONFLICT(source, providerId) DO UPDATE SET " +
            "starred = excluded.starred, " +
            "starredAt = excluded.starredAt, " +
            "updatedAt = excluded.updatedAt"
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

    /** See [setStarred] for why this SQL is inline rather than a template. */
    @Query(
        "INSERT INTO message_user_state " +
            "(source, providerId, threadId, starred, starredAt, trashedAt, purgeAt, " +
            "keepFromOtpCleanup, updatedAt) " +
            "VALUES (:source, :providerId, :threadId, 0, 0, 0, 0, :keep, :now) " +
            "ON CONFLICT(source, providerId) DO UPDATE SET " +
            "keepFromOtpCleanup = excluded.keepFromOtpCleanup, " +
            "updatedAt = excluded.updatedAt"
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
        WHERE us.starred = 1 AND ${MessageCutoff.ACTIVE_MESSAGE_FILTER_SQL}
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
        WHERE us.starred = 1 AND us.threadId = :threadId AND ${MessageCutoff.ACTIVE_MESSAGE_FILTER_SQL}
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
     * TRASH (FEATURE 8): user state of a tombstone's purged snapshot.
     *
     * Called ONLY by the trash purge, ONLY after the provider delete was
     * CONFIRMED, and BEFORE the messages rows are deleted (the statement joins
     * `messages` and the still-present tombstone). A message newer than the
     * cutoff keeps its star/keep flag: the statement's predicate is the very
     * same snapshot predicate the purge uses for the messages themselves.
     */
    @Query(DELETE_TRASHED_SNAPSHOT_USER_STATE_SQL)
    suspend fun deleteTrashedSnapshotUserState(threadId: Long): Int

    /**
     * Explicit orphan cleanup — the replacement for an FK CASCADE (see
     * [MessageUserStateEntity]). Only ever called for identities the provider has
     * PROVEN gone, never on a provider refresh. Inline for the reason documented
     * on [setStarred]; the identical statement is pinned as [DELETE_ORPHANS_SQL].
     */
    @Query(
        "DELETE FROM message_user_state " +
            "WHERE NOT EXISTS (" +
            "SELECT 1 FROM messages m " +
            "WHERE m.source = message_user_state.source " +
            "AND m.providerId = message_user_state.providerId)"
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

/**
 * One classified message's identity plus its provider direction, for the OTP
 * retention policy. Deliberately carries NO body: the local cleanup decision
 * never needs the message text, so it is never re-read and can never leak.
 */
data class ClassifiedMessageDirection(
    val source: String,
    val providerId: Long,
    val messageType: Int
)

/** One thread's effective (override-aware) category — the Home filter input. */
data class ThreadEffectiveCategory(
    val threadId: Long,
    val category: String
)

/**
 * Narrow projections of the two tables Home combines into the effective
 * category: the automatic classification, and the user override. Column sets
 * stay minimal so the two flows never read a message body.
 */
data class ThreadCategoryRow(val threadId: Long, val category: String)

/** The user's category override for one thread; null = follow the classifier. */
data class ThreadCategoryOverride(val threadId: Long, val categoryOverride: String?)

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
     * Every classified message of ONE thread, with the provider direction read
     * from the RAW mirror. `m.type` is a content fact owned by the provider, so
     * it never belongs in the classification row; joining it here keeps the
     * OTP-retention policy working on a per-thread basis (bounded by the thread,
     * never by the 360K-message table).
     */
    @Query(
        """
        SELECT c.source AS source, c.providerId AS providerId, m.type AS messageType
        FROM message_classification c
        JOIN messages m ON m.source = c.source AND m.providerId = c.providerId
        WHERE c.threadId = :threadId AND c.isOtp = 1
        """
    )
    suspend fun otpMessagesInThread(threadId: Long): List<ClassifiedMessageDirection>

    /**
     * The newest classified message of a thread, under the canonical
     * `date DESC, source DESC, providerId DESC` order. Feeding the conversation
     * projection from the NEWEST message (falling back to the thread histogram,
     * and only then to UNKNOWN) is what keeps the projection O(1) after an
     * ingest: no per-message scan, and the projection reflects what the user
     * just received.
     */
    @Query(
        """
        SELECT c.* FROM message_classification c
        JOIN messages m ON m.source = c.source AND m.providerId = c.providerId
        WHERE c.threadId = :threadId
        ORDER BY m.date DESC, m.source DESC, m.providerId DESC
        LIMIT 1
        """
    )
    suspend fun newestForThread(threadId: Long): MessageClassificationEntity?

    /** Distinct thread ids already classified — bounded by conversation count. */
    @Query("SELECT DISTINCT threadId FROM message_classification")
    suspend fun distinctThreadIds(): List<Long>

    /**
     * Home's category filter, as ONE indexed lookup instead of a scan.
     *
     * Effective category = the USER OVERRIDE when set, else the automatic
     * `conversation_classification` projection. A missing
     * `conversation_preferences` row means "no override", NOT "unclassified",
     * so both halves are UNIONed explicitly. The override lives in
     * `conversation_preferences` and is read LIVE, so it always wins and needs
     * no copy of its own.
     */
    @Query(
        """
        SELECT COALESCE(p.categoryOverride, cc.category) AS category,
               p.threadId AS threadId
        FROM conversation_preferences p
        JOIN conversation_classification cc ON cc.threadId = p.threadId
        WHERE p.categoryOverride IS NOT NULL
          AND p.categoryOverride IN (:categories)
        UNION
        SELECT cc.category AS category, cc.threadId AS threadId
        FROM conversation_classification cc
        WHERE cc.category IN (:categories)
          AND (
              NOT EXISTS (
                  SELECT 1 FROM conversation_preferences p WHERE p.threadId = cc.threadId
              )
              OR EXISTS (
                  SELECT 1 FROM conversation_preferences p
                  WHERE p.threadId = cc.threadId AND p.categoryOverride IS NULL
              )
          )
        """
    )
    suspend fun threadIdsByEffectiveCategory(
        categories: List<String>
    ): List<ThreadEffectiveCategory>

    /**
     * Home's category filter as a LIVE flow: the same indexed predicate, so the
     * chip counts and the filtered list update the moment a classification or a
     * user override lands. Category names only — never message content.
     */
    @Query(
        """
        SELECT COALESCE(p.categoryOverride, cc.category) AS category,
               p.threadId AS threadId
        FROM conversation_preferences p
        JOIN conversation_classification cc ON cc.threadId = p.threadId
        WHERE p.categoryOverride IS NOT NULL
        UNION
        SELECT cc.category AS category, cc.threadId AS threadId
        FROM conversation_classification cc
        WHERE NOT EXISTS (
            SELECT 1 FROM conversation_preferences p
            WHERE p.threadId = cc.threadId AND p.categoryOverride IS NOT NULL
        )
        """
    )
    fun observeThreadEffectiveCategories(): Flow<List<ThreadEffectiveCategory>>

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

    /**
     * How many messages are currently ENROLLED in OTP cleanup (FEATURE 14).
     *
     * One index range scan over `otpDeleteEligibleAt` — the same index the due
     * query uses — so Settings can show a real count without a table scan and
     * without ever reading a message body.
     */
    @Query("SELECT COUNT(*) FROM message_classification WHERE otpDeleteEligibleAt > 0")
    suspend fun countEnrolledOtpCleanup(): Int

    /**
     * The bounded ENROLLED set, oldest deadline first (FEATURE 14 triage).
     *
     * Same index as the due query; used by the periodic triage pass to release
     * messages that became protected after they were enrolled, and to heal a
     * wrong-clock deadline. Bounded so a 100K-OTP backlog is triaged in passes
     * instead of one unbounded transaction.
     */
    @Query(
        "SELECT * FROM message_classification WHERE otpDeleteEligibleAt > 0 " +
            "ORDER BY otpDeleteEligibleAt ASC LIMIT :limit"
    )
    suspend fun enrolledOtpCleanup(limit: Int): List<MessageClassificationEntity>

    /**
     * Live MIN of the enrolled deadlines (FEATURE 14 scheduling).
     *
     * `MIN` over an empty table is NULL and over an all-zero table is 0; both are
     * normalised to 0 so the collector needs no nullable type. Room re-emits this
     * whenever `message_classification` changes, which is exactly the "an OTP
     * arrived / a deadline moved" signal the single cleanup work needs — no
     * per-OTP job and no polling.
     */
    @Query("SELECT COALESCE(MIN(otpDeleteEligibleAt), 0) FROM message_classification")
    fun observeEarliestOtpEligibleAt(): Flow<Long>

    /**
     * Explicit lifecycle checkpoint that UNSCHEDULES one message (FEATURE 14).
     *
     * `setOtpDeleteEligibleAt` is an UPDATE and therefore a no-op for a message
     * that has never been classified. Starring such a message must still count as
     * "protect it", so this INSERTs a default row first and then clears the
     * deadline — the same insert-then-patch shape the field-scoped
     * `MessageUserStateDao` writers already use.
     *
     * [category] is the persisted `MessageCategory` NAME. Callers that do not
     * know a category pass `UNKNOWN`; the sweep re-classifies on its next pass.
     */
    @Query(
        """
        INSERT INTO message_classification
            (source, providerId, threadId, category, confidence, isOtp,
             otpDeleteEligibleAt, classifiedAt)
        VALUES (:source, :providerId, :threadId, :category, 0.0, 0, 0, :now)
        ON CONFLICT(source, providerId) DO UPDATE SET
            otpDeleteEligibleAt = 0
        """
    )
    suspend fun clearOtpDeleteEligibleAt(
        source: String,
        providerId: Long,
        threadId: Long,
        category: String,
        now: Long
    )

    /**
     * EXPLICIT "Apply to existing OTP messages" sweep (FEATURE 14), KEYSET and
     * bounded.
     *
     * This is the ONLY read that ever walks history, and it runs only when the
     * user taps that action — newly arriving OTPs are enrolled by the ingest
     * path. It still must not be a full-table load: the cursor is
     * `(date, providerId)` under the canonical order, the page is LIMIT-bounded,
     * and only rows that are NOT starred, NOT keep-flagged and NOT already
     * trashed are returned.
     *
     * The `type = 1` and confidence predicates are deliberately NOT here: they
     * are eligibility rules, they live in exactly one place
     * (`OtpRetentionPolicy`), and the caller re-checks every row anyway.
     *
     * `afterDate = Long.MAX_VALUE` starts a fresh newest-first sweep. The sort
     * matches the `messages` index, so SQLite never materialises-and-sorts
     * hundreds of thousands of rows.
     */
    @Query(
        """
        SELECT m.source AS source, m.providerId AS providerId, m.threadId AS threadId,
               m.body AS body, m.date AS date, m.rawAddress AS rawAddress,
               m.type AS messageType, c.confidence AS confidence,
               c.otpDeleteEligibleAt AS eligibleAt
        FROM messages m
        JOIN message_user_state us
          ON us.source = m.source AND us.providerId = m.providerId
        LEFT JOIN message_classification c
          ON c.source = m.source AND c.providerId = m.providerId
        WHERE us.starred = 0
          AND us.keepFromOtpCleanup = 0
          AND us.trashedAt = 0
          AND (m.date < :afterDate
               OR (m.date = :afterDate AND m.providerId < :afterProviderId))
        ORDER BY m.date DESC, m.source DESC, m.providerId DESC
        LIMIT :limit
        """
    )
    suspend fun existingOtpCleanupCandidates(
        afterDate: Long,
        afterProviderId: Long,
        limit: Int
    ): List<ExistingOtpCleanupCandidate>

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

/**
 * One row of the explicit "Apply to existing OTP messages" sweep (FEATURE 14).
 *
 * Carries the body because the OTP verdict is LOCAL and must be re-derived by
 * the single [com.autonomousone.messages.messaging.OtpDetector] — reading it
 * here keeps the sweep from issuing one query per message.
 *
 * [confidence] / [eligibleAt] are null for a message that has never been
 * classified. The body and the derived code are NEVER persisted or logged.
 */
data class ExistingOtpCleanupCandidate(
    val source: String,
    val providerId: Long,
    val threadId: Long,
    val body: String,
    val date: Long,
    val rawAddress: String,
    val messageType: Int,
    val confidence: Float?,
    val eligibleAt: Long?
)

@Dao
interface ConversationClassificationDao {

    @Query("SELECT * FROM conversation_classification WHERE threadId = :threadId LIMIT 1")
    suspend fun get(threadId: Long): ConversationClassificationEntity?

    @Query("SELECT * FROM conversation_classification")
    suspend fun all(): List<ConversationClassificationEntity>

    /**
     * LIVE automatic categories, `threadId + name` only.
     *
     * Home combines this with [ConversationPreferenceDao]'s override column to
     * derive each thread's EFFECTIVE category. Two narrow projections instead of
     * a JOIN: the override must win at read time, and one indexed pass over the
     * conversation-sized projection is O(conversations), never O(messages).
     */
    @Query("SELECT threadId, category FROM conversation_classification")
    fun observeCategories(): Flow<List<ThreadCategoryRow>>

    @Upsert
    suspend fun upsert(classification: ConversationClassificationEntity)

    @Query("SELECT threadId FROM conversation_classification WHERE category = :category")
    suspend fun threadIdsForCategory(category: String): List<Long>

    @Query("SELECT threadId FROM conversation_classification WHERE category = :category")
    fun observeThreadIdsForCategory(category: String): Flow<List<Long>>

    @Query("DELETE FROM conversation_classification WHERE threadId = :threadId")
    suspend fun delete(threadId: Long)
}

/**
 * One message handed to the Media / Links / Files backfill sweep.
 *
 * Carries the body because link extraction is LOCAL and needs no provider read:
 * the sweep would otherwise issue one query per message.
 */
data class MessageAssetBackfillRow(
    val source: String,
    val providerId: Long,
    val threadId: Long,
    val body: String,
    val date: Long
)

/**
 * One page row of the LINKS tab: the asset plus the body of the message it was
 * extracted from, so the UI can derive a snippet locally (ONE query per page,
 * never one lookup per row).
 */
data class MessageAssetPageRow(
    val assetKey: String,
    val source: String,
    val providerId: Long,
    val threadId: Long,
    val kind: String,
    val value: String,
    val mimeType: String,
    val displayName: String,
    val date: Long,
    /** null when the message row is gone (the asset is then an orphan). */
    val body: String?
)

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

    /** Paged tab body: newest first, indexed on (threadId, date). Bounded. */
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

    @Query("SELECT COUNT(*) FROM message_assets WHERE threadId = :threadId AND kind = :kind")
    suspend fun countByKind(threadId: Long, kind: String): Int

    /**
     * LINKS tab page: the asset PLUS the source message body, so the tab can show
     * a locally derived snippet without a per-row lookup (see
     * [MessageAssetSql.PAGE_LINKS_WITH_BODY_SQL] for the pinned statement).
     */
    @Query(
        "SELECT a.assetKey AS assetKey, a.source AS source, a.providerId AS providerId, " +
            "a.threadId AS threadId, a.kind AS kind, a.value AS value, " +
            "a.mimeType AS mimeType, a.displayName AS displayName, a.date AS date, " +
            "m.body AS body " +
            "FROM message_assets a LEFT JOIN messages m " +
            "ON m.source = a.source AND m.providerId = a.providerId " +
            "WHERE a.threadId = :threadId AND a.kind = :kind " +
            "ORDER BY a.date DESC, a.assetKey DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun pageWithBodyByKind(
        threadId: Long,
        kind: String,
        limit: Int,
        offset: Int
    ): List<MessageAssetPageRow>

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

    /**
     * Convergent re-index of ONE kind for ONE message: an edited body that lost a
     * link, or an MMS whose parts changed, must not leave a stale row behind.
     * Safe only when the authoritative source (provider row, or a SUCCESSFUL part
     * read) really answered — a failed read must never wipe good rows.
     */
    @Query(
        "DELETE FROM message_assets " +
            "WHERE source = :source AND providerId = :providerId AND kind = :kind"
    )
    suspend fun deleteForMessageKind(source: String, providerId: Long, kind: String)

    /**
     * Checkpointed backfill batch (keyset on the canonical order). See
     * [MessageAssetSql.BACKFILL_BATCH_SQL] for why this walks `messages` rather
     * than "messages without assets".
     */
    @Query(
        "SELECT source, providerId, threadId, body, date FROM messages " +
            "WHERE (date < :afterDate " +
            "OR (date = :afterDate AND (source < :afterSource " +
            "OR (source = :afterSource AND providerId < :afterProviderId)))) " +
            "ORDER BY date DESC, source DESC, providerId DESC LIMIT :limit"
    )
    suspend fun backfillBatch(
        afterDate: Long,
        afterSource: String,
        afterProviderId: Long,
        limit: Int
    ): List<MessageAssetBackfillRow>

    @Query(
        "DELETE FROM message_assets WHERE NOT EXISTS (" +
            "SELECT 1 FROM messages m WHERE m.source = message_assets.source " +
            "AND m.providerId = message_assets.providerId)"
    )
    suspend fun deleteOrphans(): Int
}