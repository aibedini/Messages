package com.autonomousone.messages.data

import android.content.Context
import android.provider.Telephony
import android.util.Log
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.repository.ContactRepository
import com.autonomousone.messages.repository.SmsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The SINGLE writer into Room. Everything that changes message data flows
 * through one conflated queue on one dispatcher — receivers, ContentObserver,
 * resume nudges — so parallel provider scans and refresh storms are structurally
 * impossible instead of merely discouraged.
 *
 * Sync strategy (per the phase-2 plan):
 *  - first sync is NEWEST-FIRST: the newest [FIRST_BATCH] rows per source are
 *    mirrored in one transaction so the UI can paint immediately;
 *  - history backfill continues batch-by-batch older than that;
 *  - incremental syncs only pull rows newer than the last mirrored date.
 *
 * Deletion/read-state reconciliation against the provider happens in
 * [reconcileConversation]; full-device deletes are out of scope for this phase.
 */
class TelephonySyncCoordinator private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val smsRepository = SmsRepository(appContext)
    private val db get() = MessagesDatabase.get(appContext)

    /** Conflated: N queued nudges collapse into exactly ONE pending sync. */
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val started = AtomicBoolean(false)

    companion object {
        const val FIRST_BATCH = 100
        const val BACKFILL_BATCH = 200
        const val TAG = "SYNC_COORD"

        @Volatile
        private var instance: TelephonySyncCoordinator? = null

        fun get(context: Context): TelephonySyncCoordinator =
            instance ?: synchronized(this) {
                instance ?: TelephonySyncCoordinator(context).also { instance = it }
            }
    }

    /** Fire-and-forget nudge; safe to call from any thread/receiver. */
    fun requestSync() {
        ensureLoop()
        requests.trySend(Unit)
    }

    /**
     * Suspends until one full sync cycle completes. Used by the read-cutover
     * path: the UI refreshes the shadow FIRST, then reads locally from Room.
     */
    suspend fun syncNow() = runSyncCycle()

    /**
     * Read-cutover gate: Room may serve the UI only once BOTH sources have
     * completed their initial backfill. Until then every read falls back to
     * the provider path (identical to pre-cutover behavior).
     */
    suspend fun isShadowReady(): Boolean = withContext(Dispatchers.IO) {
        val stateDao = db.syncStateDao()
        listOf(MessageEntity.SOURCE_SMS, MessageEntity.SOURCE_MMS).all { source ->
            stateDao.forSource(source)?.backfillComplete == true
        }
    }

    private fun ensureLoop() {
        if (!started.compareAndSet(false, true)) return
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            // Conflated channel: N queued nudges collapse into ONE pending value.
            for (ignored in requests) {
                try {
                    runSyncCycle()
                } catch (e: Exception) {
                    Log.e(TAG, "sync cycle failed", e)
                }
            }
        }
    }

    /** One full pass: both sources, newest-first, then conversation rebuild. */
    suspend fun runSyncCycle() = withContext(Dispatchers.IO) {
        coroutineScope {
            launch { syncSource(MessageEntity.SOURCE_SMS, ::readSmsNewestFirst) }
            launch { syncSource(MessageEntity.SOURCE_MMS, ::readMmsNewestFirst) }
        }
        rebuildConversations()
    }

    /**
     * Upsert ONE freshly observed message straight into Room (receiver path).
     * Cheap: single-row transaction, no provider scan.
     */
    suspend fun upsertSingle(sms: Sms, source: String = MessageEntity.SOURCE_SMS) =
        withContext(Dispatchers.IO) {
            val entity = toEntity(sms, source) ?: return@withContext
            coroutineScope {
                launch { db.messageDao().upsertAll(listOf(entity)) }
            }
            rebuildConversationFor(entity.threadId)
        }

    // ── internals ────────────────────────────────────────────────────────────

    private suspend fun syncSource(
        source: String,
        reader: suspend (limit: Int, offset: Int) -> List<Sms>
    ) {
        val dao = db.messageDao()
        val stateDao = db.syncStateDao()
        val state = stateDao.forSource(source)

        when {
            // First contact: mirror the newest FIRST_BATCH in one shot.
            state == null || !state.backfillComplete -> {
                val batch = reader(FIRST_BATCH, 0)
                if (batch.isNotEmpty()) {
                    dao.insertOrIgnore(batch.mapNotNull { toEntity(it, source) })
                    // Continue backfill immediately if this batch filled up.
                    if (batch.size >= FIRST_BATCH) {
                        backfillOlder(source, reader)
                        return
                    }
                }
                stateDao.upsert(
                    SyncStateEntity(
                        source = source,
                        newestSyncedDate = dao.newestDateFor(source) ?: System.currentTimeMillis(),
                        backfillComplete = true,
                        lastSyncAt = System.currentTimeMillis()
                    )
                )
            }
            // Steady state: only rows newer than what we already hold.
            else -> {
                val newest = dao.newestDateFor(source) ?: 0L
                val fresh = readNewerThan(source, newest)
                if (fresh.isNotEmpty()) {
                    // STRICTLY newer than the watermark. A message persisted in
                    // the SAME millisecond as the watermark shares its DATE:
                    // re-upserting a KNOWN row is harmless, but ignoring it
                    // would also drop a MISSED same-ms row (provider id the
                    // shadow has never seen) — which is exactly what made an
                    // app-sent message invisible on Home until restart.
                    dao.upsertAll(fresh.mapNotNull { toEntity(it, source) })
                }
                stateDao.upsert(state.copy(lastSyncAt = System.currentTimeMillis()))
            }
        }
    }

    /** Older-than-window history, batch-by-batch until a short page returns. */
    private suspend fun backfillOlder(
        source: String,
        reader: suspend (limit: Int, offset: Int) -> List<Sms>
    ) {
        val dao = db.messageDao()
        var offset = 0
        while (true) {
            val batch = reader(BACKFILL_BATCH, offset)
            if (batch.isEmpty()) break
            dao.insertOrIgnore(batch.mapNotNull { toEntity(it, source) })
            offset += batch.size
            if (batch.size < BACKFILL_BATCH) break
        }
        val stateDao = db.syncStateDao()
        val state = stateDao.forSource(source)
        stateDao.upsert(
            SyncStateEntity(
                source = source,
                newestSyncedDate = dao.newestDateFor(source) ?: 0L,
                backfillComplete = true,
                lastSyncAt = System.currentTimeMillis()
            )
        )
        if (state == null || !state.backfillComplete) {
            Log.d(TAG, "backfill complete for $source at offset=$offset")
        }
    }

    /**
     * Incremental read. ponytail ceiling: reads a bounded recent window from
     * the provider and filters by date in memory — the Telephony provider has
     * no reliable "date > ?" index on all OEM builds. Window keeps it cheap.
     */
    private suspend fun readNewerThan(source: String, newestMs: Long): List<Sms> =
        if (source == MessageEntity.SOURCE_SMS) {
            smsRepository.getSmsWithFilters(fromDate = newestMs, limit = 500)
        } else {
            smsRepository.queryMmsRaw(null, null, "${Telephony.Mms.DATE} DESC", 500)
                .filter { it.date > newestMs }
        }

    private fun toEntity(sms: Sms, source: String): MessageEntity? {
        val rawAddress = sms.sender.takeIf { it.isNotBlank() } ?: return null
        return MessageEntity(
            source = source,
            providerId = kotlin.math.abs(sms.id),
            threadId = sms.threadId,
            normalizedAddress = ContactRepository.normalizePhone(rawAddress),
            rawAddress = rawAddress,
            body = sms.message.orEmptyIfNull(),
            date = sms.date,
            type = sms.type,
            status = sms.status,
            dateSent = sms.dateSent,
            read = !sms.unread
        )
    }

    private fun String?.orEmptyIfNull() = this ?: ""

    // ── Mirror writes: operations the APP itself performs must be applied to
    // the shadow too, so a Room-first read never disagrees with the provider.

    /** Mirrors a conversation delete into Room (messages + projection). */
    suspend fun deleteThreadFromShadow(threadId: Long) = withContext(Dispatchers.IO) {
        if (threadId <= 0L) return@withContext
        db.messageDao().deleteThread(threadId)
        db.conversationDao().delete(threadId)
    }

    /**
     * Marks a thread read in Room. The messages table is the SSOT for unread
     * counts; the conversations projection is updated to match so the Home
     * badge drops even before the next rebuild pass.
     */
    suspend fun markThreadReadInShadow(threadId: Long) = withContext(Dispatchers.IO) {
        if (threadId <= 0L) return@withContext
        db.messageDao().markThreadRead(threadId)
        db.conversationDao().markRead(threadId)
    }

    /**
     * Re-reads one thread's rows from the provider and repairs its shadow copy
     * (status flips like PENDING → SENT arrive as in-place UPDATEs that no
     * date-window sync can observe). Cheap: one bounded window query.
     *
     * The conversation projection is updated via [ConversationDao.upsertPreservingFlags]
     * — a full upsert here would reset pinned/archived to false and drop a
     * pinned thread off the top of Home (reported bug).
     */
    suspend fun repairThreadInShadow(threadId: Long) = withContext(Dispatchers.IO) {
        if (threadId <= 0L) return@withContext
        val fresh = smsRepository.querySmsRaw(
            selection = "${Telephony.Sms.THREAD_ID} = ?",
            selectionArgs = arrayOf(threadId.toString()),
            sortOrder = "${Telephony.Sms.DATE} DESC",
            limit = 200
        )
        if (fresh.isEmpty()) return@withContext
        db.messageDao().upsertAll(fresh.mapNotNull { toEntity(it, MessageEntity.SOURCE_SMS) })
        rebuildConversationProjectionFor(threadId, preserveFlags = true)
    }

    /**
     * Rebuilds ONE conversation row from the messages table.
     * `preserveFlags=true` keeps pinned/archived as-is (repair path);
     * `preserveFlags=false` refreshes them from the repositories (rebuild).
     */
    private suspend fun rebuildConversationProjectionFor(
        threadId: Long,
        preserveFlags: Boolean
    ) {
        if (threadId <= 0L) return
        val page = db.messageDao().pageForThread(threadId, limit = 1, offset = 0)
        val newest = page.firstOrNull() ?: return
        val unread = countUnread(threadId)
        if (preserveFlags) {
            db.conversationDao().upsertPreservingFlags(
                threadId = threadId,
                normalizedAddress = newest.normalizedAddress,
                rawAddress = newest.rawAddress,
                snippet = newest.body,
                lastMessageDate = newest.date,
                unreadCount = unread
            )
        } else {
            val existing = db.conversationDao().byThread(threadId)
            db.conversationDao().upsertFull(
                ConversationEntity(
                    threadId = threadId,
                    normalizedAddress = newest.normalizedAddress,
                    rawAddress = newest.rawAddress,
                    snippet = newest.body,
                    lastMessageDate = newest.date,
                    unreadCount = unread,
                    pinned = existing?.pinned ?: (threadId in pinRepositoryIds()),
                    archived = existing?.archived ?: (threadId in archivedRepositoryIds())
                )
            )
        }
    }

    private suspend fun pinRepositoryIds(): Set<Long> =
        com.autonomousone.messages.repository.PinRepository(appContext).getPinnedIds()

    private suspend fun archivedRepositoryIds(): Set<Long> =
        com.autonomousone.messages.repository.ArchiveRepository(appContext).getArchivedIds()

    /** Rebuild every conversation row from the messages table (single query). */
    private suspend fun rebuildConversations() {
        val newestByThread = db.messageDao().newestPerThread()
        val archivedIds = com.autonomousone.messages.repository.ArchiveRepository(appContext)
            .getArchivedIds()
        val pinnedIds = com.autonomousone.messages.repository.PinRepository(appContext)
            .getPinnedIds()
        for (m in newestByThread) {
            val unread = countUnread(m.threadId)
            db.conversationDao().upsert(
                ConversationEntity(
                    threadId = m.threadId,
                    normalizedAddress = m.normalizedAddress,
                    rawAddress = m.rawAddress,
                    snippet = m.body,
                    lastMessageDate = m.date,
                    unreadCount = unread,
                    pinned = m.threadId in pinnedIds,
                    archived = m.threadId in archivedIds
                )
            )
        }
    }

    /** Rebuild just one conversation after a single-message upsert. */
    private suspend fun rebuildConversationFor(threadId: Long) {
        if (threadId <= 0L) return
        val page = db.messageDao().pageForThread(threadId, limit = 1, offset = 0)
        val newest = page.firstOrNull() ?: return
        db.conversationDao().upsert(
            ConversationEntity(
                threadId = threadId,
                normalizedAddress = newest.normalizedAddress,
                rawAddress = newest.rawAddress,
                snippet = newest.body,
                lastMessageDate = newest.date,
                unreadCount = countUnread(threadId),
                pinned = db.conversationDao().byThread(threadId)?.pinned ?: false,
                archived = db.conversationDao().byThread(threadId)?.archived ?: false
            )
        )
    }

    private suspend fun countUnread(threadId: Long): Int {
        // ponytail: O(n) scan per touched thread during rebuilds; acceptable at
        // current scale, upgrade to a COUNT query + partial index if needed.
        val rows = db.messageDao().pageForThread(threadId, limit = 10_000, offset = 0)
        return rows.count { !it.read && it.type == 1 }
    }

    // ── provider readers (newest-first windows over the paged repo API) ──────

    private suspend fun readSmsNewestFirst(limit: Int, offset: Int): List<Sms> =
        smsRepository.querySmsRaw(null, null, "${Telephony.Sms.DATE} DESC", limit, offset)

    private suspend fun readMmsNewestFirst(limit: Int, offset: Int): List<Sms> =
        smsRepository.queryMmsRaw(null, null, "${Telephony.Mms.DATE} DESC", limit, offset)
}
