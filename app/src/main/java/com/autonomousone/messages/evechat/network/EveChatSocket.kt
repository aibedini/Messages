package com.autonomousone.messages.evechat.network

import com.autonomousone.messages.evechat.EveChatLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

/**
 * One supervised connection to `/ws/agent`.
 *
 * A single coroutine owns the whole lifecycle (connect → read → close → back off → reconnect),
 * so there is never more than one live socket and a callback from a dead socket cannot change
 * state. OkHttp's transport ping detects half-open TCP; the server's application `ping` is
 * answered with `pong`. A handshake rejected with 401 asks [refreshToken] once before retrying.
 */
class EveChatSocket(
    private val scope: CoroutineScope,
    private val endpoint: suspend () -> Pair<String, String>?,
    private val refreshToken: suspend (staleToken: String) -> Boolean
) {
    enum class State { IDLE, CONNECTING, CONNECTED, WAITING }

    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SocketEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<SocketEvent> = _events.asSharedFlow()

    private val live = AtomicReference<WebSocket?>(null)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null

    @Synchronized
    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch { runLoop() }
    }

    @Synchronized
    fun stop() {
        loop?.cancel()
        loop = null
        live.getAndSet(null)?.close(1000, "client stop")
        _state.value = State.IDLE
    }

    /** Skip the remaining backoff, e.g. when the network comes back or the app is opened. */
    fun reconnectNow() {
        wake.trySend(Unit)
    }

    fun send(frame: String): Boolean = live.get()?.send(frame) ?: false

    private suspend fun runLoop() {
        var attempt = 0
        var authRetries = 0
        while (scope.isActive) {
            val (server, token) = endpoint() ?: run {
                _state.value = State.IDLE
                return
            }
            _state.value = State.CONNECTING
            val outcome = try {
                connectOnce(server, token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EveChatLog.w("socket", "connect error", e)
                Outcome(opened = false, openMs = 0, httpCode = null)
            }

            if (outcome.httpCode == 401 || outcome.httpCode == 403) {
                authRetries++
                EveChatLog.w("socket", "handshake rejected", null, "status" to outcome.httpCode, "try" to authRetries)
                if (authRetries <= 2 && refreshToken(token)) continue
                _state.value = State.IDLE
                return
            }
            authRetries = 0
            attempt = if (outcome.opened && outcome.openMs > STABLE_MS) 1 else attempt + 1
            val delayMs = ReconnectBackoff.delayFor(attempt)
            _state.value = State.WAITING
            EveChatLog.i("socket", "reconnect scheduled", "attempt" to attempt, "delayMs" to delayMs, "status" to outcome.httpCode)
            withTimeoutOrNull(delayMs) { wake.receive() }
        }
    }

    private data class Outcome(val opened: Boolean, val openMs: Long, val httpCode: Int?)

    private sealed interface Signal {
        data object Open : Signal
        data class Text(val text: String) : Signal
        data class Closed(val httpCode: Int?, val reason: String) : Signal
    }

    private suspend fun connectOnce(server: String, token: String): Outcome {
        val signals = Channel<Signal>(Channel.UNLIMITED)
        val url = server.trimEnd('/').replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") +
            "/ws/agent?token=" + URLEncoder.encode(token, "UTF-8")
        val ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                signals.trySend(Signal.Open)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                signals.trySend(Signal.Text(text))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                signals.trySend(Signal.Closed(null, "closed code=$code"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                signals.trySend(Signal.Closed(response?.code, t.javaClass.simpleName))
            }
        })
        var openedAt = 0L
        try {
            for (signal in signals) {
                when (signal) {
                    Signal.Open -> {
                        openedAt = System.currentTimeMillis()
                        live.set(ws)
                        _state.value = State.CONNECTED
                        EveChatLog.i("socket", "connected")
                        _events.emit(SocketEvent.Connected)
                    }
                    is Signal.Text -> {
                        val event = EveChatEventParser.parse(signal.text)
                        if (event == null) {
                            EveChatLog.d("socket", "ignored frame", "bytes" to signal.text.length)
                        } else if (event == SocketEvent.Ping) {
                            ws.send(EveChatEventParser.command("pong"))
                        } else {
                            _events.emit(event)
                        }
                    }
                    is Signal.Closed -> {
                        val openMs = if (openedAt > 0) System.currentTimeMillis() - openedAt else 0
                        EveChatLog.i("socket", "disconnected", "reason" to signal.reason, "status" to signal.httpCode, "openMs" to openMs)
                        return Outcome(openedAt > 0, openMs, signal.httpCode)
                    }
                }
            }
            return Outcome(openedAt > 0, 0, null)
        } finally {
            live.compareAndSet(ws, null)
            ws.cancel()
        }
    }

    private companion object {
        const val STABLE_MS = 30_000L
    }
}
