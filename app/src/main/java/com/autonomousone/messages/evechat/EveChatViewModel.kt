package com.autonomousone.messages.evechat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class EveChatViewModel(application: Application) : AndroidViewModel(application) {

    val prefs = EveChatPreferences(application)

    sealed class LoginState {
        data object Idle : LoginState()
        data object Loading : LoginState()
        data object Success : LoginState()
        data class Error(val message: String) : LoginState()
    }

    private val _loginState = MutableStateFlow<LoginState>(
        if (prefs.isLoggedIn) LoginState.Success else LoginState.Idle
    )
    val loginState: StateFlow<LoginState> = _loginState.asStateFlow()

    private val _serverUrl = MutableStateFlow(prefs.serverUrl)
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

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
                }
                is EveChatApiClient.Result.Error -> {
                    _loginState.value = LoginState.Error(result.message)
                }
            }
        }
    }

    fun logout() {
        prefs.clear()
        _loginState.value = LoginState.Idle
    }
}
