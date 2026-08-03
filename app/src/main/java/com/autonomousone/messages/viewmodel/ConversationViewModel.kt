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
import kotlinx.coroutines.delay
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

    private val observer = SmsContentObserver {
        refresh()
    }

    init {
        repository.registerObserver(observer)
        observeIncomingSms()
    }

    fun loadConversation(
        threadId: Long,
        phone: String = ""
    ) {
        currentThreadId = threadId
        if (phone.isNotBlank()) {
            currentPhone = phone
            SmsEventBus.activeConversationPhone = phone
        }

        viewModelScope.launch(Dispatchers.IO) {
            val loadedMessages = if (currentPhone.isNotBlank()) {
                repository.getMessagesByPhone(currentPhone)
            } else if (threadId != 0L) {
                repository.getMessagesByThread(threadId)
            } else {
                emptyList()
            }

            withContext(Dispatchers.Main) {
                messages.clear()
                messages.addAll(loadedMessages)
                if (loadedMessages.isNotEmpty()) {
                    if (currentThreadId == 0L) {
                        currentThreadId = loadedMessages.last().threadId
                    }
                    if (currentPhone.isBlank()) {
                        currentPhone = loadedMessages.first().sender
                        SmsEventBus.activeConversationPhone = currentPhone
                    }
                }
            }
        }
    }

    fun setPhone(
        phone: String
    ) {
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

                val isMatch = (normalizedCurrent.isNotBlank() && normalizedIncoming.isNotBlank() &&
                        (normalizedIncoming == normalizedCurrent || normalizedIncoming.endsWith(normalizedCurrent) || normalizedCurrent.endsWith(normalizedIncoming))) ||
                        (incomingSms.sender.isNotBlank() && incomingSms.sender == currentPhone)

                if (isMatch) {
                    val isDuplicate = messages.any { it.id == incomingSms.id || (it.message == incomingSms.message && Math.abs(it.date - incomingSms.date) < 5000) }
                    if (!isDuplicate) {
                        messages.add(incomingSms)
                    }
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val freshMessages = if (currentPhone.isNotBlank()) {
                repository.getMessagesByPhone(currentPhone)
            } else if (currentThreadId != 0L) {
                repository.getMessagesByThread(currentThreadId)
            } else {
                emptyList()
            }

            withContext(Dispatchers.Main) {
                if (freshMessages.isNotEmpty()) {
                    messages.clear()
                    messages.addAll(freshMessages)
                }
            }
        }
    }

    fun sendMessage(
        threadId: Long,
        phone: String,
        message: String
    ) {
        val trimmedMsg = message.trim()
        if (trimmedMsg.isBlank()) return

        var targetPhone = if (phone.isNotBlank()) phone else currentPhone
        if (targetPhone.isBlank()) return

        currentPhone = targetPhone
        SmsEventBus.activeConversationPhone = targetPhone
        if (threadId != 0L) {
            currentThreadId = threadId
        }

        // 1. Instant Optimistic UI Update
        val optimisticSms = Sms(
            id = System.currentTimeMillis(),
            threadId = currentThreadId,
            sender = targetPhone,
            message = trimmedMsg,
            date = System.currentTimeMillis(),
            unread = false,
            type = 2 // 2 = Sent outgoing message
        )
        messages.add(optimisticSms)

        // 2. Dispatch SMS send on IO thread
        viewModelScope.launch(Dispatchers.IO) {
            try {
                smsSender.send(targetPhone, trimmedMsg)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            delay(1000)
            refresh()
        }
    }

    override fun onCleared() {
        repository.unregisterObserver(observer)
        SmsEventBus.activeConversationPhone = ""
        super.onCleared()
    }
}