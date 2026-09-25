package com.autonomousone.messages.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    /**
     * The history backfill's page read (mission §33/§34).
     *
     * Newest-first with a COMPOUND cursor `(date, providerId)`: `date` alone is not unique — a
     * phone can hold many messages in the same millisecond (bulk imports, multi-part bursts), and
     * a date-only cursor either skips rows or loops forever on them. `providerId` breaks the tie,
     * and it is compared only within the equal-date case so the index on `(date, providerId)` is
     * usable.
     *
     * Shared as a constant so the backfill benchmark executes the SHIPPED statement rather than a
     * retyped copy that can drift from it.
     */
    companion object {
        const val CLOUD_HISTORY_PAGE_SQL =
            "SELECT * FROM messages " +
                "WHERE source = :source AND " +
                "(date < :beforeDate OR (date = :beforeDate AND providerId < :beforeId)) " +
                "ORDER BY date DESC, source DESC, providerId DESC LIMIT :limit"
    }

    @Query(MessageDao.CLOUD_HISTORY_PAGE_SQL)
    suspend fun cloudHistoryPage(source: String, beforeDate: Long, beforeId: Long, limit: Int): List<MessageEntity>

    /** Newest-first window of one conversation (the hot read path). */
    @Query(
        """
        SELECT * FROM messages
        WHERE threadId = :threadId
        ORDER BY date DESC, source DESC, providerId DESC
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
        ORDER BY date DESC, source DESC, providerId DESC
        LIMIT :limit
        """
    )
    suspend fun newestWindowForThread(threadId: Long, limit: Int): List<MessageEntity>

    /** Reactive tail for the open conversation (Room invalidation drives UI). */
    @Query(
        """
        SELECT * FROM messages
        WHERE threadId = :threadId
        ORDER BY date DESC, source DESC, providerId DESC
        LIMIT :limit
        """
    )
    fun observeThread(threadId: Long, limit: Int): Flow<List<MessageEntity>>

    /**
     * ACTIVE-UI twin of [observeThread] (v3.4.0 FEATURE 8, TRASH).
     *
     * The conversation window is part of the ACTIVE UI, so its reactive tail must
     * not carry a row the user trashed — individually, or as part of a trashed
     * conversation's snapshot. [observeThread] stays RAW for sync/repair callers
     * that must keep seeing the complete mirror.
     */
    @Query(
        """
        SELECT m.* FROM messages m
        WHERE m.threadId = :threadId AND ${MessageCutoff.ACTIVE_MESSAGE_FILTER_SQL}
        ORDER BY m.date DESC, m.source DESC, m.providerId DESC
        LIMIT :limit
        """
    )
    fun observeActiveThread(threadId: Long, limit: Int): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT * FROM messages
        WHERE normalizedAddress = :address
        ORDER BY date DESC, source DESC, providerId DESC
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

    // ── ACTIVE-UI twins (v3.4.0 FEATURE 8, TRASH) ───────────────────────────
    //
    // `conversations` is the ACTIVE UI — what Home renders — so a projection
    // builder must NEVER read `newestForThread` (RAW MIRROR): it still holds the
    // rows of a trashed conversation, and rebuilding from it would resurrect
    // exactly the item the user deleted. These twins apply the ONE shared
    // ACTIVE-UI predicate, so "hidden" and "not projected" cannot drift.
    //
    // The RAW queries above stay untouched: sync, the integrity audit and the
    // repair queue must keep seeing the complete provider mirror.

    /**
     * NEWEST ACTIVE row of one thread, or null when every row is hidden by user
     * state (individually trashed, or inside a trashed-conversation snapshot).
     * A null answers "this conversation must leave Home"; a row answers "this is
     * the snippet Home shows".
     */
    @Query(NEWEST_ACTIVE_FOR_THREAD_SQL)
    suspend fun newestActiveForThread(threadId: Long): MessageEntity?

    /** Incoming unread count of the ACTIVE rows of one thread. */
    @Query(COUNT_ACTIVE_UNREAD_SQL)
    suspend fun countActiveUnread(threadId: Long): Int

    /** Newest ACTIVE row per thread, for the recovery projection rebuild. */
    @Query(NEWEST_ACTIVE_PER_THREAD_SQL)
    suspend fun newestActivePerThread(): List<MessageEntity>

    /** ACTIVE incoming unread count per thread, for the same rebuild. */
    @Query(UNREAD_ACTIVE_COUNTS_BY_THREAD_SQL)
    suspend fun unreadActiveCountsByThread(): List<ThreadUnreadCount>

    /**
     * PERMANENT purge of one tombstone's Room range (TRASH).
     *
     * Called ONLY after the provider delete for the same range was CONFIRMED, and
     * only while the tombstone row still exists. Returns the number of removed
     * rows. It never touches a message newer than the tombstone cutoff.
     */
    @Query(DELETE_TRASHED_SNAPSHOT_SQL)
    suspend fun deleteTrashedSnapshot(threadId: Long): Int

    @Query("SELECT MAX(date) FROM messages WHERE source = :source")
    suspend fun newestDateFor(source: String): Long?

    /** Source-scoped Room keyset for integrity audit; never OFFSETs through history. */
    @Query("""
        SELECT * FROM messages
        WHERE source = :source
          AND (date < :beforeDate OR (date = :beforeDate AND providerId < :beforeId))
        ORDER BY date DESC, providerId DESC
        LIMIT :limit
    """)
    suspend fun auditPageForSource(source: String, beforeDate: Long, beforeId: Long, limit: Int): List<MessageEntity>

    /** Room rows provably inside a bounded provider thread page. */
    @Query("""
        SELECT * FROM messages
        WHERE source = :source AND threadId = :threadId
          AND (date > :boundaryDate OR (date = :boundaryDate AND providerId >= :boundaryProviderId))
        ORDER BY date DESC, providerId DESC
    """)
    suspend fun rowsInCoveredThreadRange(
        source: String,
        threadId: Long,
        boundaryDate: Long,
        boundaryProviderId: Long
    ): List<MessageEntity>

    /** Small bounded overlap set for a generic provider notification. */
    @Query("""
        SELECT threadId FROM messages
        WHERE threadId > 0
        GROUP BY threadId
        ORDER BY MAX(date) DESC
        LIMIT :limit
    """)
    suspend fun recentThreadIds(limit: Int): List<Long>

    /**
     * Provider DIRECTION of one message (FEATURE 14 eligibility).
     *
     * `message_classification` deliberately does not duplicate provider truth, so
     * the OTP cleanup must read `type` from the mirror. Point lookup on the
     * composite primary key; null when the row is not mirrored (in which case
     * cleanup refuses to act — never trash on missing data).
     */
    @Query("SELECT type FROM messages WHERE source = :source AND providerId = :providerId")
    suspend fun typeOf(source: String, providerId: Long): Int?

    /** Own timestamp of one message (FEATURE 14 wrong-clock healing). */
    @Query("SELECT date FROM messages WHERE source = :source AND providerId = :providerId")
    suspend fun dateOf(source: String, providerId: Long): Long?

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

    /**
     * Marks EVERY unread message read, in one statement.
     *
     * "Mark all read" is a whole-table fact, not a property of the rows a
     * particular Home filter happened to be rendering: a per-row loop skipped
     * archived, filtered, blocked and off-screen conversations and left unread
     * rows that reappear the moment the filter changes.
     */
    @Query("UPDATE messages SET read = 1 WHERE read = 0")
    suspend fun markAllRead()

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

    /**
     * Resolve ONE row by composite identity under the ACTIVE-UI contract.
     *
     * [findByKey] is deliberately RAW (sync/integrity callers need the row even
     * when the user trashed it). In-conversation search must never land on a
     * trashed hit, so JUMP-TO-RESULT uses this variant: the same indexed
     * (source, providerId) primary-key lookup, with the individually-trashed row
     * excluded so a search result the user deleted cannot be jumped to.
     *
     * CONTRACT (shared with the open-conversation / trash readers):
     *  - returns `null` for a missing row AND for an individually-trashed row —
     *    the two are indistinguishable to an ACTIVE-UI caller on purpose;
     *  - a row hidden by a thread TOMBSTONE is still returned: the tombstone
     *    describes the thread's snapshot, not this row's own user state, and the
     *    window queries below apply it. Callers that need both checks must use
     *    [windowBefore] / [windowAfter] or the FTS search, which apply the full
     *    [MessageCutoff.ACTIVE_MESSAGE_FILTER_SQL];
     *  - RAW callers keep [findByKey].
     */
    @Query(
        """
        SELECT * FROM messages m
        WHERE m.source = :source AND m.providerId = :providerId
          AND ${MessageCutoff.NOT_INDIVIDUALLY_TRASHED_SQL}
        LIMIT 1
        """
    )
    suspend fun findActiveByKey(source: String, providerId: Long): MessageEntity?

    /**
     * ACTIVE-UI bounded window OLDER-OR-EQUAL to the canonical anchor
     * `(anchorDate, anchorSource, anchorProviderId)`.
     *
     * INCLUSIVE on purpose: the same query carries the anchor row itself, so a
     * jump-to-message needs ONE read for the older half rather than a
     * read + a separate anchor fetch.
     *
     * Keyset, never OFFSET: `(date, source, providerId)` is the exact canonical
     * order of this table, so the predicate seeks straight into the index on
     * `(threadId, date, providerId)` and reads at most :limit rows regardless of
     * how large the thread is. Rows come back NEWEST-FIRST
     * (`date DESC, source DESC, providerId DESC`) because the loader prepends
     * them; callers flip, never re-sort.
     *
     * ACTIVE-UI filtered with the ONE shared predicate
     * ([MessageCutoff.ACTIVE_MESSAGE_FILTER_SQL] = not individually trashed AND
     * not hidden by a thread tombstone). The `m` alias is a hard requirement of
     * those shared SQL fragments: do not rename it.
     *
     * Read it with an anchor of `(Long.MAX_VALUE, "\uFFFF", Long.MAX_VALUE)` to
     * ask for "the newest :limit active rows of this thread".
     */
    @Query(
        """
        SELECT * FROM messages m
        WHERE m.threadId = :threadId
          AND (m.date < :anchorDate
               OR (m.date = :anchorDate AND m.source < :anchorSource)
               OR (m.date = :anchorDate AND m.source = :anchorSource
                   AND m.providerId <= :anchorProviderId))
          AND ${MessageCutoff.ACTIVE_MESSAGE_FILTER_SQL}
        ORDER BY m.date DESC, m.source DESC, m.providerId DESC
        LIMIT :limit
        """
    )
    suspend fun windowBefore(
        threadId: Long,
        anchorDate: Long,
        anchorSource: String,
        anchorProviderId: Long,
        limit: Int
    ): List<MessageEntity>

    /**
     * ACTIVE-UI bounded window STRICTLY NEWER than the canonical anchor
     * `(anchorDate, anchorSource, anchorProviderId)`, returned in canonical
     * ASCENDING order (the paint order the conversation LazyColumn uses).
     *
     * STRICTLY after, so this half and [windowBefore] never overlap and the
     * anchor row is painted exactly once. Same keyset/index/ACTIVE-UI contract
     * and the same `m` alias requirement as [windowBefore].
     */
    @Query(
        """
        SELECT * FROM messages m
        WHERE m.threadId = :threadId
          AND (m.date > :anchorDate
               OR (m.date = :anchorDate AND m.source > :anchorSource)
               OR (m.date = :anchorDate AND m.source = :anchorSource
                   AND m.providerId > :anchorProviderId))
          AND ${MessageCutoff.ACTIVE_MESSAGE_FILTER_SQL}
        ORDER BY m.date ASC, m.source ASC, m.providerId ASC
        LIMIT :limit
        """
    )
    suspend fun windowAfter(
        threadId: Long,
        anchorDate: Long,
        anchorSource: String,
        anchorProviderId: Long,
        limit: Int
    ): List<MessageEntity>

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

    /**
     * ACTIVE-UI conversation list (v3.4.0 FEATURE 8, TRASH).
     *
     * `conversations` is a MATERIALIZED projection, so it can hold a row that the
     * ACTIVE-UI contract no longer exposes: a trashed conversation whose snapshot
     * is hidden by its tombstone, or a thread whose every message is individually
     * trashed. Home must never render such a row — not even for the instant
     * between a process restart and the next projection rebuild — so the predicate
     * lives in the QUERY, not only in the writer.
     *
     * The predicate is the ONE shared ACTIVE-UI rule ([MessageCutoff]), evaluated
     * per conversation with an indexed `EXISTS` on `messages.threadId`: a trashed
     * conversation with a genuinely NEWER message (or any surviving active
     * message) still lists, and a conversation with no visible message at all
     * cannot be listed by any reader of this query.
     *
     * RAW callers (sync/integrity/repair) keep using [observeAll] / [all] and
     * `MessageDao`'s RAW queries: the mirror must still see everything.
     */
    @Query(
        """
        SELECT c.* FROM conversations c
        WHERE EXISTS (
            SELECT 1 FROM messages m
            WHERE m.threadId = c.threadId
              AND ${MessageCutoff.ACTIVE_MESSAGE_FILTER_SQL}
        )
        ORDER BY c.lastMessageDate DESC
        """
    )
    fun observeAllActive(): Flow<List<ConversationEntity>>

    /** One-shot twin of [observeAllActive] (cold-start paint, cache snapshot). */
    @Query(
        """
        SELECT c.* FROM conversations c
        WHERE EXISTS (
            SELECT 1 FROM messages m
            WHERE m.threadId = c.threadId
              AND ${MessageCutoff.ACTIVE_MESSAGE_FILTER_SQL}
        )
        ORDER BY c.lastMessageDate DESC
        """
    )
    suspend fun allActive(): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE threadId = :threadId")
    suspend fun byThread(threadId: Long): ConversationEntity?

    /** Exact first pass for external sms:/smsto: launches. */
    @Query(
        """
        SELECT * FROM conversations
        WHERE normalizedAddress = :normalizedAddress
        ORDER BY lastMessageDate DESC
        LIMIT 1
        """
    )
    suspend fun newestByNormalizedAddress(normalizedAddress: String): ConversationEntity?

    /**
     * Bounded country-code fallback. The caller still applies
     * ContactRepository.sameConversation to every candidate, so short codes
     * and coincidental partial matches never become a navigation target.
     */
    @Query(
        """
        SELECT * FROM conversations
        WHERE length(normalizedAddress) >= :minimumLength
          AND normalizedAddress LIKE '%' || :suffix
        ORDER BY lastMessageDate DESC
        LIMIT :limit
        """
    )
    suspend fun recentByAddressSuffix(
        suffix: String,
        minimumLength: Int,
        limit: Int
    ): List<ConversationEntity>

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

    /**
     * Clears every conversation's unread counter in one statement.
     *
     * Pairs with MessageDao.markAllRead inside ONE coordinator transaction, so the
     * message flags and the projection cannot diverge.
     */
    @Query("UPDATE conversations SET unreadCount = 0 WHERE unreadCount != 0")
    suspend fun markAllRead()

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
            "lastFailureReason, createdAt, updatedAt, intent, intentSince) " +
            "VALUES (:source, :providerId, 1, 'PENDING', 0, :now, 0, '', :now, :now, " +
            ":intent, :now) " +
            "ON CONFLICT(source, providerId) DO UPDATE SET " +
            "generation = provider_repair_queue.generation + 1, " +
            "state = 'PENDING', attempts = 0, nextRetryAt = :now, leaseUntil = 0, " +
            "updatedAt = :now, intent = :intent, intentSince = :now, absenceCount = 0"
    )
    suspend fun enqueue(source: String, providerId: Long, now: Long, intent: String)

    /**
     * Re-arms the row under a DIFFERENT intent with a NEW generation.
     *
     * The absence-maturity policy needs this: a matured EXPECT_EXISTS or
     * REFRESH_STATUS absence must never delete, so it is converted into a fresh
     * VERIFY_DELETE_CANDIDATE generation, which requires its own independent
     * successful absence read before any delete. Scoped to the generation the
     * caller claimed, so a newer intent is never overwritten.
     */
    @Query(
        "UPDATE provider_repair_queue SET generation = generation + 1, " +
            "intent = :intent, intentSince = :now, state = 'PENDING', attempts = 0, " +
            "absenceCount = 0, nextRetryAt = :now, leaseUntil = 0, updatedAt = :now " +
            "WHERE source = :source AND providerId = :providerId " +
            "AND generation = :generation"
    )
    suspend fun rearm(
        source: String,
        providerId: Long,
        generation: Long,
        intent: String,
        now: Long
    ): Int

    /**
     * Records ONE successful provider-absence observation.
     *
     * Generation- and lease-scoped: an older worker cannot contribute evidence to
     * a newer generation, and a row that is not currently IN_FLIGHT cannot be
     * credited. Returns the number of rows updated, so the caller can tell whether
     * its observation actually counted.
     */
    @Query(
        "UPDATE provider_repair_queue SET absenceCount = absenceCount + 1, " +
            "updatedAt = :now WHERE source = :source AND providerId = :providerId " +
            "AND generation = :generation AND state = 'IN_FLIGHT'"
    )
    suspend fun recordAbsence(source: String, providerId: Long, generation: Long, now: Long): Int

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

