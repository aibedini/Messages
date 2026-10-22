package com.autonomousone.messages.evechat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autonomousone.messages.evechat.EveChatSession.InboxFilter
import com.autonomousone.messages.evechat.model.EveChatConversation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class EveChatViewModel(application: Application) : AndroidViewModel(application) {

    private val session = EveChatSession.get(application)

    sealed class LoginState {
        data object Idle : LoginState()
        data object Loading : LoginState()
        data class Error(val message: String) : LoginState()
    }

    val authState: StateFlow<EveChatSession.AuthState> = session.authState
    val connection: StateFlow<EveChatSession.Connection> = session.connection
    val errors: SharedFlow<String> = session.errors

    private val _loginState = MutableStateFlow<LoginState>(LoginState.Idle)
    val loginState: StateFlow<LoginState> = _loginState.asStateFlow()

    private val _filter = MutableStateFlow(InboxFilter.OPEN)
    val filter: StateFlow<InboxFilter> = _filter.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    val conversations: StateFlow<List<EveChatConversation>> =
        combine(_filter, session.myAgentId) { f, _ -> f }
            .flatMapLatest { session.observeInbox(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val myAgentId: StateFlow<String?> = session.myAgentId

    val savedServer: String get() = session.lastServer
    val savedEmail: String get() = session.lastEmail

    fun login(server: String, email: String, password: String) {
        if (_loginState.value is LoginState.Loading) return
        _loginState.value = LoginState.Loading
        viewModelScope.launch {
            _loginState.value = when (val r = session.login(server, email, password)) {
                is ApiResult.Ok -> LoginState.Idle
                is ApiResult.Err -> LoginState.Error(r.error.userMessage)
            }
        }
    }

    fun logout() = session.logout()

    fun selectFilter(filter: InboxFilter) {
        _filter.value = filter
        if (filter == InboxFilter.RESOLVED) viewModelScope.launch { session.refreshResolved() }
    }

    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            try {
                session.catchUp("pull-to-refresh")
                if (_filter.value == InboxFilter.RESOLVED) session.refreshResolved()
            } finally {
                _refreshing.value = false
            }
        }
    }
}
