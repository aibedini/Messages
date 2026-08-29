package com.autonomousone.messages.data

import android.content.Context
import android.provider.Telephony
import android.util.Log
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.repository.ContactRepository
import com.autonomousone.messages.repository.SmsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
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

    /** Long-running work scope (mutations, reconcile, detached backfill). */
    private val syncScope = CoroutineScope(Dispatchers.IO)

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

    /** Start the shadow-sync loop without forcing a full reconcile.
     *  Called by ConnectionSupervisor once the gateway is online. */
    fun ensureLoopRunning() = ensureLoop()

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
        syncScope.launch {
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
                        unreadCount = (existing?.unreadCount ?: 0) + unreadDelta,
                        lastMessageType = entity.type
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
                // Ledger hygiene piggybacks the periodic full sync (it runs
                // on app start + pulls, never mid-conversation): the send
                // stats only power a "today" chip, so a 90-day horizon is
                // generous while keeping the table bounded.
                db.sendSegmentDao().pruneBefore(System.currentTimeMillis() - 90L * 24 * 3600 * 1000)
                val sms = syncSource(MessageEntity.SOURCE_SMS, ::readSmsKeyset)
                val mms = syncSource(MessageEntity.SOURCE_MMS, ::readMmsKeyset)
                // ── Cutover ordering (fixes "list showed, then went empty") ──
                // syncSource deliberately leaves initialWindowReady FALSE when
                // the window just landed. The flag may only flip AFTER the
                // conversations projection has been rebuilt from the mirrored
                // messages — isShadowReady() gates Room reads, so the UI can
                // never observe "ready" against an empty/missing projection.
                // If the process dies in between, the next reconcile redoes
                // the (idempotent) window + rebuild before marking again.
                if (sms.projectionStale || mms.projectionStale) {
                    fullRebuildConversations()
                }
                val now = System.currentTimeMillis()
                val stateDao = db.syncStateDao()
                if (sms.initialWindowLanded) stateDao.markInitialWindowReady(MessageEntity.SOURCE_SMS, now)
                if (mms.initialWindowLanded) stateDao.markInitialWindowReady(MessageEntity.SOURCE_MMS, now)
                // History backfill must NEVER block the caller (that was the
                // startup hang: 360K rows awaited inside the first syncNow).
                // ONE detached job crawls SMS then MMS and rebuilds the
                // projection a single time at the end — two concurrent
                // crawlers each calling fullRebuildConversations() doubled
                // provider load and made Home flicker through the crawl.
                scheduleBackfill()
            }
        }
    }

    /** One in-flight backfill per source; guarded by the DURABLE flag, not memory. */
    private val backfillInFlight = java.util.concurrent.ConcurrentHashMap<String, AtomicBoolean>()

    /**
     * The crawl runs on its own single thread at MIN_PRIORITY. Caveat,
     * stated honestly: nested `withContext(Dispatchers.IO)` inside the
     * provider readers still hop to the IO pool, so this lane mainly
     * enforces ONE crawl at a time and keeps the between-batch bookkeeping
     * (cursor reads, state writes, yield pacing) off the threads the UI
     * shares. The big win over the old code is the single-threaded crawl +
     * single rebuild; the priority bit is best-effort.
     */
    private val backfillDispatcher by lazy {
        val factory = java.util.concurrent.ThreadFactory { r ->
            Thread(r, "sms-backfill").apply { priority = Thread.MIN_PRIORITY }
        }
        val executor = java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            java.util.concurrent.LinkedBlockingQueue(), factory
        )
        executor.asCoroutineDispatcher()
    }

    /**
     * Single detached crawl for BOTH sources, one projection rebuild at the
     * end. Durable per-source keyset cursors mean a kill resumes exactly
     * where each crawl stopped; the per-source AtomicBoolean keeps two
     * reconciles from launching overlapping crawls.
     */
    private fun scheduleBackfill() {
        syncScope.launch(backfillDispatcher) {
            val smsGuard = backfillInFlight.getOrPut(MessageEntity.SOURCE_SMS) { AtomicBoolean(false) }
            val mmsGuard = backfillInFlight.getOrPut(MessageEntity.SOURCE_MMS) { AtomicBoolean(false) }
            var didWork = false
            if (smsGuard.compareAndSet(false, true)) {
                try {
                    didWork = backfillOlderKeyset(MessageEntity.SOURCE_SMS, ::readSmsKeyset) || didWork
                } catch (e: Exception) {
                    Log.e(TAG, "backfill failed for SMS", e)
                } finally {
                    smsGuard.set(false)
                }
            }
            if (mmsGuard.compareAndSet(false, true)) {
                try {
                    didWork = backfillOlderKeyset(MessageEntity.SOURCE_MMS, ::readMmsKeyset) || didWork
                } catch (e: Exception) {
                    Log.e(TAG, "backfill failed for MMS", e)
                } finally {
                    mmsGuard.set(false)
                }
            }
            // ONE rebuild for the whole crawl — previously SMS and MMS each
            // rebuilt the full projection back to back: doubled writes,
            // double Home churn mid-sync. (fullRebuildConversations hops to
            // IO internally; we are already a single sequential crawl.)
            if (didWork) fullRebuildConversations()
        }
    }

    /** What a per-source sync pass achieved this reconcile. */
    private data class SourceSyncResult(
        /** The initial window landed THIS pass → caller must rebuild the
         *  projection and only then flip initialWindowReady. */
        val initialWindowLanded: Boolean,
        /** Rows changed → the conversation projection needs a rebuild. */
        val projectionStale: Boolean
    )

    // ── Provider sync (reconcile path only) ────────────────────────────────

    private suspend fun syncSource(
        source: String,
        reader: suspend (beforeDate: Long, beforeId: Long, limit: Int) -> List<Sms>
    ): SourceSyncResult {
        val dao = db.messageDao()
        val stateDao = db.syncStateDao()
        val state = stateDao.forSource(source)
            ?: SyncStateEntity(source = source, newestDate = 0L).also { stateDao.upsert(it) }

        return if (!state.initialWindowReady) {
            // First contact: mirror the newest FIRST_BATCH via keyset from the
            // sentinel (everything is older than MAX_VALUE), persist the
            // watermarks, then continue the history backfill durably.
            val batch = reader(Long.MAX_VALUE, Long.MAX_VALUE, FIRST_BATCH)
            dao.insertOrIgnore(batch.mapNotNull { toEntity(it, source) })
            val now = System.currentTimeMillis()
            if (batch.isNotEmpty()) {
                val newest = batch.maxByOrNull { it.date }!!
                stateDao.advanceNewest(source, newest.date, providerId(newest), now)
                val oldest = batch.minByOrNull { it.date }!!
                stateDao.advanceOldest(source, oldest.date, providerId(oldest), now)
            }
            // Do NOT mark initialWindowReady here — the caller flips it only
            // after fullRebuildConversations() has populated the projection.
            // No inline backfill either: scheduleBackfill (caller) runs the
            // durable keyset crawl detached — the initial window alone is
            // enough for the first paint, and awaiting the full history here
            // was the original startup hang.
            SourceSyncResult(initialWindowLanded = true, projectionStale = true)
        } else {
            // Steady state: only rows newer than the persisted watermark, and
            // resume an interrupted history backfill if one is still pending.
            val fresh = readNewerThan(source, state.newestDate, state.newestId)
            if (fresh.isNotEmpty()) {
                dao.upsertAll(fresh.mapNotNull { toEntity(it, source) })
                val newest = fresh.maxByOrNull { it.date }!!
                stateDao.advanceNewest(source, newest.date, providerId(newest), System.currentTimeMillis())
            } else {
                stateDao.touchReconcile(source, System.currentTimeMillis())
            }
            // An interrupted crawl resumes via the caller's scheduleBackfill
            // (detached, durable cursor) — never inline here.
            SourceSyncResult(
                initialWindowLanded = false,
                projectionStale = fresh.isNotEmpty()
            )
        }
    }

    /**
     * Keyset (watermark) backfill — NO OFFSET.
     *
     * Each batch reads `WHERE (date,id) < watermark ORDER BY date DESC LIMIT n`
     * and persists the new watermark BEFORE yielding. A process kill resumes
     * exactly from the last durable cursor (next app start sees
     * historyBackfillComplete=false in steady state and calls us again) —
     * no restart from zero, no row skipped, none duplicated, immune to
     * provider inserts shifting window boundaries (the offset bug).
     *
     * Watermark updates are targeted UPDATEs: never a full-entity copy of a
     * `state` read before the loop — that stomped every cursor advanced
     * during the run.
     *
     * Returns true if any older rows were mirrored (projection needs rebuild).
     */
    private suspend fun backfillOlderKeyset(
        source: String,
        reader: suspend (beforeDate: Long, beforeId: Long, limit: Int) -> List<Sms>
    ): Boolean {
        val dao = db.messageDao()
        val stateDao = db.syncStateDao()
        var cursor = stateDao.forSource(source) ?: return false
        if (cursor.historyBackfillComplete) return false

        var insertedAny = false
        while (true) {
            val batch = reader(cursor.oldestDate, cursor.oldestId, BACKFILL_BATCH)
            if (batch.isEmpty()) break

            dao.insertOrIgnore(batch.mapNotNull { toEntity(it, source) })
            insertedAny = true

            val oldest = batch.minByOrNull { it.date }!!
            stateDao.advanceOldest(source, oldest.date, providerId(oldest), System.currentTimeMillis())

            if (batch.size < BACKFILL_BATCH) break

            // Yield: let other coroutines run; re-read the DURABLE cursor
            // (not a stale copy) before the next hop.
            cursor = stateDao.forSource(source) ?: return insertedAny
            yield()
        }

        stateDao.markHistoryComplete(source, System.currentTimeMillis())
        Log.d(TAG, "backfill complete for $source (keyset watermark cursor)")
        return insertedAny
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

    /**
     * Rows strictly newer than the (date,id) watermark — keyset, so a message
     * inserted with the same timestamp as the watermark is still picked up.
     */
    private suspend fun readNewerThan(source: String, newestMs: Long, newestId: Long): List<Sms> =
        if (source == MessageEntity.SOURCE_SMS) {
            smsRepository.querySmsRaw(
                selection = "(${Telephony.Sms.DATE} > ?) OR (${Telephony.Sms.DATE} = ? AND ${Telephony.Sms._ID} > ?)",
                selectionArgs = arrayOf(newestMs.toString(), newestMs.toString(), newestId.toString()),
                sortOrder = "${Telephony.Sms.DATE} ASC",
                limit = 500
            )
        } else {
            // Mms.DATE is SECONDS; our watermarks are millis (toEntity
            // multiplies by 1000 — MMS dates are whole seconds so division
            // here is exact).
            smsRepository.queryMmsRaw(
                selection = "(${Telephony.Mms.DATE} > ?) OR (${Telephony.Mms.DATE} = ? AND ${Telephony.Mms._ID} > ?)",
                selectionArgs = arrayOf(
                    (newestMs / 1000L).toString(),
                    (newestMs / 1000L).toString(),
                    newestId.toString()
                ),
                sortOrder = "${Telephony.Mms.DATE} ASC",
                limit = 500
            )
        }

    private fun toEntity(sms: Sms, source: String): MessageEntity? {
        val rawAddress = sms.sender.takeIf { it.isNotBlank() } ?: return null
        return MessageEntity(
            source = source,
            providerId = providerId(sms),
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

    /**
     * Native provider row id. SmsRepository encodes MMS rows with a NEGATIVE
     * `id` (id = -_id) to keep the UI's mixed list unambiguous — the
     * watermark and the Room `providerId` must use the same abs() mapping
     * toEntity writes, or the keyset predicates compare different id spaces.
     */
    private fun providerId(sms: Sms): Long = kotlin.math.abs(sms.id)

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
                    unreadCount = unread,
                    lastMessageType = newest.type
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
                        lastMessageType = newest.type,
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
                        lastMessageType = m.type,
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

    // ── Provider readers (keyset) ──────────────────────────────────────────
    //
    // `beforeDate/beforeId` is the durable (date,id) watermark: strictly
    // OLDER rows, newest-first. A sentinel (MAX,MAX) reads from the top.
    // No OFFSET anywhere — a kill resumes from the persisted cursor.

    private suspend fun readSmsKeyset(beforeDate: Long, beforeId: Long, limit: Int): List<Sms> =
        smsRepository.querySmsRaw(
            selection = "(${Telephony.Sms.DATE} < ?) OR (${Telephony.Sms.DATE} = ? AND ${Telephony.Sms._ID} < ?)",
            selectionArgs = arrayOf(beforeDate.toString(), beforeDate.toString(), beforeId.toString()),
            sortOrder = "${Telephony.Sms.DATE} DESC, ${Telephony.Sms._ID} DESC",
            limit = limit
        )

    private suspend fun readMmsKeyset(beforeDate: Long, beforeId: Long, limit: Int): List<Sms> =
        smsRepository.queryMmsRaw(
            selection = "(${Telephony.Mms.DATE} < ?) OR (${Telephony.Mms.DATE} = ? AND ${Telephony.Mms._ID} < ?)",
            // Mms.DATE is SECONDS; watermark millis division is exact.
            selectionArgs = arrayOf(
                (beforeDate / 1000L).toString(),
                (beforeDate / 1000L).toString(),
                beforeId.toString()
            ),
            sortOrder = "${Telephony.Mms.DATE} DESC, ${Telephony.Mms._ID} DESC",
            limit = limit
        )
}
