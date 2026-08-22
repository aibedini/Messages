package com.autonomousone.messages.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autonomousone.messages.model.Contact
import com.autonomousone.messages.repository.ContactRepository
import com.autonomousone.messages.repository.ProgressListener
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

    var isLoading by mutableStateOf(false)
        private set

    var loadStatus by mutableStateOf<String?>(null)
        private set

    fun loadContacts() {
        // getContacts() queries BOTH the ContactsContract provider and the whole
        // SMS table (via SmsRepository.getConversations()). Running it on the
        // main thread blocks the UI → ANR/freeze. Run it on IO and post back to Main.
        isLoading = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val progressListener = ProgressListener { p ->
                    loadStatus = if (p.total > 0) {
                        "Loading contacts… ${p.loaded}/${p.total}"
                    } else {
                        "Loading contacts…"
                    }
                }
                val freshContacts = repository.getContacts(progressListener)
                withContext(Dispatchers.Main) {
                    contacts.clear()
                    contacts.addAll(freshContacts)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isLoading = false
                    loadStatus = null
                }
            }
        }
    }
}