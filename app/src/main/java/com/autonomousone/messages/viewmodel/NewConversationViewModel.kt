package com.autonomousone.messages.viewmodel

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autonomousone.messages.model.Contact
import com.autonomousone.messages.repository.ContactRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NewConversationViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val repository =
        ContactRepository(application)

    val contacts =
        mutableStateListOf<Contact>()

    fun loadContacts() {
        // getContacts() queries BOTH the ContactsContract provider and the whole
        // SMS table (via SmsRepository.getConversations()). Running it on the
        // main thread blocks the UI → ANR/freeze. Run it on IO and post back to Main.
        viewModelScope.launch(Dispatchers.IO) {
            val freshContacts = repository.getContacts()
            withContext(Dispatchers.Main) {
                contacts.clear()
                contacts.addAll(freshContacts)
            }
        }
    }
}