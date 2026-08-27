# Architecture V2: Incremental Sync Refactor

## Problem Statement

The current sync architecture has **two distinct performance paths conflated into one**:

1. **Realtime path** (incoming SMS, delivery callbacks, read-mark) — should be O(1) per message
2. **Reconciliation path** (startup recovery, missed observer events) — can be bounded but not O(N)

Today, every incoming SMS triggers `requestSync()` → full `syncSource(SMS)` → `syncSource(MMS)` → `rebuildConversations()` → `countUnread()` (which reads up to 10,000 rows per thread). For 360K messages this is catastrophically expensive.

## Core Principle

> If you have the ID, operate on that ID. Full sync is repair, not the main path.

---

## Phase 1: MessageMutation + Dual-Channel Coordinator

### New file: `data/MessageMutation.kt`

```kotlin
package com.autonomousone.messages.data

/**
 * Identity of a message in the app's read model.
 * Composite key mirrors MessageEntity's primary key.
 */
data class MessageKey(val source: String, val providerId: Long)

/**
 * The two classes of mutation that flow through the sync coordinator.
 *
 * Exact mutations are NEVER conflated — every insert, delete, and status
 * change must reach Room exactly once.
 *
 * Reconcile requests ARE conflated — 30 observer nudges in 150ms should
 * produce exactly one bounded repair pass.
 */
sealed interface MessageMutation {

    /** Insert or update a single message by exact identity. */
    data class Upsert(
        val source: String,
        val message: com.autonomousone.messages.model.Sms
    ) : MessageMutation

    /** Delete a single message by exact identity. */
    data class Delete(
        val source: String,
        val providerId: Long,
        val threadId: Long? = null
    ) : MessageMutation

    /** Update delivery/send status for a single outgoing message. */
    data class RefreshStatus(
        val source: String,
        val providerId: Long
    ) : MessageMutation

    /** Mark all messages in a thread as read. */
    data class MarkThreadRead(
        val threadId: Long
    ) : MessageMutation

    /** Delete all messages in a thread. */
    data class DeleteThread(
        val threadId: Long
    ) : MessageMutation
}

/**
 * Reconcile request — bounded repair pass.
 * CONFLATED: N nudges → 1 execution.
 */
sealed interface ReconcileRequest {
    data object FullSync : ReconcileRequest
    data class ForThread(val threadId: Long) : ReconcileRequest
}
```

### Modify: `data/TelephonySyncCoordinator.kt`

Replace the single `Channel<Unit>(CONFLATED)` with two channels:

```kotlin
// EXACT mutations: no conflation, every event delivered.
private val mutations = Channel<MessageMutation>(capacity = 64)

// Reconcile requests: CONFLATED — N nudges collapse into 1.
private val reconciles = Channel<ReconcileRequest>(Channel.CONFLATED)
```

The `ensureLoop()` coroutine now consumes both channels in parallel:

```kotlin
private fun ensureLoop() {
    if (!started.compareAndSet(false, true)) return
    CoroutineScope(Dispatchers.IO).launch {
        // Exact mutations: sequential processing, never drops events.
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
```

New public API:

```kotlin
/** Fire-and-forget: queue an exact mutation (insert/update/delete/status). */
fun mutate(mutation: MessageMutation) {
    ensureLoop()
    mutations.trySend(mutation)
}

/** Fire-and-forget: queue a bounded reconcile (conflated). */
fun reconcile(request: ReconcileRequest = ReconcileRequest.FullSync) {
    ensureLoop()
    reconciles.trySend(request)
}

// Keep backward compat during migration:
fun requestSync() = reconcile(ReconcileRequest.FullSync)
```

### New: `applyMutation()` — the fast path

