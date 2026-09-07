package com.autonomousone.messages.security

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.TrustStatementOutboxEntity
import com.autonomousone.messages.data.TrustedDeviceEntity
import com.autonomousone.messages.gateway.GatewayPreferences
import com.autonomousone.messages.gateway.TrustStatementPublisher
import java.util.UUID

/**
 * FIX 1 — unlink the PRIMARY device (only "Re-check" used to exist).
 *
 * Order matters and everything is durable:
 *  1. Revoke every approved web device (existing DEVICE_REVOKED flow).
 *  2. Enqueue a signed DEVICE_REVOKED statement for the primary identity
 *     itself; TrustStatementPublisher transmits it to GMweb.
 *  3. Local teardown in one transaction: linked devices, conversation key
 *     epochs and device telemetry are dropped. The opaque
 *     remote_conversation_map is kept so a future re-link reuses the same
 *     conversation identities.
 *  4. Un-enroll the local identity so the UI offers "Set up Primary" again.
 */
object PrimaryDeviceController {
    private const val TAG = "PRIMARY_REVOKE"

    suspend fun unlinkPrimary(context: Context, primaryDeviceId: String) {
        val appContext = context.applicationContext
        val db = MessagesDatabase.get(appContext)
        val prefs = GatewayPreferences(appContext)

        // 1) Revoke each non-terminal linked web device first (the existing
        //    per-device flow keeps certificate info inside each statement).
        val terminal = setOf(
            TrustedDeviceEntity.STATUS_REVOKED,
            TrustedDeviceEntity.STATUS_REVOKE_PENDING,
            TrustedDeviceEntity.STATUS_FAILED,
            TrustedDeviceEntity.STATUS_EXPIRED
        )
        for (device in db.trustedDeviceDao().all()) {
            if (device.status !in terminal) {
                runCatching { TrustedDeviceRegistry.recordRevocation(appContext, device.deviceId) }
                    .onFailure { Log.w(TAG, "revoke of linked device ${device.deviceId} failed", it) }
            }
        }

        // 2) Signed DEVICE_REVOKED statement for the primary identity itself.
        db.withTransaction {
            val seq = db.trustStatementOutboxDao().maxTrustSequence() + 1
            val statementId = UUID.randomUUID().toString()
            val statement = TrustedDeviceRegistry.buildStatement(
                op = TrustStatementOutboxEntity.OP_DEVICE_REVOKED,
                statementId = statementId,
                deviceId = primaryDeviceId,
                trustSequence = seq,
                capabilities = emptyList(),
                historyGrant = "",
                certificateJson = null,
                accountId = "default"
            )
            db.trustStatementOutboxDao().enqueue(
                TrustStatementOutboxEntity(
                    statementId = statementId,
                    trustSequence = seq,
                    operation = TrustStatementOutboxEntity.OP_DEVICE_REVOKED,
                    deviceId = primaryDeviceId,
                    payload = statement.toString(),
                    rootSignature = statement.optString("rootSignature", ""),
                    state = TrustStatementOutboxEntity.STATE_PENDING,
                    attemptCount = 0,
                    createdAt = System.currentTimeMillis(),
                    ackedAt = null
                )
            )
        }
        TrustStatementPublisher.nudge()

        // 3) Local teardown — one transaction, no partial state.
        db.withTransaction {
            db.trustedDeviceDao().deleteAll()
            db.conversationKeyDao().deleteAllEpochs()
            db.deviceTelemetryDao().deleteAll()
        }

        // 4) Un-enroll: heartbeats/uploaders pause on the identity gate and
        //    the UI shows "Set up Primary" until a new phone-setup QR is used.
        prefs.identityRegistered = false
        prefs.isRegistered = false
        prefs.gatewayId = ""

        Log.i(TAG, "primary_device_revoked deviceId=$primaryDeviceId")
    }
}
