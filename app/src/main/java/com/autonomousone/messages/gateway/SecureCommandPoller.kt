package com.autonomousone.messages.gateway

import android.content.Context
import android.util.Log
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.RemoteCommandEntity
import com.autonomousone.messages.repository.GatewaySyncRepository
import com.autonomousone.messages.sms.GatewayOutgoingPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * PR-10 (TechSpec §40/§57/§58, LOCK 4): the strategic command transport.
 *
 * Outbound-only HTTPS long-poll against GMweb's /api/v1/agent/commands/claim.
 * Correctness comes from the DURABLE remote_commands queue (PR-01 tables),
 * never from the connection:
 *
 *   claim  → atomically QUEUED→DELIVERED_TO_AGENT server-side
 *   ingest → INSERT OR IGNORE by idempotencyKey (exactly-once; redelivery
 *            surfaces the existing row and is dropped here — §71)
 *   ACK    → guarded lifecycle ACCEPTED→EXECUTING→COMPLETED/FAILED (§58)
 *
 * The legacy [OutboxPoller] (/gateway/pull + EveSmsQueue) stays untouched and
 * remains the compatibility transport while the executor (Phase 3) learns to
 * drain remote_commands; until then this poller ingests and reports but hands
 * SEND_SMS rows to [GatewayOutgoingPipeline] which honours the same flag.
 *
 * Availability contract (ADR-003): this loop runs while
 * [ConnectionSupervisor] declares the gateway CONNECTED; <3s pickup SLO
 * applies only when the app process is alive (AGENT_AVAILABLE).
 */