```kotlin
private suspend fun applyMutation(m: MessageMutation) = withContext(Dispatchers.IO) {
    when (m) {
        is MessageMutation.Upsert -> {
            val entity = toEntity(m.message, m.source) ?: return@withContext
            val dao = db.messageDao()
            val convDao = db.conversationDao()

            // Single Room transaction: message + conversation, atomically.
            db.withTransaction {
                // Find old version for unread delta calculation.
                val old = dao.findByKey(m.source, entity.providerId)
                val oldRead = old?.read ?: true

                // Upsert the message.
                dao.upsertAll(listOf(entity))

                // Calculate unread delta.
                val unreadDelta = when {
                    entity.read -> 0  // read message, no delta
                    oldRead -> 1      // was read, now unread: +1
                    else -> 0         // was unread, still unread: no change
                }

                // Upsert conversation projection.
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
            val source = m.source
            val fresh = readExactMessage(source, m.providerId)
            if (fresh != null) {
                val entity = toEntity(fresh, source)
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
```

### New: `readExactMessage()` — O(1) provider read

```kotlin
/**
 * Read exactly ONE message from the provider by its native ID.
 * O(1) — no scan, no offset, no window.
 */
private suspend fun readExactMessage(source: String, providerId: Long): Sms? =
    withContext(Dispatchers.IO) {
        when (source) {
            MessageEntity.SOURCE_SMS -> {
                val repo = SmsRepository(appContext)
                val results = repo.querySmsRaw(
                    selection = "${Telephony.Sms._ID} = ?",
                    selectionArgs = arrayOf(providerId.toString()),
                    sortOrder = "${Telephony.Sms.DATE} DESC",
                    limit = 1
                )
                results.firstOrNull()
            }
            MessageEntity.SOURCE_MMS -> {
                val repo = SmsRepository(appContext)
                val results = repo.queryMmsRaw(
                    selection = "${Telephony.Mms._ID} = ?",
                    selectionArgs = arrayOf(providerId.toString()),
                    sortOrder = "${Telephony.Mms.DATE} DESC",
                    limit = 1
                )
                results.firstOrNull()
            }
            else -> null
        }
    }
```

### New: `applyReconcile()` — the repair path

```kotlin
private suspend fun applyReconcile(request: ReconcileRequest) = withContext(Dispatchers.IO) {
    when (request) {
        is ReconcileRequest.ForThread -> {
            repairThreadInShadow(request.threadId)
        }
        is ReconcileRequest.FullSync -> {
            // Bounded window sync: only recent messages.
            runSyncCycle()
        }
    }
}
```

---

## Phase 2: IncomingMessageDispatcher Fast Path

### Modify: `receiver/IncomingMessageDispatcher.kt`

**Before** (current):
```kotlin
// Mirror into Room via the single-writer sync coordinator.
TelephonySyncCoordinator.get(context).requestSync()
```

**After**:
```kotlin
// O(1) targeted upsert — no provider scan, no full sync cycle.
TelephonySyncCoordinator.get(context).mutate(
    MessageMutation.Upsert(source = MessageEntity.SOURCE_SMS, message = sms)
)
```

The blocked sender check stays BEFORE persistence (already correct). But we still
persist the row — blocking only suppresses notifications/webhooks, not Room sync.

Actually, we should also persist the blocked message for completeness:

```kotlin
fun dispatch(context: Context, sms: Sms) {
    // Always mirror into Room (blocking is a notification policy, not a sync policy).
    TelephonySyncCoordinator.get(context).mutate(
        MessageMutation.Upsert(source = MessageEntity.SOURCE_SMS, message = sms)
    )

    // Blocked sender: silent — no bus event, no webhook, no notification.
    if (BlocklistRepository.isBlocked(context, sms.sender)) {
        Log.d(TAG, "Message from blocked sender ${sms.sender} — silent handling")
        return
    }

    // Rest of the pipeline: bus, webhook, notification...
}
```

---

## Phase 3: countUnread → SQL COUNT + Incremental Maintenance

### Modify: `data/Entities.kt` — add `unreadCount` column

