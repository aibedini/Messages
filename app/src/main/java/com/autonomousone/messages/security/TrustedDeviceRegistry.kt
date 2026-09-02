package com.autonomousone.messages.security

import android.content.Context
import androidx.room.withTransaction
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.TrustStatementOutboxEntity
import com.autonomousone.messages.data.TrustedDeviceEntity
import java.util.UUID

/**
 * LINKED DEVICE CONTROL — Android's authoritative Trust Registry API.
 *
 * The pairing transaction is durable BEFORE any network call:
 *   biometric → sign certificate → build DEVICE_APPROVED statement
 *   → Room transaction {TrustedDeviceEntity + TrustStatementOutboxEntity}
 *   → approve POST → publish statement.
 *
 * A network failure leaves the device PENDING_PUBLICATION with its statement
 * in the outbox — trust state is never RAM-only.
 */
object TrustedDeviceRegistry {

    data class ApprovedDevice(
        val deviceId: String,
        val displayName: String,
        val origin: String,
        val signingPublicKey: String,
        val encryptionPublicKey: String,
        val capabilities: List<String>,
        val historyGrant: String,
        val certificateJson: String,
        val certificateSignature: String,
        val expiresAt: Long,
    )

    /**
     * Persist the approved device + enqueue the DEVICE_APPROVED statement in
     * ONE Room transaction. Returns the assigned trustSequence.
     */
    suspend fun recordApproval(
        context: Context,
        device: ApprovedDevice,
        accountId: String = "default",
    ): Int {
        val db = MessagesDatabase.get(context.applicationContext)
        val now = System.currentTimeMillis()
        return db.withTransaction {
            val seq = db.trustStatementOutboxDao().maxTrustSequence() + 1
            db.trustedDeviceDao().upsert(
                com.autonomousone.messages.data.TrustedDeviceEntity(
                    deviceId = device.deviceId,
                    accountId = accountId,
                    displayName = device.displayName,
                    deviceType = "WEB_PWA",
                    origin = device.origin,
                    signingPublicKey = device.signingPublicKey,
                    encryptionPublicKey = device.encryptionPublicKey,
                    capabilitiesJson = capabilitiesJson(device.capabilities),
                    historyGrant = device.historyGrant,
                    certificateJson = device.certificateJson,
                    certificateSignature = device.certificateSignature,
                    trustSequence = seq,
                    status = TrustedDeviceEntity.STATUS_PENDING_PUBLICATION,
                    approvedAt = now,
                    expiresAt = device.expiresAt,
                    revokedAt = null,
                    createdAt = now,
                    updatedAt = now
                )
            )
            val payload = buildStatementPayload(
                op = TrustStatementOutboxEntity.OP_DEVICE_APPROVED,
                deviceId = device.deviceId,
                trustSequence = seq,
                capabilities = device.capabilities,
                historyGrant = device.historyGrant,
                certificateJson = device.certificateJson
            )
            db.trustStatementOutboxDao().enqueue(
                TrustStatementOutboxEntity(
                    statementId = java.util.UUID.randomUUID().toString(),
                    trustSequence = seq,
                    operation = TrustStatementOutboxEntity.OP_DEVICE_APPROVED,
                    deviceId = device.deviceId,
                    payload = payload,
                    rootSignature = PrimaryTrustRoot.sign(org.json.JSONObject(payload)),
                    state = TrustStatementOutboxEntity.STATE_PENDING,
                    attemptCount = 0,
                    createdAt = now,
                    ackedAt = null
                )
            )
            seq
        }
    }

    /** Persist a capability change (biometric-confirmed). trustSequence++. */
    suspend fun recordCapabilityChange(
        context: Context,
        deviceId: String,
        capabilities: List<String>
    ): Int? {
        val db = MessagesDatabase.get(context.applicationContext)
        val now = System.currentTimeMillis()
        val device = db.trustedDeviceDao().byId(deviceId) ?: return null
        return db.withTransaction {
            val seq = db.trustStatementOutboxDao().maxTrustSequence() + 1
            db.trustedDeviceDao().update(
                device.copy(
                    capabilitiesJson = capabilitiesJson(capabilities),
                    trustSequence = seq,
                    updatedAt = now
                )
            )
            db.trustStatementOutboxDao().enqueue(
                TrustStatementOutboxEntity(
                    statementId = java.util.UUID.randomUUID().toString(),
                    trustSequence = seq,
                    operation = TrustStatementOutboxEntity.OP_DEVICE_CAPABILITIES_CHANGED,
                    deviceId = deviceId,
                    payload = buildStatementPayload(
                        TrustStatementOutboxEntity.OP_DEVICE_CAPABILITIES_CHANGED,
                        deviceId, seq, capabilities, device.historyGrant, null
                    ),
                    rootSignature = PrimaryTrustRoot.sign(
                        org.json.JSONObject(
                            buildStatementPayload(
                                TrustStatementOutboxEntity.OP_DEVICE_CAPABILITIES_CHANGED,
                                deviceId, seq, capabilities, device.historyGrant,
                                device.certificateJson
                            )
                        )
                    ),
                    state = TrustStatementOutboxEntity.STATE_PENDING,
                    attemptCount = 0,
                    createdAt = now,
                    ackedAt = null
                )
            )
            seq
        }
    }

    /** Biometric-confirmed unlink: REVOKE_PENDING + DEVICE_REVOKED outbox. */
    suspend fun recordRevocation(context: Context, deviceId: String): Int? {
        val db = MessagesDatabase.get(context.applicationContext)
        val now = System.currentTimeMillis()
        val device = db.trustedDeviceDao().byId(deviceId) ?: return null
        return db.withTransaction {
            val seq = db.trustStatementOutboxDao().maxTrustSequence() + 1
            db.trustedDeviceDao().update(
                device.copy(
                    status = TrustedDeviceEntity.STATUS_REVOKE_PENDING,
                    revokedAt = now,
                    updatedAt = now
                )
            )
            val payload = buildStatementPayload(
                TrustStatementOutboxEntity.OP_DEVICE_REVOKED,
                deviceId, seq, emptyList(), device.historyGrant, device.certificateJson
            )
            db.trustStatementOutboxDao().enqueue(
                TrustStatementOutboxEntity(
                    statementId = java.util.UUID.randomUUID().toString(),
                    trustSequence = seq,
                    operation = TrustStatementOutboxEntity.OP_DEVICE_REVOKED,
                    deviceId = deviceId,
                    payload = payload,
                    rootSignature = PrimaryTrustRoot.sign(org.json.JSONObject(payload)),
                    state = TrustStatementOutboxEntity.STATE_PENDING,
                    attemptCount = 0,
                    createdAt = now,
                    ackedAt = null
                )
            )
            seq
        }
    }

    private fun buildStatementPayload(
        op: String,
        deviceId: String,
        trustSequence: Int,
        capabilities: List<String>,
        historyGrant: String,
        certificateJson: String?
    ): String {
        val obj = org.json.JSONObject()
        obj.put("version", 1)
        obj.put("operation", op)
        obj.put("deviceId", deviceId)
        obj.put("trustSequence", trustSequence)
        obj.put("capabilities", org.json.JSONArray(capabilities))
        obj.put("historyGrant", historyGrant)
        obj.put("issuedAt", System.currentTimeMillis())
        if (certificateJson != null) obj.put("certificate", certificateJson)
        return obj.toString()
    }
}

private fun capabilitiesJson(capabilities: List<String>): String =
    org.json.JSONArray().apply { capabilities.forEach { put(it) } }.toString()
