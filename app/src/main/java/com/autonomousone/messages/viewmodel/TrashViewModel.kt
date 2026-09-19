package com.autonomousone.messages.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.TelephonySyncCoordinator
import com.autonomousone.messages.data.TrashedThreadEntity
import com.autonomousone.messages.repository.ContactRepository
import com.autonomousone.messages.repository.TrashRepository
import com.autonomousone.messages.trash.TrashPurgeScheduler
import com.autonomousone.messages.utils.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Recently Deleted / Trash (v3.4.0 FEATURE 8).
 *
 * The screen is a read model over the durable tombstones (`trashed_threads`), so
 * it needs no paging, no message scan and no provider access:
 *
 *  - the LIST is one indexed `ORDER BY deletedAt DESC` read of a table that holds
 *    one row per trashed CONVERSATION;
 *  - the CONTACT NAME and ADDRESS come from the RAW MIRROR row the tombstone
 *    stands for (`MessageDao.newestForThread`), because the provider rows are
 *    deliberately still there. That is exactly why Restore is instant;
 *  - the days-remaining arithmetic is the repository's ([TrashRepository.daysRemaining]),
 *    shared with the purge deadline, so the label and the worker cannot disagree.
 *
 * Every state the screen needs is explicit: Loading before the first emission,
 * Content / Empty afterwards, Error when the Room read fails (with a REAL retry —
 * see [retry]).
 */
class TrashViewModel(application: Application) : AndroidViewModel(application) {

    private val trashRepository by lazy { TrashRepository.get(getApplication()) }

    /** One trashed conversation, already resolved for rendering. */
    data class TrashRow(
        val threadId: Long,
        /** Contact name when one exists, else the address, else empty. */
        val displayName: String,
        val address: String,
        val deletedAt: Long,
        val daysRemaining: Long,
        val purgeAt: Long
    )

    sealed interface TrashUiState {
        /** Before the first durable emission. Never a blank screen. */
        data object Loading : TrashUiState

        data class Content(val rows: List<TrashRow>) : TrashUiState

        /** Trash is genuinely empty — not "still loading". */
        data object Empty : TrashUiState

        data class Error(val reason: String) : TrashUiState
    }

    /** One-shot outcome for the screen's snackbar, localised by the composable. */
    sealed interface TrashNotice {
        data object Restored : TrashNotice
        data object RestoreFailed : TrashNotice
        data object PermanentlyDeleted : TrashNotice
        data object PermanentDeleteFailed : TrashNotice
        data class Emptied(val purged: Int, val failed: Int) : TrashNotice
    }

    private val mutableState = MutableStateFlow<TrashUiState>(TrashUiState.Loading)

    /** Read-only UI state; Compose collects it with `collectAsState()`. */
    val uiState: StateFlow<TrashUiState> = mutableState

    /** One-shot snackbar outcome; cleared by [consumeNotice]. */
    var notice: TrashNotice? by mutableStateOf(null)
        private set

    /** Thread ids with an action in flight, so those rows disable their buttons. */
    val busyThreadIds = mutableStateSetOf<Long>()

    /** True while "Empty Trash" is running. */
    var emptying by mutableStateOf(false)
        private set

    /** The single Room observation; cancelled and rebuilt by [retry]. */
    private var observation: Job? = null

    init {
        observe()
    }

    private fun observe() {
        observation?.cancel()
        observation = viewModelScope.launch {
            mutableState.value = TrashUiState.Loading
            trashRepository.observeAll()
                .map { tombstones -> toState(tombstones) }
                .catch { error ->
                    DiagnosticLog.event("TRASH", "screen-read-failed", error)
                    mutableState.value = TrashUiState.Error(error.javaClass.simpleName)
                }
                .collect { state -> mutableState.value = state }
        }
    }

