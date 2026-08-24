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
import com.autonomousone.messages.repository.ContactRepository
import com.autonomousone.messages.repository.ProgressListener
import com.autonomousone.messages.repository.SmsRepository
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

    /** All conversations that are NOT archived — shown in "All" and "Unread" tabs. */
    val conversations = mutableStateListOf<Sms>()

    /** Conversations that have been archived — shown in the "Archived" tab. */
    val archivedConversations = mutableStateListOf<Sms>()

    /** Reactive set of archived threadIds for filtering. */
    private val archivedIds = mutableStateSetOf<Long>()

    private val observer = SmsContentObserver { loadSms() }

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

    /** Human-readable progress while loading (e.g. "Reading messages… 120/340"). Null when idle. */
    var loadStatus by mutableStateOf<String?>(null)
        private set

    private val reloadRequests = Channel<Unit>(Channel.CONFLATED)

    /** Holds a pending delete job per threadId so it can be cancelled on Undo. */
    private val pendingDeletes = mutableMapOf<Long, Job>()

    /** Delays the spinner so quick reloads never flash the progress bar. */
    private var loadingShowJob: Job? = null

    init {
        repository.registerObserver(observer)
        loadArchivedIds()
        observeIncomingSms()
        observeRefreshSignal()
        observeReloadRequests()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Load
    // ─────────────────────────────────────────────────────────────────────────

    private fun loadArchivedIds() {
        archivedIds.clear()
        archivedIds.addAll(archiveRepository.getArchivedIds())
    }

    fun loadSms() {
        reloadRequests.trySend(Unit)
    }

    private fun observeReloadRequests() {
        viewModelScope.launch {
            for (ignored in reloadRequests) performLoad()
        }
    }

    private suspend fun performLoad() {
        // Only surface the loading UI when the load actually takes a moment;
        // instant reloads (single new SMS) stay silent and seamless.
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
                val freshList = repository.getConversationsFast(progressListener) { partial ->
                    viewModelScope.launch { replaceConversations(partial, archived) }
                }
                val names = contactNames.await()
                withContext(Dispatchers.Main) { this@HomeViewModel.contactNames = names }
                freshList to archived
            }

            replaceConversations(freshList, archived)
        } catch (error: Exception) {
            Log.e("SMS_DEBUG", "Unable to refresh conversations", error)
        } finally {
            loadingShowJob?.cancel()
            isLoading = false
            loadStatus = null
            syncProgress = null
        }
    }

    private fun replaceConversations(items: List<Sms>, archived: Set<Long>) {
        // Threads with a pending (not-yet-committed) delete must stay hidden
        // even across observer-triggered rebuilds.
        val excluded = pendingDeletes.keys
        conversations.clear()
        archivedConversations.clear()
        archivedIds.clear()
        archivedIds.addAll(archived)
        items.forEach { sms ->
            if (sms.threadId in excluded) return@forEach
            if (sms.threadId in archived) archivedConversations.add(sms)
            else conversations.add(sms)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mark read
    // ─────────────────────────────────────────────────────────────────────────

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
    // Real-time incoming SMS
    // ─────────────────────────────────────────────────────────────────────────

    private fun observeIncomingSms() {
        viewModelScope.launch {
            SmsEventBus.incomingSmsFlow.collect { incomingSms ->
                val normalizedIncoming = ContactRepository.normalizePhone(incomingSms.sender)

                // Remove from whichever list it currently appears in
                val existingIndex = conversations.indexOfFirst {
                    val norm = ContactRepository.normalizePhone(it.sender)
                    norm.isNotBlank() && normalizedIncoming.isNotBlank() &&
                            (norm == normalizedIncoming ||
                                    norm.endsWith(normalizedIncoming) ||
                                    normalizedIncoming.endsWith(norm))
                }
                if (existingIndex >= 0) conversations.removeAt(existingIndex)

                // Only add to main list if not archived
                if (incomingSms.threadId !in archivedIds) {
                    conversations.add(0, incomingSms)
                }
                // Reconcile with the provider right away (conflated + debounced),
                // so the freshly prepended row is confirmed/updated ASAP.
                loadSms()
            }
        }
    }

    /** Reload from DB whenever MainActivity.onResume fires */
    private fun observeRefreshSignal() {
        viewModelScope.launch {
            SmsEventBus.refreshFlow.collect {
                loadSms()
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
