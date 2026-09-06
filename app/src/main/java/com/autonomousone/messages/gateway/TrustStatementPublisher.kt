package com.autonomousone.messages.gateway

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.TrustStatementOutboxEntity
import com.autonomousone.messages.data.TrustedDeviceEntity
import com.autonomousone.messages.security.SensitiveGrantStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Publishes the durable, strictly ordered trust outbox and advances local state only after ACK. */
class TrustStatementPublisher(
    context: Context,
    private val prefs: GatewayPreferences,
    private val scope: CoroutineScope,
    private val onLog: (String) -> Unit,
) {
    companion object {
        private const val TAG = "TRUST_PUBLISHER"
        private const val PATH = "/api/v1/agent/trust/statements"
        private const val QUIET_MS = 5_000L
        private const val MAX_BACKOFF_MS = 300_000L

        data class Health(
            val running: Boolean = false,
            val pendingCount: Int = 0,
            val oldestPendingSequence: Int? = null,
            val lastAttemptAt: Long? = null,
            val lastHttpStatus: Int? = null,
            val lastFailureReason: String? = null,
            val lastAckAt: Long? = null,
        )

        private val _health = MutableStateFlow(Health())
        val health: StateFlow<Health> = _health.asStateFlow()
        @Volatile private var active: TrustStatementPublisher? = null

        fun nudge() { active?.retryNow() }
    }

    private val appContext = context.applicationContext
    private val db = MessagesDatabase.get(appContext)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        active = this
        _health.value = _health.value.copy(running = true)
        job = scope.launch {
            while (isActive) {
                if (!prefs.isEnabled || prefs.gmwebUrl.isBlank()) {
                    awaitWake(QUIET_MS)
                    continue
                }
                val result = withContext(Dispatchers.IO) { publishBatch() }
                if (result.acked == 0) awaitWake(result.retryAfterMs)
            }
        }
        job?.invokeOnCompletion {
            if (active === this) active = null
            _health.value = _health.value.copy(running = false)
        }
        retryNow()
    }

    fun stop() {
        job?.cancel()
        job = null
        if (active === this) active = null
        _health.value = _health.value.copy(running = false)
    }

    fun retryNow() { wake.trySend(Unit) }

    private suspend fun awaitWake(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) { wake.receive() }
    }

    private data class PublishResult(val acked: Int, val retryAfterMs: Long = QUIET_MS)

    private suspend fun publishBatch(): PublishResult {
        val batch = db.trustStatementOutboxDao().pendingBatch()
        updateQueueHealth()
        if (batch.isEmpty()) return PublishResult(0)
        var acked = 0
        val base = prefs.gmwebUrl.trimEnd('/')
        if (base.isBlank()) return PublishResult(0)
        val agentDeviceId = prefs.agentDeviceId(appContext)

        for (statement in batch) {
            var conn: java.net.HttpURLConnection? = null
            try {
                _health.value = _health.value.copy(lastAttemptAt = System.currentTimeMillis())
                conn = java.net.URL(base + PATH).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.doOutput = true
                val statementObject = org.json.JSONObject(statement.payload)
                if (!statementObject.has("rootSignature")) {
                    _health.value = _health.value.copy(lastFailureReason = "missing_root_signature")
                    break
                }
                val bodyBytes = org.json.JSONObject().put("statement", statementObject)
                    .toString().toByteArray(Charsets.UTF_8)
                check(AgentAuth.sign(conn, agentDeviceId, PATH, "POST", bodyBytes)) { "agent_signing_failed" }
                conn.outputStream.use { it.write(bodyBytes) }
                val code = conn.responseCode
                _health.value = _health.value.copy(lastHttpStatus = code)
                if (code !in 200..299) {
                    db.trustStatementOutboxDao().markRetry(statement.statementId)
                    _health.value = _health.value.copy(lastFailureReason = safeHttpReason(conn, code))
                    updateQueueHealth()
                    return PublishResult(acked, backoffMs(statement.attemptCount + 1))
                }

                val ackAt = System.currentTimeMillis()
                db.withTransaction {
                    db.trustStatementOutboxDao().markPublished(statement.statementId, ackAt)
                    when (statement.operation) {
                        TrustStatementOutboxEntity.OP_DEVICE_APPROVED,
                        TrustStatementOutboxEntity.OP_DEVICE_CAPABILITIES_CHANGED ->
                            db.trustedDeviceDao().setStatusForSequence(
                                statement.deviceId, statement.trustSequence,
                                TrustedDeviceEntity.STATUS_ACTIVE, ackAt,
                            )
                        TrustStatementOutboxEntity.OP_DEVICE_REVOKED -> {
                            db.trustedDeviceDao().setStatusForSequence(
                                statement.deviceId, statement.trustSequence,
                                TrustedDeviceEntity.STATUS_REVOKED, ackAt,
                            )
                            if (db.trustedDeviceDao().byId(statement.deviceId)?.trustSequence == statement.trustSequence) {
                                SensitiveGrantStore.wipeGrants(appContext, statement.deviceId)
                            }
                        }
                    }
                }
                acked++
                _health.value = _health.value.copy(lastFailureReason = null, lastAckAt = ackAt)
                onLog("Trust statement ${statement.operation} published (seq ${statement.trustSequence})")
            } catch (error: Exception) {
                Log.w(TAG, "publish ${statement.operation} failed (${error.javaClass.simpleName})")
                db.trustStatementOutboxDao().markRetry(statement.statementId)
                _health.value = _health.value.copy(lastHttpStatus = null, lastFailureReason = "network_error")
                updateQueueHealth()
                return PublishResult(acked, backoffMs(statement.attemptCount + 1))
            } finally {
                conn?.disconnect()
            }
        }
        updateQueueHealth()
        return PublishResult(acked)
    }

    private suspend fun updateQueueHealth() {
        val dao = db.trustStatementOutboxDao()
        _health.value = _health.value.copy(
            pendingCount = dao.pendingCount(),
            oldestPendingSequence = dao.oldestPendingSequence(),
        )
    }

    private fun backoffMs(attempt: Int): Long =
        ((1L shl attempt.coerceIn(0, 6)) * QUIET_MS).coerceAtMost(MAX_BACKOFF_MS)

    private fun safeHttpReason(conn: java.net.HttpURLConnection, code: Int): String {
        val body = runCatching {
            conn.errorStream?.bufferedReader()?.use { it.readText().take(2_048) }
        }.getOrNull()
        val parsed = runCatching { org.json.JSONObject(body.orEmpty()) }.getOrNull()
        val safe = Regex("[a-zA-Z0-9_.-]{1,80}")
        return parsed?.optString("reason")?.takeIf { it.matches(safe) }
            ?: parsed?.optString("error")?.takeIf { it.matches(safe) }
            ?: "http_$code"
    }
}
