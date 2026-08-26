package com.autonomousone.messages.viewmodel

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autonomousone.messages.event.SmsEventBus
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.observer.SmsContentObserver
import com.autonomousone.messages.repository.ArchiveRepository
import com.autonomousone.messages.repository.BlocklistRepository
import com.autonomousone.messages.repository.ContactRepository
import com.autonomousone.messages.repository.PinRepository
import com.autonomousone.messages.repository.ProgressListener
import com.autonomousone.messages.repository.SmsRepository
import com.autonomousone.messages.repository.ThreadMessageCache
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val repository = SmsRepository(application)
    private val archiveRepository = ArchiveRepository(application)
    private val pinRepository = PinRepository(application)
    private val blocklistRepository = BlocklistRepository(application)

    /** All conversations that are NOT archived — shown in "All" and "Unread" tabs. */
    val conversations = mutableStateListOf<Sms>()

    /** Conversations that have been archived — shown in the "Archived" tab. */
    val archivedConversations = mutableStateListOf<Sms>()

    /** Reactive set of archived threadIds for filtering. */
    private val archivedIds = mutableStateSetOf<Long>()

    /** Reactive set of pinned threadIds for sorting + UI badges. */
    val pinnedIds = mutableStateSetOf<Long>()

    /** True while a global (all-messages) search is running. */
    var isGlobalSearchBusy by mutableStateOf(false)
        private set

    /**
     * Global search results across every stored message body —
     * one representative row per conversation, with match count.
     */
    data class GlobalHit(val sms: Sms, val matchCount: Int)

    var globalResults by mutableStateOf<List<GlobalHit>>(emptyList())
        private set

    private val observer = SmsContentObserver {
        ThreadMessageCache.generation++ // provider changed → cached threads stale
        loadSms()
        // Shadow-sync the change into Room (single writer, conflated).
        com.autonomousone.messages.data.TelephonySyncCoordinator
            .get(getApplication()).requestSync()
    }

    /** True while the conversation list is being refreshed (drives the loading spinner). */
    var isLoading by mutableStateOf(false)
        private set

    /** Real sync progress for the banner: "Syncing messages… 120/340". Null when idle. */
    data class SyncProgress(val phase: String, val loaded: Int, val total: Int)

    var syncProgress by mutableStateOf<SyncProgress?>(null)
        private set

    /** Normalized-phone → contact display name, used by search. */
    var contactNames by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** conversation key → draft text (non-empty only). Drives "Draft:" rows. */
    val drafts: StateFlow<Map<String, String>> =
        com.autonomousone.messages.repository.DraftRepository.get(application).drafts

    private val draftRepository get() = com.autonomousone.messages.repository.DraftRepository.get(getApplication())

    /** Human-readable progress while loading (e.g. "Reading messages… 120/340"). Null when idle. */
    var loadStatus by mutableStateOf<String?>(null)
        private set

    private val reloadRequests = Channel<Unit>(Channel.CONFLATED)

    /** True once the first full load has completed — afterwards we render cache instantly. */
    private var hasLoadedOnce = false

    /**
     * Newest conversation date seen in the last full load. Used by the
     * incremental sync to only fetch threads newer than this.
     */
    @Volatile
    private var newestKnownDate: Long = 0L

    /** Holds a pending delete job per threadId so it can be cancelled on Undo. */
    private val pendingDeletes = mutableMapOf<Long, Job>()

    /** Delays the spinner so quick reloads never flash the progress bar. */
    private var loadingShowJob: Job? = null

    init {
        repository.registerObserver(observer)
        loadArchivedIds()
        observeIncomingSms()
        observeRefreshSignal()
        observeThreadRead()
        observeOutgoingSent()
        observeReloadRequests()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Load — Single Source of Truth pattern
    //
    // The in-memory lists ARE the UI's source of truth once loaded. A full
    // provider scan happens only on cold start (empty lists). Everything else
    // is either an incremental merge (observer events) or a silent atomic
    // swap (explicit refresh) — never a visible "syncing" state again.
    // ─────────────────────────────────────────────────────────────────────────

    private fun loadArchivedIds() {
        archivedIds.clear()
        archivedIds.addAll(archiveRepository.getArchivedIds())
        pinnedIds.clear()
        pinnedIds.addAll(pinRepository.getPinnedIds())
    }

    fun loadSms() {
        reloadRequests.trySend(Unit)
    }

    /** True while a pull-to-refresh / resume reconcile round-trip is in flight. */
    var isRefreshing by mutableStateOf(false)
        private set

    /**
     * Explicit user-driven refresh (pull-to-refresh). Same silent atomic-swap
     * path as resume — the visible list is never cleared.
     */
    fun refreshNow() {
        if (isRefreshing) return
        viewModelScope.launch {
            isRefreshing = true
            try {
                if (hasLoadedOnce) silentRefresh() else performLoad()
            } finally {
                isRefreshing = false
            }
        }
    }

    private fun observeReloadRequests() {
        viewModelScope.launch {
            for (ignored in reloadRequests) {
                if (hasLoadedOnce) silentRefresh() else performLoad()
            }
        }
    }

    /**
     * Cold start / permission-granted path: full provider scan with progress.
     * Only this path may show the skeleton/spinner — and only while the lists
     * are still empty (no cache to render yet).
     */
    private suspend fun performLoad() {
        val cache = com.autonomousone.messages.repository.ConversationCache.get(getApplication())

        // ── Instant hydration from the persistent cache (Google Messages-style):
        // paint the last known list immediately, no skeleton, no "syncing".
        if (!hasLoadedOnce) {
            val cached = withContext(Dispatchers.IO) { cache.load() }
            if (cached.threads.isNotEmpty()) {
                replaceConversations(cached.threads, archiveRepository.getArchivedIds(), atomic = false)
                newestKnownDate = cached.threads.maxOfOrNull { it.date } ?: 0L
                hasLoadedOnce = true
            }
        }

        // Skeleton only when there is truly nothing to render (first-ever run).
        if (!hasLoadedOnce) {
            loadingShowJob?.cancel()
            loadingShowJob = viewModelScope.launch {
                delay(250)
                isLoading = true
            }
        }
        try {
            val progressListener = if (hasLoadedOnce) null else ProgressListener { progress ->
                val label = when (progress.phase) {
                    "threads" -> "Loading conversations"
                    "sms" -> "Syncing messages"
                    "mms" -> "Syncing multimedia"
                    else -> "Syncing"
                }
                viewModelScope.launch {
                    loadStatus = if (progress.total > 0) {
                        "$label… ${progress.loaded}/${progress.total}"
                    } else {
                        "$label…"
                    }
                    syncProgress = SyncProgress(progress.phase, progress.loaded, progress.total)
                }
            }

            val (freshList, archived) = withContext(Dispatchers.IO) {
                val archived = archiveRepository.getArchivedIds()
                val contactNames = async {
                    ContactRepository(getApplication()).getContactNameMapAsync()
                }
                val rawList = repository.getConversationsFast(progressListener) { partial ->
                    // Progressive paint during cold start ONLY: build on top of
                    // what's shown instead of clearing mid-load.
                    if (!hasLoadedOnce) {
                        viewModelScope.launch { replaceConversations(partial, archived, atomic = false) }
                    }
                }
                // Same Threads-table reconciliation as silentRefresh: a stale
                // snippet must never survive into the first painted list.
                val freshList = com.autonomousone.messages.repository.ThreadSnippet.reconcileAll(
                    rawList, repository.newestMessagePerThread(rawList.map { it.threadId })
                )
                val names = contactNames.await()
                withContext(Dispatchers.Main) { this@HomeViewModel.contactNames = names }
                freshList to archived
            }

            replaceConversations(freshList, archived, atomic = true)
            newestKnownDate = freshList.maxOfOrNull { it.date } ?: 0L
            hasLoadedOnce = true
            // Persist for the next cold start (write-behind, off the UI path).
            withContext(Dispatchers.IO) { cache.save(freshList) }
        } catch (error: Exception) {
            Log.e("SMS_DEBUG", "Unable to refresh conversations", error)
        } finally {
            loadingShowJob?.cancel()
            isLoading = false
            loadStatus = null
            syncProgress = null
        }
    }

    /**
     * Warm path: rebuild off-screen and atomically swap — the visible list is
     * never cleared, so returning from a conversation shows zero sync UI.
     */
    private suspend fun silentRefresh() {
        try {
            val (freshList, archived) = withContext(Dispatchers.IO) {
                val archived = archiveRepository.getArchivedIds()
                val list = repository.getConversationsFast(null, null)
                // Reconcile each thread row against the newest message actually
                // in the SMS table. The Threads table can lag (or never update)
                // for rows the provider considers orphaned, which is what made
                // the list disagree with the open conversation.
                val reconciled = com.autonomousone.messages.repository.ThreadSnippet.reconcileAll(
                    list, repository.newestMessagePerThread(list.map { it.threadId })
                )
                reconciled to archived
            }
            replaceConversations(freshList, archived, atomic = true)
            newestKnownDate = freshList.maxOfOrNull { it.date } ?: newestKnownDate
            // Keep the on-disk snapshot in step, so the next cold start does not
            // hydrate a list that is older than what the user just saw.
            withContext(Dispatchers.IO) {
                com.autonomousone.messages.repository.ConversationCache
                    .get(getApplication()).save(freshList)
            }
        } catch (error: Exception) {
            Log.e("SMS_DEBUG", "Silent refresh failed; keeping cache", error)
        }
    }

    /**
     * Rebuilds the visible lists from [items].
     *
     * @param atomic when true, new lists are built first and swapped in one
     * go — no intermediate empty state. When false (cold-start progressive
     * paint), the lists are replaced directly since there's nothing to protect.
     */
    private fun replaceConversations(items: List<Sms>, archived: Set<Long>, atomic: Boolean = true) {
        val excluded = pendingDeletes.keys
        val blocked = blocklistRepository.getBlocked()

        val main = mutableListOf<Sms>()
        val archivedOut = mutableListOf<Sms>()
        items.forEach { sms ->
            if (sms.threadId in excluded) return@forEach
            if (isBlockedAddress(sms.sender, blocked)) return@forEach
            if (sms.threadId in archived) archivedOut.add(sms) else main.add(sms)
        }
        sortByPin(main, pinnedIds)
        sortByPin(archivedOut, pinnedIds)

        if (atomic) {
            // Single recomposition: swap contents in place to keep the same
            // SnapshotStateList instances (Compose keys stay stable).
            applySwap(conversations, main)
            applySwap(archivedConversations, archivedOut)
        } else {
            conversations.apply { clear(); addAll(main) }
            archivedConversations.apply { clear(); addAll(archivedOut) }
        }
        archivedIds.clear()
        archivedIds.addAll(archived)
    }

    /** In-place swap made ATOMIC via a snapshot transaction: Compose sees the
     *  before→after state once, so keyed items move smoothly instead of the
     *  whole list flashing through clear+add in separate frames. */
    private fun applySwap(target: MutableList<Sms>, source: List<Sms>) {
        if (target == source) return
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            target.clear()
            target.addAll(source)
        }
    }

    /** Sorts pinned threads above everything else, date-desc within groups. */
    private fun sortByPin(list: MutableList<Sms>, pins: Set<Long>) {
        list.sortWith { a, b ->
            val pa = a.threadId in pins
            val pb = b.threadId in pins
            when {
                pa != pb -> if (pa) -1 else 1
                else -> b.date.compareTo(a.date)
            }
        }
    }

    /** A conversation is blocked when its normalized address matches the block list. */
    private fun isBlockedAddress(sender: String, blocked: Set<String>): Boolean {
        if (blocked.isEmpty()) return false
        val norm = BlocklistRepository.normalize(sender)
        if (norm.isBlank()) return false
        return blocked.any { ContactRepository.sameConversation(norm, it) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mark read
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Marks one conversation read in the in-memory list immediately.
     * Called by ConversationViewModel after it persists READ=1 to the provider,
     * so the Home list reflects the change instantly (no stale unread badge).
     */
    fun markConversationReadLocally(threadId: Long, phone: String) {
        fun matches(sms: Sms): Boolean =
            (threadId != 0L && sms.threadId == threadId) ||
                ContactRepository.sameConversation(sms.sender, phone)
        conversations.replaceAll { if (matches(it)) it.copy(unread = false) else it }
        archivedConversations.replaceAll { if (matches(it)) it.copy(unread = false) else it }
    }

    fun markAllAsRead() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.markAllAsRead()
            loadSms()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Delete (with undo)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Optimistically removes [sms] from the visible list immediately.
     * Schedules a permanent ContentProvider delete after [delayMs] (default 4 s).
     * The returned job can be cancelled before the delay elapses to undo.
     */
    fun deleteConversation(sms: Sms, delayMs: Long = 4_000L) {
        // Remove EVERY matching row by threadId — data-class equals is unsafe
        // because reloads produce fresh instances with updated fields.
        conversations.removeAll { it.threadId == sms.threadId }
        archivedConversations.removeAll { it.threadId == sms.threadId }

        pendingDeletes[sms.threadId]?.cancel()

        val job = viewModelScope.launch(Dispatchers.IO) {
            delay(delayMs)
            repository.deleteThread(threadId = sms.threadId, phone = sms.sender)
            synchronized(pendingDeletes) { pendingDeletes.remove(sms.threadId) }
        }
        pendingDeletes[sms.threadId] = job
    }

    /**
     * Cancels a pending delete for [sms] and re-inserts it into the correct list
     * at its sorted position (most-recent first by date).
     *
     * Safe against the two historical crash paths:
     *  - the row was re-added by an observer reload in the meantime → deduped;
     *  - the permanent delete already committed → no ghost row, just resync.
     */
    fun undoDelete(sms: Sms) {
        val job = synchronized(pendingDeletes) { pendingDeletes[sms.threadId] }
        if (job == null || !job.isActive) {
            // Deletion already committed (or raced past the window) — resync
            // from the provider instead of resurrecting a ghost conversation.
            loadSms()
            return
        }
        job.cancel()
        synchronized(pendingDeletes) { pendingDeletes.remove(sms.threadId) }

        val targetList = if (sms.threadId in archivedIds) archivedConversations else conversations
        insertDeduped(targetList, sms)
    }

    /** Removes any row with the same id first, then inserts date-sorted. */
    private fun insertDeduped(target: MutableList<Sms>, sms: Sms) {
        target.removeAll { it.id == sms.id }
        val insertIndex = target.indexOfFirst { it.date < sms.date }
        if (insertIndex >= 0) target.add(insertIndex, sms) else target.add(sms)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Archive / Unarchive
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Moves [sms] from the active conversations list to the archived list.
     * Persists the archive state to SharedPreferences. Reversible via
     * [unarchiveConversation] (wired to the snackbar's Undo action).
     */
    fun archiveConversation(sms: Sms) {
        viewModelScope.launch(Dispatchers.IO) {
            archiveRepository.archiveThread(sms.threadId)
            withContext(Dispatchers.Main) {
                archivedIds.add(sms.threadId)
                conversations.removeAll { it.threadId == sms.threadId }
                insertDeduped(archivedConversations, sms)
            }
        }
    }

    /**
     * Moves [sms] from the archived list back to active conversations.
     */
    fun unarchiveConversation(sms: Sms) {
        viewModelScope.launch(Dispatchers.IO) {
            archiveRepository.unarchiveThread(sms.threadId)
            withContext(Dispatchers.Main) {
                archivedIds.remove(sms.threadId)
                archivedConversations.removeAll { it.threadId == sms.threadId }
                insertDeduped(conversations, sms)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pin / Unpin
    // ─────────────────────────────────────────────────────────────────────────

    fun togglePin(sms: Sms) {
        val isPinned = sms.threadId in pinnedIds
        if (isPinned) pinRepository.unpinThread(sms.threadId) else pinRepository.pinThread(sms.threadId)
        if (isPinned) pinnedIds.remove(sms.threadId) else pinnedIds.add(sms.threadId)
        sortByPin(conversations, pinnedIds)
        sortByPin(archivedConversations, pinnedIds)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Block / Unblock
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Blocks the conversation's sender and hides it from every list immediately.
     * The underlying messages stay in the provider — unblocking restores them.
     */
    fun blockConversation(sms: Sms) {
        conversations.removeAll { it.threadId == sms.threadId }
        archivedConversations.removeAll { it.threadId == sms.threadId }
        viewModelScope.launch(Dispatchers.IO) {
            blocklistRepository.block(sms.sender)
        }
    }

    /** Removes [address] from the block list and reloads from the provider. */
    fun unblockNumber(address: String) {
        viewModelScope.launch(Dispatchers.IO) {
            blocklistRepository.unblock(address)
            loadSms()
        }
    }

    /** All blocked numbers, for Settings → Blocked numbers. */
    fun getBlockedNumbers(): Set<String> = blocklistRepository.getBlocked()

    // ─────────────────────────────────────────────────────────────────────────
    // Global search (all message bodies)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Searches every stored SMS body (not just conversation snippets).
     * Emits one [GlobalHit] per conversation with a match count.
     */
    fun searchAllMessages(query: String) {
        val q = query.trim()
        if (q.length < 2) {
            globalResults = emptyList()
            return
        }
        if (isGlobalSearchBusy) return
        isGlobalSearchBusy = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val all = repository.getSmsWithFilters(
                    limit = null, offset = null, type = null,
                    phone = null, fromDate = null, toDate = null
                )
                val blocked = blocklistRepository.getBlocked()
                val byThread = all.filter { !isBlockedAddress(it.sender, blocked) }
                    .groupBy { if (it.threadId != 0L) it.threadId else it.id }
                val hits = byThread.mapNotNull { (_, messages) ->
                    val count = messages.count { it.message.contains(q, ignoreCase = true) }
                    if (count == 0) null else GlobalHit(messages.maxBy { it.date }, count)
                }.sortedByDescending { it.sms.date }
                withContext(Dispatchers.Main) { globalResults = hits }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { globalResults = emptyList() }
            } finally {
                withContext(Dispatchers.Main) { isGlobalSearchBusy = false }
            }
        }
    }

    fun clearGlobalSearch() {
        globalResults = emptyList()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Real-time incoming SMS
    // ─────────────────────────────────────────────────────────────────────────

    private fun observeIncomingSms() {
        viewModelScope.launch {
            SmsEventBus.incomingSmsFlow.collect { incomingSms ->
                // Remove from whichever list it currently appears in
                val existingIndex = conversations.indexOfFirst {
                    ContactRepository.sameConversation(it.sender, incomingSms.sender)
                }
                if (existingIndex >= 0) conversations.removeAt(existingIndex)

                // Only add to main list if not archived
                if (incomingSms.threadId !in archivedIds) {
                    conversations.removeAll {
                        ContactRepository.sameConversation(it.sender, incomingSms.sender)
                    }
                    conversations.add(0, incomingSms)
                }
                // Reconcile with the provider right away (conflated + debounced),
                // so the freshly prepended row is confirmed/updated ASAP.
                loadSms()
            }
        }
    }

    private fun observeRefreshSignal() {
        viewModelScope.launch {
            SmsEventBus.refreshFlow.collect {
                // Always reconcile on resume: read-state/snippet changes don't
                // create newer rows, so hasProviderChangedSince can't see them.
                // silentRefresh is cheap (single threads-table query + atomic
                // swap) and the UI keeps showing cached data meanwhile.
                if (!hasLoadedOnce) loadSms() else silentRefresh()
            }
        }
    }

    /** Instant unread-badge drop when a chat screen marks its thread read. */
    private fun observeThreadRead() {
        viewModelScope.launch {
            SmsEventBus.threadReadFlow.collect { event ->
                markConversationReadLocally(event.threadId, event.phone)
            }
        }
    }

    /**
     * Instant list update when an outgoing SMS is persisted (chat screen is
     * still open): move that conversation to the top with the new snippet and
     * date — no waiting for the ContentObserver debounce or a back-press.
     */
    private fun observeOutgoingSent() {
        viewModelScope.launch {
            SmsEventBus.outgoingSentFlow.collect { sent ->
                val normSent = ContactRepository.normalizePhone(sent.phone)
                if (normSent.isBlank()) return@collect

                val idx = conversations.indexOfFirst {
                    ContactRepository.sameConversation(it.sender, sent.phone)
                }

                val row = if (idx >= 0) {
                    val existing = conversations.removeAt(idx)
                    // Update snippet/date; keep the row's own id/threadId.
                    existing.copy(message = sent.message, date = sent.date, type = 2, unread = false)
                } else {
                    // Brand-new conversation created by this send.
                    Sms(
                        id = sent.date,
                        threadId = 0L,
                        sender = sent.phone,
                        message = sent.message,
                        date = sent.date,
                        unread = false,
                        type = 2
                    )
                }

                if (row.threadId !in archivedIds) {
                    conversations.add(0, row)
                }
            }
        }
    }

    override fun onCleared() {
        pendingDeletes.values.forEach { it.cancel() }
        reloadRequests.close()
        repository.unregisterObserver(observer)
        super.onCleared()
    }
}
