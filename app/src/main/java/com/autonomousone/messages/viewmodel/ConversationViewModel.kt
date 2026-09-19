package com.autonomousone.messages.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autonomousone.messages.R
import com.autonomousone.messages.data.ConversationSearchHit
import com.autonomousone.messages.data.MessageKey
import com.autonomousone.messages.event.SmsEventBus
import com.autonomousone.messages.repository.BulkActionRepository
import com.autonomousone.messages.repository.BulkFeedback
import com.autonomousone.messages.repository.ConversationSearchNavigation
import com.autonomousone.messages.repository.InConversationSearchRepository
import com.autonomousone.messages.repository.MessageUserStateRepository
import com.autonomousone.messages.repository.SearchDebounce
import com.autonomousone.messages.repository.SearchJumpWindow
import com.autonomousone.messages.repository.SearchOutcome
import com.autonomousone.messages.repository.ThreadMessageCache
import com.autonomousone.messages.repository.ThreadMerge
import com.autonomousone.messages.repository.toMessageKey
import com.autonomousone.messages.messaging.MessagingPreferences
import com.autonomousone.messages.messaging.DelayedSendNotice
import com.autonomousone.messages.mms.MmsSender
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.observer.SmsContentObserver
import com.autonomousone.messages.repository.ContactRepository
import com.autonomousone.messages.repository.ProgressListener
import com.autonomousone.messages.repository.SmsRepository
import com.autonomousone.messages.repository.ThreadPager
import com.autonomousone.messages.repository.ConversationWindow
import com.autonomousone.messages.repository.MarkConversationReadUseCase
import com.autonomousone.messages.repository.MessageIdentity
import com.autonomousone.messages.messaging.VisibleConversationTracker
import com.autonomousone.messages.sms.SmsSender
import com.autonomousone.messages.ui.selection.SelectionState
import com.autonomousone.messages.utils.DiagnosticLog
import com.autonomousone.messages.diagnostics.PerfMetric
import com.autonomousone.messages.diagnostics.PerfTelemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Which boundary of the thread the visible window is anchored to. */
enum class ConversationWindowMode {
    /** Normal open: newest page, crawl older by scrolling up. */
    LATEST,

    /** "Go to first message": oldest page, crawl newer by scrolling down. */
    OLDEST
}

/**
 * One-shot scroll intents the ViewModel emits after a window REPLACE
 * (jump-to-latest / jump-to-oldest). The screen consumes them; ordinary
 * pagination NEVER emits one — in reverse layout the newest window is
 * index 0 and needs no scroll at all.
 */
sealed interface ConversationScrollCommand {
    data class Latest(val messageId: Long?) : ConversationScrollCommand
    data class Oldest(val messageId: Long?) : ConversationScrollCommand

    /**
     * v3.4.0 FEATURE 1 — land on ONE searched message.
     *
     * The target is the COMPOSITE identity `(source, providerId)`, never a raw
     * id: a row list built from Room/Sms mixes sources, and scrolling to "100"
     * would be ambiguous between SMS 100 and MMS 100. The row list itself is
     * already in canonical order, so the screen only has to resolve the key to
     * an index — it never re-sorts or re-queries.
     */
    data class SearchHit(val key: MessageIdentity.Key) : ConversationScrollCommand
}

/**
 * The in-conversation search surface (v3.4.0 FEATURE 1).
 *
 * Loading / Content / Empty / Error are mutually exclusive and total: the UI
 * renders the state it is handed and never a blank panel.
 */
sealed interface ConversationSearchState {

    /** Search mode is off, or on with a query too short to execute. */
    data object Idle : ConversationSearchState

    /** A debounced query is in flight. */
    data class Loading(val query: String) : ConversationSearchState

    /**
     * At least one active hit inside this thread.
     *
     * [hits] is one bounded page (newest-first, matching the DAO order); the
     * screen asks for the next page when the list nears its end, so the paging
     * stays bounded regardless of how many messages match.
     */
    data class Content(
        val query: String,
        val hits: List<ConversationSearchHit>,
        val total: Int,
        val loadingMore: Boolean
    ) : ConversationSearchState

    /** The query ran and matched nothing (never shown for a too-short query). */
    data class Empty(val query: String) : ConversationSearchState

    /** The query failed. [message] is a user-facing string, already localized. */
    data class Error(val message: String) : ConversationSearchState
}

