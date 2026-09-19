package com.autonomousone.messages.repository

import android.content.Context
import androidx.room.withTransaction
import com.autonomousone.messages.data.MessageKey
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.utils.DiagnosticLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * FEATURE 10 (bulk actions) — ONE bounded, batched entry point for every
 * multi-select action on Home (conversations) and in a conversation (messages).
 *
 * Non-negotiable rules encoded here:
 *
 *  1. **Batch, never N UI coroutines.** Every Room-side half is applied in ONE
 *     transaction / one shared-preferences commit through the EXISTING
 *     field-scoped writers ([ConversationPreferenceRepository],
 *     [ArchiveRepository], [PinRepository], [MessageUserStateRepository],
 *     [TrashRepository]). A bulk action allocates coroutines for PROVIDER
 *     writes only, and those are capped by [BulkConcurrency].
 *  2. **Bounded provider concurrency.** 300 selected conversations must never
 *     become 300 simultaneous ContentResolver operations. Provider writes run
 *     through [boundedParallelMap] with a hard ceiling of
 *     [BulkConcurrency.MAX_PARALLEL]; everything runs off the caller's thread.
 *  3. **Typed partial failure.** Every action returns a [BulkResult] carrying
 *     per-item outcomes so the UI can say "12 updated, 2 could not be updated"
 *     instead of silently claiming full success.
 *  4. **Never rewrite provider READ back to 0.** "Mark as unread" is the
 *     `manualUnread` UI bookmark ([ConversationPreferenceRepository]); the
 *     Telephony READ column is only ever written to 1 by the existing
 *     [MarkConversationReadUseCase] read path.
 *  5. **Diagnostics carry counts and ids only** — never a message body, OTP code
 *     or full phone number.
 */

// ─────────────────────────────────────────────────────────────────────────────
// Result types
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The UI selection identity ([MessageIdentity.Key]) bridged to the persisted
 * message-state identity ([MessageKey]). Both are the SAME composite
 * `(source, providerId)` pair; making the bridge explicit means a selection set
 * can never be passed where a raw id is expected.
 */
fun MessageIdentity.Key.toMessageKey(): MessageKey = MessageKey(source, providerId)

/**
 * One item a bulk action could not apply.
 *
 * [threadId] identifies a conversation, [source] + [providerId] the composite
 * identity of a message. Neither a body nor a phone number is ever carried.
 */
data class BulkFailure(
    val threadId: Long = 0L,
    val source: String? = null,
    val providerId: Long? = null,
    val reason: String
) {
    /** Short, loggable identity token (never a body / phone number). */
    fun token(): String = when {
        source != null && providerId != null -> "$source:$providerId"
        threadId > 0L -> "thread:$threadId"
        else -> "unknown"
    }
}

/**
 * Typed outcome of ONE bulk action. Never collapses "partly applied" into
 * "done": [failedCount] is the number of selected items the operation could not
 * apply, and [isFullSuccess] is false whenever anything failed.
 */
data class BulkResult(
    val requested: Int,
    val succeeded: Int,
    val failed: List<BulkFailure> = emptyList()
) {
    val failedCount: Int get() = failed.size

    /** True ONLY when every requested item was applied. */
    val isFullSuccess: Boolean get() = requested > 0 && failed.isEmpty()

    /** Nothing was requested (empty selection) — a quiet no-op. */
    val isEmpty: Boolean get() = requested == 0

    val isPartial: Boolean get() = succeeded > 0 && failed.isNotEmpty()

    val isCompleteFailure: Boolean get() = succeeded == 0 && failed.isNotEmpty()

    fun merge(other: BulkResult): BulkResult = BulkResult(
        requested = requested + other.requested,
        succeeded = succeeded + other.succeeded,
        failed = failed + other.failed
    )

    /** Thread ids this result could NOT apply, for "12 of 14" style reporting. */
    fun failedThreadIds(): Set<Long> = failed.mapNotNull { it.threadId.takeIf { id -> id > 0L } }.toSet()

    companion object {
        /** The quiet no-op an EMPTY selection produces. */
        fun empty(): BulkResult = BulkResult(requested = 0, succeeded = 0, failed = emptyList())

        fun success(requested: Int): BulkResult =
            BulkResult(requested = requested, succeeded = requested, failed = emptyList())

        /** The WHOLE conversation batch failed: every id is reported failed. */
        fun failedThreads(threadIds: Collection<Long>, reason: String): BulkResult = BulkResult(
            requested = threadIds.size,
            succeeded = 0,
            failed = threadIds.map { BulkFailure(threadId = it, reason = reason) }
        )

        /** The WHOLE message batch failed: every composite key is reported failed. */
        fun failedKeys(keys: Collection<MessageKey>, reason: String): BulkResult = BulkResult(
            requested = keys.size,
            succeeded = 0,
            failed = keys.map {
                BulkFailure(source = it.source, providerId = it.providerId, reason = reason)
            }
        )
    }
}

