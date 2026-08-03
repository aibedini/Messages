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

    private var currentThreadId = 0L

    private var currentPhone = ""

    private val observer = SmsContentObserver {

        refresh()

    }

    init {

        repository.registerObserver(observer)

    }

    fun loadConversation(
        threadId: Long
    ) {

        currentThreadId = threadId

        if (threadId != 0L) {

            messages.clear()

            messages.addAll(
                repository.getMessagesByThread(threadId)
            )

            if (messages.isNotEmpty()) {

                currentPhone = messages.first().sender

            }

        }

    }

    fun setPhone(
        phone: String
    ) {

        currentPhone = phone

    }

    private fun refresh() {

        if (currentThreadId != 0L) {

            messages.clear()

            messages.addAll(
                repository.getMessagesByThread(currentThreadId)
            )

            return

        }

        if (currentPhone.isBlank())
            return

        val conversation =

            repository
                .getConversations()
                .firstOrNull {

                    it.sender == currentPhone

                }

        if (conversation != null) {

            currentThreadId = conversation.threadId

            messages.clear()

            messages.addAll(

                repository.getMessagesByThread(
                    currentThreadId
                )

            )

        }

    }

    fun sendMessage(
        threadId: Long,
        phone: String,
        message: String
    ) {

        if (message.isBlank())
            return

        currentPhone = phone

        smsSender.send(
            phone,
            message
        )

        refresh()

    }

    override fun onCleared() {

        repository.unregisterObserver(
            observer
        )

        super.onCleared()

    }

}