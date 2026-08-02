package com.autonomousone.messages.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.compose.runtime.mutableStateListOf
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.repository.SmsRepository

class HomeViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val repository = SmsRepository(application)

    val conversations = mutableStateListOf<Sms>()

    fun loadSms() {

        conversations.clear()

        conversations.addAll(
            repository.getConversations()
        )

    }
}