package com.autonomousone.messages.viewmodel

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.observer.SmsContentObserver
import com.autonomousone.messages.repository.SmsRepository

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

    }

    fun loadSms() {

        val list = repository.getConversations()

        conversations.clear()

        conversations.addAll(list)

    }

    override fun onCleared() {

        repository.unregisterObserver(observer)

        super.onCleared()

    }

}