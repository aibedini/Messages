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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * LINKED DEVICE CONTROL pt2 — trust statement publisher.
 *
 * Drains the durable TrustStatementOutbox (PENDING, trustSequence ASC) to
 * POST /api/v1/agent/trust/statements with the X-Agent-Auth signature over
 * the exact body bytes. Retries with backoff until ACKed; PENDING trust
 * state is never lost (the registry row is already durable).
 *
 * Registry state machine advance happens ONLY on server ACK:
 *   DEVICE_APPROVED → ACTIVE
 *   DEVICE_REVOKED  → REVOKED
 */
class TrustStatementPublisher(
    context: Context,
    private val prefs: GatewayPreferences,
    private val scope: CoroutineScope,
    private val onLog: (String) -> Unit
) {
    companion object {
        private const val TAG = "TRUST_PUBLISHER"
        private const val PATH = "/api/v1/agent/trust/statements"
        private const val QUIET_MS = 5_000L
    }

    private val appContext = context.applicationContext
    private val db = MessagesDatabase.get(appContext)
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                if (!prefs.isEnabled || prefs.gmwebUrl.isBlank()) {
                    delay(QUIET_MS)
                    continue
                }
                val acked = withContext(Dispatchers.IO) { publishBatch() }
                if (acked == 0) delay(QUIET_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** @return number of statements ACKed this cycle (0 = quiet). */
    private suspend fun publishBatch(): Int {
        val batch = db.trustStatementOutboxDao().pendingBatch()
        if (batch.isEmpty()) return 0
        var acked = 0
        val base = prefs.gmwebUrl.trimEnd('/')
        if (base.isBlank()) return 0
        val deviceId = prefs.agentDeviceId(appContext)
        for (statement in batch) {
            var conn: java.net.HttpURLConnection? = null
            try {
                conn = java.net.URL(base + PATH).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.doOutput = true
                // Server schema: { statement: {...} } — the persisted payload
                // ALREADY contains rootSignature + statementId (P0: one
                // payload persisted==signed==published).
                val statementObj = org.json.JSONObject(statement.payload)
                if (!statementObj.has("rootSignature")) {
                    Log.e(TAG, "statement ${statement.statementId} missing rootSignature — refusing to publish")
                    break
                }
                val wrapper = org.json.JSONObject().put("statement", statementObj)
                val bodyBytes = wrapper.toString().toByteArray(Charsets.UTF_8)
                if (!AgentAuth.sign(conn, deviceId, PATH, "POST", bodyBytes)) {
                    throw IllegalStateException("agent signing failed")
                }
                conn.outputStream.use { it.write(bodyBytes) }
                val code = conn.responseCode
                if (code in 200..299) {
                    db.withTransaction {
                      db.trustStatementOutboxDao().markPublished(statement.statementId, System.currentTimeMillis())
                      when (statement.operation) {
                        TrustStatementOutboxEntity.OP_DEVICE_APPROVED,
                        TrustStatementOutboxEntity.OP_DEVICE_CAPABILITIES_CHANGED ->
                            db.trustedDeviceDao().setStatusForSequence(
                                statement.deviceId, statement.trustSequence, TrustedDeviceEntity.STATUS_ACTIVE, System.currentTimeMillis()
                            )
                        TrustStatementOutboxEntity.OP_DEVICE_REVOKED -> {
                            db.trustedDeviceDao().setStatusForSequence(
                                statement.deviceId, statement.trustSequence, TrustedDeviceEntity.STATUS_REVOKED, System.currentTimeMillis()
                            )
                            // Local sensitive grants die with the trust record.
                            if (db.trustedDeviceDao().byId(statement.deviceId)?.trustSequence == statement.trustSequence) {
                                SensitiveGrantStore.wipeGrants(appContext, statement.deviceId)
                            }
                        }
                    }
                    }
                    acked++
                    onLog("🛡 Trust statement ${statement.operation} published (seq ${statement.trustSequence})")
                } else {
                    Log.w(TAG, "publish ${statement.operation} → HTTP $code")
                    db.trustStatementOutboxDao().markRetry(statement.statementId)
                    break // Do not publish a later trust sequence past a failed predecessor.
                }
            } catch (e: Exception) {
                Log.w(TAG, "publish ${statement.operation} failed: ${e.message}")
                db.trustStatementOutboxDao().markRetry(statement.statementId)
                break
            } finally {
                conn?.disconnect()
            }
        }
        return acked
    }
}