```kotlin
@Entity(
    tableName = "conversations",
    indices = [Index("lastMessageDate")]
)
data class ConversationEntity(
    @PrimaryKey val threadId: Long,
    val normalizedAddress: String,
    val rawAddress: String = "",
    val snippet: String,
    val lastMessageDate: Long,
    val unreadCount: Int,
    val pinned: Boolean = false,
    val archived: Boolean = false
)
```

### Modify: `data/Daos.kt` — add COUNT query + partial index

```kotlin
@Dao
interface MessageDao {
    // ... existing queries ...

    /** O(1) unread count for one thread. Replace the O(n) in-memory scan. */
    @Query("""
        SELECT COUNT(*) FROM messages
        WHERE threadId = :threadId AND read = 0 AND type = 1
    """)
    suspend fun countUnread(threadId: Long): Int

    /** Find a message by composite key (for delta calculation). */
    @Query("""
        SELECT * FROM messages
        WHERE source = :source AND providerId = :providerId
        LIMIT 1
    """)
    suspend fun findByKey(source: String, providerId: Long): MessageEntity?

    /** Delete a single message by composite key. */
    @Query("""
        DELETE FROM messages
        WHERE source = :source AND providerId = :providerId
    """)
    suspend fun deleteBySourceAndId(source: String, providerId: Long)
}
```

### Modify: `data/TelephonySyncCoordinator.kt` — remove old `countUnread()`

Delete the entire old `countUnread()` method:

```kotlin
// DELETE THIS:
private suspend fun countUnread(threadId: Long): Int {
    val rows = db.messageDao().pageForThread(threadId, limit = 10_000, offset = 0)
    return rows.count { !it.read && it.type == 1 }
}
```

Replace ALL calls with:

```kotlin
db.messageDao().countUnread(threadId)
```

### Optimal partial index (Room migration v3):

```sql
CREATE INDEX idx_messages_unread ON messages(threadId)
WHERE read = 0 AND type = 1;
```

This makes `COUNT(*) WHERE threadId = ? AND read = 0 AND type = 1` O(unread_count)
instead of O(total_messages_in_thread).

---

## Phase 4: SmsContentObserver URI-Aware ChangeRouter

### Modify: `observer/SmsContentObserver.kt`

```kotlin
class SmsContentObserver(
    private val onChange: (uri: Uri?) -> Unit
) : ContentObserver(Handler(Looper.getMainLooper())) {

    companion object {
        private const val COALESCE_MS = 150L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val trailingRunnable = Runnable {
        pendingTrailing = false
        lastFiredAt = System.currentTimeMillis()
        onChange(null)  // null = unknown change type → reconcile
    }

    @Volatile private var lastFiredAt = 0L
    @Volatile private var pendingTrailing = false

    override fun onChange(selfChange: Boolean) {
        dispatch(null)
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        // Do NOT call super — it delegates back to onChange(selfChange) which
        // we also override, causing double dispatch.
        dispatch(uri)
    }

    private fun dispatch(uri: Uri?) {
        val now = System.currentTimeMillis()
        if (now - lastFiredAt >= COALESCE_MS) {
            lastFiredAt = now
            handler.removeCallbacks(trailingRunnable)
            pendingTrailing = false
            onChange(uri)
            return
        }
        if (!pendingTrailing) {
            pendingTrailing = true
            handler.postDelayed(trailingRunnable, COALESCE_MS)
        }
    }
}
```

### New: `data/ChangeRouter.kt`

