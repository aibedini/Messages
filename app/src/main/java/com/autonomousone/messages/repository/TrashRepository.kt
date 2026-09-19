package com.autonomousone.messages.repository

import android.content.Context
import androidx.room.withTransaction
import com.autonomousone.messages.data.MessageCutoff
import com.autonomousone.messages.data.MessageEntity
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.TrashedThreadDao
import com.autonomousone.messages.data.TrashedThreadEntity
import com.autonomousone.messages.utils.DiagnosticLog
import kotlinx.coroutines.flow.Flow

/**
 * Room-side seam for the trash tombstone table (plus the mirror cleanup a
 * confirmed purge performs).
 *
 * WHY IT EXISTS: the whole TRASH contract — "a delete hides, it does not
 * destroy", "Restore needs no provider re-insert", "a purge removes Room state
 * only AFTER the provider confirmed", "the retention is durable, never an
 * in-memory timer" — is the behaviour of [TrashRepository], so that behaviour
 * must be testable without an Android device. `RoomTrashStore` is the Android
 * implementation; a JVM test substitutes an in-memory store and a fake
 * [TrashRepository.ProviderPurger].
 */
interface TrashStore {

    fun observeAll(): Flow<List<TrashedThreadEntity>>

    suspend fun get(threadId: Long): TrashedThreadEntity?

    fun observe(threadId: Long): Flow<TrashedThreadEntity?>

    /** Newest deletion first — the Recently Deleted screen order. */
    suspend fun allNewestFirst(): List<TrashedThreadEntity>

    suspend fun trashedThreadIds(): List<Long>

    /** Index-backed on `purgeAt`; LIMIT-bounded, never a table scan. */
    suspend fun dueForPurge(now: Long, limit: Int): List<TrashedThreadEntity>

    suspend fun earliestPurgeAt(): Long?

    suspend fun upsert(tombstone: TrashedThreadEntity)

    suspend fun delete(threadId: Long)

    suspend fun deleteAll(threadIds: List<Long>)

    /**
     * Removes the tombstone's DELETED SNAPSHOT from the Room MIRROR: the message
     * rows at-or-before the cutoff and their user state.
     *
     * Called ONLY after the provider delete for the same range was CONFIRMED, and
     * ONLY while the tombstone still exists (the statement joins it). A message
     * newer than the cutoff survives, so a conversation that came back to life
     * after the delete keeps its new message and its projection.
     */
    suspend fun deleteSnapshot(tombstone: TrashedThreadEntity)
}

/** The Room implementation. Every query is the pinned SQL in `data/TrashSql.kt`. */
internal class RoomTrashStore(private val database: MessagesDatabase) : TrashStore {

    private val dao: TrashedThreadDao get() = database.trashedThreadDao()

    override fun observeAll(): Flow<List<TrashedThreadEntity>> = dao.observeAll()

    override suspend fun get(threadId: Long): TrashedThreadEntity? = dao.get(threadId)

    override fun observe(threadId: Long): Flow<TrashedThreadEntity?> = dao.observe(threadId)

    override suspend fun allNewestFirst(): List<TrashedThreadEntity> = dao.allNewestFirst()

    override suspend fun trashedThreadIds(): List<Long> = dao.trashedThreadIds()

    override suspend fun dueForPurge(now: Long, limit: Int): List<TrashedThreadEntity> =
        dao.dueForPurge(now, limit)

    override suspend fun earliestPurgeAt(): Long? = dao.earliestPurgeAt()

    override suspend fun upsert(tombstone: TrashedThreadEntity) = dao.upsert(tombstone)

    override suspend fun delete(threadId: Long) = dao.delete(threadId)

    override suspend fun deleteAll(threadIds: List<Long>) = dao.deleteAll(threadIds)

