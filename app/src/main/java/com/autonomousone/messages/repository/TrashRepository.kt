package com.autonomousone.messages.repository

import android.content.Context
import com.autonomousone.messages.data.MessageEntity
import com.autonomousone.messages.data.MessageCutoff
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.TrashedThreadDao
import com.autonomousone.messages.data.TrashedThreadEntity
import com.autonomousone.messages.utils.DiagnosticLog
import kotlinx.coroutines.flow.Flow

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
 *     provider still holds the rows.
 *
 * Room-side state lives here; the provider half is injected as [ProviderPurger]
 * so Phase 6 wires the existing strict provider-write path without this
 * repository growing a second provider implementation.
 */
class TrashRepository(
    context: Context,
    private val purger: ProviderPurger
) {

    private val dao: TrashedThreadDao = MessagesDatabase.get(context).trashedThreadDao()

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
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }

    fun observeAll(): Flow<List<TrashedThreadEntity>> = dao.observeAll()

    suspend fun get(threadId: Long): TrashedThreadEntity? = dao.get(threadId)

    fun observe(threadId: Long): Flow<TrashedThreadEntity?> = dao.observe(threadId)

    suspend fun trashedThreadIds(): List<Long> = dao.trashedThreadIds()

    /**
     * Moves a conversation to Trash.
     *
     * [newestActive] is the canonical newest message of the thread AT TRASH TIME
     * (`MessageDao.newestForThread`). It is the cutoff: everything at-or-before
     * it is the deleted snapshot. A null cutoff means the thread had no
     * mirrorable rows, so nothing can be hidden and restore simply clears the
     * tombstone.
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
        dao.upsert(tombstone)
        DiagnosticLog.event(
            "TRASH",
            "thread=$threadId deletedAt=$now purgeAt=${tombstone.purgeAt}"
        )
        return tombstone
    }

    /**
     * Undo / Restore. Removes the tombstone; Room immediately rebuilds the
     * projection from rows the provider mirror still holds. No provider write.
     */
    suspend fun restore(threadId: Long) {
        dao.delete(threadId)
        DiagnosticLog.event("TRASH", "thread=$threadId restored=1")
    }

    suspend fun restoreAll(threadIds: List<Long>) {
        dao.deleteAll(threadIds)
        DiagnosticLog.event("TRASH", "restored_count=${threadIds.size}")
    }

    /** Tombstones whose retention has elapsed (index-backed on purgeAt). */
    suspend fun dueForPurge(now: Long, limit: Int): List<TrashedThreadEntity> =
        dao.dueForPurge(now, limit)

    suspend fun earliestPurgeAt(): Long? = dao.earliestPurgeAt()

    /**
     * Purges tombstones that are due.
     *
     * Provider-first: only on a CONFIRMED provider delete are the tombstone and
     * its Room range cleared. A provider failure leaves everything in place so a
     * later run retries; the caller reports partial failure instead of claiming
     * success.
     *
     * @return (purged, failed) thread counts.
     */
    suspend fun purgeDue(now: Long, limit: Int = 50): Pair<Int, Int> {
        val due = dao.dueForPurge(now, limit)
        var purged = 0
        var failed = 0
        due.forEach { tombstone ->
            val providerDeleted = try {
                purger.purge(tombstone)
            } catch (error: Throwable) {
                DiagnosticLog.event("TRASH", "thread=${tombstone.threadId} purge_error", error)
                false
            }
            if (providerDeleted) {
                dao.delete(tombstone.threadId)
                purged++
            } else {
                failed++
                DiagnosticLog.event("TRASH", "thread=${tombstone.threadId} purge_retry_later")
            }
        }
        if (due.isNotEmpty()) {
            DiagnosticLog.event("TRASH", "purged=$purged failed=$failed")
        }
        return purged to failed
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