package com.autonomousone.messages.viewmodel

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.observer.SmsContentObserver
import com.autonomousone.messages.repository.SmsRepository
import com.autonomousone.messages.sms.SmsSender

class ConversationViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val repository = SmsRepository(application)

    private val smsSender = SmsSender(application)

    val messages = mutableStateListOf<Sms>()

    private var currentThreadId: Long = 0L

    private val observer = SmsContentObserver {

        if (currentThreadId != 0L) {

            loadConversation(currentThreadId)

        }

    }

    init {

        repository.registerObserver(observer)

    }

    fun loadConversation(
        threadId: Long
    ) {

        currentThreadId = threadId

        val sms = repository.getMessagesByThread(threadId)

        messages.clear()

        messages.addAll(sms)

    }

    fun sendMessage(
        threadId: Long,
        phone: String,
        message: String
    ) {

        if (message.isBlank()) return

        smsSender.send(
            phone = phone,
            text = message
        )

        // ContentObserver refreshes automatically.
        // No need to call loadConversation() here.

    }

    override fun onCleared() {

        repository.unregisterObserver(observer)

        super.onCleared()

    }

}