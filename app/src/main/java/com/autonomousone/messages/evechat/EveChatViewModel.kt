package com.autonomousone.messages.evechat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autonomousone.messages.evechat.model.EveChatConversation
import com.autonomousone.messages.evechat.network.EveChatSocket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class EveChatViewModel(application: Application) : AndroidViewModel(application) {

    val prefs = EveChatPreferences(application)
    val repository = EveChatRepository(prefs, viewModelScope)

    sealed class LoginState {
        data object Idle : LoginState()
        data object Loading : LoginState()
        data object Success : LoginState()
        data class Error(val message: String) : LoginState()
    }

    enum class InboxFilter(val label: String) {
        Pending("در انتظار"),
        Mine("من"),
        All("همه"),
        Resolved("حل شده")
    }

    private val _loginState = MutableStateFlow<LoginState>(
        if (prefs.isLoggedIn) LoginState.Success else LoginState.Idle
    )
    val loginState: StateFlow<LoginState> = _loginState.asStateFlow()

    private val _serverUrl = MutableStateFlow(prefs.serverUrl)
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _selectedFilter = MutableStateFlow(InboxFilter.Pending)
    val selectedFilter: StateFlow<InboxFilter> = _selectedFilter.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    val connectionState: StateFlow<EveChatSocket.State> = repository.socket.state

    val conversations: StateFlow<List<EveChatConversation>> = repository.conversations

    val filteredConversations: StateFlow<List<EveChatConversation>> =
        repository.conversations.map { convs ->
            when (_selectedFilter.value) {
                InboxFilter.Pending -> convs.filter { it.status == "pending" }
                InboxFilter.Mine -> convs.filter {
                    it.assignedAgentId == repository.currentAgent.value?.id
                }
                InboxFilter.Resolved -> convs.filter { it.status == "resolved" }
                InboxFilter.All -> convs
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        if (prefs.isLoggedIn) {
            connectAndLoad()
        }
    }

    fun login(serverUrl: String, email: String, password: String) {
        _loginState.value = LoginState.Loading
        viewModelScope.launch {
            when (val result = EveChatApiClient.login(serverUrl, email, password)) {
                is EveChatApiClient.Result.Success -> {
                    prefs.serverUrl = serverUrl
                    prefs.accessToken = result.data.accessToken
                    prefs.refreshToken = result.data.refreshToken
                    prefs.agentEmail = email
                    _serverUrl.value = serverUrl
                    _loginState.value = LoginState.Success
                    connectAndLoad()
                }
                is EveChatApiClient.Result.Error -> {
                    _loginState.value = LoginState.Error(result.message)
                }
            }
        }
    }

    fun logout() {
        repository.disconnect()
        prefs.clear()
        _loginState.value = LoginState.Idle
    }

    fun selectFilter(filter: InboxFilter) {
        _selectedFilter.value = filter
        viewModelScope.launch { loadForFilter(filter) }
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            loadForFilter(_selectedFilter.value)
            _isRefreshing.value = false
        }
    }

    private fun connectAndLoad() {
        viewModelScope.launch {
            repository.fetchMe()
            repository.connect()
            loadForFilter(_selectedFilter.value)
        }
    }

    private suspend fun loadForFilter(filter: InboxFilter) {
        val status = when (filter) {
            InboxFilter.Pending -> "pending"
            InboxFilter.Mine -> "mine"
            InboxFilter.Resolved -> "resolved"
            InboxFilter.All -> null
        }
        repository.loadConversations(status)
    }

    override fun onCleared() {
        super.onCleared()
        repository.disconnect()
    }
}
