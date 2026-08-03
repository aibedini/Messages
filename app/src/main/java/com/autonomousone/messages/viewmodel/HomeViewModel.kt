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
            val freshList = repository.getConversations()
            withContext(Dispatchers.Main) {
                if (conversations.isEmpty()) {
                    conversations.addAll(freshList)
                } else {
                    // Merge fresh database state with pending in-memory items
                    val freshIds = freshList.map { it.id }.toSet()
                    val pendingRealtimeItems = conversations.filter { pending ->
                        freshIds.none { it == pending.id } &&
                                freshList.none { f -> f.message == pending.message && Math.abs(f.date - pending.date) < 5000 }
                    }
                    conversations.clear()
                    conversations.addAll((pendingRealtimeItems + freshList).sortedByDescending { it.date })
                }
            }
        }
    }

    private fun observeIncomingSms() {
        viewModelScope.launch {
            SmsEventBus.incomingSmsFlow.collect { incomingSms ->
                val normalizedIncoming = ContactRepository.normalizePhone(incomingSms.sender)

                // Match existing conversation by normalized phone or threadId
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
            }
        }
    }

    override fun onCleared() {
        repository.unregisterObserver(observer)
        super.onCleared()
    }
}