    override suspend fun deleteSnapshot(tombstone: TrashedThreadEntity) {
        database.withTransaction {
            // User state FIRST: its statement joins `messages` (while the rows and
            // the tombstone still exist) and would match nothing afterwards.
            database.messageUserStateDao().deleteTrashedSnapshotUserState(tombstone.threadId)
            database.messageDao().deleteTrashedSnapshot(tombstone.threadId)
        }
        // No conversation-projection rebuild is needed here: every row this
        // removed was already HIDDEN from the ACTIVE UI by the tombstone, so the
        // projection (built from ACTIVE rows) cannot change. The tombstone row
        // itself is removed by [TrashRepository] right after this returns.
    }
}

/**
 * Durable, reversible TRASH (v3.4.0) — the evolution of the old 4-second Undo
 * Delete, NOT a second delete flow.
 *
 * Rules this class exists to enforce:
 *
 *  1. Deleting a conversation writes ONE tombstone in `trashed_threads`, never
 *     one user-state row per message (a 100K-message conversation would create
 *     100K rows in a single user action).
 *  2. Provider rows are untouched at trash time, so RESTORE needs no provider
 *     re-insert: clearing the tombstone is enough for Room to rebuild the
 *     projection from the mirror it still holds.
 *  3. The tombstone stores the canonical NEWEST row (date, source, providerId).
 *     Messages at-or-before it are hidden; a genuinely NEW message after it is
 *     VISIBLE and re-creates the conversation. A threadId is never hidden
 *     forever, which would silently swallow every future message.
 *  4. A permanent purge deletes ONLY the canonical range the tombstone stands
 *     for, and ONLY after the provider write SUCCEEDS. On provider failure the
 *     trash state is kept and retried — we never claim "deleted" while the
 *     provider still holds the rows. Room state (mirror rows, user state,
 *     tombstone) is removed strictly AFTER that confirmation.
 *  5. Nothing here is an in-memory timer: the retention deadline lives in the
 *     tombstone row, so it survives process death and reboot, and a purge that
 *     runs late still purges exactly what is due.
 *
 * Room-side state lives here; the provider half is injected as [ProviderPurger]
 * so the existing strict provider-write path is reused without this repository
 * growing a second provider implementation.
 */
