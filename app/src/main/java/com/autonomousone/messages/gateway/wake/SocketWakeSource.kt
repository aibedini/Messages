package com.autonomousone.messages.gateway.wake

import android.util.Log
import com.autonomousone.messages.repository.GatewaySyncRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** The four ACTIVITY inputs, sampled from the production components once per activity tick. */
data class WakeActivitySignals(
    val webClientLive: Boolean = false,
    val appForeground: Boolean = false,
    val lastMessageAt: Long? = null,
    val outboxPending: Boolean = false,
)

fun interface WakeActivitySignalSource {
    fun read(): WakeActivitySignals
}

/**
 * Everything the socket needs that is not a secret. Kept as a value so the tests can shrink every
 * interval instead of waiting four minutes.
 */
data class SocketWakeConfig(
    val endpoint: WakeSocketEndpoint,
    val deviceId: String,
    val pins: WakeCertificatePins = WakeCertificatePins(),
    /** STAGE-4 §3: 4 minutes. Configurable because a reverse proxy may idle-close sooner. */
    val pingIntervalMs: Long = 240_000L,
    /** How often the ACTIVITY/IDLE window is re-evaluated. */
    val activityPollMs: Long = 15_000L,
    /** A server hint is a lease, not a permanent promise: it expires if nothing refreshes it. */
    val webClientHintTtlMs: Long = 10 * 60_000L,
    val wakeFloorMs: Long = 1_000L,
    val rateWindowMs: Long = 60_000L,
    val rateCeilingPerWindow: Int = 60,
    val abusiveWindowsBeforeDisconnect: Int = 2,
)

/**
 * Self-hosted push over WSS (STAGE-4).
 *
 * The whole message vocabulary is `{"t":"wake"}` (optionally with `seq`). The source reads the
 * `t` field and **nothing else**: a frame carrying recipients or bodies is not a command, it is an
 * unknown frame that is counted and ignored. A wake only pokes `OutboxPoller.retryNow()`; content
 * keeps arriving exclusively over the existing authenticated REST path.
 *
 * POLLING is never removed. In SOCKET mode the pollers still run as the permanent floor; this source
 * adds prompt wakes while the activity window is ACTIVE. Every failure falls back to the floor and
 * is reported with its [WakeUnavailable] reason.
 *
 * A 401 on the upgrade is a transport failure, not proof that this device's identity is invalid.
 * This class has no credential-wipe path at all (STAGE-4 §4.4).
 */
