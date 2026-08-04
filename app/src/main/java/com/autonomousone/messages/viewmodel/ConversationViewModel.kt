package com.autonomousone.messages.viewmodel

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autonomousone.messages.event.SmsEventBus
import com.autonomousone.messages.mms.MmsSender
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
    private val mmsSender = MmsSender(application)

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

            val targetThreadId = if (currentThreadId != 0L) currentThreadId else loadedMessages.lastOrNull()?.threadId ?: 0L
            val targetPhone = if (currentPhone.isNotBlank()) currentPhone else loadedMessages.firstOrNull()?.sender ?: ""

            if (targetThreadId != 0L || targetPhone.isNotBlank()) {
                repository.markThreadAsRead(targetThreadId, targetPhone)
            }

            val readMessages = loadedMessages.map { it.copy(unread = false) }

            withContext(Dispatchers.Main) {
                messages.clear()
                messages.addAll(readMessages)
                if (readMessages.isNotEmpty()) {
                    if (currentThreadId == 0L) currentThreadId = readMessages.last().threadId
                    if (currentPhone.isBlank()) {
                        val sampleMsg = readMessages.firstOrNull { it.type == 1 }
                            ?: readMessages.first()
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
                currentPhone.isNotBlank() -> repository.getMessagesByPhone(currentPhone)
                currentThreadId != 0L -> repository.getMessagesByThread(currentThreadId)
                else -> emptyList()
            }

            if (currentThreadId != 0L || currentPhone.isNotBlank()) {
                repository.markThreadAsRead(currentThreadId, currentPhone)
            }

            val readMessages = freshMessages.map { it.copy(unread = false) }

            withContext(Dispatchers.Main) {
                if (readMessages.isNotEmpty()) {
                    messages.clear()
                    messages.addAll(readMessages)
                    if (currentThreadId == 0L) {
                        currentThreadId = readMessages.last().threadId
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
                val persistedId = smsSender.send(targetPhone, trimmedMsg)
                persistedSentIds.add(persistedId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
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
        messages.add(optimisticSms)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                mmsSender.sendImage(targetPhone, imageUri)
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