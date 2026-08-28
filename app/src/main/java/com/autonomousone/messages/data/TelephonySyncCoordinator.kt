package com.autonomousone.messages.data

import android.content.Context
import android.provider.Telephony
import android.util.Log
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.repository.ContactRepository
import com.autonomousone.messages.repository.SmsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import androidx.room.withTransaction
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The SINGLE writer into Room. Two completely separate channels:
 *
 *  1. **Mutations** (exact, never conflated): every insert, delete, status
 *     change, and read-mark reaches Room exactly once. This is the realtime
 *     fast path — O(1) per message.
 *
 *  2. **Reconcile requests** (CONFLATED): N queued nudges collapse into
 *     exactly ONE bounded repair pass. Used for startup, crash recovery,
 *     and fallback when the observer cannot provide a specific URI/id.
 *
 * Hot path for an incoming SMS:
 *   Provider INSERT → providerId=348201, threadId=552
 *   → mutate(Upsert("sms", sms))
 *   → Room transaction { UPSERT message + UPDATE conversation }
 *   → Room Flow → UI
 *
 * No full scan. No rebuildConversations(). No countUnread(10K rows).
 */
class TelephonySyncCoordinator private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val smsRepository = SmsRepository(appContext)
    private val db get() = MessagesDatabase.get(appContext)

    // ── Dual channels ──────────────────────────────────────────────────────

    /**
     * Exact mutations: sequential processing, NEVER drops events.
     *
     * A bounded channel + trySend could silently drop an event under a burst
     * (e.g. 100 SMS arriving in one second) — the row would then stay stale in
     * the shadow until the next reconcile. UNLIMITED trades bounded memory for
     * exactly-once delivery; each item is a small immutable value and the
     * consumer drains continuously, so the queue stays near-empty.
     */
    private val mutations = Channel<MessageMutation>(capacity = Channel.UNLIMITED)

    /** Reconcile requests: CONFLATED — N nudges collapse into 1. */
    private val reconciles = Channel<ReconcileRequest>(Channel.CONFLATED)

    private val started = AtomicBoolean(false)

    companion object {
        const val FIRST_BATCH = 500
        const val BACKFILL_BATCH = 500
        const val TAG = "SYNC_COORD"

        @Volatile
        private var instance: TelephonySyncCoordinator? = null

        fun get(context: Context): TelephonySyncCoordinator =
            instance ?: synchronized(this) {
                instance ?: TelephonySyncCoordinator(context).also { instance = it }
            }
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /** Queue an exact mutation (insert/update/delete/status). O(1). */
    fun mutate(mutation: MessageMutation) {
        ensureLoop()
        mutations.trySend(mutation)
    }

    /** Queue a bounded reconcile (conflated). */
    fun reconcile(request: ReconcileRequest = ReconcileRequest.FullSync) {
        ensureLoop()
        reconciles.trySend(request)
    }

    /** Backward compat during migration. */
    fun requestSync() = reconcile(ReconcileRequest.FullSync)

    /** Suspends until one full sync cycle completes. */
    suspend fun syncNow() = applyReconcile(ReconcileRequest.FullSync)

    /**
     * Read-cutover gate: Room may serve the UI only once BOTH sources have
     * completed their initial window. Until then every read falls back to
     * the provider path.
     */
    suspend fun isShadowReady(): Boolean = withContext(Dispatchers.IO) {
        val stateDao = db.syncStateDao()
        listOf(MessageEntity.SOURCE_SMS, MessageEntity.SOURCE_MMS).all { source ->
            stateDao.forSource(source)?.initialWindowReady == true
        }
    }

    // ── Loop ───────────────────────────────────────────────────────────────

    private fun ensureLoop() {
        if (!started.compareAndSet(false, true)) return
        CoroutineScope(Dispatchers.IO).launch {
            // Exact mutations: sequential, never conflated.
            launch {
                for (mutation in mutations) {
                    try {
                        applyMutation(mutation)
                    } catch (e: Exception) {
                        Log.e(TAG, "mutation failed: $mutation", e)
                    }
                }
            }
            // Reconcile requests: conflated, only the latest matters.
            for (request in reconciles) {
                try {
                    applyReconcile(request)
                } catch (e: Exception) {
                    Log.e(TAG, "reconcile failed", e)
                }
            }
        }
    }

    // ── Exact mutation fast path ───────────────────────────────────────────

    private suspend fun applyMutation(m: MessageMutation) = withContext(Dispatchers.IO) {
        when (m) {
            is MessageMutation.Upsert -> {
                val entity = toEntity(m.message, m.source) ?: return@withContext
                val database = db
                val dao = database.messageDao()
                val convDao = database.conversationDao()

                // Single Room transaction: message + conversation, atomically.
                database.withTransaction {
                    // Find old version for unread delta calculation.
                    val old = dao.findByKey(m.source, entity.providerId)
                    val oldRead = old?.read ?: true

                    // Upsert the message.
                    dao.upsertAll(listOf(entity))

                    // Calculate unread delta — O(1), never recounts the thread.
                    val unreadDelta = UnreadDelta.compute(
                        oldExists = old != null,
                        oldRead = oldRead,
                        newRead = entity.read
                    )

                    // Upsert conversation projection (preserve pinned/archived).
                    // upsertPreservingFlags is a TRUE upsert: a brand-new thread
                    // is INSERTED here — Home must not depend on a later rebuild.
                    val existing = convDao.byThread(entity.threadId)
                    convDao.upsertPreservingFlags(
                        threadId = entity.threadId,
                        normalizedAddress = entity.normalizedAddress,
                        rawAddress = entity.rawAddress,
                        snippet = entity.body,
                        lastMessageDate = maxOf(entity.date, existing?.lastMessageDate ?: 0L),
                        unreadCount = (existing?.unreadCount ?: 0) + unreadDelta
                    )
                }
            }

            is MessageMutation.Delete -> {
                val dao = db.messageDao()
                val threadId = m.threadId ?: dao.findByKey(m.source, m.providerId)?.threadId
                dao.deleteBySourceAndId(m.source, m.providerId)
                if (threadId != null && threadId > 0L) {
                    rebuildConversationProjection(threadId, preserveFlags = true)
                }
            }

            is MessageMutation.RefreshStatus -> {
                val fresh = readExactMessage(m.source, m.providerId)
                if (fresh != null) {
                    val entity = toEntity(fresh, m.source)
                    if (entity != null) {
                        db.messageDao().upsertAll(listOf(entity))
                        // Status changes don't affect conversation projection.
                    }
                }
            }

            is MessageMutation.MarkThreadRead -> {
                db.messageDao().markThreadRead(m.threadId)
                db.conversationDao().markRead(m.threadId)
            }

            is MessageMutation.DeleteThread -> {
                db.messageDao().deleteThread(m.threadId)
                db.conversationDao().delete(m.threadId)
            }
        }
    }

    // ── Reconcile path (repair/recovery) ───────────────────────────────────

    private suspend fun applyReconcile(request: ReconcileRequest) = withContext(Dispatchers.IO) {
        when (request) {
            is ReconcileRequest.ForThread -> {
                repairThreadInShadow(request.threadId)
            }
            is ReconcileRequest.FullSync -> {
                val initialWindowCompleted = coroutineScope {
                    val smsReady = async { syncSource(MessageEntity.SOURCE_SMS, ::readSmsNewestFirst) }
                    val mmsReady = async { syncSource(MessageEntity.SOURCE_MMS, ::readMmsNewestFirst) }
                    smsReady.await() || mmsReady.await()
                }
                // On the very first sync the conversation projection table is
                // empty — no mutation events exist yet to insert rows. Build it
                // once here (startup/repair only, never on the realtime path).
                if (initialWindowCompleted) fullRebuildConversations()
            }
        }
    }

    // ── Provider sync (reconcile path only) ────────────────────────────────

    private suspend fun syncSource(
        source: String,
        reader: suspend (limit: Int, offset: Int) -> List<Sms>
    ): Boolean {
        val dao = db.messageDao()
        val stateDao = db.syncStateDao()
        val state = stateDao.forSource(source)

        return when {
            // First contact: mirror the newest FIRST_BATCH in one shot.
            state == null || !state.initialWindowReady -> {
                val batch = reader(FIRST_BATCH, 0)
                if (batch.isNotEmpty()) {
                    dao.insertOrIgnore(batch.mapNotNull { toEntity(it, source) })
                }
                stateDao.upsert(
                    SyncStateEntity(
                        source = source,
                        newestDate = dao.newestDateFor(source) ?: System.currentTimeMillis(),
                        initialWindowReady = true,
                        lastReconcileAt = System.currentTimeMillis()
                    )
                )
                // Background backfill: batch-by-batch, yielding between batches.
                backfillOlderIncremental(source, reader)
                true // initial window just completed → projection table needs a first build
            }
            // Steady state: only rows newer than what we already hold.
            else -> {
                val newest = dao.newestDateFor(source) ?: 0L
                val fresh = readNewerThan(source, newest)
                if (fresh.isNotEmpty()) {
                    dao.upsertAll(fresh.mapNotNull { toEntity(it, source) })
                }
                stateDao.upsert(
                    state.copy(
                        lastReconcileAt = System.currentTimeMillis()
                    )
                )
                false
            }
        }
    }

    /** Incremental backfill: processes BATCH_SIZE messages, yields, repeats. */
    private suspend fun backfillOlderIncremental(
        source: String,
        reader: suspend (limit: Int, offset: Int) -> List<Sms>
    ) {
        val dao = db.messageDao()
        val stateDao = db.syncStateDao()
        val state = stateDao.forSource(source) ?: return
        if (state.historyBackfillComplete) return

        var offset = BACKFILL_BATCH // skip the initial window we already have

        while (true) {
            val batch = reader(BACKFILL_BATCH, offset)
            if (batch.isEmpty()) break

            dao.insertOrIgnore(batch.mapNotNull { toEntity(it, source) })
            offset += batch.size

            // Update oldest watermark.
            val oldest = batch.minByOrNull { it.date }
            stateDao.upsert(
                state.copy(
                    oldestDate = oldest?.date ?: state.oldestDate,
                    oldestId = oldest?.id ?: state.oldestId,
                    lastReconcileAt = System.currentTimeMillis()
                )
            )

            if (batch.size < BACKFILL_BATCH) break

            // Yield: let other coroutines run, prevent blocking the dispatcher.
            yield()
        }

        stateDao.upsert(
            state.copy(
                historyBackfillComplete = true,
                lastReconcileAt = System.currentTimeMillis()
            )
        )
        Log.d(TAG, "backfill complete for $source at offset=$offset")
    }

    /**
     * Read exactly ONE message from the provider by its native ID.
     * O(1) — no scan, no offset, no window.
     */
    private suspend fun readExactMessage(source: String, providerId: Long): Sms? =
        withContext(Dispatchers.IO) {
            when (source) {
                MessageEntity.SOURCE_SMS -> {
                    smsRepository.querySmsRaw(
                        selection = "${Telephony.Sms._ID} = ?",
                        selectionArgs = arrayOf(providerId.toString()),
                        sortOrder = "${Telephony.Sms.DATE} DESC",
                        limit = 1
                    ).firstOrNull()
                }
                MessageEntity.SOURCE_MMS -> {
                    smsRepository.queryMmsRaw(
                        selection = "${Telephony.Mms._ID} = ?",
                        selectionArgs = arrayOf(providerId.toString()),
                        sortOrder = "${Telephony.Mms.DATE} DESC",
                        limit = 1
                    ).firstOrNull()
                }
                else -> null
            }
        }

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

    // ── Mirror writes (app-initiated mutations) ────────────────────────────

    /** Mirrors a conversation delete into Room. */
    suspend fun deleteThreadFromShadow(threadId: Long) = withContext(Dispatchers.IO) {
        if (threadId <= 0L) return@withContext
        db.messageDao().deleteThread(threadId)
        db.conversationDao().delete(threadId)
    }

    /** Marks a thread read in Room. */
    suspend fun markThreadReadInShadow(threadId: Long) = withContext(Dispatchers.IO) {
        if (threadId <= 0L) return@withContext
        db.messageDao().markThreadRead(threadId)
        db.conversationDao().markRead(threadId)
    }

    /**
     * Re-reads one thread's rows from the provider and repairs its shadow copy.
     * Cheap: one bounded window query.
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
        rebuildConversationProjection(threadId, preserveFlags = true)
    }

    // ── Conversation projection ────────────────────────────────────────────

    /**
     * Rebuilds ONE conversation row from the messages table.
     * Uses SQL COUNT for unread — O(unread_count) instead of O(total_messages).
     * Message + Conversation update in a single Room transaction.
     */
    private suspend fun rebuildConversationProjection(threadId: Long, preserveFlags: Boolean) {
        if (threadId <= 0L) return
        val database = db
        val dao = database.messageDao()
        val convDao = database.conversationDao()

        database.withTransaction {
            val newest = dao.pageForThread(threadId, limit = 1, offset = 0).firstOrNull()
            if (newest == null) {
                // Last message in the thread was deleted → the conversation must
                // disappear from Home, not keep a stale snippet/date forever.
                convDao.delete(threadId)
                return@withTransaction
            }
            val unread = dao.countUnread(threadId)

            if (preserveFlags) {
                convDao.upsertPreservingFlags(
                    threadId = threadId,
                    normalizedAddress = newest.normalizedAddress,
                    rawAddress = newest.rawAddress,
                    snippet = newest.body,
                    lastMessageDate = newest.date,
                    unreadCount = unread
                )
            } else {
                val existing = convDao.byThread(threadId)
                convDao.upsertFull(
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
    }

    /** Full rebuild: startup or repair only. Never on the realtime path. */
    suspend fun fullRebuildConversations() = withContext(Dispatchers.IO) {
        val database = db
        val newestByThread = database.messageDao().newestPerThread()
        val archivedIds = com.autonomousone.messages.repository.ArchiveRepository(appContext)
            .getArchivedIds()
        val pinnedIds = com.autonomousone.messages.repository.PinRepository(appContext)
            .getPinnedIds()

        database.withTransaction {
            for (m in newestByThread) {
                val unread = database.messageDao().countUnread(m.threadId)
                database.conversationDao().upsert(
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
    }

    private suspend fun pinRepositoryIds(): Set<Long> =
        com.autonomousone.messages.repository.PinRepository(appContext).getPinnedIds()

    private suspend fun archivedRepositoryIds(): Set<Long> =
        com.autonomousone.messages.repository.ArchiveRepository(appContext).getArchivedIds()

    // ── Provider readers ───────────────────────────────────────────────────

    private suspend fun readSmsNewestFirst(limit: Int, offset: Int): List<Sms> =
        smsRepository.querySmsRaw(null, null, "${Telephony.Sms.DATE} DESC", limit, offset)

    private suspend fun readMmsNewestFirst(limit: Int, offset: Int): List<Sms> =
        smsRepository.queryMmsRaw(null, null, "${Telephony.Mms.DATE} DESC", limit, offset)
}
