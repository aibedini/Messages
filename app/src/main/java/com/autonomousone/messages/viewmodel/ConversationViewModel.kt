package com.autonomousone.messages.viewmodel

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    }

    fun loadConversation(
        threadId: Long,
        phone: String = ""
    ) {
        currentThreadId = threadId
        if (phone.isNotBlank()) {
            currentPhone = phone
        }

        val loadedMessages = if (threadId != 0L) {
            repository.getMessagesByThread(threadId)
        } else if (currentPhone.isNotBlank()) {
            repository.getMessagesByPhone(currentPhone)
        } else {
            emptyList()
        }

        if (loadedMessages.isNotEmpty()) {
            messages.clear()
            messages.addAll(loadedMessages)
            if (currentThreadId == 0L) {
                currentThreadId = loadedMessages.first().threadId
            }
            if (currentPhone.isBlank()) {
                currentPhone = loadedMessages.first().sender
            }
        }
    }

    fun setPhone(
        phone: String
    ) {
        currentPhone = phone
        if (currentThreadId == 0L && phone.isNotBlank()) {
            loadConversation(0L, phone)
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val freshMessages = if (currentThreadId != 0L) {
                repository.getMessagesByThread(currentThreadId)
            } else if (currentPhone.isNotBlank()) {
                repository.getMessagesByPhone(currentPhone)
            } else {
                emptyList()
            }

            withContext(Dispatchers.Main) {
                if (freshMessages.isNotEmpty()) {
                    val firstMatch = freshMessages.first()
                    if (currentThreadId == 0L) {
                        currentThreadId = firstMatch.threadId
                    }
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

        val targetPhone = if (phone.isNotBlank()) phone else currentPhone
        if (targetPhone.isBlank()) return

        currentPhone = targetPhone
        if (threadId != 0L) {
            currentThreadId = threadId
        }

        // 1. Instant Optimistic UI Update for zero-latency screen response
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

        // 2. Perform SMS send on IO thread and refresh database status
        viewModelScope.launch(Dispatchers.IO) {
            try {
                smsSender.send(targetPhone, trimmedMsg)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            delay(600)
            refresh()
        }
    }

    override fun onCleared() {
        repository.unregisterObserver(observer)
        super.onCleared()
    }
}