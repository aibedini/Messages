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
        // ContentObserver fires after DB commits (debounced 300 ms) — do a clean reload
        loadSms()
    }

    init {
        repository.registerObserver(observer)
        loadSms()
        observeIncomingSms()
    }

    fun loadSms() {
        viewModelScope.launch(Dispatchers.IO) {
            val freshList = repository.getConversations()
            withContext(Dispatchers.Main) {
                conversations.clear()
                conversations.addAll(freshList)
            }
        }
    }

    private fun observeIncomingSms() {
        viewModelScope.launch {
            SmsEventBus.incomingSmsFlow.collect { incomingSms ->
                // Optimistically show the incoming SMS immediately at the top
                // before the DB observer fires (which will do a clean reload shortly after)
                val normalizedIncoming = ContactRepository.normalizePhone(incomingSms.sender)

                val existingIndex = conversations.indexOfFirst {
                    val norm = ContactRepository.normalizePhone(it.sender)
                    norm.isNotBlank() && normalizedIncoming.isNotBlank() &&
                            (norm == normalizedIncoming ||
                                    norm.endsWith(normalizedIncoming) ||
                                    normalizedIncoming.endsWith(norm))
                }

                if (existingIndex >= 0) {
                    conversations.removeAt(existingIndex)
                }
                conversations.add(0, incomingSms)
            }
        }
    }

    override fun onCleared() {
        repository.unregisterObserver(observer)
        super.onCleared()
    }
}