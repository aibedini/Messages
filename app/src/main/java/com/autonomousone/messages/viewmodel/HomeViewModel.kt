package com.autonomousone.messages.viewmodel

import android.app.Application
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
import com.autonomousone.messages.repository.SmsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private var loadJob: Job? = null

    /** Holds a pending delete job per threadId so it can be cancelled on Undo. */
    private val pendingDeletes = mutableMapOf<Long, Job>()

    init {
        repository.registerObserver(observer)
        loadArchivedIds()
        loadSms()
        observeIncomingSms()
        observeRefreshSignal()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Load
    // ─────────────────────────────────────────────────────────────────────────

    private fun loadArchivedIds() {
        archivedIds.clear()
        archivedIds.addAll(archiveRepository.getArchivedIds())
    }

    fun loadSms() {
        // Cancel any in-flight reload so a newer observer/refresh tick wins and
        // the loading state is never left dangling.
        loadJob?.cancel()
        isLoading = true
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val contactRepo = ContactRepository(getApplication())
                contactRepo.getContactNameMapAsync()
                val freshList = repository.getConversations()
                val archived = archiveRepository.getArchivedIds()

                withContext(Dispatchers.Main) {
                    conversations.clear()
                    archivedConversations.clear()
                    archivedIds.clear()
                    archivedIds.addAll(archived)

                    freshList.forEach { sms ->
                        if (sms.threadId in archived) {
                            archivedConversations.add(sms)
                        } else {
                            conversations.add(sms)
                        }
                    }
                }
            } finally {
                // Always clear the spinner, even if a query throws, so the list
                // never stays stuck in a loading state.
                withContext(Dispatchers.Main) { isLoading = false }
            }
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
        // Optimistic remove from UI
        conversations.remove(sms)
        archivedConversations.remove(sms)

        // Cancel any existing pending delete for this thread
        pendingDeletes[sms.threadId]?.cancel()

        val job = viewModelScope.launch(Dispatchers.IO) {
            delay(delayMs)
            repository.deleteThread(threadId = sms.threadId, phone = sms.sender)
            withContext(Dispatchers.Main) {
                pendingDeletes.remove(sms.threadId)
            }
        }
        pendingDeletes[sms.threadId] = job
    }

    /**
     * Cancels a pending delete for [sms] and re-inserts it into the correct list
     * at its sorted position (most-recent first by date).
     */
    fun undoDelete(sms: Sms) {
        pendingDeletes[sms.threadId]?.cancel()
        pendingDeletes.remove(sms.threadId)

        val targetList = if (sms.threadId in archivedIds) archivedConversations else conversations
        val insertIndex = targetList.indexOfFirst { it.date < sms.date }
        if (insertIndex >= 0) targetList.add(insertIndex, sms) else targetList.add(sms)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Archive / Unarchive
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Moves [sms] from the active conversations list to the archived list.
     * Persists the archive state to SharedPreferences.
     */
    fun archiveConversation(sms: Sms) {
        viewModelScope.launch(Dispatchers.IO) {
            archiveRepository.archiveThread(sms.threadId)
            withContext(Dispatchers.Main) {
                archivedIds.add(sms.threadId)
                conversations.remove(sms)
                val insertIndex = archivedConversations.indexOfFirst { it.date < sms.date }
                if (insertIndex >= 0) archivedConversations.add(insertIndex, sms)
                else archivedConversations.add(sms)
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
                archivedConversations.remove(sms)
                val insertIndex = conversations.indexOfFirst { it.date < sms.date }
                if (insertIndex >= 0) conversations.add(insertIndex, sms) else conversations.add(sms)
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
        repository.unregisterObserver(observer)
        super.onCleared()
    }
}