class TrashRepository internal constructor(
    private val store: TrashStore,
    private val purger: ProviderPurger
) {

    /**
     * Android wiring: Room store + the real strict provider purger.
     *
     * A SECOND public constructor is deliberately avoided — `TrashRepository(context, purger)`
     * stays the one Android-facing shape, and the `internal` store constructor is
     * the test seam.
     */
    constructor(context: Context, purger: ProviderPurger) : this(
        RoomTrashStore(MessagesDatabase.get(context)),
        purger
    )

    /**
     * Deletes provider rows for the canonical range a tombstone stands for.
     *
     * Implementations MUST use the existing strict provider-write path (never a
     * whole-thread Telephony scan), MUST return false on any provider failure so
     * the caller keeps the trash state and retries, and MUST NOT touch messages
     * newer than the tombstone cutoff.
     */
    fun interface ProviderPurger {
        /** True only when the provider really deleted the rows. */
        suspend fun purge(tombstone: TrashedThreadEntity): Boolean
    }

    companion object {
        /** Default retention shown by the Recently Deleted screen. */
        const val RETENTION_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

        /**
         * Tombstones one "Empty Trash" or worker run may purge.
         *
         * `trashed_threads` holds ONE row per trashed CONVERSATION, so this is not
         * a message-scale bound; it exists so a single run stays a bounded piece
         * of provider work. Whatever is left is reported as `remaining` and the
         * caller loops (or the user taps again), never silently dropped.
         */
        const val PURGE_BATCH_LIMIT: Int = 200

        private const val DAY_MS = 24L * 60 * 60 * 1000

        /**
         * The ONE trash repository (Room store + strict provider purger).
         *
         * No Hilt in this app: repositories are process singletons created from a
         * Context, exactly like the other repositories.
         */
        fun get(context: Context): TrashRepository =
            TrashRepository(context.applicationContext, TrashProviderPurger(context))
    }

    fun observeAll(): Flow<List<TrashedThreadEntity>> = store.observeAll()

    suspend fun get(threadId: Long): TrashedThreadEntity? = store.get(threadId)

    fun observe(threadId: Long): Flow<TrashedThreadEntity?> = store.observe(threadId)

    suspend fun trashedThreadIds(): List<Long> = store.trashedThreadIds()

    /** Recently Deleted list source: newest deletion first. */
    suspend fun allTrashed(): List<TrashedThreadEntity> = store.allNewestFirst()

    /** True when nothing is in Trash — backs the screen's Empty state. */
    suspend fun isEmpty(): Boolean = store.trashedThreadIds().isEmpty()

    /**
     * Moves a conversation to Trash.
     *
     * [newestActive] is the canonical newest message of the thread AT TRASH TIME
     * (`MessageDao.newestForThread` — deliberately the RAW mirror, because the
     * cutoff must describe every row the user is deleting, not only the rows an
     * ACTIVE query would return). It is the cutoff: everything at-or-before it
     * is the deleted snapshot. A null cutoff means the thread had no mirrorable
     * rows, so nothing can be hidden and restore simply clears the tombstone.
     *
     * NO provider write happens here: deleting provider rows at trash time would
     * make Undo impossible, which is exactly the defect of the old 4-second flow.
     */
    suspend fun moveToTrash(
        threadId: Long,
        newestActive: MessageEntity?,
        now: Long,
        retentionMillis: Long = RETENTION_MILLIS
    ): TrashedThreadEntity {
        val tombstone = TrashedThreadEntity(
            threadId = threadId,
            deletedAt = now,
            purgeAt = now + retentionMillis,
            cutoffDate = newestActive?.date ?: 0L,
            cutoffSource = newestActive?.source ?: "sms",
            cutoffProviderId = newestActive?.providerId ?: 0L
        )
        store.upsert(tombstone)
        DiagnosticLog.event(
            "TRASH",
            "thread=$threadId deletedAt=$now purgeAt=${tombstone.purgeAt} providerWrites=0"
        )
        return tombstone
    }

    /**
     * Undo / Restore. Removes the tombstone; Room immediately rebuilds the
     * projection from rows the provider mirror still holds. No provider write.
     */
    suspend fun restore(threadId: Long) {
        store.delete(threadId)
        DiagnosticLog.event("TRASH", "thread=$threadId restored=1 providerWrites=0")
    }

    suspend fun restoreAll(threadIds: List<Long>) {
        store.deleteAll(threadIds)
        DiagnosticLog.event("TRASH", "restored_count=${threadIds.size}")
    }

    /** Tombstones whose retention has elapsed (index-backed on purgeAt). */
    suspend fun dueForPurge(now: Long, limit: Int): List<TrashedThreadEntity> =
        store.dueForPurge(now, limit)

    suspend fun earliestPurgeAt(): Long? = store.earliestPurgeAt()

    /**
     * Purges tombstones that are due.
     *
     * Provider-first: only on a CONFIRMED provider delete are the Room rows, the
     * tombstone and its user state cleared. A provider failure leaves everything
     * in place so a later run retries; the caller reports partial failure instead
     * of claiming success.
     *
     * @return (purged, failed) thread counts.
     */
    suspend fun purgeDue(now: Long, limit: Int = 50): Pair<Int, Int> {
        val due = store.dueForPurge(now, limit)
        var purged = 0
        var failed = 0
        due.forEach { tombstone ->
            if (purgeOne(tombstone)) purged++ else failed++
        }
        if (due.isNotEmpty()) {
            DiagnosticLog.event("TRASH", "purged=$purged failed=$failed")
        }
        return purged to failed
    }

    /**
     * Permanently deletes ONE trashed conversation NOW, regardless of its
     * retention deadline — the Recently Deleted screen's "Delete permanently"
     * action.
     *
     * @return true only when the provider delete was confirmed AND the Room state
     * was removed. False means nothing was destroyed: the tombstone is intact and
     * a later run retries. The caller must never claim "deleted" otherwise.
     */
    suspend fun purgeNow(threadId: Long): Boolean {
        val tombstone = store.get(threadId) ?: return true // already gone
        return purgeOne(tombstone)
    }

    /** What one "Empty Trash" run achieved. */
    data class EmptyTrashOutcome(
        val purged: Int,
        val failed: Int,
        /** Tombstones left in Trash because the batch limit was reached. */
        val remaining: Int
    ) {
        val complete: Boolean get() = remaining == 0
    }

    /**
     * Permanently deletes the ENTIRE Trash, in bounded batches.
     *
     * Failure-isolated per conversation: one provider failure keeps that
     * tombstone (and only that one) so it is retried later.
     */
    suspend fun emptyTrash(now: Long, limit: Int = PURGE_BATCH_LIMIT): EmptyTrashOutcome {
        val all = store.allNewestFirst()
        val batch = all.take(limit)
        var purged = 0
        var failed = 0
        batch.forEach { tombstone ->
            if (purgeOne(tombstone)) purged++ else failed++
        }
        val remaining = (all.size - batch.size).coerceAtLeast(0)
        DiagnosticLog.event(
            "TRASH",
            "empty purged=$purged failed=$failed remaining=$remaining now=$now"
        )
        return EmptyTrashOutcome(purged = purged, failed = failed, remaining = remaining)
    }

    /**
     * ONE tombstone's permanent delete, in the ONLY safe order:
     *
     *   1. provider delete of the canonical range (strict path, verified);
     *   2. ONLY on confirmation, Room: user state + mirror rows of the snapshot,
     *      then the tombstone.
     *
     * A failure or an exception at step 1 returns false with NOTHING removed, so
     * Trash still shows the conversation and a later run retries it.
     */
    private suspend fun purgeOne(tombstone: TrashedThreadEntity): Boolean {
        val providerDeleted = try {
            purger.purge(tombstone)
        } catch (error: Throwable) {
            DiagnosticLog.event("TRASH", "thread=${tombstone.threadId} purge_error", error)
            false
        }
        if (!providerDeleted) {
            DiagnosticLog.event("TRASH", "thread=${tombstone.threadId} purge_retry_later")
            return false
        }
        return try {
            store.deleteSnapshot(tombstone)
            store.delete(tombstone.threadId)
            DiagnosticLog.event(
                "TRASH",
                "thread=${tombstone.threadId} purged=1 cutoff=${tombstone.cutoffDate}"
            )
            true
        } catch (error: Throwable) {
            // The provider rows are gone but the local cleanup failed. Keeping the
            // tombstone is the honest state: the next run re-confirms the provider
            // range (already empty) and finishes the local cleanup.
            DiagnosticLog.event("TRASH", "thread=${tombstone.threadId} purge_local_error", error)
            false
        }
    }

    /** Days remaining before a tombstone purges, for the Recently Deleted row. */
    fun daysRemaining(tombstone: TrashedThreadEntity, now: Long): Long {
        val remaining = tombstone.purgeAt - now
        if (remaining <= 0L) return 0L
        return (remaining + DAY_MS - 1) / DAY_MS
    }

    /**
     * Kotlin mirror of the ACTIVE-UI visibility rule for one thread's rows. Used
     * by view models that already hold the tombstone in memory; DAO queries use
     * [MessageCutoff.VISIBLE_UNDER_TOMBSTONE_SQL] directly.
     */
    fun isVisibleUnderTombstone(
        tombstone: TrashedThreadEntity?,
        date: Long,
        source: String,
        providerId: Long
    ): Boolean = MessageCutoff.isVisible(tombstone, date, source, providerId)
}
