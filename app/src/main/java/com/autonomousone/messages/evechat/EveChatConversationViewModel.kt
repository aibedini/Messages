package com.autonomousone.messages.evechat

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.autonomousone.messages.evechat.model.EveChatConversation
import com.autonomousone.messages.evechat.model.EveChatMessage
import com.autonomousone.messages.evechat.model.EveChatVisitorSnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class EveChatConversationViewModel(
    private val application: Application,
    private val conversationId: String
) : ViewModel() {

    private val session = EveChatSession.get(application)

    val conversation: StateFlow<EveChatConversation?> = session.observeConversation(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val messages: StateFlow<List<EveChatMessage>> = session.observeMessages(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val visitorTyping: StateFlow<Boolean> = session.observeTyping(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** The server's visitor profile for this chat; null until a detail fetch succeeds. */
    val profile: StateFlow<EveChatVisitorSnapshot?> = session.observeVisitor(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val connection: StateFlow<EveChatSession.Connection> = session.connection
    val myAgentId: StateFlow<String?> = session.myAgentId

    private val _syncing = MutableStateFlow(true)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _loadingOlder = MutableStateFlow(false)
    val loadingOlder: StateFlow<Boolean> = _loadingOlder.asStateFlow()

    private val _busyAction = MutableStateFlow<String?>(null)
    val busyAction: StateFlow<String?> = _busyAction.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private var typingSent = false
    private var typingStop: Job? = null

    init {
        viewModelScope.launch {
            try {
                if (!session.syncThread(conversationId) && conversation.value == null) {
                    _events.tryEmit(application.getString(com.autonomousone.messages.R.string.eve_load_failed))
                }
            } finally {
                _syncing.value = false
            }
        }
    }

    /** Visible chat: suppresses its notifications and keeps the server read pointer current. */
    fun onVisible() = session.setActiveConversation(conversationId)

    fun onHidden() {
        session.setActiveConversation(null)
        stopTyping()
    }

    fun send(text: String) {
        session.send(conversationId, text)
        stopTyping()
    }

    fun retry(message: EveChatMessage) = session.retry(message.id)
    fun discard(message: EveChatMessage) = session.discard(message.id)

    fun loadOlder() {
        if (_loadingOlder.value || conversation.value?.historyComplete != false) return
        viewModelScope.launch {
            _loadingOlder.value = true
            try {
                session.loadOlder(conversationId)
            } finally {
                _loadingOlder.value = false
            }
        }
    }

    /** Manual refresh from the visitor sheet; keeps the last good profile if the fetch fails. */
    fun refreshProfile() {
        viewModelScope.launch { session.refreshProfile(conversationId) }
    }

    fun claim() = act("claim") { session.claim(conversationId) }
    fun resolve() = act("resolve") { session.resolve(conversationId) }
    fun reopen() = act("reopen") { session.reopen(conversationId) }

    private fun act(name: String, block: suspend () -> ApiResult<Unit>) {
        if (_busyAction.value != null) return
        viewModelScope.launch {
            _busyAction.value = name
            try {
                val r = block()
                if (r is ApiResult.Err) _events.tryEmit(r.error.message(application))
            } finally {
                _busyAction.value = null
            }
        }
    }

    /** Throttled: one `typing.start` per burst, `typing.stop` after a pause. */
    fun onInputChanged(text: String) {
        if (text.isBlank()) {
            stopTyping()
            return
        }
        if (!typingSent) {
            typingSent = true
            session.setTyping(conversationId, true)
        }
        typingStop?.cancel()
        typingStop = viewModelScope.launch {
            delay(4_000)
            stopTyping()
        }
    }

    private fun stopTyping() {
        typingStop?.cancel()
        if (typingSent) {
            typingSent = false
            session.setTyping(conversationId, false)
        }
    }

    override fun onCleared() {
        stopTyping()
        super.onCleared()
    }

    class Factory(
        private val application: Application,
        private val conversationId: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            EveChatConversationViewModel(application, conversationId) as T
    }
}
