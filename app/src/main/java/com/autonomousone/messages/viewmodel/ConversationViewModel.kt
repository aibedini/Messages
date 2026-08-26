package com.autonomousone.messages.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autonomousone.messages.R
import com.autonomousone.messages.event.SmsEventBus
import com.autonomousone.messages.repository.ThreadMessageCache
import com.autonomousone.messages.messaging.MessagingPreferences
import com.autonomousone.messages.mms.MmsSender
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.observer.SmsContentObserver
import com.autonomousone.messages.repository.ContactRepository
import com.autonomousone.messages.repository.ProgressListener
import com.autonomousone.messages.repository.SmsRepository
import com.autonomousone.messages.sms.SmsSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConversationViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val repository = SmsRepository(application)
    private val smsSender = SmsSender(application)
    private val mmsSender = MmsSender(application)

    val messages = mutableStateListOf<Sms>()

    var isLoading by mutableStateOf(false)
        private set

    var loadStatus by mutableStateOf<String?>(null)
        private set

    private var currentThreadId = 0L
    private var currentPhone = ""

    // Track IDs of sent messages we've persisted so we can match them during refresh
    private val persistedSentIds = mutableSetOf<Long>()

    // Optimistic sent rows not yet confirmed in the provider DB (kept visible on refresh).
    private val optimisticMessages = mutableListOf<Sms>()

    private val observer = SmsContentObserver {
        refresh()
    }

    init {
        repository.registerObserver(observer)
        observeIncomingSms()
        observeRefreshSignal()
        observeOutgoingSent()
    }

    // ── Windowed history (paged) ─────────────────────────────────────────────
    // Only the newest page is read on open; scrolling up pulls older pages.
    private var pager: com.autonomousone.messages.repository.ThreadPager? = null

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

    fun loadOlderMessages() {
        val p = pager ?: return
        if (!p.hasMore) return
        if (olderMessagesJob?.isActive == true) return
        val gen = conversationGeneration
        olderMessagesJob = viewModelScope.launch(Dispatchers.IO) {
            val older = p.loadOlder()
            // Screen moved to another conversation while the page was loading.
            if (gen != conversationGeneration || older.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                // Prepend only rows not already on screen (a refresh may have
                // widened the window since the pager counters were set).
                val merged = com.autonomousone.messages.repository.ThreadMerge.prependOlder(
                    messages.toList(), older.map { it.copy(unread = false) }
                )
                if (merged.size > messages.size) {
                    messages.clear()
                    messages.addAll(merged)
                }
            }
        }
    }

    private var olderMessagesJob: kotlinx.coroutines.Job? = null

    fun loadConversation(threadId: Long, phone: String = "") {
        currentThreadId = threadId
        if (phone.isNotBlank()) {
            currentPhone = phone
            SmsEventBus.activeConversationPhone = phone
        }

        // A slow load of the PREVIOUS conversation must never paint over this
        // one — cancel it and stamp this run with the thread it owns.
        conversationLoadJob?.cancel()
        olderMessagesJob?.cancel()
        olderMessagesJob = null
        conversationGeneration++
        val myThread = threadId
        val myPhone = currentPhone
        val gen = conversationGeneration

        conversationLoadJob = viewModelScope.launch(Dispatchers.IO) {
            // ── Stale-while-revalidate: paint the cached thread INSTANTLY
            // (Google Messages-style), then refresh from the provider.
            val cache = ThreadMessageCache
            val cacheKeyThread = if (threadId != 0L) threadId else 0L
            val stale = if (cacheKeyThread != 0L || phone.isNotBlank())
                cache.getStale(cacheKeyThread, phone.ifBlank { currentPhone }) else null

            if (stale != null && stale.first.isNotEmpty()) {
                val cachedList = stale.first.map { it.copy(unread = false) }
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
                // No cache: only show a spinner if the (windowed, ≤80 row) read
                // actually takes long enough for a human to notice. Below that
                // the screen goes straight from nothing to messages.
                val spinnerJob = launch {
                    delay(120)
                    withContext(Dispatchers.Main) { isLoading = true }
                }
                spinnerGuard = spinnerJob
            }

            try {
                // Windowed loading means there is no long-running scan anymore;
                // the pager reads ≤80 rows per page. No progress UI is needed.
                val loadedMessages = when {
                    currentPhone.isNotBlank() || threadId != 0L -> {
                        // Windowed load: newest page only (Google Messages-style).
                        val p = com.autonomousone.messages.repository.ThreadPager(
                            getApplication(),
                            if (currentThreadId != 0L) currentThreadId else threadId,
                            currentPhone.ifBlank { phone }
                        )
                        pager = p
                        val firstPage = p.loadFirstPage()
                        // Merge any optimistic sends already queued this session.
                        (firstPage + mergeOptimistic(firstPage)).distinctBy { it.id }
                            .sortedBy { it.date }
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
                        // rebuild the pager on it so loadNewerSince/loadOlder
                        // query the resolved thread instead of THREAD_ID = 0.
                        val resolvedThreadId = readMessages.last().threadId
                        if (myThread == 0L && resolvedThreadId != 0L) {
                            pager = com.autonomousone.messages.repository.ThreadPager(
                                getApplication(), resolvedThreadId, currentPhone
                            ).also { it.loadFirstPage() } // align consumed counters
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
            SmsEventBus.emitThreadRead(threadId, phone)
        }
    }

    private fun observeIncomingSms() {
        viewModelScope.launch {
            SmsEventBus.incomingSmsFlow.collect { incomingSms ->
                if (currentPhone.isBlank() && currentThreadId == 0L) return@collect

                val isMatch = ContactRepository.sameConversation(incomingSms.sender, currentPhone)

                if (isMatch) {
                    val isDuplicate = messages.any {
                        it.id == incomingSms.id ||
                                (it.message == incomingSms.message &&
                                        Math.abs(it.date - incomingSms.date) < 5000)
                    }
                    if (!isDuplicate) {
                        val readIncoming = incomingSms.copy(unread = false)
                        messages.add(readIncoming)
                        // Mirror into the instant-open cache so leaving and
                        // re-entering shows this message with no reload.
                        ThreadMessageCache.append(currentThreadId, currentPhone, readIncoming)
                        viewModelScope.launch(Dispatchers.IO) {
                            repository.markThreadAsRead(currentThreadId, currentPhone)
                        }
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
        viewModelScope.launch(Dispatchers.IO) {
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
            val merged = com.autonomousone.messages.repository.ThreadMerge.mergeTail(
                messages.toList(), (tail + statusRows).map { it.copy(unread = false) }
            )
            messages.clear()
            messages.addAll(merged)
            if (currentThreadId == 0L && messages.isNotEmpty()) {
                currentThreadId = messages.last().threadId
            }
            // Keep the instant-open cache in step with what is on screen, so
            // re-entering this chat paints the SAME list with zero delay.
            ThreadMessageCache.put(currentThreadId, currentPhone, merged)
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
                if (!ContactRepository.sameConversation(sent.phone, currentPhone)) return@collect
                val normSent = ContactRepository.normalizePhone(sent.phone)
                val row = Sms(
                    id = sent.date, threadId = currentThreadId, sender = normSent,
                    message = sent.message, date = sent.date, unread = false, type = 2
                )
                val duplicate = messages.any {
                    it.message == row.message && kotlin.math.abs(it.date - row.date) < 5000L
                }
                if (!duplicate) messages.add(row)
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
            persisted.none { it.message == opt.message && Math.abs(it.date - opt.date) < 5000L }
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
