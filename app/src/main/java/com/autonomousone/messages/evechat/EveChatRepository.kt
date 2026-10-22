package com.autonomousone.messages.evechat

import com.autonomousone.messages.evechat.model.EveChatAgent
import com.autonomousone.messages.evechat.model.EveChatConversation
import com.autonomousone.messages.evechat.model.EveChatMessage
import com.autonomousone.messages.evechat.network.EveChatSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class EveChatRepository(
    private val prefs: EveChatPreferences,
    private val scope: CoroutineScope
) {
    val socket = EveChatSocket(scope)

    private val _conversations = MutableStateFlow<List<EveChatConversation>>(emptyList())
    val conversations: StateFlow<List<EveChatConversation>> = _conversations.asStateFlow()

    private val _currentAgent = MutableStateFlow<EveChatAgent?>(null)
    val currentAgent: StateFlow<EveChatAgent?> = _currentAgent.asStateFlow()

    private val _messages = MutableStateFlow<List<EveChatMessage>>(emptyList())
    val messages: StateFlow<List<EveChatMessage>> = _messages.asStateFlow()

    private var currentConversationId: String? = null

    init {
        scope.launch { collectSocketEvents() }
    }

    fun connect() {
        if (prefs.isLoggedIn) {
            socket.connect(prefs.serverUrl, prefs.accessToken)
        }
    }

    fun disconnect() {
        socket.disconnect()
    }

    suspend fun fetchMe() {
        val result = EveChatApiClient.getMe(prefs.serverUrl, prefs.accessToken)
        if (result is EveChatApiClient.Result.Success) {
            _currentAgent.value = result.data.agent
        }
    }

    suspend fun loadConversations(status: String? = null): Boolean {
        val agentId = if (status == "mine") "me" else null
        val actualStatus = if (status == "mine") null else status
        val result = EveChatApiClient.listConversations(
            prefs.serverUrl, prefs.accessToken,
            status = actualStatus, agentId = agentId
        )
        return when (result) {
            is EveChatApiClient.Result.Success -> {
                _conversations.value = result.data.data
                true
            }
            is EveChatApiClient.Result.Error -> false
        }
    }

    suspend fun loadMessages(conversationId: String): Boolean {
        currentConversationId = conversationId
        val result = EveChatApiClient.getConversation(
            prefs.serverUrl, prefs.accessToken, conversationId
        )
        return when (result) {
            is EveChatApiClient.Result.Success -> {
                _messages.value = result.data.messages
                true
            }
            is EveChatApiClient.Result.Error -> false
        }
    }

    fun clearMessages() {
        currentConversationId = null
        _messages.value = emptyList()
    }

    suspend fun sendMessage(conversationId: String, content: String): Boolean {
        val clientId = UUID.randomUUID().toString()
        val agent = _currentAgent.value
        val optimistic = EveChatMessage(
            id = clientId,
            conversationId = conversationId,
            senderType = "agent",
            senderId = agent?.id,
            senderName = agent?.name,
            content = content,
            clientMessageId = clientId,
            sendState = EveChatMessage.SendState.Sending
        )
        _messages.value = _messages.value + optimistic

        val result = EveChatApiClient.sendMessage(
            prefs.serverUrl, prefs.accessToken,
            conversationId, content, clientId
        )
        return when (result) {
            is EveChatApiClient.Result.Success -> {
                _messages.value = _messages.value.map {
                    if (it.clientMessageId == clientId) result.data else it
                }
                true
            }
            is EveChatApiClient.Result.Error -> {
                _messages.value = _messages.value.map {
                    if (it.clientMessageId == clientId) it.copy(sendState = EveChatMessage.SendState.Failed)
                    else it
                }
                false
            }
        }
    }

    suspend fun claimConversation(conversationId: String): Boolean {
        val result = EveChatApiClient.claimConversation(
            prefs.serverUrl, prefs.accessToken, conversationId
        )
        return result is EveChatApiClient.Result.Success
    }

    suspend fun resolveConversation(conversationId: String): Boolean {
        val result = EveChatApiClient.resolveConversation(
            prefs.serverUrl, prefs.accessToken, conversationId
        )
        return result is EveChatApiClient.Result.Success
    }

    suspend fun reopenConversation(conversationId: String): Boolean {
        val result = EveChatApiClient.reopenConversation(
            prefs.serverUrl, prefs.accessToken, conversationId
        )
        return result is EveChatApiClient.Result.Success
    }

    suspend fun markRead(conversationId: String) {
        EveChatApiClient.markRead(prefs.serverUrl, prefs.accessToken, conversationId)
    }

    suspend fun refreshTokenIfNeeded(): Boolean {
        val result = EveChatApiClient.refreshToken(prefs.serverUrl, prefs.refreshToken)
        return when (result) {
            is EveChatApiClient.Result.Success -> {
                prefs.accessToken = result.data.accessToken
                prefs.refreshToken = result.data.refreshToken
                socket.updateToken(result.data.accessToken)
                true
            }
            is EveChatApiClient.Result.Error -> false
        }
    }

    private suspend fun collectSocketEvents() {
        socket.events.collect { event ->
            when (event) {
                is EveChatSocket.Event.ConversationNew -> {
                    _conversations.value = listOf(event.conversation) + _conversations.value
                }
                is EveChatSocket.Event.MessageReceived -> {
                    if (event.message.conversationId == currentConversationId) {
                        val existing = _messages.value.any { it.id == event.message.id }
                        if (!existing) _messages.value = _messages.value + event.message
                    }
                    updateConversationPreview(event.message)
                }
                is EveChatSocket.Event.MessageAck -> {
                    _messages.value = _messages.value.map {
                        if (it.clientMessageId == event.clientMessageId) event.message else it
                    }
                }
                is EveChatSocket.Event.ConversationUpdated -> {
                    _conversations.value = _conversations.value.map {
                        if (it.id == event.conversation.id) event.conversation else it
                    }
                }
                is EveChatSocket.Event.ConversationResolved -> {
                    _conversations.value = _conversations.value.map {
                        if (it.id == event.conversationId) it.copy(status = "resolved") else it
                    }
                }
                is EveChatSocket.Event.AgentAssigned -> {
                    _conversations.value = _conversations.value.map {
                        if (it.id == event.conversationId)
                            it.copy(assignedAgentId = event.agentId) else it
                    }
                }
                is EveChatSocket.Event.VisitorTyping -> {}
            }
        }
    }

    private fun updateConversationPreview(msg: EveChatMessage) {
        _conversations.value = _conversations.value.map {
            if (it.id == msg.conversationId) {
                it.copy(
                    lastMessagePreview = msg.content,
                    lastMessageAt = msg.createdAt,
                    unreadAgent = if (msg.conversationId != currentConversationId)
                        it.unreadAgent + 1 else it.unreadAgent
                )
            } else it
        }
    }
}
