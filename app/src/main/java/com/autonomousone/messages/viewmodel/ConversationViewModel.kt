package com.autonomousone.messages.viewmodel

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autonomousone.messages.event.SmsEventBus
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.observer.SmsContentObserver
import com.autonomousone.messages.repository.ContactRepository
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

    val messages = mutableStateListOf<Sms>()

    private var currentThreadId = 0L
    private var currentPhone = ""

    // Track IDs of sent messages we've persisted so we can match them during refresh
    private val persistedSentIds = mutableSetOf<Long>()

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
            val loadedMessages = when {
                currentPhone.isNotBlank() -> repository.getMessagesByPhone(currentPhone)
                threadId != 0L -> repository.getMessagesByThread(threadId)
                else -> emptyList()
            }

            withContext(Dispatchers.Main) {
                messages.clear()
                messages.addAll(loadedMessages)
                if (loadedMessages.isNotEmpty()) {
                    if (currentThreadId == 0L) currentThreadId = loadedMessages.last().threadId
                    if (currentPhone.isBlank()) {
                        // For received messages the sender is the contact; for sent it's the address
                        val sampleMsg = loadedMessages.firstOrNull { it.type == 1 }
                            ?: loadedMessages.first()
                        currentPhone = sampleMsg.sender
                        SmsEventBus.activeConversationPhone = currentPhone
                    }
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
                        messages.add(incomingSms)
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
                currentPhone.isNotBlank() -> repository.getMessagesByPhone(currentPhone)
                currentThreadId != 0L -> repository.getMessagesByThread(currentThreadId)
                else -> emptyList()
            }

            withContext(Dispatchers.Main) {
                if (freshMessages.isNotEmpty()) {
                    messages.clear()
                    messages.addAll(freshMessages)
                    // Update thread ID if we had it as 0
                    if (currentThreadId == 0L) {
                        currentThreadId = freshMessages.last().threadId
                    }
                }
            }
        }
    }

    fun sendMessage(threadId: Long, phone: String, message: String) {
        val trimmedMsg = message.trim()
        if (trimmedMsg.isBlank()) return

        val targetPhone = if (phone.isNotBlank()) phone else currentPhone
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

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // persistToSent is called inside send() BEFORE the actual SmsManager dispatch
                // so by the time SmsContentObserver fires, the sent SMS is already in the DB
                val persistedId = smsSender.send(targetPhone, trimmedMsg)
                persistedSentIds.add(persistedId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            // refresh() will be called automatically by SmsContentObserver
            // after persistToSent triggers DB change. No need for a manual delay+refresh.
        }
    }

    override fun onCleared() {
        repository.unregisterObserver(observer)
        SmsEventBus.activeConversationPhone = ""
        super.onCleared()
    }
}