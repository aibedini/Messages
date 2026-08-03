package com.autonomousone.messages.viewmodel

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import com.autonomousone.messages.model.Contact
import com.autonomousone.messages.repository.ContactRepository

class NewConversationViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val repository =
        ContactRepository(application)

    val contacts =
        mutableStateListOf<Contact>()

    fun loadContacts() {

        contacts.clear()

        contacts.addAll(
            repository.getContacts()
        )

    }

}