class ConversationViewModel(
    application: Application
) : AndroidViewModel(application) {

    companion object {
        /**
         * Rows painted from the Room shadow on instant-open. Room is a single
         * merged table (no per-source quota), so 20 ≈ the union of the
         * provider pager's INITIAL_PER_SOURCE windows.
         */
        private const val ROOM_WINDOW = ConversationWindow.OPEN_WINDOW

        /**
         * `Sms.status` sentinel for a message held by Undo Send.
         *
         * Deliberately outside every `Telephony.Sms.STATUS_*` value: it is OUR
         * UI state, and a value the provider also uses would make a delayed
         * bubble indistinguishable from a real pending/failed provider row after
         * a merge.
         */
        const val STATUS_DELAYED_PENDING = -11

        /**
         * Bound on the ONE durable decision a composer tap waits for (the ledger
         * INSERT, or the undo compare-and-set). Generous for a single-row write
         * and short enough that a wedged database cannot freeze the composer;
         * both callers have a safe fallback when it expires.
         */
        private const val HOLD_DECISION_TIMEOUT_MILLIS = 1_500L
    }

    private val repository = SmsRepository(application)

    /**
     * The single read entry point for EVERY conversation-open path (Home,
     * search, deep link, notification action, cache/Room/provider fallback,
     * explicit mark-read, and an incoming message into an already-open chat).
     */
    private val markReadUseCase = MarkConversationReadUseCase.get(getApplication())
    private val smsSender = SmsSender(application)
    private val mmsSender = MmsSender(application)

    /** FEATURE 10: the single batched entry point for bulk message actions. */
    private val bulkActions = BulkActionRepository.get(application)

    /** FEATURE 9/10: per-message user state (star / individual trash). */
    private val userState = MessageUserStateRepository(application)

    val messages = mutableStateListOf<Sms>()

    var isLoading by mutableStateOf(false)
        private set

    var loadStatus by mutableStateOf<String?>(null)

    /**
     * Which end of the thread the visible window is anchored to. Opens in
     * LATEST; "Go to first message" flips to OLDEST until the user jumps back.
     */
    var windowMode by mutableStateOf(ConversationWindowMode.LATEST)
        private set

    var isLoadingOlder by mutableStateOf(false)
        private set

    var isLoadingNewer by mutableStateOf(false)
        private set

    /** A boundary jump (latest/oldest window replace) is in flight. */
    var isJumpingToBoundary by mutableStateOf(false)
        private set

    /**
     * Incoming messages that arrived while the user was reading OLDEST history.
     * They must NOT be dropped into a historical window (the gap would be
     * years wide) — we count them and the Jump-to-latest badge renders it.
     */
    var pendingNewMessagesCount by mutableIntStateOf(0)
        private set

    /**
     * True while the user is parked at the newest message (reverse-layout
     * index 0). Drives auto-follow for incoming messages and outgoing sends:
     * scrolling away disarms it, scrolling back re-arms it. The Screen owns
     * the truth (it is the only one who sees geometry) via setUserAtLatest.
     */
    var userAtLatest = true
        private set

    fun setUserAtLatest(atLatest: Boolean) {
        if (userAtLatest == atLatest) return
        userAtLatest = atLatest
        if (atLatest && windowMode == ConversationWindowMode.LATEST) {
            pendingNewMessagesCount = 0
        }
    }

    /**
     * v2.6.8 motion polish — true from the moment Send is tapped until the
     * follow-to-newest glide finishes. Inserting the optimistic bubble can
     * make the reverse-layout LazyColumn transiently shift
     * firstVisibleItemIndex while it re-anchors; without this latch the ↓
     * button flashes exactly after every send. While it is on the FAB is
     * pinned hidden no matter what the layout does mid-flight.
     */
    var ownSendFollowActive by mutableStateOf(false)
        private set

    /** Arm the single Send intent BEFORE the optimistic insert lands. */
    fun beginOwnSend() {
        ownSendFollowActive = true
        userAtLatest = true
        pendingNewMessagesCount = 0
    }

    /** Called once the screen's animateScrollToItem(0) has settled. */
    fun finishOwnSendFollow() {
        ownSendFollowActive = false
    }

    /**
     * v2.6.9 live-entry motion: ids of messages that appeared WHILE the
     * screen was open (own optimistic send, incoming SMS near the latest
     * edge). Only these bubbles get MessageEntrance; the initial Room/cache
     * hydration must never animate per-bubble. Consumed by the screen once
     * a bubble finished entering so scrolling back later shows it static.
     */
    private val liveEntryIds =
        mutableStateMapOf<Long, Unit>()

    fun shouldAnimateEntry(
        id: Long
    ): Boolean {
        return liveEntryIds.containsKey(id)
    }

    fun consumeEntryAnimation(
        id: Long
    ) {
        liveEntryIds.remove(id)
    }

    /** Mark BEFORE inserting the message into [messages] — order matters. */
    private fun markForEntryAnimation(
        id: Long
    ) {
        liveEntryIds[id] = Unit

        // Bounded set: oldest marks evict first; 24 is far above anything
        // that can be on/near screen at once.
        while (liveEntryIds.size > 24) {
            liveEntryIds.keys.firstOrNull()?.let {
                liveEntryIds.remove(it)
            } ?: break
        }
    }

    // A burst can enqueue several live rows before Compose completes a frame.
    // We only need the newest follow request, so retain it instead of letting
    // tryEmit() silently fail while the single-slot buffer is occupied.
    private val _scrollCommands = MutableSharedFlow<ConversationScrollCommand>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val scrollCommands = _scrollCommands.asSharedFlow()

    /**
     * Adds a genuinely live row and applies the same follow/animation policy
     * to incoming SMS and sends produced outside this screen (REST, queue,
     * notification quick reply). The decision is captured before insertion:
     * reverse-layout may briefly re-anchor after the new index 0 exists.
     */
    private fun appendLiveMessage(row: Sms, source: String): Boolean {
        val duplicate = messages.any { existing ->
            ConversationWindow.identity(existing.id) == ConversationWindow.identity(row.id)
        }
        if (duplicate) {
            DiagnosticLog.event(
                "INCOMING_DEDUP",
                "source=$source identity=${ConversationWindow.identity(row.id)} decision=duplicate"
            )
            return false
        }

        if (windowMode == ConversationWindowMode.OLDEST) {
            pendingNewMessagesCount++
            ThreadMessageCache.append(currentThreadId, currentPhone, row)
            DiagnosticLog.event(
                "CHAT_LIVE",
                "source=$source id=${row.id} mode=oldest follow=false"
            )
            return false
        }

        val shouldFollow = userAtLatest || ownSendFollowActive
        if (shouldFollow) markForEntryAnimation(row.id)
        messages.add(row)
        ThreadMessageCache.append(currentThreadId, currentPhone, row)

        if (shouldFollow) {
            _scrollCommands.tryEmit(ConversationScrollCommand.Latest(row.id))
        } else {
            pendingNewMessagesCount++
        }
        DiagnosticLog.event(
            "CHAT_LIVE",
            "source=$source id=${row.id} follow=$shouldFollow"
        )
        DiagnosticLog.event(
            "INCOMING_DEDUP",
            "source=$source identity=${ConversationWindow.identity(row.id)} decision=append"
        )
        return true
    }

    /**
     * Last swallowed background failure, surfaced once as a dismissible
     * snackbar by ConversationScreen. Non-null only after crashGuard fires —
     * the screen stays usable (cached rows remain painted) either way.
     */
    var errorMessage by mutableStateOf<String?>(null)
        private set

    /** Called by the screen after the snackbar for [errorMessage] is shown. */
    fun consumeError() { errorMessage = null }

    private var currentThreadId = 0L
    private var currentPhone = ""

    // Track IDs of sent messages we've persisted so we can match them during refresh
    private val persistedSentIds = mutableSetOf<Long>()

    // Optimistic sent rows not yet confirmed in the provider DB (kept visible on refresh).
    private val optimisticMessages = mutableListOf<Sms>()

    // ════════════════════════════════════════════════════════════════════════
    // v3.4.0 FEATURE 11 — Send delay / Undo Send (composer only).
    //
    // The delay decision itself lives in DelayedSendGate, the durable state and
    // the at-most-once claim live in pending_delayed_sends, and the TIMER is the
    // app's existing scheduler (ScheduledSms → WorkManager). This ViewModel only
    // ROUTES the composer send and RENDERS the pending intent, so there is
    // exactly one scheduling engine and exactly one copy of the policy.
    //
    // The recipient is NOT tracked here: the kind of a pending bubble comes from
    // the durable ledger, never from a ViewModel field, so a process death
    // cannot leave the composer showing a state the worker does not agree with.
    // ════════════════════════════════════════════════════════════════════════

    /** Synthetic `Sms.id` of every rendered pending bubble, oldest first. */
    private val pendingDelayedScanIds = mutableListOf<Long>()

    /** Live ledger observer for the OPEN conversation; cancelled on switch. */
    private var pendingDelayedJob: kotlinx.coroutines.Job? = null

    /** Composer routing + undo for delayed sends. */
    private val delayedSend: com.autonomousone.messages.messaging.DelayedSendCoordinator by lazy {
        com.autonomousone.messages.messaging.DelayedSendCoordinator(
            context = application,
            sink = com.autonomousone.messages.sms.DelayedSendSink { phone, body, subscriptionId ->
                smsSender.sendForResult(phone, body, subscriptionId)
            }
        )
    }

    /**
     * Told when the composer's message was HELD instead of sent, so the screen
     * can show "Sending in N seconds" with UNDO. A one-shot signal, not state:
     * the Snackbar is an event.
     */
    private val _delayedSendStarted = MutableSharedFlow<DelayedSendNotice>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val delayedSendStarted = _delayedSendStarted.asSharedFlow()

    private val observer = SmsContentObserver { batch ->
        // Conversation screen uses merge-based refresh (targeted tail query),
        // not full reload. The URI is not needed here because the pager's
        // loadNewerSince() already does a bounded query.
        //
        // Per-thread cache revision: only the threads this provider burst
        // actually touched become stale. Activity in another conversation
        // must NOT invalidate B/C/D's instant-open windows.
        batch.threadIds.forEach { ThreadMessageCache.invalidateThread(it) }
        refresh()
    }

    init {
        repository.registerObserver(observer)
        observeIncomingSms()
        observeRefreshSignal()
        observeOutgoingSent()
    }

    // ── Bidirectional windowed history (paged) ───────────────────────────────
    // Only a small page is read on open; scrolling toward either boundary pulls
    // the next keyset page. Never the whole thread.
    private var pager: ThreadPager? = null

    /** ReactiveRoomTail for the currently open thread (bounded window). */
    private var roomTailJob: kotlinx.coroutines.Job? = null

    /**
     * Builds the windowed pager for the CURRENT thread when one does not exist
     * yet. Bounded by construction: ThreadPager only ever issues keyset pages.
     */
    private fun ensurePager(): ThreadPager? {
        pager?.let { return it }
        val thread = currentThreadId
        val phone = currentPhone
        if (thread == 0L && phone.isBlank()) return null
        return ThreadPager(getApplication(), thread, phone).also { pager = it }
    }

    /**
     * ReactiveRoomTail for the open conversation.
     *
     * Observes the BOUNDED newest window (MessageDao.observeThread with a
     * LIMIT) and merges every emission into the visible list. Architecture:
     * ReactiveRoomTail + LoadedOlderPages + OptimisticRows = VisibleMessages.
     * A Room emission updates the recent rows' read/status but NEVER deletes
     * older pages the user already scrolled to (ConversationWindow.mergeRoomTail).
     */
    private fun startRoomTail(threadId: Long, gen: Long) {
        if (threadId <= 0L) return
        roomTailJob = viewModelScope.launch(Dispatchers.IO + crashGuard("roomTail")) {
            com.autonomousone.messages.data.MessagesDatabase.get(getApplication())
                .messageDao()
                // ACTIVE-UI tail (v3.4.0 FEATURE 8, TRASH): the reactive tail must
                // not re-introduce a message the user deleted — individually, or as
                // part of a trashed conversation's snapshot. `observeThread` remains
                // the RAW variant for sync/repair callers.
                .observeActiveThread(threadId, ConversationWindow.OPEN_WINDOW)
                .collect { entities ->
                    if (gen != conversationGeneration) return@collect
                    val tail = entities.map { it.toSms() }
                    withContext(Dispatchers.Main) {
                        if (gen != conversationGeneration) return@withContext
                        val optimistic = mergeOptimistic(tail)
                        val merged = ConversationWindow.mergeRoomTail(
                            messages.toList(),
                            tail.map { it.copy(unread = false) },
                            optimistic
                        )
                        messages.clear()
                        messages.addAll(merged)
                        // A search JUMP paints a bounded window that is NOT the
                        // conversation; caching it here would reopen this chat
                        // on 40 messages tomorrow. The window is stored only
                        // while the normal conversation list owns the screen.
                        if (windowMode == ConversationWindowMode.LATEST &&
                            searchJumpWindow == null
                        ) {
                            ThreadMessageCache.put(currentThreadId, currentPhone, merged)
                        }
                    }
                }
        }
    }

    /** The ONE canonical order every window mutation ends in (Q). */
    private val chronologicalOrder = ThreadMerge.canonicalChronological

    /** Cancels the previous load when a new conversation is opened, so a slow
     *  old query can never overwrite the freshly opened thread's messages. */
    private var conversationLoadJob: kotlinx.coroutines.Job? = null

    /**
     * Monotonic generation stamp for conversation switches. Every async job
     * (initial load, older-page, refresh, spinner) captures the generation it
     * started under and drops its result when the screen has moved on — one
     * guard for ALL paths instead of per-job checks.
     */
    @Volatile
    private var conversationGeneration = 0L

    /**
     * Conversation-screen boundary for every background job. A failing
     * provider/Room/Pager query must degrade to an error state on screen —
     * it must NEVER become an uncaught coroutine exception that kills the
     * whole process (the silent "app just closes" report). Cancellation is
     * a normal lifecycle event and rethrows.
     */
    private fun crashGuard(context: String): kotlinx.coroutines.CoroutineExceptionHandler =
        kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(
                "CONV_VM",
                "Conversation job failed ($context) threadId=$currentThreadId " +
                    "phone=${if (currentPhone.isNotBlank()) "set" else "none"} " +
                    "msgCount=${messages.size}",
                e
            )
            DiagnosticLog.event(
                "CONVERSATION_CRASH",
                "job=$context thread=$currentThreadId phone=${DiagnosticLog.phoneToken(currentPhone)} " +
                    "messages=${messages.size}",
                e
            )
            viewModelScope.launch(Dispatchers.Main) {
                isLoading = false
                loadStatus = null
                errorMessage = getApplication<Application>()
                    .getString(R.string.conv_load_failed)
            }
        }

    /** Whether a scroll toward the OLD end could still yield rows. */
    fun hasMoreOlder(): Boolean = pager?.hasOlder == true

    /** Whether a scroll toward the NEW end could still yield rows (OLDEST mode). */
    fun hasMoreNewer(): Boolean = pager?.hasNewer == true

    fun loadOlderMessages() {
        val p = pager ?: return
        if (!p.hasOlder) return
        if (olderMessagesJob?.isActive == true) return
        val gen = conversationGeneration
        isLoadingOlder = true
        olderMessagesJob = viewModelScope.launch(Dispatchers.IO + crashGuard("loadOlder")) {
            try {
                val older = p.loadOlder()
                // Screen moved to another conversation while the page was loading.
                if (gen != conversationGeneration || older.isEmpty()) return@launch
                withContext(Dispatchers.Main) {
                    if (gen != conversationGeneration) return@withContext
                    // Prepend only rows not already on screen (a refresh may have
                    // widened the window since the pager counters were set).
                    val merged = ThreadMerge.prependOlder(
                        messages.toList(), older.map { it.copy(unread = false) }
                    )
                    if (merged.size > messages.size) {
                        messages.clear()
                        messages.addAll(merged)
                    }
                }
            } finally {
                withContext(Dispatchers.Main) { isLoadingOlder = false }
            }
        }
    }

    private var olderMessagesJob: kotlinx.coroutines.Job? = null

    /**
     * Forward crawl while the window is anchored to the OLDEST boundary:
     * the next keyset page of NEWER rows merges into the canonical ASC list.
     * The reverse-layout mapper renders newest data first, so these rows
     * appear toward the visual bottom (index-0 side) — where the user is
     * scrolling when they hit the newer boundary.
     */
    fun loadNewerMessages() {
        val p = pager ?: return
        if (!p.hasNewer) return
        if (newerMessagesJob?.isActive == true) return
        val gen = conversationGeneration
        isLoadingNewer = true
        newerMessagesJob = viewModelScope.launch(Dispatchers.IO + crashGuard("loadNewer")) {
            try {
                val newer = p.loadNewer()
                if (gen != conversationGeneration || newer.isEmpty()) return@launch
                withContext(Dispatchers.Main) {
                    if (gen != conversationGeneration) return@withContext
                    val merged = ThreadMerge.appendNewer(
                        messages.toList(), newer.map { it.copy(unread = false) }
                    )
                    if (merged.size > messages.size) {
                        messages.clear()
                        messages.addAll(merged)
                    }
                }
            } finally {
                withContext(Dispatchers.Main) { isLoadingNewer = false }
            }
        }
    }

    private var newerMessagesJob: kotlinx.coroutines.Job? = null

    /**
     * Floating "Jump to latest". In LATEST mode the newest window is already
     * loaded and the UI just scrolls to index 0 — this method is for OLDEST
     * mode, where it must NOT animate through hundreds of pages: it builds a
     * fresh pager, queries the latest window directly (O(page size)), and
     * REPLACES the historical window.
     */
    fun jumpToLatest() {
        if (windowMode == ConversationWindowMode.LATEST) {
            pendingNewMessagesCount = 0
            viewModelScope.launch(Dispatchers.Main) {
                _scrollCommands.tryEmit(
                    ConversationScrollCommand.Latest(messages.lastOrNull()?.id)
                )
            }
            return
        }
        val thread = currentThreadId
        val phone = currentPhone
        if (thread == 0L && phone.isBlank()) return

        olderMessagesJob?.cancel()
        newerMessagesJob?.cancel()
        val gen = conversationGeneration

        viewModelScope.launch(Dispatchers.IO + crashGuard("jumpToLatest")) {
            withContext(Dispatchers.Main) { isJumpingToBoundary = true }
            try {
                val p = ThreadPager(getApplication(), thread, phone)
                val latest = p.loadLatest()
                    .map { it.copy(unread = false) }
                    .distinctBy { ConversationWindow.identity(it.id) }
                    .sortedWith(chronologicalOrder)

                if (gen != conversationGeneration) return@launch

                withContext(Dispatchers.Main) {
                    if (gen != conversationGeneration) return@withContext
                    pager = p
                    windowMode = ConversationWindowMode.LATEST
                    pendingNewMessagesCount = 0

                    messages.clear()
                    messages.addAll(latest)
                    // Optimistic sends from this session survive the jump too.
                    messages.addAll(mergeOptimistic(latest))

                    _scrollCommands.tryEmit(
                        ConversationScrollCommand.Latest(latest.lastOrNull()?.id)
                    )
                }
                // Latest window only — this IS the instant-open cache.
                ThreadMessageCache.put(thread, phone, latest)
            } finally {
                withContext(Dispatchers.Main) { isJumpingToBoundary = false }
            }
        }
    }

    /**
     * Three-dot "Go to first message": the TRUE first message comes from the
     * TELEPHONY provider (a historical backfill into Room may be incomplete,
     * so Room is never trusted for the oldest boundary). One bounded
     * ASC-order page — no full scan, no OFFSET, no backfill await.
     */
    fun jumpToOldest() {
        val thread = currentThreadId
        val phone = currentPhone
        if (thread == 0L && phone.isBlank()) return
        if (isJumpingToBoundary) return

        olderMessagesJob?.cancel()
        newerMessagesJob?.cancel()
        val gen = conversationGeneration

        viewModelScope.launch(Dispatchers.IO + crashGuard("jumpToOldest")) {
            withContext(Dispatchers.Main) { isJumpingToBoundary = true }
            try {
                val p = ThreadPager(getApplication(), thread, phone)
                val oldest = p.loadOldest()
                    .map { it.copy(unread = false) }
                    .distinctBy { ConversationWindow.identity(it.id) }
                    .sortedWith(chronologicalOrder)

                if (gen != conversationGeneration) return@launch

                withContext(Dispatchers.Main) {
                    if (gen != conversationGeneration) return@withContext
                    pager = p
                    windowMode = ConversationWindowMode.OLDEST

                    messages.clear()
                    messages.addAll(oldest)

                    _scrollCommands.tryEmit(
                        ConversationScrollCommand.Oldest(oldest.firstOrNull()?.id)
                    )
                }
                // INTENTIONALLY not ThreadMessageCache.put(): that cache is
                // for the LATEST window only. Caching an oldest page here
                // would reopen this thread in 2017 tomorrow.
            } finally {
                withContext(Dispatchers.Main) { isJumpingToBoundary = false }
            }
        }
    }

    /**
     * Shared tail for window REPLACEs: every place that rebuilds `messages`
     * from a page list ends through canonical order (Q) — never raw provider
     * order, never insertion order.
     */
    private fun canonicalize(vararg lists: List<Sms>): List<Sms> =
        (lists.asList().flatten())
            .distinctBy { ConversationWindow.identity(it.id) }
            .sortedWith(chronologicalOrder)

    fun loadConversation(threadId: Long, phone: String = "") {
        val openPerfMark = PerfTelemetry.mark()
        val firstPaintRecorded = java.util.concurrent.atomic.AtomicBoolean(false)
        fun recordFirstPaintIfNeeded() {
            if (messages.isNotEmpty() && firstPaintRecorded.compareAndSet(false, true)) {
                PerfTelemetry.recordSince(PerfMetric.CONVERSATION_TAP_TO_FIRST_BUBBLES, openPerfMark)
            }
        }
        DiagnosticLog.event(
            "CONVERSATION",
            "open thread=$threadId phone=${DiagnosticLog.phoneToken(phone)} currentMessages=${messages.size}"
        )
        currentThreadId = threadId
        if (phone.isNotBlank()) {
            currentPhone = phone
            SmsEventBus.activeConversationPhone = phone
        }

        // A slow load of the PREVIOUS conversation must never paint over this
        // one — cancel it and stamp this run with the thread it owns.
        conversationLoadJob?.cancel()
        olderMessagesJob?.cancel()
        newerMessagesJob?.cancel()
        roomTailJob?.cancel()
        olderMessagesJob = null
        newerMessagesJob = null
        roomTailJob = null
        // Every OPEN is deterministic: the conversation starts at the LATEST
        // boundary regardless of where a previous visit ended.
        windowMode = ConversationWindowMode.LATEST
        pendingNewMessagesCount = 0
        conversationGeneration++
        // A search result set belongs to the thread it was queried in: a switch
        // must drop it rather than let another conversation inherit the panel.
        if (isSearchActive || searchState !is ConversationSearchState.Idle) {
            isSearchActive = false
            searchLoadMoreJob?.cancel()
            searchLoadMoreJob = null
            clearSearchResults()
        }
        val myThread = threadId
        val myPhone = currentPhone
        val gen = conversationGeneration

        // Announce visibility to the sync core BEFORE any async read so an
        // incoming message for this thread is written read in the same
        // transaction that inserts it (no 0 → 1 → 0 badge flash).
        if (threadId > 0L) VisibleConversationTracker.onOpened(threadId)

        // ReactiveRoomTail: bounded Room window feeds the UI from here on.
        startRoomTail(threadId, gen)

        // v3.4.0 FEATURE 11: render the OPEN conversation's held messages from
        // the durable ledger, bounded by threadId. Unrelated threads are never
        // read, and the collector is replaced on every conversation switch.
        observePendingDelayedSends(threadId, gen)

        conversationLoadJob = viewModelScope.launch(Dispatchers.IO + crashGuard("loadConversation")) {
            // ── Stale-while-revalidate: paint the cached thread INSTANTLY
            // (Google Messages-style), then refresh from the provider.
            val cache = ThreadMessageCache
            val cacheKeyThread = if (threadId != 0L) threadId else 0L
            val stale = if (cacheKeyThread != 0L || phone.isNotBlank())
                cache.getStale(cacheKeyThread, phone.ifBlank { currentPhone }) else null

            if (stale == null || stale.first.isEmpty()) {
                // In-memory cache miss (fresh process): paint from the local
                // Room shadow instead of showing an empty/spinner screen.
                val coordinator = com.autonomousone.messages.data.TelephonySyncCoordinator
                    .get(getApplication())
                if (!roomReadEnabled) {
                    roomReadEnabled = kotlin.runCatching { coordinator.isShadowReady() }.getOrDefault(false)
                }
                val key = if (cacheKeyThread != 0L) cacheKeyThread else currentThreadId
                val normPhone = ContactRepository.normalizePhone(phone.ifBlank { currentPhone })
                if (roomReadEnabled && (key != 0L || normPhone.isNotBlank())) {
                    val roomRows = kotlin.runCatching {
                        com.autonomousone.messages.data.MessagesDatabase.get(getApplication())
                            .messageDao()
                            .let { dao ->
                                if (key != 0L) dao.newestWindowForThread(key, limit = ROOM_WINDOW)
                                else dao.newestForAddress(normPhone, limit = ROOM_WINDOW)
                            }
                            .map { it.toSms() }
                            // DAO rows come back date-DESC (newest first); the
                            // UI is ALWAYS oldest→newest, and the painted
                            // window is hard-capped to ROOM_WINDOW rows so a
                            // 100 000-message thread opens on a page, not a
                            // scan.
                            .let { ConversationWindow.boundedNewest(it, ROOM_WINDOW) }
                    }.getOrNull().orEmpty()
                    if (roomRows.isNotEmpty() && gen == conversationGeneration) {
                        withContext(Dispatchers.Main) {
                            if (gen != conversationGeneration) return@withContext
                            messages.clear()
                            messages.addAll(roomRows.map { it.copy(unread = false) })
                            messages.addAll(mergeOptimistic(messages.toList()))
                            isLoading = false
                            loadStatus = null
                            recordFirstPaintIfNeeded()
                        }
                        markReadAndNotify(targetOf(cacheKeyThread, phone), phoneIfBlank(phone))
                    }
                }
            }

            if (stale != null && stale.first.isNotEmpty()) {
                // R: the cache may predate this release and hold unsorted
                // rows — canonicalize before painting, never trust insertion.
                val cachedList = canonicalize(stale.first.map { it.copy(unread = false) })
                withContext(Dispatchers.Main) {
                    if (gen != conversationGeneration) return@withContext
                    messages.clear()
                    messages.addAll(cachedList)
                    messages.addAll(mergeOptimistic(cachedList))
                    isLoading = false
                    loadStatus = null
                    recordFirstPaintIfNeeded()
                }
                // Cached copy was already fresh → nothing more to do. BUT the
                // pager must still exist, or scroll-up history and tail refresh
                // silently degrade on every cache-hit re-open.
                if (!stale.second) {
                    if (gen == conversationGeneration) {
                        pager = com.autonomousone.messages.repository.ThreadPager(
                            getApplication(),
                            if (cacheKeyThread != 0L) cacheKeyThread else currentThreadId,
                            phone.ifBlank { currentPhone }
                        )
                    }
                    markReadAndNotify(targetOf(cacheKeyThread, phone), phoneIfBlank(phone))
                    return@launch
                }
            } else {
                // No cache: only show a spinner if the (windowed, ≤2×12 row)
                // read actually takes long enough for a human to notice.
                // Below that the screen goes straight from nothing to messages.
                val spinnerJob = launch {
                    delay(120)
                    withContext(Dispatchers.Main) { isLoading = true }
                }
                spinnerGuard = spinnerJob
            }

            try {
                // Windowed loading means there is no long-running scan anymore;
                // the pager reads ≤24 rows on open (older pages are user-
                // initiated). No progress UI is needed.
                val loadedMessages = when {
                    currentPhone.isNotBlank() || threadId != 0L -> {
                        // Windowed load: newest page only (Google Messages-style).
                        val p = ThreadPager(
                            getApplication(),
                            if (currentThreadId != 0L) currentThreadId else threadId,
                            currentPhone.ifBlank { phone }
                        )
                        pager = p
                        val firstPage = p.loadLatest()
                        // Merge any optimistic sends already queued this session.
                        canonicalize(firstPage, mergeOptimistic(firstPage))
                    }
                    else -> emptyList()
                }

                val targetThreadId = if (currentThreadId != 0L) currentThreadId else loadedMessages.lastOrNull()?.threadId ?: 0L
                val targetPhone = if (currentPhone.isNotBlank()) currentPhone else loadedMessages.firstOrNull()?.sender ?: ""

                if (targetThreadId != 0L || targetPhone.isNotBlank()) {
                    // Unified read path: local Room transaction first, the
                    // provider write is eventual persistence.
                    markReadUseCase.markRead(targetThreadId, targetPhone)
                }

                val readMessages = loadedMessages.map { it.copy(unread = false) }

                // Stale-result guard: if the user has since opened another
                // conversation, this result is obsolete — drop it silently.
                val stillCurrent = gen == conversationGeneration &&
                        currentThreadId == myThread &&
                        (myPhone.isBlank() || currentPhone == myPhone)
                if (!stillCurrent) {
                    Log.d("CONV_VM", "Dropping stale load for thread=$myThread (now on $currentThreadId)")
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    if (gen != conversationGeneration) return@withContext
                    messages.clear()
                    messages.addAll(readMessages)
                    // Keep unconfirmed optimistic sends visible until the provider reports them.
                    messages.addAll(mergeOptimistic(readMessages))
                    if (readMessages.isNotEmpty()) {
                        if (currentThreadId == 0L) currentThreadId = readMessages.last().threadId
                        if (currentPhone.isBlank()) {
                            val sampleMsg = readMessages.firstOrNull { it.type == 1 }
                                ?: readMessages.first()
                            currentPhone = sampleMsg.sender
                            SmsEventBus.activeConversationPhone = currentPhone
                        }
                        // Phone-only pager: once the real thread id is known,
                        // rebuild the pager on it so loadNewerSince/loadOlder/
                        // loadNewer query the resolved thread, not THREAD_ID = 0.
                        val resolvedThreadId = readMessages.last().threadId
                        if (myThread == 0L && resolvedThreadId != 0L) {
                            pager = ThreadPager(
                                getApplication(), resolvedThreadId, currentPhone
                            ).also { it.loadLatest() } // align consumed cursors
                        }
                    }
                    // Push the read state into the Home list immediately via
                    // the shared event bus (no ViewModel-to-ViewModel coupling).
                    if (currentThreadId != 0L || currentPhone.isNotBlank()) {
                        SmsEventBus.emitThreadRead(currentThreadId, currentPhone)
                    }
                    recordFirstPaintIfNeeded()
                }
                // Store for instant re-open.
                ThreadMessageCache.put(targetThreadId, targetPhone, loadedMessages)
            } finally {
                // Generation check: a superseded load must not cancel the
                // spinner of the NEW conversation's load (shared guard bug).
                if (gen == conversationGeneration) {
                    spinnerGuard?.cancel()
                    spinnerGuard = null
                    withContext(Dispatchers.Main) {
                        isLoading = false
                        loadStatus = null
                    }
                }
            }
        }
    }

    /** Cancels the delayed "show spinner" job when the load beat it. */
    private var spinnerGuard: kotlinx.coroutines.Job? = null

    /**
     * Read-cutover latch (mirrors HomeViewModel): once both sources are fully
     * backfilled, Room may serve the instant-open paint when the in-memory
     * thread cache has no copy (fresh process).
     */
    @Volatile
    private var roomReadEnabled = false

    fun setPhone(phone: String) {
        currentPhone = phone
        SmsEventBus.activeConversationPhone = phone
        if (phone.isNotBlank()) {
            loadConversation(currentThreadId, phone)
        }
    }

    /** Cache-key helpers for the stale-while-revalidate path. */
    private fun targetOf(threadKey: Long, phone: String): Long =
        if (threadKey != 0L) threadKey else currentThreadId

    private fun phoneIfBlank(phone: String): String =
        if (phone.isNotBlank()) phone else currentPhone

    // ════════════════════════════════════════════════════════════════════════
    // v3.4.0 FEATURE 1 — SEARCH INSIDE THIS CONVERSATION
    //
    // Isolation contract (why the search surface is a SEPARATE list):
    //  • `messages` (the conversation window) is NEVER mutated by search;
    //  • `searchJumpWindow` is the bounded window painted while search mode is
    //    on, so jumping to a hit does not have to page `messages` to it and
    //    does not destroy the reader's position;
    //  • leaving search drops the search window and the LazyColumn falls back
    //    to `messages` exactly as it was when search opened.
    // ════════════════════════════════════════════════════════════════════════

    /** The search repository — one FTS index (`messages_fts`), no second copy. */
    private val conversationSearch: InConversationSearchRepository by lazy {
        InConversationSearchRepository.create(getApplication())
    }

    /** Raw query typed by the user (already debounced downstream). */
    var searchQuery by mutableStateOf("")
        private set

    /** Loading / Content / Empty / Error for the search surface. */
    var searchState: ConversationSearchState by mutableStateOf(ConversationSearchState.Idle)
        private set

    /** True while the search TOP BAR owns the conversation header. */
    var isSearchActive by mutableStateOf(false)
        private set

    /**
     * Position in the FULL result set (not just the loaded page) for the
     * "3 of 14" counter. -1 when there is nothing to point at.
     */
    var searchIndex by mutableIntStateOf(ConversationSearchNavigation.NO_INDEX)
        private set

    /** Total active matches for the current query (counter denominator). */
    var searchTotal by mutableIntStateOf(0)
        private set

    /**
     * The bounded window painted while the user is inside a search hit. Null in
     * normal mode → the screen paints `messages`.
     */
    var searchJumpWindow by mutableStateOf<SearchJumpWindow?>(null)
        private set

    /**
     * Monotonic search-session revision. Incremented by every query/keyboard
     * change and by (re)entering search mode, and the key of the screen's
     * debounced search effect — so a keystroke re-arms the pipeline while an
     * unrelated recomposition does not.
     */
    var searchRevision by mutableIntStateOf(0)
        private set

    /** True while a debounced query is in flight (drives the progress state). */
    var isSearchLoading by mutableStateOf(false)
        private set

    /** Every hit loaded so far (bounded pages), newest-first like the DAO. */
    private var searchHits: List<ConversationSearchHit> = emptyList()

    private var searchExhausted = false
    private var searchLoadMoreJob: kotlinx.coroutines.Job? = null

    /** Enter search mode. The conversation window is left untouched. */
    fun openSearch() {
        if (isSearchActive) return
        isSearchActive = true
        searchRevision++
        DiagnosticLog.event(
            "CONV_SEARCH",
            "open thread=$currentThreadId index=$searchIndex total=$searchTotal"
        )
    }

    /**
     * Leave search mode. Drops every search artefact and repaints the ORIGINAL
     * conversation window from `messages` — closing search must not disturb it.
     */
    fun closeSearch() {
        if (!isSearchActive) return
        isSearchActive = false
        searchLoadMoreJob?.cancel()
        searchLoadMoreJob = null
        clearSearchResults()
        DiagnosticLog.event("CONV_SEARCH", "close thread=$currentThreadId")
    }

    /**
     * Drop the search surface without emitting diagnostics. Called from
     * closeSearch and from a conversation switch — a result set belongs to the
     * thread it was queried in and must never leak into another one.
     */
    private fun clearSearchResults() {
        searchQuery = ""
        searchHits = emptyList()
        searchExhausted = false
        searchTotal = 0
        searchIndex = ConversationSearchNavigation.NO_INDEX
        searchJumpWindow = null
        searchState = ConversationSearchState.Idle
        isSearchLoading = false
        searchRevision++
    }

    /**
     * One keystroke. Nothing is queried here — the value is debounced by the
     * screen's search effect, so typing in a 100K-message thread stays free.
     */
    fun onQueryChange(q: String) {
        if (searchQuery == q) return
        searchQuery = q
        searchRevision++
    }

    /**
     * Re-run the CURRENT query after an Error. Nothing about the query changed,
     * so the revision is bumped to re-arm the debounced pipeline; the retry is
     * therefore debounced exactly like a keystroke.
     */
    fun retrySearch() {
        if (!isSearchActive) return
        DiagnosticLog.event(
            "CONV_SEARCH",
            "retry thread=$currentThreadId query=" +
                InConversationSearchRepository.queryToken(searchQuery)
        )
        searchRevision++
    }

    /**
     * The query pipeline: debounce, then ONE search.
     *
     * `debounceMillis` is the quiet period that must elapse after the last
     * keystroke, enforced by the repository's injectable-delay flow (so the
     * policy is unit-testable without wall-clock waiting). The screen's
     * `LaunchedEffect(searchRevision)` cancels this coroutine the instant the
     * query changes again, so a cancelled pipeline never publishes anything —
     * that cancellation is the debounce, not a second timer.
     *
     * Every settled query replaces the result page and re-anchors the jump
     * window onto its first hit, so the arrows and the message view can never
     * disagree.
     */
    suspend fun runSearchPipeline(
        debounceMillis: Long = SearchDebounce.DEBOUNCE_MS
    ) {
        if (!isSearchActive) return
        val threadId = currentThreadId
        if (threadId <= 0L) return
        val gen = conversationGeneration
        val raw = searchQuery
        if (!SearchDebounce.isExecutable(raw)) {
            // A one-character query never reaches FTS: it would match the whole
            // thread. The surface returns to Idle rather than showing results.
            if (searchState !is ConversationSearchState.Idle) clearSearchResults()
            return
        }

        // The viewModelScope runs on Main, so this write is part of the same
        // uninterrupted block as the await below: a later keystroke cannot see
        // a half-published state.
        isSearchLoading = true

        val outcome = try {
            conversationSearch.outcomes(
                threadId = threadId,
                queryFlow = kotlinx.coroutines.flow.flowOf(raw),
                debounceMillis = debounceMillis
            ).first()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // The user kept typing (or left search): the pipeline is superseded.
            // Reset the progress flag and publish NOTHING — a cancelled query
            // must never flash stale results.
            isSearchLoading = false
            throw cancelled
        } catch (failure: Exception) {
            DiagnosticLog.event(
                InConversationSearchRepository.LOG_CATEGORY,
                "search failed thread=$threadId query=" +
                    InConversationSearchRepository.queryToken(raw),
                failure
            )
            withContext(Dispatchers.Main) {
                isSearchLoading = false
                if (threadId == currentThreadId && gen == conversationGeneration) {
                    searchState = ConversationSearchState.Error(
                        getApplication<Application>().getString(R.string.conv_search_failed)
                    )
                }
            }
            return
        }

        withContext(Dispatchers.Main) {
            isSearchLoading = false
            if (threadId != currentThreadId || gen != conversationGeneration) return@withContext
            if (raw != searchQuery) return@withContext
            when (outcome) {
                is SearchOutcome.Skipped -> clearSearchResults()
                is SearchOutcome.Completed -> {
                    val page = outcome.page
                    searchHits = page.hits
                    searchTotal = page.total
                    searchExhausted = page.hits.size < InConversationSearchRepository.MAX_PAGE
                    searchIndex = ConversationSearchNavigation.initial(page.total)
                    searchState = if (page.total <= 0 || page.hits.isEmpty()) {
                        ConversationSearchState.Empty(outcome.query)
                    } else {
                        ConversationSearchState.Content(
                            query = outcome.query,
                            hits = page.hits,
                            total = page.total,
                            loadingMore = false
                        )
                    }
                    // A fresh result set re-anchors the message view onto the
                    // first hit, so arrows and content never disagree.
                    if (page.hits.isNotEmpty()) jumpToIndex(searchIndex) else searchJumpWindow = null
                }
            }
        }
    }

    /**
     * Append the next bounded page. Called by the results list as it nears its
     * end; a no-op once the set is exhausted or a page is already in flight.
     */
    fun loadMoreSearchResults() {
        val query = searchQuery
        if (query.length < SearchDebounce.MIN_QUERY_LENGTH || searchExhausted) return
        if (searchLoadMoreJob?.isActive == true) return
        val current = searchState
        if (current !is ConversationSearchState.Content) return
        val loaded = searchHits.size
        if (loaded <= 0) return

        searchState = current.copy(loadingMore = true)
        val threadId = currentThreadId
        val gen = conversationGeneration
        searchLoadMoreJob = viewModelScope.launch(Dispatchers.IO + crashGuard("conversationSearchMore")) {
            val outcome = runCatching {
                conversationSearch.loadMore(threadId, query, offset = loaded)
            }.getOrNull()
            withContext(Dispatchers.Main) {
                if (threadId != currentThreadId || gen != conversationGeneration) return@withContext
                if (query != searchQuery) return@withContext
                val latest = searchState
                if (latest !is ConversationSearchState.Content) return@withContext
                val page = (outcome as? SearchOutcome.Completed)?.page
                if (page == null || page.hits.isEmpty()) {
                    searchExhausted = true
                    searchState = latest.copy(loadingMore = false)
                    return@withContext
                }
                // The FTS index can change between pages (a message arrives, a
                // row is trashed), so the merge is by composite identity.
                val merged = (searchHits + page.hits)
                    .distinctBy { MessageIdentity.Key(it.source, it.providerId) }
                searchHits = merged
                searchExhausted = page.hits.size < InConversationSearchRepository.MAX_PAGE
                searchState = latest.copy(
                    hits = merged,
                    loadingMore = false,
                    total = maxOf(latest.total, page.total)
                )
            }
        }
    }

    /**
     * ↓ — next hit. Rendered DISABLED (not called) when there is exactly one
     * hit; with several, navigation WRAPS (see [ConversationSearchNavigation]).
     */
    fun nextHit() {
        moveSelection { index, total -> ConversationSearchNavigation.next(index, total) }
    }

    /** ↑ — previous hit, same wrap rule. */
    fun previousHit() {
        moveSelection { index, total -> ConversationSearchNavigation.previous(index, total) }
    }

    private fun moveSelection(step: (Int, Int) -> Int) {
        val total = searchTotal
        if (total <= 0) return
        val target = step(searchIndex, total)
        if (target < 0) return
        searchIndex = target
        DiagnosticLog.event(
            "CONV_SEARCH",
            "navigate thread=$currentThreadId index=${ConversationSearchNavigation.displayOrdinal(target, total)} " +
                "total=$total"
        )
        // A page that has not been fetched yet is pulled first; the selection
        // is applied as soon as it lands (loadMore keeps searchIndex).
        if (target >= searchHits.size && !searchExhausted) {
            loadMoreSearchResults()
            return
        }
        jumpToIndex(target)
    }

    /** Select + scroll to the hit at [index] of the loaded page. */
    private fun jumpToIndex(index: Int) {
        val hit = searchHits.getOrNull(index) ?: return
        jumpToMessage(hit.source, hit.providerId)
    }

    /**
     * Jump to ONE result by exact composite identity.
     *
     * Returns the bounded window around the message and emits a
     * [ConversationScrollCommand.SearchHit] carrying `(source, providerId)` so
     * SMS 100 and MMS 100 can never be confused on the way to the list. The
     * conversation window in [messages] is NOT touched.
     */
    fun jumpToMessage(source: String, providerId: Long) {
        val key = MessageIdentity.Key(source, providerId)
        val threadId = currentThreadId
        val gen = conversationGeneration
        viewModelScope.launch(Dispatchers.IO + crashGuard("conversationSearchJump")) {
            val window = conversationSearch.jumpToMessage(source, providerId, threadId)
            withContext(Dispatchers.Main) {
                // Stale-result guard, same shape as loadConversation's: the
                // conversation may have been switched while the window loaded.
                // A phone-only open (threadId 0) is NOT stale just because the
                // real thread id was resolved meanwhile.
                if (threadId != 0L && threadId != currentThreadId) return@withContext
                if (gen != conversationGeneration) return@withContext
                if (window == null) {
                    // The hit was trashed (or repaired away) between the query
                    // and the tap. Drop it from the page instead of scrolling
                    // into history that no longer exists.
                    val remaining = searchHits.filterNot {
                        MessageIdentity.Key(it.source, it.providerId) == key
                    }
                    searchHits = remaining
                    searchTotal = maxOf(0, searchTotal - 1)
                    searchState = (searchState as? ConversationSearchState.Content)?.let { content ->
                        if (remaining.isEmpty()) ConversationSearchState.Empty(content.query)
                        else content.copy(hits = remaining, total = maxOf(remaining.size, searchTotal))
                    } ?: searchState
                    if (searchIndex >= remaining.size) {
                        searchIndex = ConversationSearchNavigation.initial(remaining.size)
                    }
                    return@withContext
                }
                searchJumpWindow = window
                _scrollCommands.tryEmit(ConversationScrollCommand.SearchHit(key))
            }
        }
    }

    private fun markReadAndNotify(threadId: Long, phone: String) {
        if (threadId == 0L && phone.isBlank()) return
        // Read state is a UI-level overlay (Home badge); do NOT invalidate the
        // thread cache for it — messages themselves didn't change, and
        // invalidating here is what causes "Reading messages…" on re-open.
        //
        // The use case owns the order: local Room read FIRST (immediate), the
        // optimistic SmsEventBus signal second, the provider ContentResolver
        // write last (eventual, off the UI thread).
        viewModelScope.launch(Dispatchers.IO) {
            markReadUseCase.markRead(threadId, phone)
        }
    }

    private fun observeIncomingSms() {
        viewModelScope.launch {
            SmsEventBus.incomingSmsFlow.collect { incomingSms ->
                if (currentPhone.isBlank() && currentThreadId == 0L) return@collect

                val isMatch = ContactRepository.sameConversation(incomingSms.sender, currentPhone)

                if (isMatch) {
                    val readIncoming = incomingSms.copy(unread = false)
                    appendLiveMessage(readIncoming, source = "incoming")
                    // An already-open conversation receiving an incoming
                    // message takes the SAME unified read path as an open.
                    val thread = currentThreadId
                    val phone = currentPhone
                    viewModelScope.launch(Dispatchers.IO) {
                        markReadUseCase.markRead(thread, phone)
                    }
                }
            }
        }
    }

    /** Reload from DB whenever MainActivity.onResume fires */
    private fun observeRefreshSignal() {
        viewModelScope.launch {
            SmsEventBus.refreshFlow.collect {
                if (currentPhone.isNotBlank() || currentThreadId != 0L) {
                    refresh()
                }
            }
        }
    }

    fun refresh() {
        // Re-entrant: a provider burst (multipart SMS, MMS parts) must never be
        // swallowed. If a refresh is already running we mark it dirty and run
        // exactly one more pass when it finishes — no dropped final update.
        if (isRefreshing) {
            refreshRequestedAgain = true
            return
        }
        isRefreshing = true
        viewModelScope.launch(Dispatchers.IO + crashGuard("refresh")) {
            try {
                do {
                    refreshRequestedAgain = false
                    refreshOnce()
                } while (refreshRequestedAgain)
            } finally {
                withContext(Dispatchers.Main) { isRefreshing = false }
            }
        }
    }

    @Volatile
    private var refreshRequestedAgain = false

    /** One merge-based reconcile pass. */
    private suspend fun refreshOnce() {
        // ── MERGE-based refresh (never replaces the visible window):
        // 1. cheap tail query for rows newer than the newest we show;
        // 2. fold them into the list with ThreadMerge (dedup by id / body+time,
        //    optimistic rows collapse into confirmed ones).
        // History already on screen NEVER disappears or changes shape.
        val newestShown = messages.maxOfOrNull { it.date } ?: 0L

        // WINDOWED foreground refresh — never a whole-conversation provider
        // read. Every path is bounded:
        //   • no window yet → one newest keyset page (≤ 2 × 12 rows);
        //   • otherwise     → rows strictly newer than the newest we show.
        // The whole-thread getMessagesByThread/getMessagesByPhone fallbacks are
        // gone: a 100 000-message thread is never read to paint a tail.
        val activePager = pager ?: ensurePager()
        val tail: List<Sms> = when {
            activePager == null -> emptyList()
            messages.isEmpty() -> activePager.loadLatest()
            else -> activePager.loadNewerSince(newestShown)
        }
        val statusRows = activePager?.loadSmsRowsById(
            messages.asSequence()
                .filter { it.type == 2 }
                .map { it.id }
                .toList()
        ).orEmpty()

        if (currentThreadId != 0L || currentPhone.isNotBlank()) {
            markReadUseCase.markRead(currentThreadId, currentPhone)
        }

        withContext(Dispatchers.Main) {
            val merged = ThreadMerge.mergeTail(
                messages.toList(), (tail + statusRows).map { it.copy(unread = false) }
            )
            messages.clear()
            messages.addAll(merged)
            if (currentThreadId == 0L && messages.isNotEmpty()) {
                currentThreadId = messages.last().threadId
            }
            // Keep the instant-open cache in step with what is on screen —
            // but ONLY in LATEST mode with the NORMAL conversation window. The
            // cache exists so the next open paints the newest window; storing an
            // OLDEST-boundary history window would reopen this chat years in the
            // past, and storing a SEARCH-jump window would reopen it on the
            // handful of messages around a search hit.
            if (windowMode == ConversationWindowMode.LATEST && searchJumpWindow == null) {
                ThreadMessageCache.put(currentThreadId, currentPhone, merged)
            }
        }
    }

    /** True while a pull-to-refresh / observer refresh round-trip is in flight. */
    var isRefreshing by mutableStateOf(false)
        private set

    /**
     * Outgoing sends fired from THIS screen while it is open are appended via
     * sendMessage's optimistic path; this collector covers sends that were
     * persisted elsewhere (e.g. quick-reply from a notification) so an open
     * chat still shows them immediately.
     */
    private fun observeOutgoingSent() {
        viewModelScope.launch {
            SmsEventBus.outgoingSentFlow.collect { sent ->
                val sameThread = sent.threadId != 0L && sent.threadId == currentThreadId
                if (!sameThread &&
                    !ContactRepository.sameConversation(sent.phone, currentPhone)
                ) return@collect
                val normSent = ContactRepository.normalizePhone(sent.phone)
                val row = Sms(
                    id = MessageIdentity.outgoingEventId(sent.providerRowId, sent.date),
                    threadId = sent.threadId.takeIf { it != 0L } ?: currentThreadId,
                    sender = normSent,
                    message = sent.message, date = sent.date, unread = false, type = 2
                )
                appendLiveMessage(row, source = "outgoing-event")
            }
        }
    }

    /**
     * Returns optimistic sent messages not yet present in [persisted] and prunes
     * the ones that have now been confirmed. Matching is by message text plus
     * timestamp proximity because the optimistic row uses a synthetic id.
     */
    private fun mergeOptimistic(persisted: List<Sms>): List<Sms> {
        if (optimisticMessages.isEmpty()) return emptyList()
        val remaining = optimisticMessages.filter { opt ->
            persisted.none {
                it.type == opt.type &&
                    it.message == opt.message &&
                    Math.abs(it.date - opt.date) < 5000L
            }
        }
        optimisticMessages.clear()
        optimisticMessages.addAll(remaining)
        return remaining.sortedBy { it.date }
    }

    fun sendMessage(threadId: Long, phone: String, message: String) {
        sendMessage(threadId, phone, message, subscriptionOverride = null)
    }

    /**
     * Sends with an optional per-call SIM override (from the in-chat SIM
     * switcher). `null` → the user's global Messaging preference applies.
     */
    fun sendMessage(threadId: Long, phone: String, message: String, subscriptionOverride: Int?) {
        val trimmedMsg = message.trim()
        if (trimmedMsg.isBlank()) return

        // Strip spaces/dashes the user may have pasted ("+98 991 716 6454")
        // so telephony always receives a clean dialable number.
        val targetPhone = ContactRepository.normalizePhone(
            if (phone.isNotBlank()) phone else currentPhone
        )
        if (targetPhone.isBlank()) return

        currentPhone = targetPhone
        SmsEventBus.activeConversationPhone = targetPhone
        if (threadId != 0L) currentThreadId = threadId

        val now = System.currentTimeMillis()

        // Optimistic UI update with a temporary ID
        val optimisticId = now
        val optimisticSms = Sms(
            id = optimisticId,
            threadId = currentThreadId,
            sender = targetPhone,
            message = trimmedMsg,
            date = now,
            unread = false,
            type = 2,
            status = android.provider.Telephony.Sms.STATUS_PENDING
        )
        markForEntryAnimation(optimisticSms.id)
        messages.add(optimisticSms)
        optimisticMessages.add(optimisticSms)
        // Our own write: append to the cached thread instead of invalidating it,
        // so the next open paints cache instantly (incl. this message) and the
        // background provider refresh confirms/normalizes it.
        ThreadMessageCache.append(currentThreadId, targetPhone, optimisticSms)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val recipients = splitRecipients(targetPhone)
                when {
                    // ── v3.4.0 FEATURE 11: the delay gate ────────────────────
                    // A plain text composer send is the ONLY thing Undo Send may
                    // hold back. With the setting OFF (the default) this returns
                    // null and the code below runs exactly as it did in v3.3.6.
                    // Group sends and attachments stay immediate: a held group
                    // would need per-recipient undo semantics, and this feature
                    // does not have them.
                    recipients.size == 1 && routeComposerSend(
                        phone = recipients.first(),
                        body = trimmedMsg,
                        threadId = currentThreadId,
                        subscriptionId = subscriptionOverride
                    ) != null -> {
                        // Held: the durable ledger owns the message now, and its
                        // worker will send it. Nothing is sent from here.
                        com.autonomousone.messages.utils.DiagnosticLog.event(
                            "SEND_DELAY",
                            "composer-held thread=$currentThreadId token=" +
                                com.autonomousone.messages.utils.DiagnosticLog
                                    .phoneToken(recipients.first())
                        )
                    }
                    // Google Messages-style group chat: ONE group MMS instead of N SMS.
                    recipients.size > 1 &&
                            MessagingPreferences(getApplication()).groupMessagingEnabled -> {
                        mmsSender.sendGroupText(recipients, trimmedMsg)
                    }
                    // Group toggle off → classic behaviour: one SMS per recipient.
                    recipients.size > 1 -> recipients.forEach {
                        smsSender.send(it, trimmedMsg, subscriptionOverride, null)
                    }
                    else -> {
                        val persistedId = smsSender.send(
                            recipients.first(), trimmedMsg, subscriptionOverride, null
                        )
                        persistedSentIds.add(persistedId)
                    }
                }
            } catch (e: Exception) {
                // A send failure must never disappear into Logcat. The
                // optimistic bubble is already on screen, so flip it to the
                // durable Failed state the user can see and retry instead of
                // leaving an eternal "Sending...". A synchronous rejection
                // means the submit never reached the radio.
                android.util.Log.e("SMS_SEND", "outgoing send failed", e)
                com.autonomousone.messages.utils.DiagnosticLog.event(
                    "SMS_SEND",
                    "optimistic-send-failed code=DISPATCH_REJECTED"
                )
                val failed = optimisticSms.copy(
                    status = android.provider.Telephony.Sms.STATUS_FAILED
                )
                val index = messages.indexOfFirst { it.id == optimisticSms.id }
                if (index >= 0) messages[index] = failed
                val optimisticIndex = optimisticMessages.indexOfFirst { it.id == optimisticSms.id }
                if (optimisticIndex >= 0) optimisticMessages[optimisticIndex] = failed
            }
        }
    }

    /** Splits "a, b; c" recipient strings coming from group selection UI. */
    private fun splitRecipients(raw: String): List<String> =
        raw.split(',', ';')
            .map { ContactRepository.normalizePhone(it.trim()) }
            .filter { it.isNotBlank() }

    // ════════════════════════════════════════════════════════════════════════
    // v3.4.0 FEATURE 11 — Send delay / Undo Send
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Routes ONE already-composed message through the delay gate.
     *
     * Returns the durable intent id when the message was HELD (the caller must
     * not treat it as delivered), or null when it must go out through the
     * unchanged direct path.
     *
     * ── Why this is a bounded blocking call ──────────────────────────────────
     * This is the moment the user's tap must be answered (composer cleared,
     * Snackbar with UNDO shown), and the answer depends on ONE durable decision:
     * insert a single small row into a work-queue table. The call is bounded by
     * [HOLD_DECISION_TIMEOUT_MILLIS] so a wedged database can never freeze the
     * composer; on timeout or failure the caller falls back to the immediate
     * send, which means a message is never lost to a ledger problem. No provider
     * read, no scan and no Room query is performed here — only the one INSERT.
     */
    private fun routeComposerSend(
        phone: String,
        body: String,
        threadId: Long,
        subscriptionId: Int?
    ): String? = runCatching {
        kotlinx.coroutines.runBlocking {
            withTimeoutOrNull(HOLD_DECISION_TIMEOUT_MILLIS) {
                delayedSend.send(
                    phone = phone,
                    body = body,
                    threadId = threadId,
                    subscriptionId = subscriptionId,
                    source = com.autonomousone.messages.sms.SendSource.COMPOSER
                )
            }
        }
    }.fold(
        onSuccess = { result ->
            if (result is com.autonomousone.messages.messaging.SendResult.DelayedSend) {
                _delayedSendStarted.tryEmit(
                    DelayedSendNotice(
                        intentId = result.row.intentId,
                        seconds = result.delaySeconds,
                        body = result.row.body
                    )
                )
                result.row.intentId
            } else {
                // Immediate (delay OFF), ignored, or the bounded wait expired.
                null
            }
        },
        onFailure = { error ->
            // A ledger/timer failure must never silently drop a message the user
            // just typed: fall back to the immediate send.
            com.autonomousone.messages.utils.DiagnosticLog.event(
                "SEND_DELAY",
                "hold-failed fallback=immediate token=" +
                    com.autonomousone.messages.utils.DiagnosticLog.phoneToken(phone),
                error
            )
            null
        }
    )

    /**
     * UNDO the pending message [intentId].
     *
     * Returns the restored text so the caller can put it back in the composer, or
     * null when it was too late (the deadline was already claimed) — in which
     * case the message IS on its way and the UI must say so rather than pretend
     * the undo worked.
     *
     * Like [routeComposerSend] this is bounded and blocking: it is one
     * compare-and-set the user is watching.
     */
    fun undoDelayedSend(intentId: String): String? = runCatching {
        kotlinx.coroutines.runBlocking {
            withTimeoutOrNull(HOLD_DECISION_TIMEOUT_MILLIS) { delayedSend.undo(intentId) }
        }
    }.getOrNull().let { outcome ->
        when (outcome) {
            is com.autonomousone.messages.sms.DelayedSendStateMachine.Undo.Cancelled -> {
                removePendingDelayedBubble(intentId)
                outcome.body
            }
            // Too late, or the bounded wait expired: the bubble stays exactly as
            // the durable ledger describes it.
            else -> null
        }
    }

    /**
     * Mirrors the OPEN conversation's live ledger rows into the bubble list.
     *
     * A row observed in PENDING becomes a bubble with the never-reached
     * `STATUS_DELAYED_PENDING` sentinel, so the UI can render a clock and
     * "Sending…" WITHOUT the screen having to know about the ledger. The bubble
     * leaves on the ordinary path: once the send really happens its provider row
     * is merged in, and [mergeOptimistic] prunes the optimistic copy.
     */
    private fun observePendingDelayedSends(threadId: Long, gen: Long) {
        pendingDelayedJob?.cancel()
        pendingDelayedJob = null
        if (threadId <= 0L) return
        pendingDelayedJob = viewModelScope.launch(Dispatchers.IO + crashGuard("pendingDelayed")) {
            delayedSend.observeLive(threadId).collect { rows ->
                val pending = rows.filter {
                    it.state == com.autonomousone.messages.sms.DelayedSendState.PENDING
                }
                withContext(Dispatchers.Main) {
                    if (gen != conversationGeneration) return@withContext
                    applyPendingDelayedRows(threadId, pending)
                }
            }
        }
    }

    /** Main-thread projection of the ledger onto the visible bubble list. */
    private fun applyPendingDelayedRows(
        threadId: Long,
        pending: List<com.autonomousone.messages.sms.PendingDelayedSend>
    ) {
        val wanted = pending.associateBy { pendingDelayedBubbleId(it.intentId) }

        // Drop bubbles whose intent is gone (claimed, undone, failed, pruned).
        pendingDelayedScanIds.removeAll { id ->
            val keep = wanted.containsKey(id)
            if (!keep) {
                val index = messages.indexOfFirst { it.id == id }
                if (index >= 0) messages.removeAt(index)
                optimisticMessages.removeAll { it.id == id }
            }
            !keep
        }

        // Add a bubble for every newly-durable intent, oldest first so the
        // canonical order of the list is preserved by the sort below.
        wanted.forEach { (id, row) ->
            if (pendingDelayedScanIds.contains(id)) return@forEach
            val bubble = Sms(
                id = id,
                threadId = threadId,
                sender = currentPhone,
                message = row.body,
                date = row.dueAt,
                unread = false,
                type = 2,
                status = STATUS_DELAYED_PENDING
            )
            pendingDelayedScanIds.add(id)
            markForEntryAnimation(bubble.id)
            messages.add(bubble)
            optimisticMessages.add(bubble)
        }
        messages.sortWith(chronologicalOrder)
    }

    /**
     * Deterministic synthetic id for a pending bubble.
     *
     * Derived from the intent id so the same intent always renders as the same
     * row, and the SAME derivation the sender uses for a real outgoing event —
     * so a bubble can never collide with a persisted provider row.
     */
    private fun pendingDelayedBubbleId(intentId: String): Long =
        MessageIdentity.outgoingEventId(
            providerRowId = intentId.hashCode().toLong(),
            fallbackDate = intentId.hashCode().toLong()
        )

    /** Removes a pending bubble immediately, without waiting for Room. */
    private fun removePendingDelayedBubble(intentId: String) {
        val id = pendingDelayedBubbleId(intentId)
        pendingDelayedScanIds.remove(id)
        val index = messages.indexOfFirst { it.id == id }
        if (index >= 0) messages.removeAt(index)
        optimisticMessages.removeAll { it.id == id }
    }

    fun sendImageMessage(threadId: Long, phone: String, imageUri: Uri, caption: String = "") {
        val targetPhone = if (phone.isNotBlank()) phone else currentPhone
        if (targetPhone.isBlank()) return

        currentPhone = targetPhone
        SmsEventBus.activeConversationPhone = targetPhone
        if (threadId != 0L) currentThreadId = threadId

        val now = System.currentTimeMillis()
        val trimmedCaption = caption.trim()
        val textBody = if (trimmedCaption.isNotBlank()) "[IMAGE:$imageUri]\n$trimmedCaption" else "[IMAGE:$imageUri]"

        val optimisticSms = Sms(
            id = now,
            threadId = currentThreadId,
            sender = targetPhone,
            message = textBody,
            date = now,
            unread = false,
            type = 2
        )
        markForEntryAnimation(optimisticSms.id)
        messages.add(optimisticSms)
        optimisticMessages.add(optimisticSms)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                mmsSender.sendImage(targetPhone, imageUri)
                // Home list: show the image thread on top instantly.
                com.autonomousone.messages.event.SmsEventBus.emitOutgoingSent(
                    threadId = currentThreadId,
                    phone = targetPhone,
                    message = if (trimmedCaption.isNotBlank()) "🖼 $trimmedCaption" else "🖼",
                    date = now
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun sendAudioMessage(threadId: Long, phone: String, audioUri: Uri, caption: String = "") {
        val targetPhone = if (phone.isNotBlank()) phone else currentPhone
        if (targetPhone.isBlank()) return

        currentPhone = targetPhone
        SmsEventBus.activeConversationPhone = targetPhone
        if (threadId != 0L) currentThreadId = threadId

        val now = System.currentTimeMillis()
        val trimmedCaption = caption.trim()
        val textBody = if (trimmedCaption.isNotBlank()) "[AUDIO:$audioUri]\n$trimmedCaption" else "[AUDIO:$audioUri]"

        val optimisticSms = Sms(
            id = now,
            threadId = currentThreadId,
            sender = targetPhone,
            message = textBody,
            date = now,
            unread = false,
            type = 2
        )
        markForEntryAnimation(optimisticSms.id)
        messages.add(optimisticSms)
        optimisticMessages.add(optimisticSms)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                mmsSender.sendAudio(targetPhone, audioUri)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FEATURE 9/10 — message multi-select + bulk actions (additive)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Message selection, keyed by the COMPOSITE identity
     * ([MessageIdentity.Key] = (source, providerId)) — never a raw id, because
     * SMS 100 and MMS 100 are different messages.
     *
     * The state lives here, not in the rendered list, so a Room tail emission or
     * a refresh cannot drop it; [reconcileMessageSelection] intersects it
     * explicitly when the window changes.
     */
    var messageSelection by mutableStateOf(SelectionState.idle<MessageIdentity.Key>())
        private set

    /** True while a bulk message action is in flight. */
    var bulkInProgress by mutableStateOf(false)
        private set

    /** One-shot bulk outcome for the screen's snackbar. */
    var bulkFeedback by mutableStateOf<BulkFeedback?>(null)
        private set

    /** True when every selected message is starred (Star/Unstar toggle). */
    var selectionAllStarred by mutableStateOf(false)
        private set

    private var selectionStarredJob: kotlinx.coroutines.Job? = null

    fun consumeBulkFeedback() {
        bulkFeedback = null
    }

    /** Long-press entry point. */
    fun enterMessageSelection(sms: Sms) {
        val key = MessageIdentity.keyOf(sms.id)
        if (key.providerId <= 0L) return
        messageSelection = messageSelection.start(key)
        refreshSelectionStarred()
    }

    fun toggleMessageSelection(sms: Sms) {
        val key = MessageIdentity.keyOf(sms.id)
        if (key.providerId <= 0L) return
        messageSelection = messageSelection.toggle(key)
        refreshSelectionStarred()
    }

    /** X in the selection top bar, BACK, or leaving the screen. */
    fun clearMessageSelection() {
        selectionStarredJob?.cancel()
        selectionStarredJob = null
        selectionAllStarred = false
        if (messageSelection.active) messageSelection = messageSelection.clear()
    }

    /**
     * Explicit reconciliation against the window that is now on screen. Merging
     * and paging replace the list, so the selection is intersected — a refresh
     * that still holds the selected rows keeps the selection intact.
     */
    fun reconcileMessageSelection(present: Set<MessageIdentity.Key>) {
        if (!messageSelection.active) return
        val next = messageSelection.afterListRefresh(present)
        if (next != messageSelection) {
            messageSelection = next
            refreshSelectionStarred()
        }
    }

    /** Star/unstar every selected message (composite identity preserved). */
    fun starSelection(starred: Boolean) {
        val keys = messageSelection.keys.mapTo(LinkedHashSet()) { it.toMessageKey() }
        if (keys.isEmpty() || bulkInProgress) return
        viewModelScope.launch {
            bulkInProgress = true
            try {
                val result = bulkActions.star(keys, starred)
                if (result.succeeded > 0) selectionAllStarred = starred
                bulkFeedback = BulkFeedback.from(result)
                if (!result.isCompleteFailure) messageSelection = messageSelection.clear()
            } finally {
                bulkInProgress = false
            }
        }
    }

    /**
     * Move the selection to Trash. The durable per-message state is written by
     * [BulkActionRepository]; the rows are ALSO dropped from the visible window
     * so the conversation matches the ACTIVE-UI rule immediately.
     */
    fun trashSelection() {
        val keys = messageSelection.keys
        if (keys.isEmpty() || bulkInProgress) return
        viewModelScope.launch {
            bulkInProgress = true
            try {
                val result = bulkActions.moveToTrash(keys.map { it.toMessageKey() })
                // A failed key stays selected so the user can retry it.
                val failed = result.failed.mapNotNull { failure ->
                    failure.source?.let { source ->
                        failure.providerId?.let { id -> MessageIdentity.Key(source, id) }
                    }
                }.toSet()
                val applied = keys - failed
                if (applied.isNotEmpty()) {
                    messages.removeAll { row -> MessageIdentity.keyOf(row.id) in applied }
                    if (windowMode == ConversationWindowMode.LATEST) {
                        // The instant-open cache must not resurrect a trashed row.
                        ThreadMessageCache.put(currentThreadId, currentPhone, messages.toList())
                    } else {
                        ThreadMessageCache.invalidateThread(currentThreadId)
                    }
                }
                bulkFeedback = BulkFeedback.from(result)
                if (!result.isCompleteFailure) {
                    messageSelection = messageSelection.clear()
                } else {
                    // Nothing applied: keep exactly the messages that failed.
                    messageSelection = SelectionState(failed, active = failed.isNotEmpty())
                }
            } finally {
                bulkInProgress = false
            }
        }
    }

    /**
     * The copy payload of the current selection: message bodies joined in the
     * canonical order of the visible window. Never logged, never persisted.
     */
    fun selectedText(): String {
        val keys = messageSelection.keys
        if (keys.isEmpty()) return ""
        return messages
            .filter { MessageIdentity.keyOf(it.id) in keys }
            .joinToString(separator = "\n\n") { it.message }
    }

    /** The single body of a one-message selection, for the Forward workflow. */
    fun selectedSingleText(): String? {
        val keys = messageSelection.keys
        if (keys.size != 1) return null
        return messages.firstOrNull { MessageIdentity.keyOf(it.id) in keys }?.message
    }

    /** Called by the screen after a successful copy. */
    fun onSelectionCopied(count: Int) {
        if (count > 0) messageSelection = messageSelection.clear()
    }

    /** Starred state of the CURRENT selection, refreshed off the composition path. */
    private fun refreshSelectionStarred() {
        selectionStarredJob?.cancel()
        val keys = messageSelection.keys
        if (keys.isEmpty()) {
            selectionAllStarred = false
            return
        }
        selectionStarredJob = viewModelScope.launch(Dispatchers.IO) {
            val all = keys.all { userState.isStarred(it.source, it.providerId) }
            withContext(Dispatchers.Main) {
                // Only publish if the selection has not moved on meanwhile.
                if (messageSelection.keys == keys) selectionAllStarred = all
            }
        }
    }

    override fun onCleared() {
        repository.unregisterObserver(observer)
        roomTailJob?.cancel()
        roomTailJob = null
        searchLoadMoreJob?.cancel()
        searchLoadMoreJob = null
        clearMessageSelection()
        // Leaving the conversation: the sync core must stop suppressing
        // unread for this thread (a fresh incoming message is unread again).
        if (currentThreadId > 0L) VisibleConversationTracker.onClosed(currentThreadId)
        SmsEventBus.activeConversationPhone = ""
        // Leaving this chat must reconcile the Home list deterministically:
        // chat → home never passes through Activity.onResume, so without this
        // the list could keep a pre-chat snapshot (stale snippet/badge).
        SmsEventBus.notifyResume()
        super.onCleared()
    }
}
