package com.autonomousone.messages.evechat.network

import android.util.Log
import com.autonomousone.messages.evechat.model.EveChatConversation
import com.autonomousone.messages.evechat.model.EveChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class EveChatSocket(private val scope: CoroutineScope) {

    enum class State { Disconnected, Connecting, Connected }

    sealed class Event {
        data class ConversationNew(val conversation: EveChatConversation) : Event()
        data class MessageReceived(val message: EveChatMessage) : Event()
        data class MessageAck(val clientMessageId: String, val message: EveChatMessage) : Event()
        data class ConversationUpdated(val conversation: EveChatConversation) : Event()
        data class ConversationResolved(val conversationId: String) : Event()
        data class VisitorTyping(val conversationId: String, val typing: Boolean) : Event()
        data class AgentAssigned(val conversationId: String, val agentId: String) : Event()
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var ws: WebSocket? = null
    private var reconnectJob: Job? = null
    private var backoffMs = 1000L
    private var serverUrl = ""
    private var token = ""

    private val _state = MutableStateFlow(State.Disconnected)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun connect(serverUrl: String, token: String) {
        this.serverUrl = serverUrl
        this.token = token
        doConnect()
    }

    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        ws?.close(1000, "bye")
        ws = null
        _state.value = State.Disconnected
    }

    fun updateToken(token: String) {
        this.token = token
    }

    fun send(type: String, data: JSONObject? = null, id: String? = null) {
        val envelope = JSONObject().apply {
            put("type", type)
            id?.let { put("id", it) }
            data?.let { put("data", it) }
        }
        ws?.send(envelope.toString())
    }

    private fun doConnect() {
        _state.value = State.Connecting
        val wsScheme = if (serverUrl.startsWith("https")) "wss" else "ws"
        val host = serverUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')
        val wsUrl = "$wsScheme://$host/ws/agent?token=$token"

        val request = Request.Builder().url(wsUrl).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _state.value = State.Connected
                backoffMs = 1000L
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _state.value = State.Disconnected
                if (code != 1000) scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w("EveChatSocket", "WS failure: ${t.message}")
                _state.value = State.Disconnected
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            if (_state.value == State.Disconnected) doConnect()
        }
    }

    private fun handleMessage(text: String) {
        try {
            val envelope = JSONObject(text)
            val type = envelope.optString("type", "")
            val data = envelope.optJSONObject("data")

            when (type) {
                "ping" -> send("pong")

                "conversation.new" -> data?.let {
                    _events.tryEmit(Event.ConversationNew(EveChatConversation.fromJson(it)))
                }

                "message.received", "message.sent" -> data?.let {
                    _events.tryEmit(Event.MessageReceived(EveChatMessage.fromJson(it)))
                }

                "message.ack" -> data?.let {
                    val clientId = it.optString("client_message_id", "")
                    if (clientId.isNotBlank()) {
                        _events.tryEmit(Event.MessageAck(clientId, EveChatMessage.fromJson(it)))
                    }
                }

                "conversation.updated" -> data?.let {
                    _events.tryEmit(Event.ConversationUpdated(EveChatConversation.fromJson(it)))
                }

                "conversation.resolved" -> data?.let {
                    _events.tryEmit(Event.ConversationResolved(it.optString("conversation_id", it.optString("id", ""))))
                }

                "visitor.typing" -> data?.let {
                    _events.tryEmit(Event.VisitorTyping(it.optString("conversation_id", ""), true))
                }

                "visitor.typing.stop" -> data?.let {
                    _events.tryEmit(Event.VisitorTyping(it.optString("conversation_id", ""), false))
                }

                "agent.assigned" -> data?.let {
                    _events.tryEmit(Event.AgentAssigned(
                        it.optString("conversation_id", ""),
                        it.optString("agent_id", "")
                    ))
                }
            }
        } catch (e: Exception) {
            Log.w("EveChatSocket", "Parse error: ${e.message}")
        }
    }
}