```kotlin
package com.autonomousone.messages.data

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.repository.SmsRepository

/**
 * Routes ContentObserver notifications into targeted mutations when the
 * URI carries an extractable ID, or falls back to bounded reconciliation.
 *
 * Android guarantees: when a single row changes, the observer MAY receive
 * content://sms/12345 (with the row id). But this is NOT guaranteed on all
 * OEM builds. So we treat extractable URIs as an optimization, not a contract.
 */
object ChangeRouter {

    private const val TAG = "CHANGE_ROUTER"

    /**
     * Parse the observer URI and dispatch the cheapest possible mutation.
     */
    fun route(context: Context, uri: Uri?) {
        val coordinator = TelephonySyncCoordinator.get(context)

        if (uri == null) {
            // Unknown change type → bounded reconcile.
            coordinator.reconcile(ReconcileRequest.FullSync)
            return
        }

        val id = extractRowId(uri)
        if (id != null && id > 0L) {
            val source = when {
                uri.path?.contains("mms") == true -> MessageEntity.SOURCE_MMS
                else -> MessageEntity.SOURCE_SMS
            }
            // Try targeted mutation: read the exact row.
            val repo = SmsRepository(context)
            val fresh = when (source) {
                MessageEntity.SOURCE_SMS -> repo.querySmsRaw(
                    selection = "${Telephony.Sms._ID} = ?",
                    selectionArgs = arrayOf(id.toString()),
                    sortOrder = "${Telephony.Sms.DATE} DESC",
                    limit = 1
                ).firstOrNull()
                MessageEntity.SOURCE_MMS -> repo.queryMmsRaw(
                    selection = "${Telephony.Mms._ID} = ?",
                    selectionArgs = arrayOf(id.toString()),
                    sortOrder = "${Telephony.Mms.DATE} DESC",
                    limit = 1
                ).firstOrNull()
                else -> null
            }

            if (fresh != null) {
                coordinator.mutate(MessageMutation.Upsert(source = source, message = fresh))
            } else {
                // Row was deleted externally.
                coordinator.mutate(MessageMutation.Delete(source, id))
            }
        } else {
            // URI without extractable ID → bounded reconcile (not full sync).
            coordinator.reconcile(ReconcileRequest.FullSync)
        }
    }

    /**
     * Try to extract a numeric row ID from a content URI.
     * content://sms/12345 → 12345
     * content://sms → null
     */
    private fun extractRowId(uri: Uri): Long? {
        val lastSegment = uri.lastPathSegment ?: return null
        return lastSegment.toLongOrNull()
    }
}
```

### Modify: `HomeViewModel` observer callback

**Before**:
```kotlin
private val observer = SmsContentObserver {
    ThreadMessageCache.generation++
    loadSms()
    TelephonySyncCoordinator.get(getApplication()).requestSync()
}
```

**After**:
```kotlin
private val observer = SmsContentObserver { uri ->
    ThreadMessageCache.generation++
    // Route to targeted mutation or bounded reconcile.
    ChangeRouter.route(getApplication(), uri)
    // UI update from Room (the mutation above will trigger Room invalidation).
}
```

---

## Phase 5: Conversation Rebuild in Single Transaction

### Modify: `data/TelephonySyncCoordinator.kt`

Replace `rebuildConversationFor()` to use SQL COUNT instead of in-memory scan:

```kotlin
/**
 * Rebuilds ONE conversation row from the messages table.
 * Uses SQL COUNT for unread — O(unread_count) instead of O(total_messages).
 */
private suspend fun rebuildConversationProjection(threadId: Long, preserveFlags: Boolean) {
    if (threadId <= 0L) return
    val dao = db.messageDao()
    val convDao = db.conversationDao()

    db.withTransaction {
        val newest = dao.pageForThread(threadId, limit = 1, offset = 0).firstOrNull()
            ?: return@withTransaction
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
                    pinned = existing?.pinned ?: false,
                    archived = existing?.archived ?: false
                )
            )
        }
    }
}
```

Delete the old `rebuildConversations()` (full table rebuild) — it should never
be called on the realtime path. Keep it only for startup/repair:

```kotlin
/** Full rebuild: startup or repair only. Never called on the realtime path. */
suspend fun fullRebuildConversations() = withContext(Dispatchers.IO) {
    val newestByThread = db.messageDao().newestPerThread()
    val archivedIds = ArchiveRepository(appContext).getArchivedIds()
    val pinnedIds = PinRepository(appContext).getPinnedIds()

    db.withTransaction {
        for (m in newestByThread) {
            val unread = db.messageDao().countUnread(m.threadId) // SQL COUNT
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
}
```

---

## Phase 6: Keyset Pagination for ThreadPager