/**
 * UI projection of a [BulkResult]: the snackbar needs counts, and a bulk Trash
 * needs the ids it moved so it can offer Undo.
 */
data class BulkFeedback(
    val succeeded: Int,
    val failed: Int,
    val trashedThreadIds: List<Long> = emptyList()
) {
    val isFullSuccess: Boolean get() = failed == 0 && succeeded > 0
    val isCompleteFailure: Boolean get() = succeeded == 0 && failed > 0

    companion object {
        fun from(result: BulkResult, trashedThreadIds: List<Long> = emptyList()): BulkFeedback =
            BulkFeedback(result.succeeded, result.failedCount, trashedThreadIds)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Bounded concurrency
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The one place the provider-write fan-out ceiling is defined.
 *
 * Sized for the real constraint: a burst of `ContentResolver.update` calls
 * competes for the telephony provider's own single writer, so a small cap keeps
 * the UI responsive without serialising the whole batch.
 */
object BulkConcurrency {
    /** Hard ceiling on simultaneous provider writes for ONE bulk action. */
    const val MAX_PARALLEL: Int = 3

    /** Lowest cap that still overlaps provider latency. */
    const val MIN_PARALLEL: Int = 1
}

/**
 * Runs [block] for every item with at most [maxParallel] in flight, always on
 * [Dispatchers.IO], and returns ONE ordered `Result` per item — never a thrown
 * batch, so the caller can report per-item outcomes.
 *
 * Cancellation is rethrown (it is not a per-item failure), and an empty input
 * allocates nothing at all.
 */
suspend fun <T, R> boundedParallelMap(
    items: Collection<T>,
    maxParallel: Int = BulkConcurrency.MAX_PARALLEL,
    block: suspend (T) -> R
): List<Result<R>> {
    if (items.isEmpty()) return emptyList()
    require(maxParallel >= BulkConcurrency.MIN_PARALLEL) {
        "maxParallel must be >= ${BulkConcurrency.MIN_PARALLEL} (got $maxParallel)"
    }
    val gate = Semaphore(maxParallel)
    return coroutineScope {
        items.map { item ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    try {
                        Result.success(block(item))
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        Result.failure(error)
                    }
                }
            }
        }.awaitAll()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Injected halves (so the engine is plain-JVM testable and the Android/Room
// dependencies stay in ONE production implementation each)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Room-side half of a bulk CONVERSATION action. Implementations MUST apply the
 * whole set in one transaction / one commit, off the caller's thread.
 */
interface BulkThreadStateWriter {
    suspend fun setManualUnread(threadIds: Set<Long>, unread: Boolean, now: Long)
    suspend fun setMutedUntil(threadIds: Set<Long>, until: Long, now: Long)
    suspend fun setArchived(threadIds: Set<Long>, archived: Boolean)
    suspend fun setPinned(threadIds: Set<Long>, pinned: Boolean)
}

/**
 * Room-side TRASH writer for conversations.
 *
 * `moveToTrash` writes ONE tombstone per thread (never one row per message) and
 * returns the ids it could NOT write. `restore` clears tombstones — the provider
 * rows were never touched, so no re-insert is needed.
 */
interface BulkThreadTrashWriter {
    suspend fun moveToTrash(threadIds: Set<Long>, now: Long): Set<Long>
    suspend fun restore(threadIds: Set<Long>)
}

/**
 * Provider/local READ applier for ONE conversation, reusing the single
 * [MarkConversationReadUseCase] path. Returns false when the conversation could
 * not be marked read.
 */
fun interface BulkThreadReader {
    suspend fun markThreadRead(threadId: Long): Boolean
}

/**
 * Room-side half of a bulk MESSAGE action. Identity is ALWAYS the composite
 * (source, providerId); [threadIdsFor] resolves the owning thread so the
 * per-message user-state row carries the right `threadId`.
 */
interface BulkMessageStateWriter {
    /** Composite key → owning threadId. Keys with no Room row are omitted. */
    suspend fun threadIdsFor(keys: Collection<MessageKey>): Map<MessageKey, Long>

    /** Star/unstar every key of ONE thread. False when the batch failed. */
    suspend fun setStarredForThread(
        keys: Collection<MessageKey>,
        threadId: Long,
        starred: Boolean,
        now: Long
    ): Boolean

    /** Individually trashed state for every key of ONE thread. */
    suspend fun markTrashedForThread(
        keys: Collection<MessageKey>,
        threadId: Long,
        trashedAt: Long,
        purgeAt: Long,
        now: Long
    ): Boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// The bulk engine
// ─────────────────────────────────────────────────────────────────────────────

class BulkActionRepository(
    private val threadState: BulkThreadStateWriter,
    private val threadTrash: BulkThreadTrashWriter,
    private val threadReader: BulkThreadReader,
    private val messageState: BulkMessageStateWriter,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Provider-write fan-out cap; never above [BulkConcurrency.MAX_PARALLEL]. */
    private val maxParallel: Int = BulkConcurrency.MAX_PARALLEL
) {

    // ── Conversations ──────────────────────────────────────────────────────

    /**
     * Mark conversations UNREAD. This is the `manualUnread` UI bookmark only:
     * the Telephony READ column is deliberately NOT rewritten to 0.
     */
    suspend fun markUnread(threadIds: Set<Long>): BulkResult {
        val ids = positive(threadIds)
        return localThreadBatch(ids, "markUnread") {
            threadState.setManualUnread(ids, unread = true, now = clock())
        }
    }

    /**
     * Mark conversations READ: clears the bookmark in one transaction, then
     * runs the EXISTING read path ([BulkThreadReader] → MarkConversationReadUseCase)
     * per thread with bounded provider concurrency.
     */
    suspend fun markRead(threadIds: Set<Long>): BulkResult {
        val ids = positive(threadIds)
        if (ids.isEmpty()) return BulkResult.empty()

        // Home unread = real unread OR the bookmark, so a mark-read that left the
        // bookmark set would be invisible. Clearing it is part of the action.
        val bookmark = localThreadBatch(ids, "markRead-bookmark") {
            threadState.setManualUnread(ids, unread = false, now = clock())
        }
        val bookmarkFailed = bookmark.failedThreadIds()

        val ordered = ids.toList()
        val outcomes = boundedParallelMap(ordered, maxParallel) { id -> threadReader.markThreadRead(id) }

        var succeeded = 0
        val failed = ArrayList<BulkFailure>()
        ordered.forEachIndexed { index, id ->
            when {
                id in bookmarkFailed -> failed += BulkFailure(threadId = id, reason = "bookmark")
                outcomes[index].getOrNull() == true -> succeeded++
                else -> failed += BulkFailure(
                    threadId = id,
                    reason = outcomes[index].exceptionOrNull()?.javaClass?.simpleName ?: "read-failed"
                )
            }
        }
        return finish("markRead", BulkResult(ids.size, succeeded, failed))
    }

    suspend fun archive(threadIds: Set<Long>): BulkResult =
        setArchived(threadIds, archived = true, action = "archive")

    suspend fun unarchive(threadIds: Set<Long>): BulkResult =
        setArchived(threadIds, archived = false, action = "unarchive")

    /**
     * Mute every conversation until [until] (epoch millis, or
     * [ConversationPreferenceEntity.MUTE_FOREVER]).
     */
    suspend fun mute(threadIds: Set<Long>, until: Long): BulkResult {
        val ids = positive(threadIds)
        val now = clock()
        return localThreadBatch(ids, "mute") {
            threadState.setMutedUntil(ids, until = until, now = now)
        }
    }

    /** Unmute: `mutedUntil = 0` means "not muted", by comparison. */
    suspend fun unmute(threadIds: Set<Long>): BulkResult {
        val ids = positive(threadIds)
        val now = clock()
        return localThreadBatch(ids, "unmute") {
            threadState.setMutedUntil(ids, until = 0L, now = now)
        }
    }

    suspend fun pin(threadIds: Set<Long>, pinned: Boolean): BulkResult {
        val ids = positive(threadIds)
        return localThreadBatch(ids, if (pinned) "pin" else "unpin") {
            threadState.setPinned(ids, pinned = pinned)
        }
    }

    /**
     * Move conversations to Trash: ONE durable tombstone per thread, provider
     * rows untouched, retention owned by [TrashRepository].
     */
    suspend fun moveToTrash(threadIds: Set<Long>): BulkResult {
        val ids = positive(threadIds)
        if (ids.isEmpty()) return BulkResult.empty()
        val failedIds = try {
            threadTrash.moveToTrash(ids, clock())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            DiagnosticLog.event(CATEGORY, "action=trash requested=${ids.size} batch-failed", error)
            return BulkResult.failedThreads(ids, error.javaClass.simpleName)
        }
        return finish("trash", resultOf(ids, failedIds))
    }

    /** Undo of [moveToTrash]; provider rows were never deleted, so it is local. */
    suspend fun restoreFromTrash(threadIds: Set<Long>): BulkResult {
        val ids = positive(threadIds)
        if (ids.isEmpty()) return BulkResult.empty()
        return try {
            threadTrash.restore(ids)
            finish("restore", BulkResult.success(ids.size))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            finish("restore", BulkResult.failedThreads(ids, error.javaClass.simpleName))
        }
    }

    // ── Messages ───────────────────────────────────────────────────────────

    /**
     * Star or unstar every selected message. Identity is the composite key, so
     * SMS 100 and MMS 100 never share a star.
     */
    suspend fun star(keys: Set<MessageKey>, starred: Boolean): BulkResult {
        val unique = validKeys(keys)
        if (unique.isEmpty()) return BulkResult.empty()
        return messageBatch("star", unique) { group, threadId, now ->
            messageState.setStarredForThread(group, threadId, starred, now)
        }
    }

    /**
     * Move selected MESSAGES to Trash (individually-trashed state, retained for
     * [TrashRepository.RETENTION_MILLIS] before permanent purge eligibility).
     *
     * Takes a [Collection] rather than a `Set` because the conversation-level
     * overload `moveToTrash(Set<Long>)` erases to the same JVM signature — the
     * two cannot coexist as `Set` parameters.
     */
    suspend fun moveToTrash(keys: Collection<MessageKey>): BulkResult {
        val unique = validKeys(keys)
        if (unique.isEmpty()) return BulkResult.empty()
        return messageBatch("message-trash", unique) { group, threadId, now ->
            messageState.markTrashedForThread(
                keys = group,
                threadId = threadId,
                trashedAt = now,
                purgeAt = now + TrashRepository.RETENTION_MILLIS,
                now = now
            )
        }
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private suspend fun setArchived(
        threadIds: Set<Long>,
        archived: Boolean,
        action: String
    ): BulkResult {
        val ids = positive(threadIds)
        return localThreadBatch(ids, action) { threadState.setArchived(ids, archived) }
    }

    /**
     * One Room-side batch. A failure inside the transaction is reported as
     * "nothing succeeded" — never as success — and the whole selected set is
     * listed as failed so the UI can state exactly how many were not applied.
     */
    private suspend fun localThreadBatch(
        ids: Set<Long>,
        action: String,
        apply: suspend () -> Unit
    ): BulkResult {
        if (ids.isEmpty()) return BulkResult.empty()
        return try {
            withContext(Dispatchers.IO) { apply() }
            finish(action, BulkResult.success(ids.size))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            DiagnosticLog.event(
                CATEGORY,
                "action=$action requested=${ids.size} succeeded=0 failed=${ids.size} batch-failed",
                error
            )
            BulkResult.failedThreads(ids, error.javaClass.simpleName)
        }
    }

    /**
     * Resolves the owning thread of every key ONCE (one batched Room read), then
     * applies one Room transaction PER THREAD and reports per-item outcomes.
     */
    private suspend fun messageBatch(
        action: String,
        unique: Set<MessageKey>,
        apply: suspend (
            group: List<MessageKey>,
            threadId: Long,
            now: Long
        ) -> Boolean
    ): BulkResult {
        val now = clock()
        val resolved = try {
            messageState.threadIdsFor(unique)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            DiagnosticLog.event(
                CATEGORY,
                "action=$action requested=${unique.size} resolve-failed",
                error
            )
            return BulkResult.failedKeys(unique, "resolve-failed")
        }

        var succeeded = 0
        val failed = ArrayList<BulkFailure>()
        unique.groupBy { resolved[it] }.forEach { (threadId, group) ->
            if (threadId == null || threadId <= 0L) {
                group.forEach {
                    failed += BulkFailure(
                        source = it.source,
                        providerId = it.providerId,
                        reason = "no-thread"
                    )
                }
                return@forEach
            }
            val ok = try {
                apply(group, threadId, now)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                DiagnosticLog.event(
                    CATEGORY,
                    "action=$action thread=$threadId count=${group.size} failed",
                    error
                )
                false
            }
            if (ok) {
                succeeded += group.size
            } else {
                group.forEach {
                    failed += BulkFailure(
                        threadId = threadId,
                        source = it.source,
                        providerId = it.providerId,
                        reason = "write-failed"
                    )
                }
            }
        }
        return finish(action, BulkResult(unique.size, succeeded, failed))
    }

    private fun positive(threadIds: Set<Long>): Set<Long> =
        threadIds.filterTo(LinkedHashSet(threadIds.size)) { it > 0L }

    /** Provider ids are positive; an id <= 0 is not an identity. */
    private fun validKeys(keys: Collection<MessageKey>): Set<MessageKey> =
        keys.filterTo(LinkedHashSet(keys.size)) { it.providerId > 0L && it.source.isNotBlank() }

    private fun resultOf(ids: Set<Long>, failedIds: Set<Long>): BulkResult {
        val failed = ids.filter { it in failedIds }
        return BulkResult(
            requested = ids.size,
            succeeded = ids.size - failed.size,
            failed = failed.map { BulkFailure(threadId = it, reason = "trash-failed") }
        )
    }

    /** Counts + ids only — never a body, OTP code or full phone number. */
    private fun finish(action: String, result: BulkResult): BulkResult {
        if (result.isEmpty) return result
        val failureDetail = if (result.failed.isEmpty()) {
            ""
        } else {
            " failed_ids=" +
                result.failed.take(MAX_LOGGED_IDS).joinToString(",") { it.token() }
        }
        DiagnosticLog.event(
            CATEGORY,
            "action=$action requested=${result.requested} succeeded=${result.succeeded} " +
                "failed=${result.failedCount}$failureDetail"
        )
        return result
    }

    companion object {
        private const val CATEGORY = "BULK_ACTION"

        /** A bulk action must never write a 300-id line into the log. */
        private const val MAX_LOGGED_IDS = 20

        @Volatile
        private var instance: BulkActionRepository? = null

        /** Process-wide instance wired to the real repositories. */
        fun get(context: Context): BulkActionRepository {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }
        }

        private fun build(context: Context): BulkActionRepository = BulkActionRepository(
            threadState = RoomBulkThreadStateWriter(context),
            threadTrash = RoomBulkThreadTrashWriter(context),
            threadReader = UseCaseBulkThreadReader(context),
            messageState = RoomBulkMessageStateWriter(context)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Production halves
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Conversation user-state via the EXISTING field-scoped writers, applied inside
 * ONE Room transaction so a 300-thread selection is 300 statements in one
 * commit instead of 300 transactions.
 */
class RoomBulkThreadStateWriter(context: Context) : BulkThreadStateWriter {

    private val database = MessagesDatabase.get(context)
    private val preferences = ConversationPreferenceRepository(context)
    private val archive = ArchiveRepository(context)
    private val pins = PinRepository(context)

    override suspend fun setManualUnread(threadIds: Set<Long>, unread: Boolean, now: Long) {
        database.withTransaction {
            threadIds.forEach { preferences.setManualUnread(it, unread, now) }
        }
    }

    override suspend fun setMutedUntil(threadIds: Set<Long>, until: Long, now: Long) {
        database.withTransaction {
            threadIds.forEach { preferences.setMutedUntil(it, until, now) }
        }
    }

    override suspend fun setArchived(threadIds: Set<Long>, archived: Boolean) {
        archive.setArchived(threadIds, archived)
    }

    override suspend fun setPinned(threadIds: Set<Long>, pinned: Boolean) {
        pins.setPinned(threadIds, pinned)
    }
}

/**
 * Conversation Trash writer.
 *
 * Delegates to [TrashRepository.moveToTrash] — the single owner of tombstone
 * semantics (cutoff + retention). This writer NEVER purges: permanent deletion
 * (and its provider write) belongs to the durable Trash flow, so the purge seam
 * is a fail-loud guard rather than a second provider implementation.
 */
class RoomBulkThreadTrashWriter(context: Context) : BulkThreadTrashWriter {

    private val database = MessagesDatabase.get(context)
    private val messages = database.messageDao()
    private val trash = TrashRepository(
        context,
        TrashRepository.ProviderPurger {
            throw UnsupportedOperationException(
                "bulk trash writes tombstones only; provider purge is owned by the Trash flow"
            )
        }
    )

    override suspend fun moveToTrash(threadIds: Set<Long>, now: Long): Set<Long> {
        val failed = LinkedHashSet<Long>()
        database.withTransaction {
            threadIds.forEach { threadId ->
                try {
                    // The cutoff is the canonical newest row AT TRASH TIME; a
                    // genuinely newer message afterwards stays visible.
                    val newest = messages.newestForThread(threadId)
                    trash.moveToTrash(threadId, newest, now)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    failed += threadId
                    DiagnosticLog.event("TRASH", "thread=$threadId tombstone-failed", error)
                }
            }
        }
        return failed
    }

    override suspend fun restore(threadIds: Set<Long>) {
        trash.restoreAll(threadIds.toList())
    }
}

/**
 * Mark-read half: the ONE existing read path, so local Room read, the optimistic
 * UI signal, the eventual provider write and the narrow repair all behave
 * exactly as they do for a single conversation.
 */
class UseCaseBulkThreadReader(context: Context) : BulkThreadReader {

    private val useCase = MarkConversationReadUseCase.get(context)

    override suspend fun markThreadRead(threadId: Long): Boolean {
        if (threadId <= 0L) return false
        // The use case isolates its own provider failure (typed diagnostic +
        // narrow repair); a thrown error here really is a batch failure.
        useCase.markRead(threadId, "")
        return true
    }
}

/**
 * Message user-state via the EXISTING field-scoped writers, in one transaction
 * per thread. Composite identity throughout: SMS 100 and MMS 100 never collide.
 */
class RoomBulkMessageStateWriter(context: Context) : BulkMessageStateWriter {

    private val database = MessagesDatabase.get(context)
    private val messages = database.messageDao()
    private val userState = MessageUserStateRepository(context)

    override suspend fun threadIdsFor(keys: Collection<MessageKey>): Map<MessageKey, Long> {
        val resolved = LinkedHashMap<MessageKey, Long>(keys.size)
        database.withTransaction {
            keys.forEach { key ->
                // Primary-key lookup on (source, providerId); a selection is
                // bounded by the loaded window, never by total history.
                val row = messages.findByKey(key.source, key.providerId)
                if (row != null && row.threadId > 0L) resolved[key] = row.threadId
            }
        }
        return resolved
    }

    override suspend fun setStarredForThread(
        keys: Collection<MessageKey>,
        threadId: Long,
        starred: Boolean,
        now: Long
    ): Boolean = writeBatch {
        keys.forEach { userState.setStarred(it, threadId, starred, now) }
    }

    override suspend fun markTrashedForThread(
        keys: Collection<MessageKey>,
        threadId: Long,
        trashedAt: Long,
        purgeAt: Long,
        now: Long
    ): Boolean = writeBatch {
        userState.markTrashed(keys, threadId, trashedAt, purgeAt, now)
    }

    private suspend fun writeBatch(block: suspend () -> Unit): Boolean = try {
        database.withTransaction { block() }
        true
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        DiagnosticLog.event(CATEGORY, "message-user-state batch-failed", error)
        false
    }

    private companion object {
        const val CATEGORY = "BULK_ACTION"
    }
}
