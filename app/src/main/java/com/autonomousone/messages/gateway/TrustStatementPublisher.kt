package com.autonomousone.messages.gateway

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.TrustStatementOutboxEntity
import com.autonomousone.messages.data.TrustedDeviceEntity
import com.autonomousone.messages.security.SensitiveGrantStore
import com.autonomousone.messages.security.TrustedDeviceRegistry
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

internal object TrustPublicationPolicy {
    enum class WaitingApprovalAction { ACTIVATE, WAIT, VOID }

    fun waitingApprovalAction(
        sequence: Int, deviceId: String, pairingSessionId: String?,
        confirmed: Set<Pair<Int, String>>, pendingSessions: Set<String>,
    ): WaitingApprovalAction = when {
        (sequence to deviceId) in confirmed -> WaitingApprovalAction.ACTIVATE
        !pairingSessionId.isNullOrBlank() && pairingSessionId in pendingSessions -> WaitingApprovalAction.WAIT
        else -> WaitingApprovalAction.VOID
    }

    fun durableReceipt(receipt: org.json.JSONObject?, sequence: Int): Boolean =
        receipt != null && receipt.optBoolean("ok") &&
            receipt.optInt("trustSequence", -1) == sequence &&
            (receipt.optBoolean("applied") || receipt.optString("reason") == "duplicate")

    fun hasContiguousReplay(serverSequence: Int, rows: List<TrustStatementOutboxEntity>): Boolean {
        var expected = serverSequence + 1
        for (row in rows) {
            if (row.trustSequence != expected || row.state !in setOf(
                TrustStatementOutboxEntity.STATE_PENDING,
                TrustStatementOutboxEntity.STATE_PUBLISHED,
            )) return false
            expected++
        }
        return true
    }

