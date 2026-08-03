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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val repository = SmsRepository(application)

    val conversations = mutableStateListOf<Sms>()

    private val observer = SmsContentObserver {
        loadSms()
    }

    init {
        repository.registerObserver(observer)
        loadSms()
        observeIncomingSms()
    }

    fun loadSms() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = repository.getConversations()
            withContext(Dispatchers.Main) {
                conversations.clear()
                conversations.addAll(list)
            }
        }
    }

    private fun observeIncomingSms() {
        viewModelScope.launch {
            SmsEventBus.incomingSmsFlow.collect { incomingSms ->
                val normalizedIncoming = ContactRepository.normalizePhone(incomingSms.sender)

                // Match existing conversation by phone or threadId
                val existingIndex = conversations.indexOfFirst {
                    val norm = ContactRepository.normalizePhone(it.sender)
                    (norm.isNotBlank() && normalizedIncoming.isNotBlank() &&
                            (norm == normalizedIncoming || norm.endsWith(normalizedIncoming) || normalizedIncoming.endsWith(norm))) ||
                            (it.threadId != 0L && incomingSms.threadId != 0L && it.threadId == incomingSms.threadId)
                }

                if (existingIndex >= 0) {
                    conversations.removeAt(existingIndex)
                }
                conversations.add(0, incomingSms)

                // Sync with background storage
                loadSms()
            }
        }
    }

    override fun onCleared() {
        repository.unregisterObserver(observer)
        super.onCleared()
    }
}