### Modify: `repository/ThreadPager.kt`

Replace OFFSET-based paging with keyset (cursor) pagination:

```kotlin
class ThreadPager(
    private val context: Context,
    private val threadId: Long,
    private val phone: String = ""
) {
    companion object {
        const val PAGE = 40
    }

    // Cursor for keyset pagination.
    private var lastDate: Long = Long.MAX_VALUE
    private var lastId: Long = Long.MAX_VALUE

    @Volatile
    var hasMore: Boolean = true
        private set

    fun loadFirstPage(): List<Sms> {
        lastDate = Long.MAX_VALUE
        lastId = Long.MAX_VALUE
        val page = loadPage()
        hasMore = page.size >= (PAGE / 2)
        return page
    }

    fun loadOlder(): List<Sms> {
        if (!hasMore) return emptyList()
        val page = loadPage()
        if (page.isEmpty()) {
            hasMore = false
            return emptyList()
        }
        hasMore = page.size >= (PAGE / 2)
        return page
    }

    private fun loadPage(): List<Sms> {
        val repo = SmsRepository(context)

        // Keyset pagination: WHERE date < :lastDate OR (date = :lastDate AND providerId < :lastId)
        val keysetSelection = if (lastDate < Long.MAX_VALUE) {
            "(${Telephony.Sms.THREAD_ID} = ?) AND (" +
                "${Telephony.Sms.DATE} < ? OR " +
                "(${Telephony.Sms.DATE} = ? AND ${Telephony.Sms._ID} < ?))"
        } else {
            "${Telephony.Sms.THREAD_ID} = ?"
        }

        val keysetArgs = if (lastDate < Long.MAX_VALUE) {
            arrayOf(threadId.toString(), lastDate.toString(), lastDate.toString(), lastId.toString())
        } else {
            arrayOf(threadId.toString())
        }

        val sms = repo.querySmsRaw(
            selection = keysetSelection,
            selectionArgs = keysetArgs,
            sortOrder = "${Telephony.Sms.DATE} DESC, ${Telephony.Sms._ID} DESC",
            limit = PAGE
        )

        // Update cursor.
        if (sms.isNotEmpty()) {
            val last = sms.last()
            lastDate = last.date
            lastId = last.id
        }

        return sms.sortedBy { it.date } // ASC for display
    }

    // loadNewerSince() and loadSmsRowsById() remain unchanged.
}
```

---

## Phase 7: SyncState Dual Watermarks

### Modify: `data/Entities.kt` — enhanced SyncStateEntity

```kotlin
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val source: String, // "sms" | "mms"

    // ── Newest watermark (incoming direction) ──
    val newestDate: Long,
    val newestId: Long = 0L,

    // ── Oldest watermark (backfill direction) ──
    val oldestDate: Long = Long.MAX_VALUE,
    val oldestId: Long = Long.MAX_VALUE,

    // ── State flags ──
    val initialWindowReady: Boolean = false,
    val historyBackfillComplete: Boolean = false,

    // ── Repair bookkeeping ──
    val lastReconcileAt: Long = 0L,
    val schemaVersion: Int = 1
)
```

Migration: rename `newestSyncedDate` → `newestDate`, add new columns.

---

## Phase 8: Room Migration v2 → v3

```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Add partial index for unread count.
        db.execSQL("""
            CREATE INDEX IF NOT EXISTS idx_messages_thread_unread
            ON messages(threadId)
            WHERE read = 0 AND type = 1
        """)

        // 2. Add index for keyset pagination.
        db.execSQL("""
            CREATE INDEX IF NOT EXISTS idx_messages_thread_date_id
            ON messages(threadId, date DESC, providerId DESC)
        """)

        // 3. Expand sync_state with new watermarks.
        db.execSQL("ALTER TABLE sync_state ADD COLUMN newestId INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sync_state ADD COLUMN oldestDate INTEGER NOT NULL DEFAULT 9223372036854775807")
        db.execSQL("ALTER TABLE sync_state ADD COLUMN oldestId INTEGER NOT NULL DEFAULT 9223372036854775807")
        db.execSQL("ALTER TABLE sync_state ADD COLUMN initialWindowReady INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sync_state ADD COLUMN historyBackfillComplete INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sync_state ADD COLUMN lastReconcileAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sync_state ADD COLUMN schemaVersion INTEGER NOT NULL DEFAULT 1")
    }
}
```