    /**
     * Resolves the durable tombstones into renderable rows.
     *
     * One bounded indexed read per trashed thread for its address (the mirror's
     * newest row) — never a message scan: Trash holds one row per CONVERSATION.
     * The contact map is cached process-wide by [ContactRepository].
     */
    private suspend fun toState(tombstones: List<TrashedThreadEntity>): TrashUiState =
        withContext(Dispatchers.IO) {
            if (tombstones.isEmpty()) return@withContext TrashUiState.Empty
            val now = System.currentTimeMillis()
            val messages = MessagesDatabase.get(getApplication()).messageDao()
            val contacts = runCatching {
                ContactRepository(getApplication()).getContactNameMapAsync()
            }.getOrDefault(emptyMap())
            val rows = tombstones.map { tombstone ->
                val raw = runCatching { messages.newestForThread(tombstone.threadId) }.getOrNull()
                val address = raw?.rawAddress.orEmpty()
                val name = contacts[ContactRepository.normalizePhone(address)]
                TrashRow(
                    threadId = tombstone.threadId,
                    displayName = name ?: address,
                    address = address,
                    deletedAt = tombstone.deletedAt,
                    daysRemaining = trashRepository.daysRemaining(tombstone, now),
                    purgeAt = tombstone.purgeAt
                )
            }
            TrashUiState.Content(rows)
        }

    /** Reloads the list after a read failure. The previous observation is dropped. */
    fun retry() {
        observe()
    }

    fun consumeNotice() {
        notice = null
    }

    /**
     * Restore: clears the tombstone. The provider rows were never touched, so
     * nothing is re-inserted — and the Home projection is rebuilt from the mirror
     * Room already holds. Immediate, offline, no provider write.
     */
    fun restore(threadId: Long) {
        if (threadId <= 0L || !busyThreadIds.add(threadId)) return
        viewModelScope.launch {
            val restored = runCatching {
                trashRepository.restore(threadId)
                TelephonySyncCoordinator.get(getApplication())
                    .rebuildThreadProjectionFromMirror(threadId)
                true
            }.getOrElse { error ->
                DiagnosticLog.event("TRASH", "restore-failed thread=$threadId", error)
                false
            }
            // A restore can remove the earliest deadline: re-arm (or cancel) the
            // ONE purge worker so it never points at a tombstone that is gone.
            runCatching { TrashPurgeScheduler.scheduleNext(getApplication()) }
            busyThreadIds.remove(threadId)
            notice = if (restored) TrashNotice.Restored else TrashNotice.RestoreFailed
        }
    }

    /**
     * Delete permanently: provider-first. The tombstone and the Room rows survive
     * any provider failure, so this must not report success unless the delete was
     * CONFIRMED ([TrashRepository.purgeNow]).
     */
    fun deletePermanently(threadId: Long) {
        if (threadId <= 0L || !busyThreadIds.add(threadId)) return
        viewModelScope.launch {
            val deleted = runCatching { trashRepository.purgeNow(threadId) }
                .getOrElse { error ->
                    DiagnosticLog.event("TRASH", "purge-now-failed thread=$threadId", error)
                    false
                }
            runCatching { TrashPurgeScheduler.scheduleNext(getApplication()) }
            busyThreadIds.remove(threadId)
            notice = if (deleted) TrashNotice.PermanentlyDeleted else TrashNotice.PermanentDeleteFailed
        }
    }

    /**
     * Empty Trash: permanently deletes every trashed conversation.
     *
     * Runs in bounded batches and stops as soon as a batch makes no progress
     * (every remaining tombstone failed its provider delete), so it always
     * terminates and always reports honest counts. Failures stay in Trash and are
     * retried by the purge worker.
     */
    fun emptyTrash() {
        if (emptying) return
        emptying = true
        viewModelScope.launch {
            var purged = 0
            var failed = 0
            var rounds = 0
            try {
                while (rounds < MAX_EMPTY_ROUNDS) {
                    val outcome = trashRepository.emptyTrash(System.currentTimeMillis())
                    purged += outcome.purged
                    failed += outcome.failed
                    rounds++
                    // No progress (everything left failed) or nothing left: stop.
                    if (outcome.purged == 0 || outcome.remaining == 0) break
                }
                runCatching { TrashPurgeScheduler.scheduleNext(getApplication()) }
                notice = TrashNotice.Emptied(purged = purged, failed = failed)
            } catch (error: Throwable) {
                DiagnosticLog.event("TRASH", "empty-trash-failed", error)
                notice = TrashNotice.Emptied(purged = purged, failed = failed + 1)
            } finally {
                emptying = false
            }
        }
    }

    private companion object {
        /**
         * Hard stop for Empty Trash. Each round purges up to
         * [TrashRepository.PURGE_BATCH_LIMIT] conversations, so this covers
         * thousands of tombstones; it exists only so a pathological store can never
         * spin the UI forever.
         */
        const val MAX_EMPTY_ROUNDS = 20
    }
}
