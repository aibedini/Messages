package com.autonomousone.messages.viewmodel

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autonomousone.messages.data.ChangeRouter
import com.autonomousone.messages.data.ConversationEntity
import com.autonomousone.messages.data.DailyWindowProvider
import com.autonomousone.messages.data.MessageCategory
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.ReconcileRequest
import com.autonomousone.messages.data.TelephonySyncCoordinator
import com.autonomousone.messages.event.SmsEventBus
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.observer.SmsContentObserver
import com.autonomousone.messages.repository.ArchiveRepository
import com.autonomousone.messages.repository.BlocklistRepository
import com.autonomousone.messages.repository.BulkActionRepository
import com.autonomousone.messages.repository.BulkFeedback
import com.autonomousone.messages.repository.BulkResult
import com.autonomousone.messages.repository.ContactRepository
import com.autonomousone.messages.repository.ConversationCache
import com.autonomousone.messages.repository.ConversationPreferenceRepository
import com.autonomousone.messages.repository.PinRepository
import com.autonomousone.messages.repository.ProgressListener
import com.autonomousone.messages.repository.SmsRepository
import com.autonomousone.messages.repository.ThreadMessageCache
import com.autonomousone.messages.repository.ThreadSnippet
import com.autonomousone.messages.ui.home.CategoryFilter
import com.autonomousone.messages.repository.ConversationCategoryResolver
import com.autonomousone.messages.ui.selection.SelectionState
import com.autonomousone.messages.utils.DiagnosticLog
import com.autonomousone.messages.diagnostics.PerfTelemetry
import com.autonomousone.messages.diagnostics.TraceSections
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
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

    /** FEATURE 10: the single batched entry point for every bulk action. */
    private val bulkActions = BulkActionRepository.get(application)

    /** FEATURE 9/10: the `manualUnread` bookmark store (never Telephony READ). */
    private val preferenceRepository = ConversationPreferenceRepository(application)

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

    /**
     * Normalized-phone → contact display name.
     *
     * KEYED BY EVERY SPELLING of a number (see [PhoneIdentity.lookupKeys]), so a
     * conversation addressed as `0912…` finds a contact stored as `+98912…`.
     */
    var contactNames by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /**
     * THE display-name resolver for every Home surface: rows, search hits, the
     * navigation snapshot and confirmation dialogs. Uses the alias policy above and
     * falls back to the address so an unknown sender still renders.
     */
    fun displayNameFor(address: String): String =
        ContactRepository.displayNameFrom(contactNames, address)

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

    /**
     * TRASH (FEATURE 8): the durable tombstone store. Lazy because constructing it
     * opens Room (`MessagesDatabase.get`), and this ViewModel is built on the main
     * thread — every use below is already inside an IO coroutine.
     */
    private val trashRepository by lazy {
        com.autonomousone.messages.repository.TrashRepository.get(getApplication())
    }

    /**
     * Reloads the directory after the Contacts provider actually changed.
     *
     * The cached directory is invalidated FIRST: [ContactRepository.clearCache] also
     * clears the single-recipient participant cache, so the conversation header and
     * Home agree again after a rename instead of the header keeping a stale name.
     */
    private fun onContactsDirectoryChanged() {
        ContactRepository.clearCache()
        refreshContactNames()
    }

    /**
     * Registers the Contacts observer while Home is on screen. Called on resume and
     * released in [onCleared], so a backgrounded app does not hold a provider
     * registration it cannot use.
     */
    private fun observeContactsChanges() {
        contactsObserver.register()
    }

    /** Delays the spinner so quick reloads never flash the progress bar. */
    private var loadingShowJob: Job? = null

    /** In-flight contact-directory load, so a burst of triggers issues ONE query. */
    private var contactLoadJob: Job? = null

    /** Contacts-provider watcher; debounces a burst of change notifications. */
    private val contactsObserver by lazy {
        com.autonomousone.messages.observer.ContactsChangeObserver(
            context = getApplication(),
            onContactsChanged = { onContactsDirectoryChanged() }
        )
    }

    init {
        repository.registerObserver(observer)
        loadArchivedIds()
        // CONTACTS ARE LOADED HERE, NOT INSIDE THE PROVIDER FALLBACK.
        //
        // `loadProviderConversations()` was the only caller of the contact map, and
        // the Room read-SSOT path returns before reaching it — so on a normal launch
        // `contactNames` stayed empty and Home rendered raw numbers while the chat
        // header showed the name. Contact names are UI metadata; where the message
        // rows came from is irrelevant to them.
        refreshContactNames()
        observeIncomingSms()
        observeRoomConversations()
        observeRefreshSignal()
        observeThreadRead()
        observeOutgoingSent()
        observeReloadRequests()
        observeSentSegmentsToday()
        observeSmartCategories()
        observeConversationUserState()
        observeTrashState()
    }

    /**
     * Loads the contact directory in the background and publishes it on Main.
     *
     * Deliberately INDEPENDENT of Room, of the provider fallback and of message sync:
     * it never scans SMS, it never blocks the first Room paint (numbers render first
     * and are replaced by names when the map arrives), and it runs on IO because the
     * Contacts query must never touch the main thread.
     *
     * A no-op while a load is already in flight, so bursts (a Contacts change plus a
     * resume) collapse into one query. [force] invalidates [ContactRepository]'s cache
     * first — used after the user edits a contact.
     */
    fun refreshContactNames(force: Boolean = false) {
        if (contactLoadJob?.isActive == true) return
        contactLoadJob = viewModelScope.launch(Dispatchers.IO) {
            if (force) ContactRepository.clearCache()
            val names = runCatching {
                ContactRepository(getApplication()).getContactNameMapAsync()
            }.getOrDefault(emptyMap())
            withContext(Dispatchers.Main) {
                contactNames = names
                DiagnosticLog.event("CONTACT_DIRECTORY", "loaded=${names.size}")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TRASH (FEATURE 8) — the durable replacement for the 4-second undo
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Keeps the ACTIVE-UI conversation projection in agreement with the durable
     * tombstones, and keeps the ONE purge worker armed.
     *
     * WHY AN OBSERVER, NOT A CALL SITE: `trashed_threads` has several legitimate
     * writers (this screen's delete, the multi-select bulk trash, and a future
     * OTP-retention move), and a rebuild call in each of them would drift — the
     * projector would eventually miss one and a trashed conversation would
     * reappear on Home. Room invalidation is the ONE signal that covers all of
     * them.
     *
     * IT ALSO HEALS PROCESS DEATH: the first emission of every process rebuilds
     * every trashed thread's projection, so a crash between the tombstone write
     * and the projection write cannot leave the deleted conversation on Home.
     * Later emissions only touch threads whose tombstone actually CHANGED (or
     * disappeared on Restore), so the work is proportional to the user's action,
     * never to the size of Trash.
     *
     * `rebuildThreadProjectionFromMirror` is idempotent and reads no provider: it
     * re-derives the projection from the newest ACTIVE row of the mirror Room
     * still holds.
     */
    private fun observeTrashState() {
        viewModelScope.launch {
            val known = HashMap<Long, com.autonomousone.messages.data.TrashedThreadEntity>()
            trashRepository.observeAll()
                .catch { error ->
                    DiagnosticLog.event("TRASH", "trash-observer-failed", error)
                }
                .collect { tombstones ->
                    val current = tombstones.associateBy { it.threadId }
                    val changed = (current.keys + known.keys).filter { current[it] != known[it] }
                    known.clear()
                    known.putAll(current)
                    withContext(Dispatchers.IO) {
                        if (changed.isNotEmpty()) {
                            changed.forEach {
                                runCatching { coordinator.rebuildThreadProjectionFromMirror(it) }
                                    .onFailure { error ->
                                        DiagnosticLog.event("TRASH", "projection-sync-failed thread=$it", error)
                                    }
                            }
                            DiagnosticLog.event("TRASH", "projection-synced count=${changed.size}")
                        }
                        // Cheap safety net (one indexed MIN read): a deadline that
                        // survived process death is re-armed even if the app is
                        // killed right after a trash action.
                        runCatching {
                            com.autonomousone.messages.trash.TrashPurgeScheduler
                                .scheduleNext(getApplication())
                        }
                    }
                }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FEATURE 12 — Smart Categories (additive; no existing Home path changes)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Per-thread EFFECTIVE category, kept live from the two conversation-sized
     * tables ([ConversationClassificationDao.observeCategories] = the automatic
     * classifier, [ConversationPreferenceDao.observeCategoryOverrides] = the user
     * override). Both are O(conversations); neither reads a message.
     *
     * Public READ-ONLY so Compose creates a proper snapshot subscription: a read
     * of the state map during composition is what recomposes Home when a
     * classification or an override lands.
     */
    val effectiveCategories:
        androidx.compose.runtime.snapshots.SnapshotStateMap<Long, MessageCategory> =
        androidx.compose.runtime.mutableStateMapOf()

    /**
     * Thread ids per chip, precomputed ONCE per projection update.
     *
     * Precomputing keeps the per-recomposition cost of the chip row at a map
     * read: selecting a chip triggers no query, no scan and no set rebuild.
     */
    private val categoryThreadIds =
        androidx.compose.runtime.mutableStateMapOf<CategoryFilter, Set<Long>>()

    /** Live conversation count per chip; absent = the category has no data. */
    private val categoryCounts =
        androidx.compose.runtime.mutableStateMapOf<CategoryFilter, Int>()

    private fun observeSmartCategories() {
        viewModelScope.launch {
            val db = MessagesDatabase.get(getApplication())
            val automatic = db.conversationClassificationDao().observeCategories()
            val overrides = db.conversationPreferenceDao().observeCategoryOverrides()
            val userSpam = db.conversationPreferenceDao().observeSpamThreadIds()
            combine(automatic, overrides, userSpam) { a, o, s ->
                ConversationCategoryResolver.effectiveCategories(a, o, s.toSet())
            }.collect { rows ->
                // Precompute off the composition path.
                val ids = LinkedHashMap<CategoryFilter, MutableSet<Long>>()
                for (row in rows) {
                    CategoryFilter.displayOrder
                        .firstOrNull { it.category == row.category }
                        ?.let { chips -> ids.getOrPut(chips) { linkedSetOf() }.add(row.threadId) }
                }
                withContext(Dispatchers.Main) {
                    effectiveCategories.clear()
                    rows.forEach { effectiveCategories[it.threadId] = it.category }
                    categoryThreadIds.clear()
                    ids.forEach { (chip, threadIds) -> categoryThreadIds[chip] = threadIds }
                    categoryCounts.clear()
                    ids.forEach { (chip, threadIds) -> categoryCounts[chip] = threadIds.size }
                }
            }
        }
    }

    /**
     * The thread ids a category chip narrows to, or null for "All".
     *
     * A precomputed set read — selecting a chip issues NO query and NO message
     * scan, and the filtering the UI then does is a set lookup over the
     * already-loaded conversations.
     */
    fun smartCategoryThreadIds(selected: CategoryFilter?): Set<Long>? {
        if (selected == null) return null
        return categoryThreadIds[selected] ?: emptySet()
    }

    /** Live conversation counts per chip; a category with no data is absent. */
    fun smartCategoryCounts(): Map<CategoryFilter, Int> = categoryCounts.toMap()

    /**
     * The durable conversation rows narrowed to ONE category, or null when the
     * chip row is showing "All" (no category selected).
     */
    fun conversationsForCategory(selected: CategoryFilter?): List<Sms>? {
        val threadIds = smartCategoryThreadIds(selected) ?: return null
        return roomConversationsState.filter { it.threadId in threadIds }
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
        // Resume is also the cheap moment to pick up contacts changed while the app
        // was backgrounded (create/rename/delete, or READ_CONTACTS granted in
        // Settings). `getContactNameMapAsync` returns the cached directory without
        // touching the provider, so this is one map read when nothing changed.
        refreshContactNames()
        // ...and while Home is visible, watch the provider so a contact edited in
        // another app lands here without a restart.
        observeContactsChanges()
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
        val main = filterBlocked(rendered.main, blocked).withManualUnread()
        val archived = filterBlocked(rendered.archived, blocked).withManualUnread()
        applySwap(conversations, main.withSpamVisibility())
        applySwap(archivedConversations, archived.withSpamVisibility())
        // FEATURE 9: the ONE explicit place a durable list change reconciles the
        // multi-selection. A refresh that still contains the selected rows leaves
        // the selection untouched — nothing here can silently reset it.
        if (selection.active) {
            selection = selection.afterListRefresh(visibleThreadIds())
        }
    }

    /**
     * FEATURE 9/10: Home unread = real unread OR the user's `manualUnread`
     * bookmark. Applied at render time (never written into the durable row), so
     * the bookmark and the provider's READ column can never fight.
     */
    private fun List<Sms>.withManualUnread(): List<Sms> {
        if (manuallyUnreadIds.isEmpty()) return this
        return map { row ->
            if (!row.unread && row.threadId in manuallyUnreadIds) row.copy(unread = true) else row
        }
    }

    /** Every thread id Home is currently rendering (both tabs). */
    private fun visibleThreadIds(): Set<Long> {
        val ids = LinkedHashSet<Long>(conversations.size + archivedConversations.size)
        conversations.forEach { ids.add(it.threadId) }
        archivedConversations.forEach { ids.add(it.threadId) }
        return ids
    }

    /**
     * FEATURE 15 — spam visibility.
     *
     * A conversation the user reported as spam is hidden from the normal inbox
     * EVERYWHERE (including the Archived tab), and is shown ONLY while the SPAM
     * category chip is selected. Messages are never deleted by a report; the thread
     * remains reachable, and Not-Spam brings it straight back.
     *
     * This runs LAST in the render pipeline on purpose: it is a visibility rule over
     * rows that already passed the blocklist and the overlay, so no optimistic merge
     * can re-introduce a reported thread into the inbox.
     */
    private fun List<Sms>.withSpamVisibility(): List<Sms> {
        if (spamIds.isEmpty()) return this
        // While the SPAM chip is selected the rows stay in the durable list: the
        // chip's own thread-id set is what narrows the screen to them, and dropping
        // them here would make the chip permanently empty. Note this list is the
        // RAW mirror rows (`roomConversationsState`), which still contain a reported
        // thread even though its sender is now blocked — the normal inbox excludes
        // blocked numbers earlier, at the overlay/render merge.
        return if (spamCategoryVisible) this
        else filter { it.threadId !in spamIds }
    }

    /**
     * The reported-spam conversation rows for the SPAM chip.
     *
     * A reported conversation is blocked, so it never reaches the normal rendered
     * lists; the chip therefore reads the RAW mirror state directly and applies the
     * same overlay (so a pinned/optimistic state still shows) plus the manual-unread
     * badge. Bounded by the report set, never a scan.
     */
    fun spamConversations(): List<Sms> {
        if (spamIds.isEmpty()) return emptyList()
        return roomConversationsState
            .filter { it.threadId in spamIds }
            .withManualUnread()
            .sortedByDescending { it.date }
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
            // TRASH (FEATURE 8): the cached snapshot can predate a conversation
            // being trashed, and it is painted BEFORE Room speaks. A thread the
            // user deleted must never flash back on screen, so the durable
            // tombstones filter it here too. (A trashed thread that has since
            // received a NEW message reappears with the correct snippet the moment
            // the ACTIVE-UI Room query answers, milliseconds later.)
            val cachedThreads = withContext(Dispatchers.IO) {
                val trashed = runCatching { trashRepository.trashedThreadIds() }.getOrDefault(emptyList())
                if (trashed.isEmpty()) cached.threads
                else cached.threads.filterNot { it.threadId in trashed }
            }
            if (cachedThreads.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    setRoomConversations(cachedThreads, HomeConversationSource.CACHE)
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
                // ACTIVE-UI (TRASH, FEATURE 8): the one-shot twin of the Flow
                // below, so the first paint and the authoritative list can never
                // disagree about a trashed conversation.
                .allActive()
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
            // P0-11: ONE Room transaction over the WHOLE table.
            //
            // This used to call repository.markAllAsRead() and then loop the
            // RENDERED conversations. That issued one shadow transaction per
            // rendered row and skipped archived, filtered, blocked and off-screen
            // conversations entirely - leaving unread rows behind that reappear the
            // moment the Home filter changes. Room is the authority here, so the
            // update is expressed over the table and the Flow clears every
            // rendered conversation on its own; no loadSms() re-scan is needed.
            coordinator.markAllThreadsReadInShadow()

            // Provider persistence stays a separate, eventual pass and is
            // failure-isolated: Home has already converged from Room above, and a
            // provider failure must never roll that back.
            val providerResult = repository.markAllAsReadStrict()
            if (providerResult.hasFailure) {
                com.autonomousone.messages.utils.DiagnosticLog.event(
                    "HOME_STATE",
                    "provider mark-all-read partial; local Room read retained sms=${providerResult.sms} mms=${providerResult.mms}",
                    null
                )
                coordinator.reconcile(com.autonomousone.messages.data.ReconcileRequest.TailDelta)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Delete → Trash (with undo)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Moves a conversation to TRASH. This is THE one delete flow.
     *
     * The old implementation hid the row optimistically and, 4 seconds later,
     * PERMANENTLY deleted the thread from the provider and from Room unless the
     * user pressed Undo in time. Two defects followed from that shape and both are
     * gone here:
     *
     *  - A "delete" destroyed data the user never confirmed twice, and any missed
     *    snackbar (process death, rotation, another screen) made it unrecoverable.
     *  - It had no durable state: after a restart nothing remembered the delete,
     *    and a provider reconcile could resurrect the thread.
     *
     * The replacement is a DURABLE TOMBSTONE: the provider rows are left alone, the
     * cutoff is the newest row at trash time, and the projection rolls back to the
     * newest ACTIVE message (or disappears). Undo is then a local tombstone delete
     * — instant, with no provider re-insert — and the 30-day retention in
     * [TrashRepository] is what eventually makes the deletion permanent.
     *
     * @return true when the durable tombstone was written. On false nothing was
     *         trashed and the row is un-hidden again, so the caller must not claim
     *         success — it tells the user the delete failed instead.
     */
    suspend fun deleteConversation(sms: Sms): Boolean {
        val threadId = sms.threadId
        if (threadId <= 0L) return false

        // 1. Immediate hide. This is an OVERLAY on the durable list, not a list
        //    mutation: the row disappears in the same frame as the tap, while Room
        //    remains the authority. It retires on its own once the durable
        //    projection no longer contains the thread.
        overlay.recordRemoval(threadId)
        renderConversations()

        // 2. Durable tombstone, off-main. NO provider write happens here: that is
        //    exactly what keeps Undo free and immediate.
        return withContext(Dispatchers.IO) {
            val trashed = runCatching {
                val newest = MessagesDatabase.get(getApplication())
                    .messageDao()
                    .newestForThread(threadId)
                trashRepository.moveToTrash(
                    threadId = threadId,
                    newestActive = newest,
                    now = System.currentTimeMillis()
                )
                // 3. The Home projection IS the ACTIVE UI, so it must now roll
                //    back to the newest ACTIVE message. The tombstone Flow
                //    observer does this too (and repairs a crash window); doing it
                //    here as well makes the rollout immediate for the tap.
                coordinator.rebuildThreadProjectionFromMirror(threadId)
                true
            }.getOrElse { error ->
                DiagnosticLog.event("TRASH", "move-failed thread=$threadId", error)
                false
            }

            if (trashed) {
                // Arm the ONE purge worker for the new deadline.
                runCatching {
                    com.autonomousone.messages.trash.TrashPurgeScheduler
                        .scheduleNext(getApplication())
                }
            } else {
                // Nothing durable was written, so Room stays the authoritative
                // owner: stop hiding the row instead of letting an overlay live
                // forever.
                withContext(Dispatchers.Main) {
                    overlay.clearOverride(threadId)
                    renderConversations()
                }
            }
            trashed
        }
    }

    /**
     * Undo of [deleteConversation]: clears the durable tombstone.
     *
     * The provider rows were never touched, so nothing is re-inserted — clearing
     * the tombstone is enough, and the SAME projection rebuild re-derives the
     * pre-trash snippet, date and unread count from the mirror.
     *
     * @return true when the tombstone was cleared (the conversation is back).
     */
    suspend fun undoDelete(sms: Sms): Boolean {
        val threadId = sms.threadId
        if (threadId <= 0L) return false
        return withContext(Dispatchers.IO) {
            val restored = runCatching {
                trashRepository.restore(threadId)
                coordinator.rebuildThreadProjectionFromMirror(threadId)
                true
            }.getOrElse { error ->
                DiagnosticLog.event("TRASH", "restore-failed thread=$threadId", error)
                false
            }
            // The removal overlay must go even on failure: while it is set it hides
            // the thread forever (a removal is "satisfied" only by an absent Room
            // row, and Room intentionally keeps the rows in Trash).
            withContext(Dispatchers.Main) {
                overlay.clearRemoval(threadId)
                renderConversations()
            }
            runCatching {
                com.autonomousone.messages.trash.TrashPurgeScheduler
                    .scheduleNext(getApplication())
            }
            restored
        }
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
                    // ACTIVE-UI, not the RAW projection: a trashed conversation
                    // (or one whose every message is individually trashed) is
                    // excluded IN SQL, so Home can never render it — not even in
                    // the window between a process restart and the next projection
                    // rebuild. The `conversations` row itself is dropped by the
                    // coordinator's ACTIVE-UI rebuild; this is the read-side half of
                    // the same rule.
                    .observeAllActive()
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
                            TraceSections.begin(TraceSections.HOME_ROOM_EMIT)
                            try {
                                setRoomConversations(converted, HomeConversationSource.ROOM)
                                PerfTelemetry.recordRoomCommitToHome()
                            } finally {
                                TraceSections.end()
                            }
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
                PerfTelemetry.recordMarkReadObserved(event.threadId)
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

    // ─────────────────────────────────────────────────────────────────────────
    // FEATURE 9 — multi-select (additive; owns no durable state)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Home's selection, keyed by conversation `threadId` (never a message id).
     *
     * Held HERE, not derived from the rendered list, so a Room refresh cannot
     * drop it; [renderConversations] reconciles it explicitly.
     */
    var selection by mutableStateOf(SelectionState.idle<Long>())
        private set

    /** True while a bulk action is in flight (progress indicator + disabled actions). */
    var bulkInProgress by mutableStateOf(false)
        private set

    /** One-shot bulk outcome for the screen's snackbar. */
    var bulkFeedback by mutableStateOf<BulkFeedback?>(null)
        private set

    /** Threads the last bulk Trash moved, so the snackbar can offer Undo. */
    private var lastBulkTrashedIds: List<Long> = emptyList()

    /** Threads bookmarked unread (`manualUnread`), live from Room. */
    private val manuallyUnreadIds = mutableStateSetOf<Long>()

    /**
     * Threads the user REPORTED as spam (v3.4.0 FEATURE 15).
     *
     * A report blocks the sender, which is what removes it from the normal inbox
     * (the blocklist filter already does that and must keep doing it — even for an
     * archived thread). This set is the extra information Home needs: a reported
     * conversation is deliberately reachable under the SPAM category chip, so
     * "hidden from the inbox" must not become "unreachable from the app".
     */
    private val spamIds = mutableStateSetOf<Long>()

    /** True while the SPAM category chip is the selected one. */
    private var spamCategoryVisible: Boolean = false

    /** Threads muted RIGHT NOW; drives the Mute/Unmute toggle. */
    private val mutedIds = mutableStateSetOf<Long>()

    /**
     * The conversation rows the SPAM chip may show.
     *
     * Read defensively by the screen and combined with the chip's own thread-id
     * set; empty when the user has reported nothing.
     */
    fun spamThreadIds(): Set<Long> = spamIds.toSet()

    /**
     * The screen tells Home whether the SPAM chip is selected.
     *
     * WHY THIS LIVES HERE: a reported conversation must be hidden from the normal
     * inbox but VISIBLE under Spam. The blocklist filter runs at render time, so
     * the render has to know which of the two states it is rendering. The flag is a
     * plain field (not a Compose state) because the caller re-renders explicitly,
     * exactly like the category chip selection does.
     */
    fun onCategoryChipSelected(selected: CategoryFilter?) {
        val visible = selected == CategoryFilter.Spam
        if (visible == spamCategoryVisible) return
        spamCategoryVisible = visible
        renderConversations()
    }

    /** Called by the screen once the snackbar for [bulkFeedback] was shown. */
    fun consumeBulkFeedback() {
        bulkFeedback = null
    }

    /** Long-press entry point. */
    fun enterSelection(threadId: Long) {
        if (threadId <= 0L) return
        selection = selection.start(threadId)
    }

    fun toggleSelection(threadId: Long) {
        if (threadId <= 0L) return
        selection = selection.toggle(threadId)
    }

    /** X in the selection top bar, BACK, or leaving the screen. */
    fun clearSelection() {
        if (selection.active) selection = selection.clear()
    }

    /** True when every selected conversation is pinned (drives Pin/Unpin). */
    fun allSelectedPinned(): Boolean =
        selection.keys.isNotEmpty() && selection.keys.all { it in pinnedIds }

    /** True when every selected conversation is currently muted. */
    fun allSelectedMuted(): Boolean =
        selection.keys.isNotEmpty() && selection.keys.all { it in mutedIds }

    fun bulkMarkRead() = runBulkAction(
        action = { bulkActions.markRead(it) },
        onApplied = { applied ->
            applied.forEach { manuallyUnreadIds.remove(it) }
            renderConversations()
        }
    )

    fun bulkMarkUnread() = runBulkAction(
        action = { bulkActions.markUnread(it) },
        onApplied = { applied ->
            applied.forEach { manuallyUnreadIds.add(it) }
            renderConversations()
        }
    )

    fun bulkArchive() = runBulkAction(
        action = { bulkActions.archive(it) },
        onApplied = { applied ->
            archivedIds.addAll(applied)
            renderConversations()
        }
    )

    fun bulkUnarchive() = runBulkAction(
        action = { bulkActions.unarchive(it) },
        onApplied = { applied ->
            archivedIds.removeAll(applied)
            renderConversations()
        }
    )

    fun bulkMute(until: Long) = runBulkAction(
        action = { bulkActions.mute(it, until) },
        onApplied = { applied -> mutedIds.addAll(applied) }
    )

    fun bulkUnmute() = runBulkAction(
        action = { bulkActions.unmute(it) },
        onApplied = { applied -> mutedIds.removeAll(applied) }
    )

    fun bulkPin() = runBulkAction(
        action = { bulkActions.pin(it, pinned = true) },
        onApplied = { applied ->
            pinnedIds.addAll(applied)
            renderConversations()
        }
    )

    fun bulkUnpin() = runBulkAction(
        action = { bulkActions.pin(it, pinned = false) },
        onApplied = { applied ->
            pinnedIds.removeAll(applied)
            renderConversations()
        }
    )

    /**
     * Move the selection to Trash. Durable tombstones are written by
     * [BulkActionRepository]; the removal is ALSO applied to the optimistic
     * overlay so the rows disappear immediately, and the applied ids are kept so
     * the snackbar can offer Undo.
     */
    fun bulkMoveToTrash() = runBulkAction(
        action = { bulkActions.moveToTrash(it) },
        onApplied = { applied ->
            lastBulkTrashedIds = applied.toList()
            applied.forEach { overlay.recordRemoval(it) }
            renderConversations()
        }
    )

    /** Undo of [bulkMoveToTrash]: clears the tombstones (provider rows untouched). */
    fun undoBulkTrash() {
        val ids = lastBulkTrashedIds
        if (ids.isEmpty()) return
        lastBulkTrashedIds = emptyList()
        viewModelScope.launch {
            runCatching { bulkActions.restoreFromTrash(ids.toSet()) }
            ids.forEach { overlay.clearRemoval(it) }
            renderConversations()
        }
    }

    /**
     * One bulk action, one coroutine.
     *
     * Selection exits as soon as the action could apply to anything; on a TOTAL
     * failure the selection is KEPT so the user can retry instead of losing the
     * work they just marked. An empty selection is a quiet no-op — no coroutine,
     * no snackbar, no progress indicator.
     */
    private fun runBulkAction(
        action: suspend (Set<Long>) -> BulkResult,
        onApplied: (Set<Long>) -> Unit = {}
    ) {
        val ids = selection.keys
        if (ids.isEmpty() || bulkInProgress) return
        viewModelScope.launch {
            bulkInProgress = true
            lastBulkTrashedIds = emptyList()
            try {
                val result = action(ids)
                val applied = ids - result.failedThreadIds()
                if (applied.isNotEmpty()) onApplied(applied)
                bulkFeedback = BulkFeedback.from(
                    result = result,
                    trashedThreadIds = lastBulkTrashedIds
                )
                if (!result.isCompleteFailure) selection = selection.clear()
            } finally {
                bulkInProgress = false
            }
        }
    }

    /**
     * FEATURE 9/10: live `manualUnread` + mute state so the Home badge and the
     * Mute/Unmute toggle reflect the durable bookmark without a reload.
     */
    private fun observeConversationUserState() {
        viewModelScope.launch {
            preferenceRepository.observeManuallyUnreadThreadIds().collect { ids ->
                withContext(Dispatchers.Main) {
                    manuallyUnreadIds.clear()
                    manuallyUnreadIds.addAll(ids)
                    renderConversations()
                }
            }
        }
        viewModelScope.launch {
            preferenceRepository.observeMutedThreadIds(System.currentTimeMillis()).collect { ids ->
                withContext(Dispatchers.Main) {
                    mutedIds.clear()
                    mutedIds.addAll(ids)
                }
            }
        }
        // FEATURE 15: a spam report is durable user state, so Home re-renders the
        // moment it lands (hiding the thread from the inbox, or revealing it under
        // the Spam chip if that is the selected one). Never deletes a message.
        viewModelScope.launch {
            preferenceRepository.observeSpamThreadIds().collect { ids ->
                withContext(Dispatchers.Main) {
                    spamIds.clear()
                    spamIds.addAll(ids)
                    renderConversations()
                }
            }
        }
    }

    override fun onCleared() {
        // TRASH changed this from "cancel the 4-second pending permanent delete" to
        // "nothing to cancel": the trash state is durable Room data plus a
        // WorkManager request, so leaving the screen (or dying) can no longer
        // destroy or resurrect anything.
        reloadRequests.close()
        repository.unregisterObserver(observer)
        runCatching { contactsObserver.unregister() }
        super.onCleared()
    }
}
