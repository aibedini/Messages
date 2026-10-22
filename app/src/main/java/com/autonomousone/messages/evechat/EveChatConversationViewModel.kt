package com.autonomousone.messages.evechat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autonomousone.messages.evechat.model.EveChatConversation
import com.autonomousone.messages.evechat.model.EveChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class EveChatConversationViewModel(
    private val repository: EveChatRepository,
    private val conversationId: String
) : ViewModel() {

    val messages: StateFlow<List<EveChatMessage>> = repository.messages

    private val _conversation = MutableStateFlow<EveChatConversation?>(null)
    val conversation: StateFlow<EveChatConversation?> = _conversation.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        viewModelScope.launch {
            _isLoading.value = true
            repository.loadMessages(conversationId)
            _conversation.value = repository.conversations.value.find { it.id == conversationId }
            repository.markRead(conversationId)
            _isLoading.value = false
        }
    }

    fun sendMessage(content: String) {
        if (content.isBlank()) return
        viewModelScope.launch {
            repository.sendMessage(conversationId, content)
        }
    }

    fun claim() {
        viewModelScope.launch {
            if (repository.claimConversation(conversationId)) {
                _conversation.value = _conversation.value?.copy(
                    assignedAgentId = repository.currentAgent.value?.id,
                    status = "active"
                )
            }
        }
    }

    fun resolve() {
        viewModelScope.launch {
            if (repository.resolveConversation(conversationId)) {
                _conversation.value = _conversation.value?.copy(status = "resolved")
            }
        }
    }

    fun reopen() {
        viewModelScope.launch {
            if (repository.reopenConversation(conversationId)) {
                _conversation.value = _conversation.value?.copy(status = "active")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.clearMessages()
    }
}