---

## Phase 9: Startup Path — Fast Window + Background Backfill

### Modify: `data/TelephonySyncCoordinator.kt`

```kotlin
/**
 * Startup path: fast window + decoupled backfill.
 * The UI is ready after initialWindowReady = true (first 200-500 messages).
 * Backfill continues independently in the background.
 */
suspend fun startupSync() = withContext(Dispatchers.IO) {
    coroutineScope {
        launch { startupSource(MessageEntity.SOURCE_SMS) }
        launch { startupSource(MessageEntity.SOURCE_MMS) }
    }
}

private suspend fun startupSource(source: String) {
    val dao = db.messageDao()
    val stateDao = db.syncStateDao()
    val state = stateDao.forSource(source)

    if (state?.initialWindowReady == true) {
        // Already have initial window — just check for new messages.
        val newest = dao.newestDateFor(source) ?: 0L
        val fresh = readNewerThan(source, newest)
        if (fresh.isNotEmpty()) {
            dao.upsertAll(fresh.mapNotNull { toEntity(it, source) })
        }
        return
    }

    // First startup: mirror the newest 500 messages.
    val reader = if (source == MessageEntity.SOURCE_SMS) ::readSmsNewestFirst else ::readMmsNewestFirst
    val batch = reader(500, 0)
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
}

/**
 * Incremental backfill: processes BATCH_SIZE messages, yields, repeats.
 * Does NOT block the UI. The user sees messages immediately from the
 * initial window.
 */
private suspend fun backfillOlderIncremental(
    source: String,
    reader: suspend (limit: Int, offset: Int) -> List<Sms>,
    batchSize: Int = 500
) {
    val dao = db.messageDao()
    val stateDao = db.syncStateDao()
    val state = stateDao.forSource(source) ?: return
    if (state.historyBackfillComplete) return

    // Start from where we left off (or from the end if first time).
    var offset = batchSize // skip the initial window we already have

    while (true) {
        val batch = reader(batchSize, offset)
        if (batch.isEmpty()) break

        dao.insertOrIgnore(batch.mapNotNull { toEntity(it, source) })
        offset += batch.size

        // Update watermarks.
        val oldest = batch.minByOrNull { it.date }
        stateDao.upsert(
            state.copy(
                oldestDate = oldest?.date ?: state.oldestDate,
                oldestId = oldest?.id ?: state.oldestId,
                lastReconcileAt = System.currentTimeMillis()
            )
        )

        if (batch.size < batchSize) break

        // Yield: let other coroutines run, prevent blocking the dispatcher.
        kotlinx.coroutines.yield()
    }

    stateDao.upsert(
        state.copy(
            historyBackfillComplete = true,
            lastReconcileAt = System.currentTimeMillis()
        )
    )
}
```

---

## Phase 10: SmsStatusReceiver Targeted Mutation

### Modify: `sms/SmsStatusReceiver.kt`

**Before**:
```kotlin
SmsEventBus.notifyResume()
```

**After**:
```kotlin
// Targeted: only refresh the status of the specific message.
TelephonySyncCoordinator.get(context).mutate(
    MessageMutation.RefreshStatus(
        source = MessageEntity.SOURCE_SMS,
        providerId = rowId
    )
)
```

---

## Phase 11: HomeViewModel Cleanup

### Remove: full reload on every incoming SMS

**Before** (`observeIncomingSms()`):
```kotlin
SmsEventBus.incomingSmsFlow.collect { incomingSms ->
    // ... optimistic prepend ...
    loadSms()  // <-- triggers full provider scan!
}
```

