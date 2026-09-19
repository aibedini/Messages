package com.autonomousone.messages.repository

import android.content.Context
import com.autonomousone.messages.data.MessageKey
import com.autonomousone.messages.data.MessageUserStateDao
import com.autonomousone.messages.data.MessageUserStateEntity
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.StarredMessageRow
import com.autonomousone.messages.utils.DiagnosticLog
import kotlinx.coroutines.flow.Flow

/**
 * Per-message user state (v3.4.0): starring, individual trash, OTP-cleanup opt
 * -out.
 *
 * Identity is ALWAYS (source, providerId) — [MessageKey], never a raw provider
 * id, because SMS 100 and MMS 100 are different messages. Nothing here is
 * reachable from a provider Upsert, so a star cannot be wiped by a refresh.
 */
class MessageUserStateRepository(context: Context) {

    private val dao: MessageUserStateDao =
        MessagesDatabase.get(context).messageUserStateDao()

    fun observe(source: String, providerId: Long): Flow<MessageUserStateEntity?> =
        dao.observe(source, providerId)

    suspend fun get(source: String, providerId: Long): MessageUserStateEntity? =
        dao.get(source, providerId)

    suspend fun isStarred(source: String, providerId: Long): Boolean =
        dao.get(source, providerId)?.starred == true

    suspend fun countStarredInThread(threadId: Long): Int =
        dao.countStarredInThread(threadId)

    fun observeStarredCountInThread(threadId: Long): Flow<Int> =
        dao.observeStarredCountInThread(threadId)

    /** Newest-first, ACTIVE-UI filtered, paged — never a full history load. */
    suspend fun starredPage(limit: Int, offset: Int): List<StarredMessageRow> =
        dao.starredPage(limit, offset)

    suspend fun starredPageInThread(
        threadId: Long,
        limit: Int,
        offset: Int
    ): List<StarredMessageRow> = dao.starredPageInThread(threadId, limit, offset)

    /**
     * Star / unstar. Field-scoped: this can never un-trash the row, and a star
     * is exactly what exempts a message from global OTP cleanup.
     */
    suspend fun setStarred(
        key: MessageKey,
        threadId: Long,
        starred: Boolean,
        now: Long
    ) {
        dao.setStarred(
            source = key.source,
            providerId = key.providerId,
            threadId = threadId,
            starred = starred,
            starredAt = if (starred) now else 0L,
            now = now
        )
        DiagnosticLog.event(
            "STAR",
            "thread=$threadId source=${key.source} starred=$starred"
        )
    }

    suspend fun setKeepFromOtpCleanup(
        key: MessageKey,
        threadId: Long,
        keep: Boolean,
        now: Long
    ) {
        dao.setKeepFromOtpCleanup(
            source = key.source,
            providerId = key.providerId,
            threadId = threadId,
            keep = keep,
            now = now
        )
        DiagnosticLog.event("OTP_RETENTION", "thread=$threadId keep=$keep")
    }

    suspend fun markTrashed(
        keys: Collection<MessageKey>,
        threadId: Long,
        trashedAt: Long,
        purgeAt: Long,
        now: Long
    ) {
        keys.forEach { key ->
            dao.markTrashed(
                source = key.source,
                providerId = key.providerId,
                threadId = threadId,
                trashedAt = trashedAt,
                purgeAt = purgeAt,
                now = now
            )
        }
        DiagnosticLog.event("TRASH", "thread=$threadId messages=${keys.size}")
    }

    suspend fun restore(key: MessageKey, now: Long) {
        dao.restore(source = key.source, providerId = key.providerId, now = now)
        DiagnosticLog.event("TRASH", "restored source=${key.source} id=${key.providerId}")
    }

    suspend fun dueForPurge(now: Long, limit: Int): List<MessageUserStateEntity> =
        dao.dueForPurge(now, limit)

    suspend fun earliestPurgeAt(): Long? = dao.earliestPurgeAt()

    suspend fun trashedInThread(threadId: Long): List<MessageUserStateEntity> =
        dao.trashedInThread(threadId)

    /**
     * Explicit orphan cleanup — the replacement for an FK CASCADE. Call ONLY for
     * identities the provider has PROVEN gone (a proven-absence repair), never on
     * a routine refresh: a refresh that deletes and re-inserts a row must not
     * take the user's star or trash flag with it.
     */
    suspend fun deleteOrphans(): Int {
        val removed = dao.deleteOrphans()
        if (removed > 0) {
            DiagnosticLog.event("STAR", "orphans_removed=$removed")
        }
        return removed
    }
}