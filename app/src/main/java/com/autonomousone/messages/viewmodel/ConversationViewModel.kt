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
import com.autonomousone.messages.event.SmsEventBus
import com.autonomousone.messages.repository.ThreadMessageCache
import com.autonomousone.messages.repository.ThreadMerge
import com.autonomousone.messages.messaging.MessagingPreferences
import com.autonomousone.messages.mms.MmsSender
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.observer.SmsContentObserver
import com.autonomousone.messages.repository.ContactRepository
import com.autonomousone.messages.repository.ProgressListener
import com.autonomousone.messages.repository.SmsRepository
import com.autonomousone.messages.repository.ThreadPager
import com.autonomousone.messages.sms.SmsSender
import com.autonomousone.messages.utils.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        private const val ROOM_WINDOW = 20
    }

    private val repository = SmsRepository(application)
    private val smsSender = SmsSender(application)
    private val mmsSender = MmsSender(application)

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
            existing.id == row.id ||
                (existing.type == row.type &&
                    existing.message == row.message &&
                    kotlin.math.abs(existing.date - row.date) < 5000L)
        }
        if (duplicate) return false

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

    private val observer = SmsContentObserver { _uri ->
        // Conversation screen uses merge-based refresh (targeted tail query),
        // not full reload. The URI is not needed here because the pager's
        // loadNewerSince() already does a bounded query.
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
                    .distinctBy { it.id }
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
                    .distinctBy { it.id }
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
        (lists.asList().flatten()).distinctBy { it.id }.sortedWith(chronologicalOrder)

    fun loadConversation(threadId: Long, phone: String = "") {
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
        olderMessagesJob = null
        newerMessagesJob = null
        // Every OPEN is deterministic: the conversation starts at the LATEST
        // boundary regardless of where a previous visit ended.
        windowMode = ConversationWindowMode.LATEST
        pendingNewMessagesCount = 0
        conversationGeneration++
        val myThread = threadId
        val myPhone = currentPhone
        val gen = conversationGeneration

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
                            // UI is ALWAYS oldest→newest. Without this the
                            // bottom anchor lands on the OLDEST row of the
                            // window and the user must scroll by hand.
                            .sortedWith(chronologicalOrder)
                    }.getOrNull().orEmpty()
                    if (roomRows.isNotEmpty() && gen == conversationGeneration) {
                        withContext(Dispatchers.Main) {
                            if (gen != conversationGeneration) return@withContext
                            messages.clear()
                            messages.addAll(roomRows.map { it.copy(unread = false) })
                            messages.addAll(mergeOptimistic(messages.toList()))
                            isLoading = false
                            loadStatus = null
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
                    repository.markThreadAsRead(targetThreadId, targetPhone)
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

    private fun markReadAndNotify(threadId: Long, phone: String) {
        if (threadId != 0L || phone.isNotBlank()) {
            // Read state is a UI-level overlay (Home badge); do NOT invalidate
            // the thread cache for it — messages themselves didn't change, and
            // invalidating here is what causes "Reading messages…" on every
            // re-open of a conversation.
            repository.markThreadAsRead(threadId, phone)
            // Mirror the read state into the shadow so a Room-first read
            // (fresh process) doesn't resurrect stale unread badges.
            viewModelScope.launch(Dispatchers.IO) {
                kotlin.runCatching {
                    com.autonomousone.messages.data.TelephonySyncCoordinator
                        .get(getApplication()).markThreadReadInShadow(threadId)
                }
            }
            SmsEventBus.emitThreadRead(threadId, phone)
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
                    viewModelScope.launch(Dispatchers.IO) {
                        repository.markThreadAsRead(currentThreadId, currentPhone)
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

        val tail = when {
            currentThreadId != 0L && pager != null ->
                pager!!.loadNewerSince(newestShown)
            currentPhone.isNotBlank() -> repository.getMessagesByPhone(
                currentPhone, threadIdHint = currentThreadId
            )
            currentThreadId != 0L -> repository.getMessagesByThread(currentThreadId)
            else -> emptyList()
        }
        val statusRows = pager?.loadSmsRowsById(
            messages.asSequence()
                .filter { it.type == 2 }
                .map { it.id }
                .toList()
        ).orEmpty()

        if (currentThreadId != 0L || currentPhone.isNotBlank()) {
            repository.markThreadAsRead(currentThreadId, currentPhone)
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
            // but ONLY in LATEST mode. The cache exists so the next open
            // paints the newest window; storing an OLDEST-boundary history
            // window here would reopen this chat years in the past.
            if (windowMode == ConversationWindowMode.LATEST) {
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
                    id = sent.date,
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
                e.printStackTrace()
            }
        }
    }

    /** Splits "a, b; c" recipient strings coming from group selection UI. */
    private fun splitRecipients(raw: String): List<String> =
        raw.split(',', ';')
            .map { ContactRepository.normalizePhone(it.trim()) }
            .filter { it.isNotBlank() }

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

    override fun onCleared() {
        repository.unregisterObserver(observer)
        SmsEventBus.activeConversationPhone = ""
        // Leaving this chat must reconcile the Home list deterministically:
        // chat → home never passes through Activity.onResume, so without this
        // the list could keep a pre-chat snapshot (stale snippet/badge).
        SmsEventBus.notifyResume()
        super.onCleared()
    }
}