class SecureCommandPoller(
    private val context: Context,
    private val prefs: GatewayPreferences,
    private val scope: CoroutineScope,
    private val onLog: (String) -> Unit = {},
) {
    companion object {
        private const val TAG = "CMD_POLLER"
        private const val CLAIM_PATH = "/api/v1/agent/commands/claim"
        private const val STATUS_PATH = "/api/v1/agent/commands/%s/status"
        private const val QUIET_MS = 2_000L        // empty-claim cadence (§57)
        private const val ERROR_RETRY_MS = 5_000L
        private const val CLAIM_LIMIT = 25

        internal fun buildClaimBody(deviceId: String): ByteArray = JSONObject()
            .put("agentId", deviceId)
            .put("limit", CLAIM_LIMIT)
            .toString()
            .toByteArray(Charsets.UTF_8)

        internal fun decodeCiphertext(command: JSONObject): ByteArray? {
            val encoded = command.optString("ciphertext")
            if (encoded.isBlank()) return null
            return runCatching {
                java.util.Base64.getDecoder().decode(encoded)
            }.getOrNull()?.takeIf { it.isNotEmpty() }
        }
    }

    enum class State { IDLE, POLLING, INGESTING, ERROR }

    private val repo = GatewaySyncRepository(MessagesDatabase.get(context.applicationContext))
    private var job: Job? = null
    private val _stateFlow = MutableStateFlow(State.IDLE)
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    /** Stable device id — the SAME one sent at registration (PR-05/08b). */
    private fun deviceId(): String = prefs.gatewayId.ifBlank {
        android.provider.Settings.Secure.getString(
            context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown-device"
    }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                if (!prefs.isEnabled || prefs.gmwebUrl.isBlank()) {
                    // Same runtime gate as EventUploader: the supervisor owns
                    // enablement; zero HTTP until CONNECTED.
                    _stateFlow.value = State.IDLE
                    delay(5_000)
                    continue
                }
                _stateFlow.value = State.POLLING
                try {
                    val commands = claim()
                    if (commands.isEmpty()) {
                        delay(QUIET_MS)
                        continue
                    }
                    _stateFlow.value = State.INGESTING
                    var fresh = 0
                    for (cmd in commands) {
                        val accepted = ingest(cmd)
                        if (accepted) {
                            fresh++
                            ack(cmd.commandId, "ACCEPTED", null)
                            executeIfSendSms(cmd)
                        } else {
                            // Redelivery of an already-ingested command: it is
                            // EITHER completed (durable state) or executing —
                            // report honestly; never double-execute (§71).
                            ackIfTerminal(cmd.commandId)
                        }
                    }
                    if (fresh > 0) onLog("📥 $fresh new command(s) ingested")
                } catch (e: Exception) {
                    Log.w(TAG, "poll cycle failed: ${e.message}")
                    _stateFlow.value = State.ERROR
                    delay(ERROR_RETRY_MS)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _stateFlow.value = State.IDLE
    }

    /** POST /api/v1/agent/commands/claim → opaque rows (base64 payloads). */
    private suspend fun claim(): List<RemoteCommandEntity> {
        val base = prefs.gmwebUrl.trimEnd('/')
        val path = "/api/v1/agent/commands/claim"
        val conn = URL("$base$path").openConnection() as HttpURLConnection
        return try {
            conn.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connectTimeout = 15_000
                readTimeout = 30_000
                doOutput = true
            }
            val currentDeviceId = deviceId()
            val bodyBytes = buildClaimBody(currentDeviceId)
            // PR-08b: per-device signature over the canonical request (ADR-001).
            // X-API-Key stays as a legacy fallback for older GMweb builds.
            conn.setRequestProperty("X-API-Key", prefs.apiKey)
            if (!AgentAuth.sign(conn, currentDeviceId, path, "POST", bodyBytes)) {
                throw IllegalStateException("agent signing failed (keystore unavailable)")
            }
            conn.outputStream.use { it.write(bodyBytes) }
            if (conn.responseCode != 200) {
                throw IllegalStateException("claim HTTP ${conn.responseCode}")
            }
            val body = conn.inputStream.use { it.bufferedReader().readText() }
            val rows = JSONObject(body).optJSONArray("commands") ?: JSONArray()
            val now = System.currentTimeMillis()
            (0 until rows.length()).mapNotNull { i ->
                val c = rows.optJSONObject(i) ?: return@mapNotNull null
                val id = c.optString("id")
                val idem = c.optString("idempotencyKey")
                if (id.isBlank() || idem.isBlank()) return@mapNotNull null
                val ciphertext = decodeCiphertext(c)
                if (ciphertext == null) {
                    Log.e(TAG, "protocol_error: command $id has missing or invalid ciphertext")
                    ack(id, "FAILED", "protocol_error: missing or invalid ciphertext")
                    return@mapNotNull null
                }
                RemoteCommandEntity(
                    commandId = id,
                    type = c.optString("type", "UNKNOWN"),
                    ciphertext = ciphertext,
                    encoding = c.optString("encoding", "application/json"),
                    schemaVersion = c.optInt("schemaVersion", 1),
                    cryptoVersion = c.optInt("cryptoVersion", 0),
                    signature = runCatching {
                        android.util.Base64.decode(c.optString("clientSignature", ""), android.util.Base64.NO_WRAP)
                    }.getOrDefault(ByteArray(0)),
                    issuedAt = c.optLong("createdAt", now),
                    receivedAt = now,
                    expiresAt = c.optLong("expiresAt", 0L),
                    idempotencyKey = idem,
                    state = RemoteCommandEntity.STATE_RECEIVED
                )
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Exactly-once ingest: INSERT OR IGNORE by unique idempotencyKey.
     * True = fresh command (caller executes + ACKs); False = redelivery.
     */
    private suspend fun ingest(cmd: RemoteCommandEntity): Boolean =
        withContext(Dispatchers.IO) { repo.ingestCommand(cmd) }

    /** SEND_SMS executes immediately through the single funnel (§19/§20). */
    private suspend fun executeIfSendSms(cmd: RemoteCommandEntity) {
        if (cmd.type != "SEND_SMS") return
        scope.launch {
            try {
                val done = withContext(Dispatchers.IO) {
                    GatewayOutgoingPipeline.executeIngested(cmd, repo)
                }
                // executeIngested reports terminal state itself (COMPLETED/FAILED)
                if (!done) ackIfTerminal(cmd.commandId)
            } catch (e: Exception) {
                Log.e(TAG, "SEND_SMS execution failed for ${cmd.commandId}", e)
                runCatching {
                    repo.markCommandState(
                        cmd.commandId, RemoteCommandEntity.STATE_FAILED,
                        listOf(RemoteCommandEntity.STATE_ACCEPTED, RemoteCommandEntity.STATE_EXECUTING)
                    )
                    ack(cmd.commandId, "FAILED", e.message ?: "execution error")
                }
            }
        }
    }

    /** Report lifecycle to GMweb (§58); failures are logged, never fatal. */
    private suspend fun ack(commandId: String, state: String, result: String?) {
        val base = prefs.gmwebUrl.trimEnd('/')
        val path = "/api/v1/agent/commands/$commandId/status"
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
            }
            val bodyBytes = JSONObject()
                .put("state", state)
                .put("result", result ?: JSONObject.NULL)
                .toString().toByteArray(Charsets.UTF_8)
            conn.setRequestProperty("X-API-Key", prefs.apiKey)
            AgentAuth.sign(conn, deviceId(), path, "POST", bodyBytes)
            conn.outputStream.use { it.write(bodyBytes) }
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "ack $state for $commandId → HTTP ${conn.responseCode}")
            }
        } catch (e: Exception) {
            // A lost ack must not re-execute; the durable state row is truth.
            Log.w(TAG, "ack failed for $commandId: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    /** Report the DURABLE local state of a redelivered command (§58 honest). */
    private suspend fun ackIfTerminal(commandId: String) {
        val cmd = withContext(Dispatchers.IO) { repo.getCommand(commandId) } ?: return
        val resultText = String(cmd.ciphertext, Charsets.UTF_8).take(120)
        when (cmd.state) {
            RemoteCommandEntity.STATE_COMPLETED -> ack(commandId, "COMPLETED", "already executed")
            RemoteCommandEntity.STATE_FAILED -> ack(commandId, "FAILED", resultText)
            else -> Unit // still in-flight locally; its own executor will report
        }
    }
}
