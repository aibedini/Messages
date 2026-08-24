package com.autonomousone.messages.viewmodel

import android.app.Application
import android.net.Uri
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
    }

    fun loadConversation(threadId: Long, phone: String = "") {
        currentThreadId = threadId
        if (phone.isNotBlank()) {
            currentPhone = phone
            SmsEventBus.activeConversationPhone = phone
        }

        viewModelScope.launch(Dispatchers.IO) {
            // ── Stale-while-revalidate: paint the cached thread INSTANTLY
            // (Google Messages-style), then refresh from the provider.
            val cache = ThreadMessageCache
            val cacheKeyThread = if (threadId != 0L) threadId else 0L
            val stale = if (cacheKeyThread != 0L || phone.isNotBlank())
                cache.getStale(cacheKeyThread, phone.ifBlank { currentPhone }) else null

            if (stale != null && stale.first.isNotEmpty()) {
                val cachedList = stale.first.map { it.copy(unread = false) }
                withContext(Dispatchers.Main) {
                    messages.clear()
                    messages.addAll(cachedList)
                    messages.addAll(mergeOptimistic(cachedList))
                    isLoading = false
                    loadStatus = null
                }
                // Cached copy was already fresh → nothing more to do.
                if (!stale.second) {
                    markReadAndNotify(targetOf(cacheKeyThread, phone), phoneIfBlank(phone))
                    return@launch
                }
            } else {
                withContext(Dispatchers.Main) { isLoading = true }
            }

            try {
                val progressListener = ProgressListener { p ->
                    // Only surface progress when the user is actually waiting
                    // (no instant cached copy was painted). Avoids the jarring
                    // "Reading messages…" flash on every thread open.
                    if (loadStatus != null || isLoading) {
                        val appContext = getApplication<Application>()
                        val label = when (p.phase) {
                            "sms" -> appContext.getString(R.string.conv_loading_messages)
                            "mms" -> appContext.getString(R.string.conv_loading_multimedia)
                            else -> appContext.getString(R.string.conv_loading_generic)
                        }
                        loadStatus = if (p.total > 0) "$label… ${p.loaded}/${p.total}" else "$label…"
                    }
                }
                val loadedMessages = when {
                    currentPhone.isNotBlank() -> repository.getMessagesByPhone(
                        currentPhone, progressListener, threadIdHint = currentThreadId
                    )
                    threadId != 0L -> repository.getMessagesByThread(threadId, progressListener)
                    else -> emptyList()
                }

                val targetThreadId = if (currentThreadId != 0L) currentThreadId else loadedMessages.lastOrNull()?.threadId ?: 0L
                val targetPhone = if (currentPhone.isNotBlank()) currentPhone else loadedMessages.firstOrNull()?.sender ?: ""

                if (targetThreadId != 0L || targetPhone.isNotBlank()) {
                    repository.markThreadAsRead(targetThreadId, targetPhone)
                }

                val readMessages = loadedMessages.map { it.copy(unread = false) }

                withContext(Dispatchers.Main) {
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
                withContext(Dispatchers.Main) {
                    isLoading = false
                    loadStatus = null
                }
            }
        }
    }

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
                val normalizedIncoming = ContactRepository.normalizePhone(incomingSms.sender)
                val normalizedCurrent = ContactRepository.normalizePhone(currentPhone)

                if (currentPhone.isBlank() && currentThreadId == 0L) return@collect

                val isMatch = normalizedCurrent.isNotBlank() && normalizedIncoming.isNotBlank() &&
                        (normalizedIncoming == normalizedCurrent ||
                                normalizedIncoming.endsWith(normalizedCurrent) ||
                                normalizedCurrent.endsWith(normalizedIncoming))

                if (isMatch) {
                    val isDuplicate = messages.any {
                        it.id == incomingSms.id ||
                                (it.message == incomingSms.message &&
                                        Math.abs(it.date - incomingSms.date) < 5000)
                    }
                    if (!isDuplicate) {
                        val readIncoming = incomingSms.copy(unread = false)
                        messages.add(readIncoming)
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
        viewModelScope.launch(Dispatchers.IO) {
            val freshMessages = when {
                currentPhone.isNotBlank() -> repository.getMessagesByPhone(
                    currentPhone, threadIdHint = currentThreadId
                )
                currentThreadId != 0L -> repository.getMessagesByThread(currentThreadId)
                else -> emptyList()
            }

            if (currentThreadId != 0L || currentPhone.isNotBlank()) {
                repository.markThreadAsRead(currentThreadId, currentPhone)
            }

            val readMessages = freshMessages.map { it.copy(unread = false) }

            withContext(Dispatchers.Main) {
                messages.clear()
                messages.addAll(readMessages)
                // Never wipe optimistic sends that the provider hasn't confirmed yet.
                messages.addAll(mergeOptimistic(readMessages))
                if (currentThreadId == 0L && readMessages.isNotEmpty()) {
                    currentThreadId = readMessages.last().threadId
                }
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
            type = 2
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
        super.onCleared()
    }
}