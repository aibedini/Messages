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
import com.autonomousone.messages.data.ChangeRouter
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

    /** Cap on search result threads — bounded output for 360K-scale data. */
    private companion object {
        const val SEARCH_RESULT_LIMIT = 100
    }

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

    /** Global search results across every stored message body. */
    data class GlobalHit(val sms: Sms, val matchCount: Int)

    var globalResults by mutableStateOf<List<GlobalHit>>(emptyList())
        private set

    /**
     * V2: ContentObserver callback routes through ChangeRouter for O(1)
     * targeted mutations instead of triggering a full provider scan.
     */
    private val observer = SmsContentObserver { uri ->
        ThreadMessageCache.generation++ // provider changed → cached threads stale
        // Route to targeted mutation or bounded reconcile — NOT full reload.
        ChangeRouter.route(getApplication(), uri)
    }

    /** True while the conversation list is being refreshed. */
    var isLoading by mutableStateOf(false)
        private set

    /** Real sync progress for the banner. */
    data class SyncProgress(val phase: String, val loaded: Int, val total: Int)

    var syncProgress by mutableStateOf<SyncProgress?>(null)
        private set

    /** Normalized-phone → contact display name, used by search. */
    var contactNames by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** conversation key → draft text (non-empty only). */
    val drafts: StateFlow<Map<String, String>> =
        com.autonomousone.messages.repository.DraftRepository.get(application).drafts

    private val draftRepository get() = com.autonomousone.messages.repository.DraftRepository.get(getApplication())

    /** Human-readable progress while loading. Null when idle. */
    var loadStatus by mutableStateOf<String?>(null)
        private set

    private val reloadRequests = Channel<Unit>(Channel.CONFLATED)

    /** True once the first full load has completed. */
    private var hasLoadedOnce = false

    /** Read-cutover latch. */
    @Volatile
    private var roomReadEnabled = false

    /** Newest conversation date seen in the last full load. */
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
        observeRoomConversations()
        observeRefreshSignal()
        observeThreadRead()
        observeOutgoingSent()
        observeReloadRequests()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Load
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

    private suspend fun performLoad() {
        val cache = com.autonomousone.messages.repository.ConversationCache.get(getApplication())

        val coordinator = com.autonomousone.messages.data.TelephonySyncCoordinator.get(getApplication())
        if (!roomReadEnabled) {
            roomReadEnabled = coordinator.isShadowReady()
        }
        if (roomReadEnabled && !hasLoadedOnce) {
            val roomList = kotlin.runCatching {
                coordinator.syncNow()
                roomConversations()
            }.getOrNull()
            if (roomList != null) {
                replaceConversations(roomList, archiveRepository.getArchivedIds(), atomic = false)
                newestKnownDate = roomList.maxOfOrNull { it.date } ?: 0L
                hasLoadedOnce = true
                withContext(Dispatchers.IO) { cache.save(roomList) }
            }
        }

        if (!hasLoadedOnce) {
            val cached = withContext(Dispatchers.IO) { cache.load() }
            if (cached.threads.isNotEmpty()) {
                replaceConversations(cached.threads, archiveRepository.getArchivedIds(), atomic = false)
                newestKnownDate = cached.threads.maxOfOrNull { it.date } ?: 0L
                hasLoadedOnce = true
            }
        }

        loadingShowJob?.cancel()
        loadingShowJob = viewModelScope.launch {
            delay(250)
            isLoading = true
        }
        try {
            val progressListener = ProgressListener { progress ->
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
                    if (!hasLoadedOnce) {
                        viewModelScope.launch { replaceConversations(partial, archived, atomic = false) }
                    }
                }
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

    private suspend fun silentRefresh() {
        try {
            val coordinator = com.autonomousone.messages.data.TelephonySyncCoordinator.get(getApplication())
            if (!roomReadEnabled) {
                roomReadEnabled = coordinator.isShadowReady()
            }
            if (roomReadEnabled) {
                val roomList = kotlin.runCatching {
                    coordinator.syncNow()
                    roomConversations()
                }.getOrNull()
                if (roomList != null) {
                    replaceConversations(roomList, archiveRepository.getArchivedIds(), atomic = true)
                    newestKnownDate = maxOf(newestKnownDate, roomList.maxOfOrNull { it.date } ?: 0L)
                    withContext(Dispatchers.IO) {
                        com.autonomousone.messages.repository.ConversationCache
                            .get(getApplication()).save(roomList)
                    }
                    return
                }
            }

            val (freshList, archived) = withContext(Dispatchers.IO) {
                val archived = archiveRepository.getArchivedIds()
                val list = repository.getConversationsFast(null, null)
                val reconciled = com.autonomousone.messages.repository.ThreadSnippet.reconcileAll(
                    list, repository.newestMessagePerThread(list.map { it.threadId })
                )
                reconciled to archived
            }
            replaceConversations(freshList, archived, atomic = true)
            newestKnownDate = freshList.maxOfOrNull { it.date } ?: newestKnownDate
            withContext(Dispatchers.IO) {
                com.autonomousone.messages.repository.ConversationCache
                    .get(getApplication()).save(freshList)
            }
        } catch (error: Exception) {
            Log.e("SMS_DEBUG", "Silent refresh failed; keeping cache", error)
        }
    }

    private suspend fun roomConversations(): List<Sms> =
        withContext(Dispatchers.IO) {
            com.autonomousone.messages.data.MessagesDatabase.get(getApplication())
                .conversationDao()
                .all()
                .map { c ->
                    Sms(
                        id = c.threadId,
                        threadId = c.threadId,
                        sender = c.rawAddress.ifBlank { c.normalizedAddress },
                        message = c.snippet,
                        date = c.lastMessageDate,
                        unread = c.unreadCount > 0,
                        type = 1
                    )
                }
        }

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
            applySwap(conversations, main)
            applySwap(archivedConversations, archivedOut)
        } else {
            conversations.apply { clear(); addAll(main) }
            archivedConversations.apply { clear(); addAll(archivedOut) }
        }
        archivedIds.clear()
        archivedIds.addAll(archived)
    }

    private fun applySwap(target: MutableList<Sms>, source: List<Sms>) {
        if (target == source) return
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            target.clear()
            target.addAll(source)
        }
    }

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

    private fun isBlockedAddress(sender: String, blocked: Set<String>): Boolean {
        if (blocked.isEmpty()) return false
        val norm = BlocklistRepository.normalize(sender)
        if (norm.isBlank()) return false
        return blocked.any { ContactRepository.sameConversation(norm, it) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mark read
    // ─────────────────────────────────────────────────────────────────────────

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
            kotlin.runCatching {
                val coordinator = com.autonomousone.messages.data.TelephonySyncCoordinator
                    .get(getApplication())
                (conversations + archivedConversations).toList().forEach { sms ->
                    coordinator.markThreadReadInShadow(sms.threadId)
                }
            }
            loadSms()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Delete (with undo)
    // ─────────────────────────────────────────────────────────────────────────

    fun deleteConversation(sms: Sms, delayMs: Long = 4_000L) {
        conversations.removeAll { it.threadId == sms.threadId }
        archivedConversations.removeAll { it.threadId == sms.threadId }

        pendingDeletes[sms.threadId]?.cancel()

        val job = viewModelScope.launch(Dispatchers.IO) {
            delay(delayMs)
            repository.deleteThread(threadId = sms.threadId, phone = sms.sender)
            kotlin.runCatching {
                com.autonomousone.messages.data.TelephonySyncCoordinator
                    .get(getApplication()).deleteThreadFromShadow(sms.threadId)
            }
            synchronized(pendingDeletes) { pendingDeletes.remove(sms.threadId) }
        }
        pendingDeletes[sms.threadId] = job
    }

    fun undoDelete(sms: Sms) {
        val job = synchronized(pendingDeletes) { pendingDeletes[sms.threadId] }
        if (job == null || !job.isActive) {
            loadSms()
            return
        }
        job.cancel()
        synchronized(pendingDeletes) { pendingDeletes.remove(sms.threadId) }

        val targetList = if (sms.threadId in archivedIds) archivedConversations else conversations
        insertDeduped(targetList, sms)
    }

    private fun insertDeduped(target: MutableList<Sms>, sms: Sms) {
        target.removeAll { it.id == sms.id }
        val insertIndex = target.indexOfFirst { it.date < sms.date }
        if (insertIndex >= 0) target.add(insertIndex, sms) else target.add(sms)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Archive / Unarchive
    // ─────────────────────────────────────────────────────────────────────────

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

    fun blockConversation(sms: Sms) {
        conversations.removeAll { it.threadId == sms.threadId }
        archivedConversations.removeAll { it.threadId == sms.threadId }
        viewModelScope.launch(Dispatchers.IO) {
            blocklistRepository.block(sms.sender)
        }
    }

    fun unblockNumber(address: String) {
        viewModelScope.launch(Dispatchers.IO) {
            blocklistRepository.unblock(address)
            loadSms()
        }
    }

    fun getBlockedNumbers(): Set<String> = blocklistRepository.getBlocked()

    // ─────────────────────────────────────────────────────────────────────────
    // Global search (all message bodies)
    // ─────────────────────────────────────────────────────────────────────────

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
                // V2.6: Room FTS4 instead of loading every SMS row into memory.
                // For 360K messages the old path was O(total) per keystroke.
                val match = com.autonomousone.messages.data.FtsQuery.build(q)
                if (match.isEmpty()) {
                    withContext(Dispatchers.Main) { globalResults = emptyList() }
                    return@launch
                }
                val db = com.autonomousone.messages.data.MessagesDatabase.get(getApplication())
                val hits = db.messageFtsDao().threadHits(match, limit = SEARCH_RESULT_LIMIT)
                val blocked = blocklistRepository.getBlocked()
                val results = hits.mapNotNull { hit ->
                    val newest = db.messageDao()
                        .pageForThread(hit.threadId, limit = 1, offset = 0)
                        .firstOrNull() ?: return@mapNotNull null
                    val sms = newest.toSms()
                    if (isBlockedAddress(sms.sender, blocked)) null
                    else GlobalHit(sms, hit.matchCount)
                }
                withContext(Dispatchers.Main) { globalResults = results }
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

    /**
     * V2: The incoming SMS was already persisted to Room via mutate(Upsert)
     * in IncomingMessageDispatcher. We just need to optimistically prepend
     * for instant UI feedback, then let Room Flow handle the rest.
     *
     * V2.6: NO silentRefresh() here anymore — the exact mutation commits to
     * Room, invalidation fires the conversation Flow (observeRoomConversations)
     * and Home repaints the authoritative row. A provider scan on every
     * incoming SMS would make the O(1) mutation pointless.
     */
    private fun observeIncomingSms() {
        viewModelScope.launch {
            SmsEventBus.incomingSmsFlow.collect { incomingSms ->
                val existingIndex = conversations.indexOfFirst {
                    ContactRepository.sameConversation(it.sender, incomingSms.sender)
                }
                if (existingIndex >= 0) conversations.removeAt(existingIndex)

                if (incomingSms.threadId !in archivedIds) {
                    conversations.removeAll {
                        ContactRepository.sameConversation(it.sender, incomingSms.sender)
                    }
                    conversations.add(0, incomingSms)
                }
            }
        }
    }

    /**
     * Room Flow → Home list. Once the read-cutover gate is open the list is
     * driven by Room INVALIDATION: an exact mutation commits → Flow re-emits →
     * Home repaints — no provider scan anywhere on the realtime path.
     *
     * Pre-cutover (first launch while the shadow syncs) the collector simply
     * skips emissions; performLoad/silentRefresh keep serving the provider.
     */
    private fun observeRoomConversations() {
        viewModelScope.launch {
            val db = com.autonomousone.messages.data.MessagesDatabase.get(getApplication())
            db.conversationDao()
                .observeAll()
                .collect { rows ->
                    if (!roomReadEnabled) return@collect
                    val converted = rows.map { c ->
                        Sms(
                            id = c.threadId,
                            threadId = c.threadId,
                            sender = c.rawAddress.ifBlank { c.normalizedAddress },
                            message = c.snippet,
                            date = c.lastMessageDate,
                            unread = c.unreadCount > 0,
                            type = 1
                        )
                    }
                    withContext(Dispatchers.Main) {
                        if (!roomReadEnabled) return@withContext
                        replaceConversations(
                            converted,
                            archiveRepository.getArchivedIds(),
                            atomic = true
                        )
                    }
                }
        }
    }

    private fun observeRefreshSignal() {
        viewModelScope.launch {
            SmsEventBus.refreshFlow.collect {
                if (!hasLoadedOnce) loadSms() else silentRefresh()
            }
        }
    }

    private fun observeThreadRead() {
        viewModelScope.launch {
            SmsEventBus.threadReadFlow.collect { event ->
                markConversationReadLocally(event.threadId, event.phone)
            }
        }
    }

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
                    existing.copy(message = sent.message, date = sent.date, type = 2, unread = false)
                } else {
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
