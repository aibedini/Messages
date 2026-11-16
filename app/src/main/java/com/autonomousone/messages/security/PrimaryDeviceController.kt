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
 *  0. LDV-004 PR-E slice 2c: withdraw every linked AI device's conversation scope while the
 *     trust rows and scope state still exist, then (step 3) clear the scope tables in the same
 *     transaction as the rest of the teardown. A failure here is recorded and never aborts the
 *     unlink — the device-level DEVICE_REVOKED flow below is the authoritative kill switch.
 *  1. Revoke every approved web device (existing DEVICE_REVOKED flow).
 *  2. Enqueue a signed DEVICE_REVOKED statement for the primary identity
 *     itself; TrustStatementPublisher transmits it to GMweb.
 *  3. Local teardown in one transaction: linked devices, conversation key
 *     epochs, device telemetry and the linked-device scope tables are
 *     dropped. The opaque remote_conversation_map is kept so a future
 *     re-link reuses the same conversation identities.
 *  4. Un-enroll the local identity so the UI offers "Set up Primary" again.
 *
 * No key material, message body, phone number or conversation id is logged: only device ids,
 * counts and failure stages.
 */
object PrimaryDeviceController {
    private const val TAG = "PRIMARY_REVOKE"

    suspend fun unlinkPrimary(context: Context, primaryDeviceId: String) {
        val appContext = context.applicationContext
        val db = MessagesDatabase.get(appContext)
        val prefs = GatewayPreferences(appContext)

        // 0) LDV-004 PR-E slice 2c — withdraw the conversation scope of every linked AI
        //    device BEFORE the local teardown destroys the rows/keys that describe it.
        //    `pauseAll` publishes key-less tombstones (account + enabled conversations); it
        //    needs no CKE, which is what makes this runnable while the keyring is about to be
        //    dropped. Recorded, never fatal: see UnlinkScopeRevocation's KDoc.
        val scopeOutcome = try {
            val coordinator = ConversationGrantWiring.create(appContext)
            UnlinkScopeRevocation(
                aiDevices = { TrustedAiAgentDeviceSource(db).aiAgentDevices() },
                pauseAll = { target, reason -> coordinator.pauseAll(target, reason) },
                onWarning = { message, error -> Log.w(TAG, message, error) },
            ).run()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "scope pause step failed", t)
            null
        }
        scopeOutcome?.let { outcome ->
            Log.i(
                TAG,
                "ai_scope_pause devices=${outcome.attempted} paused=${outcome.paused.size} " +
                    "already=${outcome.alreadyRevoked.size} " +
                    "notGranted=${outcome.notGranted.size} failed=${outcome.failed.size}"
            )
        }

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

        // 3) Local teardown — one transaction, no partial state. The LDV-004 scope tables are
        //    cleared here too: their tombstones are already durable in the outbox (step 0), and
        //    leaving rows behind would describe devices this phone no longer trusts.
        db.withTransaction {
            db.trustedDeviceDao().deleteAll()
            db.conversationKeyDao().deleteAllEpochs()
            db.deviceTelemetryDao().deleteAll()
            db.linkedDeviceScopeDao().clearAll()
        }

        // 4) Un-enroll: heartbeats/uploaders pause on the identity gate and
        //    the UI shows "Set up Primary" until a new phone-setup QR is used.
        prefs.identityRegistered = false
        prefs.isRegistered = false
        prefs.gatewayId = ""

        Log.i(TAG, "primary_device_revoked deviceId=$primaryDeviceId")
    }
}