class SocketWakeSource(
    private val config: SocketWakeConfig,
    private val signer: WakeHandshakeSigner,
    /** Ensures the POLLING floor is running. Idempotent by contract. */
    private val startPollingFallback: () -> Unit = {},
    /** Production health sink; counts and enums only. */
    private val onHealth: (WakeHealth) -> Unit = {},
    private val activitySignals: WakeActivitySignalSource = WakeActivitySignalSource {
        WakeActivitySignals()
    },
    private val clock: () -> Long = System::currentTimeMillis,
    private val backoffMs: (Int) -> Long = { attempt ->
        GatewaySyncRepository.Policy.backoffDelayMs(attempt, Random.Default)
            .coerceAtLeast(500L)
    },
    baseClient: OkHttpClient = OkHttpClient(),
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "wake-socket").apply { isDaemon = true }
        },
) : WakeSource {

    private companion object {
        const val TAG = "WAKE_SOCKET"
        const val WAKE_TYPE = "wake"
        const val WAKE_ACTIVE_HEADER = "X-Wake-Active"
        const val CLOSE_NORMAL = 1000
        val EMPTY_BODY = ByteArray(0)
    }

    override val id = WakeSourceId.SOCKET

    private val lock = Any()

    private val client: OkHttpClient = baseClient.newBuilder()
        .pingInterval(config.pingIntervalMs, TimeUnit.MILLISECONDS)
        .apply {
            config.pins.certificatePinner(config.endpoint.host)?.let { certificatePinner(it) }
        }
        .build()

    private val initialPinning =
        if (config.pins.configured) WakePinning.ENABLED else WakePinning.DISABLED

    private val _health = MutableStateFlow(
        WakeHealth(
            id = id,
            active = false,
            pinning = initialPinning,
            pingIntervalMs = config.pingIntervalMs
        )
    )
    override val health: StateFlow<WakeHealth> = _health.asStateFlow()

    @Volatile
    private var onWake: (() -> Unit)? = null

    private var started = false
    private var desired = false
    private var intentionalClose = false

    private var webSocket: WebSocket? = null
    private var socketState = WakeSocketState.CLOSED
    private var lastSocketChangeAt = 0L
    private var lastAttemptAt = 0L
    private var lastWakeAt = 0L

    private var activityState = WakeActivityState()
    private var serverHintAt = 0L
    private var serverSaysWebClientLive = false

    private var activityFuture: ScheduledFuture<*>? = null
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var reconnectAttempt = 0
    private var reconnectCount = 0
    private var consecutiveFailures = 0
    private var wakeCount = 0L
    private var suppressedWakes = 0L
    private var unknownFrames = 0L

    private var pinning = initialPinning
    private var unavailable: WakeUnavailable? = null

    private var rateLimiter = newRateLimiter()

    private fun newRateLimiter() = WakeRateLimiter(
        minWakeIntervalMs = config.wakeFloorMs,
        windowMs = config.rateWindowMs,
        ceilingPerWindow = config.rateCeilingPerWindow,
        abusiveWindowsBeforeDisconnect = config.abusiveWindowsBeforeDisconnect,
    )

    /**
     * Ready to run on this device now: a WSS endpoint, a device id, no typo in the pins.
     *
     * `ws://` cannot get this far: [WakeSocketEndpoint] only derives `wss://` from an HTTPS origin,
     * and its init rejects anything else.
     */
    override fun isAvailable(): Boolean =
        config.endpoint.url.startsWith("wss://") &&
            config.deviceId.isNotBlank() &&
            !config.pins.invalidInput

    /** Which source is actually delivering wake-ups right now (for the diagnostic card). */
    fun currentSourceId(): WakeSourceId = synchronized(lock) {
        if (socketState == WakeSocketState.OPEN) WakeSourceId.SOCKET else WakeSourceId.POLLING
    }

    override fun start(onWake: () -> Unit) {
        synchronized(lock) {
            this.onWake = onWake
            if (started) return
            started = true
            desired = true
            rateLimiter = newRateLimiter()
            val now = clock()
            activityState = WakeActivityPolicy.next(null, inputsLocked(now))
            lastAttemptAt = now
            if (!isAvailable()) {
                failLocked(WakeUnavailable.NOT_CONFIGURED)
                return
            }
            if (activityState.activity == WakeActivity.ACTIVE) connectLocked()
            scheduleActivityLocked()
        }
    }

    override fun stop() {
        synchronized(lock) {
            if (!started) return
            started = false
            desired = false
            onWake = null
            activityFuture?.cancel(false)
            activityFuture = null
            reconnectFuture?.cancel(false)
            reconnectFuture = null
            intentionalClose = true
            webSocket?.close(CLOSE_NORMAL, "gateway stopped")
            webSocket = null
            setSocketStateLocked(WakeSocketState.CLOSED)
            publishLocked()
        }
    }

    // --- Connect / reconnect ------------------------------------------------

    private fun connectLocked() {
        if (!desired || socketState != WakeSocketState.CLOSED) return
        if (!isAvailable()) {
            failLocked(WakeUnavailable.NOT_CONFIGURED)
            return
        }
        val headers = runCatching {
            signer.sign(config.deviceId, WakeSocketEndpoint.PATH, "GET", EMPTY_BODY)
        }.getOrNull()
        if (headers == null) {
            // Fail closed: an unsigned upgrade is never attempted (STAGE-4 §1).
            Log.w(TAG, "handshake signing unavailable - not connecting")
            failLocked(WakeUnavailable.TRANSPORT_FAILED)
            return
        }
        val request = Request.Builder()
            .url(config.endpoint.url)
            .header("X-Agent-Auth", headers.agentAuth)
            .header("X-Agent-TS", headers.timestamp.toString())
            .build()
        lastAttemptAt = clock()
        intentionalClose = false
        setSocketStateLocked(WakeSocketState.CONNECTING)
        publishLocked()
        try {
            webSocket = client.newWebSocket(request, listener)
        } catch (e: Exception) {
            Log.w(TAG, "websocket construction failed: ${e.javaClass.simpleName}")
            failLocked(WakeUnavailable.TRANSPORT_FAILED)
        }
    }

    private fun scheduleReconnectLocked() {
        if (!desired || activityState.activity != WakeActivity.ACTIVE) return
        // A permanently unusable configuration (plaintext origin, missing device id, a typo in a
        // pin) is not a transient network failure: retrying it forever would be the silent loop
        // this programme exists to remove.
        if (!isAvailable()) return
        reconnectFuture?.cancel(false)
        val attempt = reconnectAttempt++
        val delay = runCatching { backoffMs(attempt) }.getOrDefault(2_000L).coerceAtLeast(0L)
        reconnectFuture = scheduler.schedule(
            {
                synchronized(lock) {
                    if (desired && activityState.activity == WakeActivity.ACTIVE) connectLocked()
                }
            },
            delay,
            TimeUnit.MILLISECONDS
        )
    }

    // --- Frames -------------------------------------------------------------

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                if (!desired || this@SocketWakeSource.webSocket !== webSocket) {
                    webSocket.close(CLOSE_NORMAL, null)
                    return
                }
                intentionalClose = false
                consecutiveFailures = 0
                reconnectAttempt = 0
                unavailable = null
                val hint = response.header(WAKE_ACTIVE_HEADER)?.trim()
                serverSaysWebClientLive = hint == "1" || hint.equals("true", ignoreCase = true)
                serverHintAt = if (serverSaysWebClientLive) clock() else 0L
                pinning = if (config.pins.configured) WakePinning.ENABLED else WakePinning.DISABLED
                setSocketStateLocked(WakeSocketState.OPEN)
                publishLocked()
                // A hint on the upgrade response is the only server-to-client ACTIVITY signal on
                // this channel. Apply it without waiting a full activity poll.
                evaluateActivityLocked()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            onInboundFrame(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // The only permitted frame is text JSON. Binary is unknown, counted, never parsed.
            onInboundFrame(null)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(CLOSE_NORMAL, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handleDrop(webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            handleFailure(webSocket, t)
        }
    }

    private fun onInboundFrame(text: String?) {
        var fireWake = false
        synchronized(lock) {
            if (!desired || socketState != WakeSocketState.OPEN) return
            val now = clock()
            val decision = rateLimiter.onFrame(now)
            if (decision == WakeRateLimiter.Decision.ABUSE) {
                Log.w(TAG, "wake server exceeded the frame ceiling - disconnecting")
                failLocked(WakeUnavailable.TRANSPORT_FAILED)
                return
            }
            if (!isWakeFrame(text)) {
                unknownFrames++
                publishLocked()
                return
            }
            if (decision == WakeRateLimiter.Decision.WAKE) {
                wakeCount++
                lastWakeAt = now
                fireWake = true
            } else {
                suppressedWakes++
            }
            publishLocked()
        }
        if (fireWake) onWake?.invoke()
    }

    /**
     * Reads ONLY `t`. Every other key is deliberately never looked up: a payload field cannot
     * become behaviour because there is no code path that asks for it.
     */
    private fun isWakeFrame(text: String?): Boolean {
        if (text == null) return false
        return runCatching { JSONObject(text).optString("t", "") == WAKE_TYPE }.getOrDefault(false)
    }

    private fun handleFailure(webSocket: WebSocket, error: Throwable) {
        synchronized(lock) {
            if (!desired || intentionalClose || this@SocketWakeSource.webSocket !== webSocket) return
            val mismatch = generateSequence(error as Throwable?) { it.cause }
                .any { it is javax.net.ssl.SSLPeerUnverifiedException }
            if (mismatch) {
                pinning = WakePinning.MISMATCH
                Log.w(TAG, "certificate pin mismatch - falling back to polling")
            }
            Log.w(TAG, "socket failure: ${error.javaClass.simpleName}")
            failLocked(WakeUnavailable.TRANSPORT_FAILED)
        }
    }

    private fun handleDrop(webSocket: WebSocket) {
        synchronized(lock) {
            if (!desired || intentionalClose || this.webSocket !== webSocket) {
                intentionalClose = false
                return
            }
            failLocked(WakeUnavailable.TRANSPORT_FAILED)
        }
    }

    /**
     * One failure path for every way a socket can stop being useful: connect refusal (including
     * 401/404 when the server has not deployed `/gateway/wake`), pin mismatch, protocol abuse, an
     * abrupt drop, or the server closing. Always: close, report, fall back to POLLING, back off.
     *
     * There is deliberately no call to any credential or enrollment API here. A rejected upgrade is
     * not evidence that the whole identity is invalid (STAGE-4 §4.4).
     */
    private fun failLocked(reason: WakeUnavailable) {
        intentionalClose = true
        webSocket?.cancel()
        webSocket = null
        setSocketStateLocked(WakeSocketState.CLOSED)
        unavailable = reason
        consecutiveFailures++
        reconnectCount++
        publishLocked()
        startPollingFallback()
        scheduleReconnectLocked()
    }

    // --- ACTIVITY / IDLE ----------------------------------------------------

    private fun inputsLocked(now: Long): WakeActivityInputs {
        val signals = runCatching { activitySignals.read() }.getOrDefault(WakeActivitySignals())
        val hintLive = serverSaysWebClientLive &&
            serverHintAt > 0L &&
            now - serverHintAt <= config.webClientHintTtlMs
        return WakeActivityInputs(
            webClientLive = hintLive || signals.webClientLive,
            appForeground = signals.appForeground,
            lastMessageAt = signals.lastMessageAt,
            outboxPending = signals.outboxPending,
            now = now
        )
    }

    private fun evaluateActivityLocked() {
        val previous = activityState.activity
        val next = WakeActivityPolicy.next(activityState, inputsLocked(clock()))
        activityState = next
        if (next.activity != previous) {
            if (next.activity == WakeActivity.ACTIVE) {
                connectLocked()
            } else {
                closeForIdleLocked()
            }
        }
        publishLocked()
    }

    private fun closeForIdleLocked() {
        intentionalClose = true
        webSocket?.close(CLOSE_NORMAL, "idle")
        webSocket = null
        setSocketStateLocked(WakeSocketState.CLOSED)
        unavailable = null
        publishLocked()
    }

    private fun scheduleActivityLocked() {
        activityFuture?.cancel(false)
        activityFuture = scheduler.schedule(
            {
                synchronized(lock) {
                    if (!desired) return@synchronized
                    evaluateActivityLocked()
                    scheduleActivityLocked()
                }
            },
            config.activityPollMs,
            TimeUnit.MILLISECONDS
        )
    }

    // --- Health -------------------------------------------------------------

    private fun setSocketStateLocked(state: WakeSocketState) {
        if (socketState != state) {
            socketState = state
            lastSocketChangeAt = clock()
        }
    }

    private fun publishLocked() {
        val snapshot = _health.value.copy(
            active = desired && socketState == WakeSocketState.OPEN,
            unavailable = unavailable,
            lastWakeAt = lastWakeAt,
            lastAttemptAt = lastAttemptAt,
            consecutiveFailures = consecutiveFailures,
            wakeCount = wakeCount,
            socketState = socketState,
            lastSocketChangeAt = lastSocketChangeAt,
            reconnectCount = reconnectCount,
            suppressedWakes = suppressedWakes,
            unknownFrames = unknownFrames,
            activity = activityState.activity,
            activityReason = activityState.reason,
            pinning = pinning,
            pingIntervalMs = config.pingIntervalMs
        )
        _health.value = snapshot
        runCatching { onHealth(snapshot) }
    }
}