**After**:
```kotlin
SmsEventBus.incomingSmsFlow.collect { incomingSms ->
    // The mutation in IncomingMessageDispatcher already updated Room.
    // Room Flow will deliver the updated conversation list.
    // No manual reload needed — Room invalidation handles it.
}
```

The Home list should observe `conversationDao().observeAll()` (a `Flow<List<ConversationEntity>>`)
instead of maintaining its own mutable state and reloading from the provider.

### Long-term: Room Flow → UI

```kotlin
class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val db = MessagesDatabase.get(application)

    // The SSOT: Room Flow drives the UI directly.
    val conversations: StateFlow<List<ConversationEntity>> = db.conversationDao()
        .observeAll()
        .map { list -> list.filter { !it.archived } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val archivedConversations: StateFlow<List<ConversationEntity>> = db.conversationDao()
        .observeAll()
        .map { list -> list.filter { it.archived } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
```

This eliminates the entire `replaceConversations()` / `applySwap()` /
`silentRefresh()` machinery — Room Flow delivers changes automatically
when the underlying table changes.

---

## Summary: Performance Characteristics

### Before (current):

| Event | Operations | Complexity |
|---|---|---|
| SMS arrives | requestSync → full syncSource × 2 → rebuildConversations → countUnread(10K) per thread | O(N × threads) |
| Status callback | notifyResume → full refresh | O(N) |
| Conversation open | Full thread load or cache hit | O(thread_size) |
| Scroll to older page | OFFSET-based pagination | O(offset + limit) |

### After (V2):

| Event | Operations | Complexity |
|---|---|---|
| SMS arrives | 1 Provider read → 1 Room transaction (message + conversation) | O(1) |
| Status callback | 1 Provider read → 1 Room upsert | O(1) |
| Conversation open | Keyset paginated first page (40 rows) | O(1) |
| Scroll to older page | Keyset WHERE date < cursor | O(1) |
| ContentObserver (URI known) | Targeted mutation | O(1) |
| ContentObserver (URI unknown) | Bounded reconcile | O(window) |
| Startup | 500-row window + background backfill | O(window) |
| Crash recovery | Watermark gap detection → bounded repair | O(gap) |

### For 360,000 messages:

- **Incoming SMS**: 1 provider read + 1 Room transaction = **constant time**
- **Home list**: Room Flow → single indexed query on conversations table = **O(threads)**
- **Conversation scroll**: Keyset pagination = **O(page_size)** regardless of position
- **Startup**: 500 messages visible instantly, backfill in background = **sub-second UI**
- **Full rebuild**: Only on explicit repair = **background, non-blocking**

---

## Migration Plan (Ordered by Risk)

| Phase | Risk | Files Changed | Depends On |
|---|---|---|---|
| 1. MessageMutation + dual channel | Low | New file + TelephonySyncCoordinator | — |
| 2. countUnread → SQL COUNT | Low | Daos.kt, TelephonySyncCoordinator | — |
| 3. IncomingMessageDispatcher fast path | Medium | IncomingMessageDispatcher | Phase 1 |
| 4. SmsStatusReceiver targeted | Low | SmsStatusReceiver | Phase 1 |
| 5. Conversation rebuild transaction | Low | TelephonySyncCoordinator | Phase 2 |
| 6. SmsContentObserver URI-aware | Medium | SmsContentObserver, new ChangeRouter | Phase 1 |
| 7. Room migration v2→v3 | Medium | MessagesDatabase, Entities | Phase 2 |
| 8. Keyset pagination | Medium | ThreadPager | — |
| 9. SyncState dual watermarks | Low | Entities, TelephonySyncCoordinator | Phase 7 |
| 10. Startup path | Medium | TelephonySyncCoordinator | Phase 1, 8, 9 |
| 11. HomeViewModel Room Flow | High | HomeViewModel, HomeScreen | All above |

Each phase is independently deployable. Phases 1-6 can ship as a single release.
Phases 7-10 as a second release. Phase 11 as a third.
