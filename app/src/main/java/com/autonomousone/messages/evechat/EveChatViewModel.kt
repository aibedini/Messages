package com.autonomousone.messages.evechat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.autonomousone.messages.R
import androidx.lifecycle.viewModelScope
import com.autonomousone.messages.evechat.EveChatSession.InboxFilter
import com.autonomousone.messages.evechat.model.EveChatConversation
import com.autonomousone.messages.evechat.push.EveChatPushRegistrar
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
    val myAgentName: StateFlow<String?> = session.myAgentName
    val pushStatus: StateFlow<EveChatPushRegistrar.Status> = session.pushStatus
    val lastPushAt: Long get() = session.push.lastPushAt

    val savedServer: String get() = session.lastServer
    val savedEmail: String get() = session.lastEmail

    fun login(server: String, email: String, password: String) {
        if (_loginState.value is LoginState.Loading) return
        val normalized = EveChatServerUrl.normalize(server)
        if (normalized is EveChatServerUrl.Result.Invalid) {
            _loginState.value = LoginState.Error(getApplication<Application>().getString(normalized.reasonRes))
            return
        }
        _loginState.value = LoginState.Loading
        viewModelScope.launch {
            _loginState.value = when (val r = session.login(server, email, password)) {
                is ApiResult.Ok -> LoginState.Idle
                // A rejected sign-in is not an "expired session": say what the user can fix.
                is ApiResult.Err -> LoginState.Error(
                    if (r.error.kind == ApiError.Kind.UNAUTHORIZED) {
                        getApplication<Application>().getString(R.string.eve_login_failed)
                    } else {
                        r.error.message(getApplication())
                    }
                )
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
