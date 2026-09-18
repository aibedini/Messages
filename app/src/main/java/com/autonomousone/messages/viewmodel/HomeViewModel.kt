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
import com.autonomousone.messages.data.ConversationEntity
import com.autonomousone.messages.data.DailyWindowProvider
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.ReconcileRequest
import com.autonomousone.messages.data.TelephonySyncCoordinator
import com.autonomousone.messages.event.SmsEventBus
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.observer.SmsContentObserver
import com.autonomousone.messages.repository.ArchiveRepository
import com.autonomousone.messages.repository.BlocklistRepository
import com.autonomousone.messages.repository.ContactRepository
import com.autonomousone.messages.repository.ConversationCache
import com.autonomousone.messages.repository.PinRepository
import com.autonomousone.messages.repository.ProgressListener
import com.autonomousone.messages.repository.SmsRepository
import com.autonomousone.messages.repository.ThreadMessageCache
import com.autonomousone.messages.repository.ThreadSnippet
import com.autonomousone.messages.utils.DiagnosticLog
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Where the durable conversation rows last handed to the renderer came from. */
private enum class HomeConversationSource { NONE, ROOM, CACHE, PROVIDER_FALLBACK }

/**
 * PHASE 8 — ONE DURABLE STATE OWNER FOR HOME.
 *
 * After the Room bootstrap window is ready, `ConversationDao.observeAll()` is the
 * authoritative durable list. The rendered list is ALWAYS
 *
 *     RenderedConversations = RoomConversations + OptimisticOverrides
 *
 * (see [HomeConversationState]). There is exactly one writer of the durable
 * rows — [setRoomConversations] — and it is reachable only from:
 *
 *  1. [observeRoomConversations] — the authoritative Room Flow (source ROOM);
 *  2. [performLoad] — a one-shot Room projection read while the gate opens
 *     (source ROOM), or the last durable cache snapshot as an instant paint
 *     before Room is ready (source CACHE);
 *  3. [loadProviderConversations] — the explicitly GATED provider fallback,
 *     used only while Room is not authoritative (source PROVIDER_FALLBACK) and
 *     always accompanied by a typed HOME_STATE diagnostic.
 *
 * Every other realtime path (incoming, outgoing, mark-read, delete, archive,
 * pin, block) mutates the overlay or the pin/archive/blocklist stores and then
 * re-renders; none of them can replace the durable list. A provider-wide
 * conversation scan is impossible on a normal Room-ready resume.
 */
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

    /**
     * The latest durable conversation rows. This is the ONLY input to the
     * rendered list besides [overlay]; every assignment goes through
     * [setRoomConversations].
     */
    private var roomConversationsState: List<Sms> = emptyList()

    /** Optimistic layer merged on top of [roomConversationsState]. */
    private val overlay = HomeConversationState()

    /** Diagnostic breadcrumb (never rendered, never contains PII). */
    private var lastConversationSource = HomeConversationSource.NONE

    private val coordinator
        get() = TelephonySyncCoordinator.get(getApplication())

    /** True while a global (all-messages) search is running. */
    var isGlobalSearchBusy by mutableStateOf(false)
        private set

    /** Global search results across every stored message body. */
    data class GlobalHit(val sms: Sms, val matchCount: Int)

    var globalResults by mutableStateOf<List<GlobalHit>>(emptyList())
        private set

    /**
     * Outgoing SMS SEGMENTS SUBMITTED since local midnight — the Home top-bar
     * chip. It counts the immutable submission ledger (a 3-part send = 3), not
     * delivery callbacks, so a late or failed callback can never make the
     * number jump backwards. Live via a Room Flow over the current local
     * calendar day.
     */
    var sentSegmentsToday by mutableStateOf(0)
        private set

    /** Owns the day-window observation; cancelled and rebuilt on every refresh. */
    private var sentSegmentsJob: Job? = null

    /** Local calendar-day boundaries for the counter (Clock + ZoneId, DST-safe). */
    private val dailyWindow = DailyWindowProvider()

    /**
     * V2: ContentObserver callback routes through ChangeRouter for O(1)
     * targeted mutations instead of triggering a full provider scan.
     */
    private val observer = SmsContentObserver { batch ->
        // PHASE 12: invalidate ONLY the threads this burst actually touched.
        //
        // This used to be a global epoch bump on every provider event, which
        // neutralised per-thread revisions completely: one incoming SMS for
        // conversation A threw away the cached message pages of B, C and D, so
        // opening any of them paid a full re-read again.
        //
        // A burst with NO identifiable thread is the one case that is genuinely
        // global (we cannot say what changed), so only then do we invalidate all.
        val affectedThreads = batch.threadIds
        if (affectedThreads.isEmpty()) {
            ThreadMessageCache.invalidateAll()
        } else {
            affectedThreads.forEach { ThreadMessageCache.invalidateThread(it) }
        }
        // The accumulated burst is routed to the narrowest repair it justifies
        // (exact row -> thread -> bounded tail). An ordinary incoming SMS is an
        // O(1) exact mutation and can no longer escalate to a full reconcile.
        ChangeRouter.route(getApplication(), batch)
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

    /**
     * Set once Room cannot be opened (e.g. a failed migration). The read-model
     * is a SHADOW — the Telephony provider remains the source of truth — so a
     * broken shadow must downgrade to the provider path, never kill the app.
     */
    @Volatile
    private var roomUnavailable = false

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
        observeSentSegmentsToday()
    }

    /**
     * Room-backed today-segment count over the immutable submission ledger.
     *
     * The window is a real local calendar day — [today, tomorrow) in the
     * current system zone via [DailyWindowProvider] — never "start + 24h", which
     * is wrong on DST days and after a timezone change.
     *
     * It is rebuilt on process start, on ON_RESUME and on
     * DATE/TIME/TIMEZONE change, so a Home left open across midnight moves to
     * the new day's query with no Activity recreation.
     *
     * withTimeoutOrNull is only a wakeup optimisation: every restart re-derives
     * the window from Clock + ZoneId, so a missed timer (Doze, manual clock
     * change) can never pin yesterday's number.
     */
    private fun observeSentSegmentsToday() {
        sentSegmentsJob?.cancel()
        sentSegmentsJob = viewModelScope.launch {
            val dao = MessagesDatabase.get(getApplication()).sendSegmentDao()
            while (isActive) {
                val window = dailyWindow.currentWindow()
                val awakeMs = (window.endMillis - System.currentTimeMillis()).coerceAtLeast(1_000L)
                withTimeoutOrNull(awakeMs) {
                    dao.observeSubmittedBetween(window.startMillis, window.endMillis)
                        .collect { count -> sentSegmentsToday = count }
                }
                // Window ended (or the timer was missed): rebuild it from the
                // clock and re-observe. The displayed value is never reset to 0
                // in between — the new window's count replaces it directly.
            }
        }
    }

    /**
     * Rebuilds the day window and re-subscribes the counter query.
     * Idempotent; called at start, on ON_RESUME and on every system
     * date/time/timezone change.
     */
    fun onDayWindowMaybeChanged() {
        observeSentSegmentsToday()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Durable-row ownership (Phase 8)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The single writer of the durable rendered rows. [rows] is either the
     * authoritative Room projection or — only while Room is not authoritative —
     * an explicitly gated fallback list. The optimistic [overlay] is always
     * merged on top, never replaced.
     */
    private fun setRoomConversations(rows: List<Sms>, source: HomeConversationSource) {
        roomConversationsState = rows
        lastConversationSource = source
        overlay.onRoomConversations(rows)
        renderConversations()
    }

    /** The single merge/render point: Room rows + overrides → the two tabs. */
    private fun renderConversations() {
        val blocked = blocklistRepository.getBlocked()
        val rendered = overlay.render(
            filterBlocked(roomConversationsState, blocked),
            archivedIds,
            pinnedIds
        )
        // The blocklist is applied AFTER the merge so an optimistic override can
        // never re-introduce a blocked thread.
        applySwap(conversations, filterBlocked(rendered.main, blocked))
        applySwap(archivedConversations, filterBlocked(rendered.archived, blocked))
    }

    /** Applies the local blocklist without ever mutating the durable rows. */
    private fun filterBlocked(rows: List<Sms>, blocked: Set<String>): List<Sms> {
        if (blocked.isEmpty()) return rows
        return rows.filter { !isBlockedAddress(it.sender, blocked) }
    }

    /**
     * Opens the read-cutover latch if both sources have finished their initial
     * window. Until then Room is not authoritative.
     */
    private suspend fun ensureRoomGate() {
        if (roomReadEnabled || roomUnavailable) return
        val ready = runCatching { coordinator.isShadowReady() }.getOrDefault(false)
        if (ready) {
            roomReadEnabled = true
            DiagnosticLog.event("HOME_STATE", "room-cutover-open source=room")
        }
    }

    /**
     * Bounded background catch-up through the EXISTING coordinator API.
     *
     * ReconcileRequest.TailDelta reads only provider rows newer than the durable
     * per-source watermark; it never rebuilds the global projection and never
     * re-reads the newest window. This is the ONLY provider access a normal
     * Room-ready resume performs.
     */
    private fun scheduleTailDeltaCatchUp() {
        runCatching {
            coordinator.reconcile(ReconcileRequest.TailDelta)
        }.onFailure { error ->
            Log.w("SMS_DEBUG", "Tail-delta catch-up could not be scheduled", error)
        }
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
        ensureRoomGate()

        if (!hasLoadedOnce) {
            // Instant paint from the last durable snapshot. This is the local
            // cache, NOT a provider scan.
            val cached = withContext(Dispatchers.IO) {
                ConversationCache.get(getApplication()).load()
            }
            if (cached.threads.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    setRoomConversations(cached.threads, HomeConversationSource.CACHE)
                    hasLoadedOnce = true
                }
            }
        }

        if (roomReadEnabled) {
            // Room owns the durable list. Paint its current projection once so
            // the first frame is correct, then let the always-on observeAll()
            // collector carry every later change. No provider conversation scan
            // and no newest-message-per-thread probe on this path.
            val roomList = kotlin.runCatching { roomConversations() }.getOrNull()
            if (roomList != null) {
                withContext(Dispatchers.Main) {
                    setRoomConversations(roomList, HomeConversationSource.ROOM)
                    hasLoadedOnce = true
                }
                withContext(Dispatchers.IO) {
                    ConversationCache.get(getApplication()).save(roomList)
                }
            }
            scheduleTailDeltaCatchUp()
            return
        }

        // Gated provider fallback: Room is not the authoritative owner yet.
        loadProviderConversations(
            reason = if (roomUnavailable) "room-unavailable" else "room-not-ready"
        )
    }

    private suspend fun silentRefresh() {
        ensureRoomGate()

        if (roomReadEnabled) {
            // Normal Room-ready resume: the durable owner is the always-on
            // observeAll() collector, which is already rendering. Schedule the
            // bounded tail delta and return — ZERO provider-wide scans.
            scheduleTailDeltaCatchUp()
            withContext(Dispatchers.IO) {
                ConversationCache.get(getApplication()).save(roomConversationsState)
            }
            return
        }

        loadProviderConversations(
            reason = if (roomUnavailable) "room-unavailable" else "room-not-ready"
        )
    }

    /**
     * The ONLY path that can put provider-sourced rows into the rendered list.
     *
     * Explicitly gated on Room not being authoritative; every use emits a typed
     * HOME_STATE diagnostic so a provider list appearing on Home is observable.
     * The result is discarded if the Room gate opens while the scan is in
     * flight, so a provider list can never overwrite the durable owner.
     */
    private suspend fun loadProviderConversations(reason: String) {
        DiagnosticLog.event(
            "HOME_STATE",
            "provider-fallback reason=$reason previous=${lastConversationSource.name}"
        )

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
                    // Progressive paint during the bootstrap fallback only; a
                    // partial provider list must never touch a Room-owned list.
                    if (!roomReadEnabled && !hasLoadedOnce) {
                        viewModelScope.launch {
                            setRoomConversations(partial, HomeConversationSource.PROVIDER_FALLBACK)
                        }
                    }
                }
                val freshList = ThreadSnippet.reconcileAll(
                    rawList, repository.newestMessagePerThread(rawList.map { it.threadId })
                )
                val names = contactNames.await()
                withContext(Dispatchers.Main) { this@HomeViewModel.contactNames = names }
                freshList to archived
            }

            if (!roomReadEnabled) {
                withContext(Dispatchers.Main) {
                    archivedIds.clear()
                    archivedIds.addAll(archived)
                    setRoomConversations(freshList, HomeConversationSource.PROVIDER_FALLBACK)
                    hasLoadedOnce = true
                }
                withContext(Dispatchers.IO) {
                    ConversationCache.get(getApplication()).save(freshList)
                }
            }
        } catch (error: Exception) {
            Log.e("SMS_DEBUG", "Provider fallback load failed", error)
        } finally {
            loadingShowJob?.cancel()
            withContext(Dispatchers.Main) {
                isLoading = false
                loadStatus = null
                syncProgress = null
            }
        }
    }

    private suspend fun roomConversations(): List<Sms> =
        withContext(Dispatchers.IO) {
            MessagesDatabase.get(getApplication())
                .conversationDao()
                .all()
                .map { it.toHomeSms() }
        }

    /**
     * One projection mapping used by BOTH the Room Flow and the one-shot read,
     * so the authoritative list and the initial paint can never disagree. The
     * projection carries the newest message's type — no O(N) probe back into
     * messages.
     */
    private fun ConversationEntity.toHomeSms(): Sms = Sms(
        id = threadId,
        threadId = threadId,
        sender = rawAddress.ifBlank { normalizedAddress },
        message = snippet,
        date = lastMessageDate,
        unread = unreadCount > 0,
        type = lastMessageType
    )

    private fun applySwap(target: MutableList<Sms>, source: List<Sms>) {
        if (target == source) return
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            target.clear()
            target.addAll(source)
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
        var changed = false
        if (threadId != 0L) {
            overlay.recordMarkRead(threadId)
            changed = true
        }
        if (phone.isNotBlank()) {
            val matches = LinkedHashSet<Long>()
            roomConversationsState.forEach {
                if (ContactRepository.sameConversation(it.sender, phone)) matches.add(it.threadId)
            }
            overlay.overrideRows().forEach {
                if (ContactRepository.sameConversation(it.sender, phone)) matches.add(it.threadId)
            }
            matches.forEach {
                overlay.recordMarkRead(it)
                changed = true
            }
        }
        if (changed) renderConversations()
    }

    fun markAllAsRead() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.markAllAsRead()
            kotlin.runCatching {
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
        // Immediate removal is an OVERLAY, not a list mutation: the durable row
        // stays until the actual delete commits, and Room remains the truth.
        overlay.recordRemoval(sms.threadId)
        renderConversations()

        pendingDeletes[sms.threadId]?.cancel()

        val job = viewModelScope.launch(Dispatchers.IO) {
            delay(delayMs)
            var durableDeleteCommitted = false
            try {
                repository.deleteThread(threadId = sms.threadId, phone = sms.sender)
                durableDeleteCommitted = kotlin.runCatching {
                    coordinator.deleteThreadFromShadow(sms.threadId)
                }.isSuccess
            } finally {
                synchronized(pendingDeletes) { pendingDeletes.remove(sms.threadId) }
                if (!durableDeleteCommitted) {
                    // The delete did not reach the durable projection: stop
                    // hiding the row so Room stays the authoritative owner
                    // instead of an overlay living forever.
                    withContext(Dispatchers.Main) {
                        overlay.clearOverride(sms.threadId)
                        renderConversations()
                    }
                }
                // On success the removal overlay is retired by the Room emission
                // the shadow delete invalidates: reconcile sees the thread gone.
            }
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
        overlay.clearRemoval(sms.threadId)
        renderConversations()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Archive / Unarchive
    // ─────────────────────────────────────────────────────────────────────────

    fun archiveConversation(sms: Sms) {
        viewModelScope.launch(Dispatchers.IO) {
            archiveRepository.archiveThread(sms.threadId)
            withContext(Dispatchers.Main) {
                archivedIds.add(sms.threadId)
                renderConversations()
            }
        }
    }

    fun unarchiveConversation(sms: Sms) {
        viewModelScope.launch(Dispatchers.IO) {
            archiveRepository.unarchiveThread(sms.threadId)
            withContext(Dispatchers.Main) {
                archivedIds.remove(sms.threadId)
                renderConversations()
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
        renderConversations()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Block / Unblock
    // ─────────────────────────────────────────────────────────────────────────

    fun blockConversation(sms: Sms) {
        viewModelScope.launch(Dispatchers.IO) {
            blocklistRepository.block(sms.sender)
            withContext(Dispatchers.Main) { renderConversations() }
        }
    }

    fun unblockNumber(address: String) {
        viewModelScope.launch(Dispatchers.IO) {
            blocklistRepository.unblock(address)
            // The durable row was never removed, only filtered, so re-rendering
            // is enough to bring it back.
            withContext(Dispatchers.Main) { renderConversations() }
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
                val db = MessagesDatabase.get(getApplication())
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
     * V2: The incoming SMS was already persisted to Room via mutate(Upsert) in
     * IncomingMessageDispatcher. Phase 8: it is an OPTIMISTIC OVERLAY row — it
     * appears immediately, and Room remains the durable owner. The override is
     * retired automatically the moment the Room emission contains the thread
     * (observeRoomConversations), so it can never shadow a newer durable row.
     */
    private fun observeIncomingSms() {
        viewModelScope.launch {
            SmsEventBus.incomingSmsFlow.collect { incomingSms ->
                val threadId = incomingSms.threadId.takeIf { it > 0L }
                    ?: resolveThreadIdByPhone(incomingSms.sender)
                if (threadId <= 0L) return@collect
                overlay.recordIncoming(incomingSms.copy(threadId = threadId))
                renderConversations()
            }
        }
    }

    /**
     * Room Flow → Home list. Once the read-cutover gate is open this is the
     * ONLY durable writer: an exact mutation commits → Flow re-emits → Home
     * repaints via the overlay merge — no provider scan anywhere on the
     * realtime path.
     *
     * The gate can also open while Home is already on screen: every emission
     * re-checks readiness until it does, so Room takes over without waiting for
     * the next resume.
     *
     * Fail-safe: if the shadow DB can't be opened (bad migration, corruption),
     * we log, disable the cutover and fall back to the provider path. The app
     * must keep working — the shadow is not the source of truth.
     */
    private fun observeRoomConversations() {
        viewModelScope.launch {
            try {
                val db = MessagesDatabase.get(getApplication())
                db.conversationDao()
                    .observeAll()
                    .collect { rows ->
                        if (!roomReadEnabled && !roomUnavailable) {
                            roomReadEnabled = runCatching {
                                coordinator.isShadowReady()
                            }.getOrDefault(false)
                        }
                        if (!roomReadEnabled) return@collect
                        val converted = rows.map { it.toHomeSms() }
                        withContext(Dispatchers.Main) {
                            if (!roomReadEnabled) return@withContext
                            setRoomConversations(converted, HomeConversationSource.ROOM)
                            hasLoadedOnce = true
                        }
                    }
            } catch (e: Exception) {
                Log.e("HOME_DB", "Room startup failed — falling back to provider path", e)
                DiagnosticLog.event(
                    "HOME_STATE",
                    "room-observer-failed; provider fallback",
                    e
                )
                roomReadEnabled = false
                roomUnavailable = true
                withContext(Dispatchers.Main) { loadSms() }
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

    /**
     * Outgoing message: an optimistic overlay row. The sender emits threadId 0
     * for the plain SMS path (resolved here by phone match); if the thread is
     * still unknown the exact Room mutation materializes it and this event is
     * intentionally ignored.
     */
    private fun observeOutgoingSent() {
        viewModelScope.launch {
            SmsEventBus.outgoingSentFlow.collect { sent ->
                val normSent = ContactRepository.normalizePhone(sent.phone)
                if (normSent.isBlank()) return@collect

                val threadId = sent.threadId.takeIf { it > 0L }
                    ?: resolveThreadIdByPhone(sent.phone)
                if (threadId <= 0L) return@collect

                val existing = existingRow(threadId)
                val row = existing?.copy(
                    message = sent.message,
                    date = sent.date,
                    type = 2,
                    unread = false
                ) ?: Sms(
                    id = sent.date,
                    threadId = threadId,
                    sender = sent.phone,
                    message = sent.message,
                    date = sent.date,
                    unread = false,
                    type = 2
                )
                overlay.recordOutgoingPending(row)
                renderConversations()
            }
        }
    }

    /** The rendered row for a thread, preferring the durable Room projection. */
    private fun existingRow(threadId: Long): Sms? =
        roomConversationsState.firstOrNull { it.threadId == threadId }
            ?: overlay.overrideRows().firstOrNull { it.threadId == threadId }

    /** Resolves a thread id by phone across the durable rows and the overlay. */
    private fun resolveThreadIdByPhone(phone: String): Long {
        if (phone.isBlank()) return 0L
        roomConversationsState.firstOrNull {
            ContactRepository.sameConversation(it.sender, phone)
        }?.let { return it.threadId }
        return overlay.overrideRows().firstOrNull {
            ContactRepository.sameConversation(it.sender, phone)
        }?.threadId ?: 0L
    }

    override fun onCleared() {
        pendingDeletes.values.forEach { it.cancel() }
        reloadRequests.close()
        repository.unregisterObserver(observer)
        super.onCleared()
    }
}
