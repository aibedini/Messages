package com.autonomousone.messages.viewmodel

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.observer.SmsContentObserver
import com.autonomousone.messages.repository.ContactRepository
import com.autonomousone.messages.repository.SmsRepository

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
    }

    fun loadSms() {
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

        conversations.clear()
        conversations.addAll(enrichedList)
    }

    override fun onCleared() {
        repository.unregisterObserver(observer)
        super.onCleared()
    }
}