    /** Only absent sequence numbers are voided; existing approvals retain their original bytes. */
    fun missingSequences(serverSequence: Int, rows: List<TrustStatementOutboxEntity>, limit: Int = 32): List<Int>? {
        var expected = serverSequence + 1
        val missing = mutableListOf<Int>()
        for (row in rows) {
            if (row.trustSequence < expected) return null
            while (expected < row.trustSequence) {
                if (missing.size == limit) return null
                missing += expected++
            }
            expected++
        }
        return missing
    }
}

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
        private const val POSITION_PATH = "/api/v1/agent/trust/position"
        private const val QUIET_MS = 5_000L
        private const val MAX_BACKOFF_MS = 300_000L
        private const val POSITION_REFRESH_MS = 60_000L

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
    private var lastPositionCheckAt = 0L

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
        if (System.currentTimeMillis() - lastPositionCheckAt >= POSITION_REFRESH_MS &&
            !reconcileServerPosition()) return PublishResult(0, QUIET_MS)
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
                    lastPositionCheckAt = 0L
                    updateQueueHealth()
                    return PublishResult(acked, backoffMs(statement.attemptCount + 1))
                }

                val receipt = runCatching {
                    org.json.JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                }.getOrNull()
                val persisted = TrustPublicationPolicy.durableReceipt(receipt, statement.trustSequence)
                if (!persisted) {
                    db.trustStatementOutboxDao().markRetry(statement.statementId)
                    _health.value = _health.value.copy(lastFailureReason = "trust_receipt_not_applied")
                    lastPositionCheckAt = 0L
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
                if (statement.operation == TrustStatementOutboxEntity.OP_DEVICE_CAPABILITIES_CHANGED) {
                    com.autonomousone.messages.data.TelephonySyncCoordinator.get(appContext)
                        .requestSensitiveHistoryReplayForLinkedDevice(statement.deviceId)
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

    /** Recover signed statements that an old client falsely marked published after a server gap. */
    private suspend fun reconcileServerPosition(): Boolean {
        val base = prefs.gmwebUrl.trimEnd('/')
        val agentDeviceId = prefs.agentDeviceId(appContext)
        var conn: java.net.HttpURLConnection? = null
        try {
            conn = java.net.URL(base + POSITION_PATH).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            check(AgentAuth.sign(conn, agentDeviceId, POSITION_PATH, "GET", ByteArray(0))) {
                "agent_signing_failed"
            }
            val status = conn.responseCode
            if (status !in 200..299) {
                _health.value = _health.value.copy(lastHttpStatus = status,
                    lastFailureReason = safeHttpReason(conn, status))
                return false
            }
            val position = org.json.JSONObject(
                conn.inputStream.bufferedReader().use { it.readText() }
            )
            val serverSequence = position.getInt("trustSequence")
            if (serverSequence < 0) error("invalid_server_trust_position")
            // Older GMweb releases only returned the sequence. They cannot prove
            // whether a stranded approval succeeded, so never infer a void.
            val hasApprovalEvidence = position.has("confirmedApprovals") &&
                position.has("pendingPairingSessionIds")
            val confirmed = mutableSetOf<Pair<Int, String>>()
            val pendingSessions = mutableSetOf<String>()
            if (hasApprovalEvidence) {
                val approvals = position.getJSONArray("confirmedApprovals")
                for (i in 0 until approvals.length()) {
                    val item = approvals.getJSONObject(i)
                    confirmed += item.getInt("trustSequence") to item.getString("deviceId")
                }
                val pending = position.getJSONArray("pendingPairingSessionIds")
                for (i in 0 until pending.length()) pendingSessions += pending.getString(i)
            }
            val wipeGrantsFor = mutableSetOf<String>()
            val rows = db.withTransaction {
                val dao = db.trustStatementOutboxDao()
                val existing = dao.afterSequence(serverSequence)
                val gaps = TrustPublicationPolicy.missingSequences(serverSequence, existing)
                if (gaps == null) return@withTransaction existing
                for (sequence in gaps) {
                    // Older builds deleted failed pairing approvals. The primary
                    // root signs a present-day no-op; it never grants a device.
                    val id = java.util.UUID.randomUUID().toString()
                    val deviceId = "void:$sequence"
                    val statement = TrustedDeviceRegistry.buildStatement(
                        op = TrustStatementOutboxEntity.OP_TRUST_SEQUENCE_VOIDED,
                        statementId = id,
                        deviceId = deviceId,
                        trustSequence = sequence,
                        capabilities = emptyList(),
                        historyGrant = "",
                        certificateJson = null,
                    )
                    dao.enqueue(TrustStatementOutboxEntity(
                        statementId = id,
                        trustSequence = sequence,
                        operation = TrustStatementOutboxEntity.OP_TRUST_SEQUENCE_VOIDED,
                        deviceId = deviceId,
                        payload = statement.toString(),
                        rootSignature = statement.getString("rootSignature"),
                        state = TrustStatementOutboxEntity.STATE_PENDING,
                        attemptCount = 0,
                        createdAt = System.currentTimeMillis(),
                        ackedAt = null,
                    ))
                }
                if (gaps.isNotEmpty()) onLog("Voided ${gaps.size} missing trust sequence(s)")
                if (hasApprovalEvidence) for (row in existing) {
                    if (row.state != TrustStatementOutboxEntity.STATE_WAITING_SERVER_APPROVAL) continue
                    val certificate = runCatching {
                        org.json.JSONObject(org.json.JSONObject(row.payload).getString("certificate"))
                    }.getOrNull()
                    val sessionId = certificate?.optString("pairingSessionId")
                    when (TrustPublicationPolicy.waitingApprovalAction(
                        row.trustSequence, row.deviceId, sessionId, confirmed, pendingSessions,
                    )) {
                        TrustPublicationPolicy.WaitingApprovalAction.ACTIVATE -> {
                            dao.update(row.copy(state = TrustStatementOutboxEntity.STATE_PENDING))
                            db.trustedDeviceDao().setStatusForSequence(row.deviceId, row.trustSequence,
                                TrustedDeviceEntity.STATUS_PENDING_PUBLICATION, System.currentTimeMillis())
                        }
                        TrustPublicationPolicy.WaitingApprovalAction.WAIT -> Unit
                        TrustPublicationPolicy.WaitingApprovalAction.VOID -> {
                            val statement = TrustedDeviceRegistry.buildStatement(
                                op = TrustStatementOutboxEntity.OP_TRUST_SEQUENCE_VOIDED,
                                statementId = row.statementId,
                                deviceId = row.deviceId,
                                trustSequence = row.trustSequence,
                                capabilities = emptyList(), historyGrant = "", certificateJson = null,
                            )
                            dao.update(row.copy(
                                operation = TrustStatementOutboxEntity.OP_TRUST_SEQUENCE_VOIDED,
                                payload = statement.toString(),
                                rootSignature = statement.getString("rootSignature"),
                                state = TrustStatementOutboxEntity.STATE_PENDING,
                            ))
                            if (db.trustedDeviceDao().byId(row.deviceId)?.trustSequence == row.trustSequence) {
                                db.trustedDeviceDao().setStatusForSequence(row.deviceId, row.trustSequence,
                                    TrustedDeviceEntity.STATUS_FAILED, System.currentTimeMillis())
                                wipeGrantsFor += row.deviceId
                            }
                        }
                    }
                }
                dao.afterSequence(serverSequence)
            }
            for (deviceId in wipeGrantsFor) SensitiveGrantStore.wipeGrants(appContext, deviceId)
            if (!TrustPublicationPolicy.hasContiguousReplay(serverSequence, rows)) {
                val reason = if (rows.any { it.state == TrustStatementOutboxEntity.STATE_WAITING_SERVER_APPROVAL })
                    "trust_approval_waiting" else "local_trust_sequence_gap"
                _health.value = _health.value.copy(lastFailureReason = reason)
                return false
            }
            if (rows.isNotEmpty()) {
                val recovered = db.trustStatementOutboxDao().requeuePublishedAfter(serverSequence)
                if (recovered > 0) onLog("Trust publication recovered $recovered signed statement(s)")
            }
            lastPositionCheckAt = System.currentTimeMillis()
            _health.value = _health.value.copy(lastFailureReason = null)
            updateQueueHealth()
            return true
        } catch (error: Exception) {
            Log.w(TAG, "trust position reconciliation failed (${error.javaClass.simpleName})")
            _health.value = _health.value.copy(lastFailureReason = "trust_position_unavailable")
            return false
        } finally {
            conn?.disconnect()
        }
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
