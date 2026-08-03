package com.autonomousone.messages.viewmodel

import android.app.Application
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.repository.SmsRepository

class HomeViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val repository = SmsRepository(application)

    val conversations = mutableStateListOf<Sms>()

    fun loadSms() {

        Log.d("SMS_DEBUG", "loadSms() called")

        try {

            val smsList = repository.getConversations()

            Log.d(
                "SMS_DEBUG",
                "Loaded ${smsList.size} conversations"
            )

            conversations.clear()
            conversations.addAll(smsList)

        } catch (e: Exception) {

            Log.e(
                "SMS_DEBUG",
                "Error loading SMS",
                e
            )

        }

    }
}