@Dao
interface IntegrityAuditDao {
    @Query("SELECT * FROM integrity_audit_state WHERE source = :source AND direction = :direction LIMIT 1")
    suspend fun get(source: String, direction: String): IntegrityAuditStateEntity?

    @Upsert
    suspend fun upsert(state: IntegrityAuditStateEntity)

    @Query("SELECT * FROM integrity_audit_state")
    suspend fun all(): List<IntegrityAuditStateEntity>
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

    /**
     * IN-CONVERSATION SEARCH (v3.4.0).
     *
     * Reuses the SAME `messages_fts` index — NO second FTS index. The per-thread
     * pin (`m.threadId = :threadId`) is what bounds a 100K-message conversation,
     * and trashed rows (individually, or hidden by a thread tombstone) are
     * excluded so search never resurfaces deleted history.
     *
     * OFFSET here is bounded by the number of MATCHING rows actually paged
     * through (the FTS index answers the MATCH first, then the join), not by
     * the size of the thread: a 100K-message conversation whose query matches
     * 40 rows costs 40. Jump-to-message never pages at all — it resolves the
     * exact row and reads a bounded keyset window (MessageDao.windowBefore /
     * windowAfter).
     */
    @Query(
        """
        SELECT m.source AS source, m.providerId AS providerId, m.threadId AS threadId,
               m.body AS body, m.date AS date
        FROM messages_fts
        JOIN messages m ON messages_fts.docid = m.rowid
        WHERE messages_fts MATCH :match
          AND m.threadId = :threadId
          AND NOT EXISTS (
              SELECT 1 FROM message_user_state us
              WHERE us.source = m.source
                AND us.providerId = m.providerId
                AND us.trashedAt > 0
          )
          AND NOT EXISTS (
              SELECT 1 FROM trashed_threads t
              WHERE t.threadId = m.threadId AND ${MessageCutoff.HIDDEN_BY_TOMBSTONE_SQL}
          )
        ORDER BY m.date DESC, m.source DESC, m.providerId DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun searchThread(
        threadId: Long,
        match: String,
        limit: Int,
        offset: Int
    ): List<ConversationSearchHit>

    /** Total in-conversation matches, for the "3 of 14" counter. */
    @Query(
        """
        SELECT COUNT(*)
        FROM messages_fts
        JOIN messages m ON messages_fts.docid = m.rowid
        WHERE messages_fts MATCH :match
          AND m.threadId = :threadId
          AND NOT EXISTS (
              SELECT 1 FROM message_user_state us
              WHERE us.source = m.source
                AND us.providerId = m.providerId
                AND us.trashedAt > 0
          )
          AND NOT EXISTS (
              SELECT 1 FROM trashed_threads t
              WHERE t.threadId = m.threadId AND ${MessageCutoff.HIDDEN_BY_TOMBSTONE_SQL}
          )
        """
    )
    suspend fun countThreadMatches(threadId: Long, match: String): Int
}

/** In-conversation search result (v3.4.0 FEATURE 1). */
data class ConversationSearchHit(
    val source: String,
    val providerId: Long,
    val threadId: Long,
    val body: String,
    val date: Long
)
