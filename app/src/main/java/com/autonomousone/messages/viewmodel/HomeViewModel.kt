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
    private val contactRepository = ContactRepository(application)

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
            val contactMap = try {
                contactRepository.getContactNameMap()
            } catch (e: Exception) {
                emptyMap()
            }

            val enrichedList = list.map { sms ->
                val normalizedSender = ContactRepository.normalizePhone(sms.sender)
                val contactName = contactMap[normalizedSender] ?: contactMap[sms.sender]
                if (contactName != null) {
                    sms.copy(sender = contactName)
                } else {
                    sms
                }
            }

            withContext(Dispatchers.Main) {
                conversations.clear()
                conversations.addAll(enrichedList)
            }
        }
    }

    private fun observeIncomingSms() {
        viewModelScope.launch {
            SmsEventBus.incomingSmsFlow.collect { incomingSms ->
                val contactMap = try {
                    contactRepository.getContactNameMap()
                } catch (e: Exception) {
                    emptyMap()
                }

                val normalizedIncoming = ContactRepository.normalizePhone(incomingSms.sender)
                val contactName = contactMap[normalizedIncoming] ?: contactMap[incomingSms.sender]
                val displaySms = if (contactName != null) incomingSms.copy(sender = contactName) else incomingSms

                // Instantly update UI list state by placing incoming conversation at top
                val existingIndex = conversations.indexOfFirst {
                    val norm = ContactRepository.normalizePhone(it.sender)
                    norm == normalizedIncoming || it.sender == incomingSms.sender || it.threadId == incomingSms.threadId
                }

                if (existingIndex >= 0) {
                    conversations.removeAt(existingIndex)
                }
                conversations.add(0, displaySms)

                // Sync with repository database in background
                loadSms()
            }
        }
    }

    override fun onCleared() {
        repository.unregisterObserver(observer)
        super.onCleared()
    }
}