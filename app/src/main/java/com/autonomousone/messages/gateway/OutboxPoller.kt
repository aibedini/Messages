package com.autonomousone.messages.gateway

import android.content.Context
import android.util.Log
import com.autonomousone.messages.eve.EveSmsQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Pull-based bridge to a GMweb-API server (github.com/aibedini/GMweb-API).
 *
 * The phone dials OUT to the server over HTTPS — no tunnel, no inbound port,
 * no static IP. The server hands over one queued send per request; the SMS is
 * delivered through the existing local [EveSmsQueue] (priority, persistence,
 * native SIM send) and the outcome is acked so the server's ledger updates.
 *
 *   GET  {gmwebUrl}/gateway/pull?waitMs=…  → {"task":{requestId,to,text}} | {"task":null}
 *   POST {gmwebUrl}/gateway/ack            → {"ok":true}
 *
 * Auth uses the phone's own gateway API key as X-API-Key; the GMweb side is
 * configured with the same key (GMWEB_ANDROID_DEVICE_KEY).
 */
class OutboxPoller(
    private val context: Context,
    private val prefs: GatewayPreferences,
    private val scope: CoroutineScope,
    private val onLog: (String) -> Unit = {},
) {
    companion object {
        private const val TAG = "OUTBOX_POLLER"
        private const val LONG_POLL_MS = 25_000L     // server hold when queue empty
        private const val ERROR_RETRY_MS = 5_000L    // backoff after a failed cycle
        private const val ACK_TIMEOUT_MS = 15_000L
        private const val PULL_TIMEOUT_MS = 40_000L   // long-poll + margin
    }

    enum class State { IDLE, POLLING, DELIVERING, ERROR }

    private val _stateFlow = MutableStateFlow(State.IDLE)
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    private var pollJob: Job? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        pollJob = scope.launch {
            _stateFlow.value = State.POLLING
            Log.i(TAG, "Outbox poller started")
            while (isActive) {
                if (!GatewayAccessPolicy.canTransmit(prefs.hasGatewayConsent, prefs.isEnabled)) {
                    _stateFlow.value = State.IDLE
                    break
                }
                try {
                    cycle()
                    _stateFlow.value = State.POLLING
                } catch (e: Exception) {
                    _stateFlow.value = State.ERROR
                    onLog("⚠️ Pull failed: ${e.message ?: "network error"} — retry in ${ERROR_RETRY_MS / 1000}s")
                    delay(ERROR_RETRY_MS)
                }
            }
        }
    }

    fun stop() {
        running = false
        pollJob?.cancel()
        pollJob = null
        _stateFlow.value = State.IDLE
    }

    /** One pull → deliver → ack round trip. Throws only on transport errors. */
    private suspend fun cycle() {
        val base = prefs.gmwebUrl.trimEnd('/')
        if (base.isBlank()) {
            // No GMweb configured — idle quietly; the LAN/cloud features still work.
            delay(30_000)
            return
        }
        _stateFlow.value = State.POLLING
        val task = pull(base) ?: return // long-poll returned empty

        _stateFlow.value = State.DELIVERING
        onLog("📨 Pulled ${task.requestId} → ${task.to}")
        // Deliver through the SAME priority queue the /send endpoint uses:
        // persistence across reboot, highest-first ordering, radio-level result.
        val result = EveSmsQueue.enqueue(task.to, task.text, task.priority.ifBlank { "announcement" }, null)

        val ok = drainUntilTerminal(result.record.requestId)
        ack(base, task.requestId, ok, if (ok) null else "device_send_failed")
        if (ok) onLog("✅ Delivered ${task.requestId}") else onLog("❌ Failed ${task.requestId}")
    }

    private suspend fun pull(base: String): Task? {
        val conn = open("${base}/gateway/pull?waitMs=$LONG_POLL_MS", "GET", PULL_TIMEOUT_MS)
        return try {
            if (conn.responseCode != 200) throw IllegalStateException("pull HTTP ${conn.responseCode}")
            val body = conn.inputStream.use { it.bufferedReader().readText() }
            val json = JSONObject(body)
            val t = json.optJSONObject("task") ?: return null
            Task(
                requestId = t.getString("requestId"),
                to = t.getString("to"),
                text = t.getString("text"),
                priority = t.optString("priority", "announcement")
            )
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Wait for the local EveSmsQueue record to reach a terminal status.
     * ponytail: bounded polling of an in-memory map — cheap and exact enough;
     * no callback plumbing needed for a single-record wait.
     */
    private suspend fun drainUntilTerminal(requestId: String): Boolean {
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            EveSmsQueue.drainOne() // drive the queue even if its worker thread is busy
            val rec = EveSmsQueue.status(requestId)
            if (rec == null || rec.terminal) return rec?.successful == true
            delay(500)
        }
        return false
    }

    private fun ack(base: String, requestId: String, ok: Boolean, reason: String?) {
        val conn = open("$base/gateway/ack", "POST", ACK_TIMEOUT_MS)
        try {
            val payload = JSONObject().apply {
                put("requestId", requestId)
                put("ok", ok)
                if (!ok && reason != null) put("reason", reason)
                put("sentAt", System.currentTimeMillis())
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val accepted = conn.responseCode in 200..299
            if (!accepted) Log.w(TAG, "ack HTTP ${conn.responseCode} for $requestId")
        } catch (e: Exception) {
            // A lost ack must NOT re-send locally; the server times the task out.
            Log.w(TAG, "ack failed for $requestId: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, method: String, timeoutMs: Long): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = method
            setRequestProperty("X-API-Key", prefs.apiKey)
            setRequestProperty("Accept", "application/json")
            connectTimeout = 15_000
            readTimeout = timeoutMs.toInt()
            if (method == "POST") {
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
        }
        return conn
    }

    data class Task(val requestId: String, val to: String, val text: String, val priority: String)
}
