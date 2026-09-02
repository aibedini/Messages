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
    /**
     * P0-5: the trust decision is durable BEFORE the network call.
     * Allocates the authoritative trustSequence and persists the device
     * row (PENDING_PUBLICATION, certificate empty until approve succeeds).
     */
    suspend fun beginApproval(
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
                    certificateJson = "", // attached in completeApproval
                    certificateSignature = "",
                    trustSequence = seq,
                    status = TrustedDeviceEntity.STATUS_PENDING_PUBLICATION,
                    approvedAt = now,
                    expiresAt = device.expiresAt,
                    revokedAt = null,
                    createdAt = now,
                    updatedAt = now
                )
            )
            seq
        }
    }

    /** Attach the certificate + enqueue the signed DEVICE_APPROVED statement. */
    suspend fun completeApproval(
        context: Context,
        deviceId: String,
        certificateJson: String
    ) {
        val db = MessagesDatabase.get(context.applicationContext)
        val now = System.currentTimeMillis()
        db.withTransaction {
            val device = db.trustedDeviceDao().byId(deviceId) ?: return@withTransaction
            val cert = org.json.JSONObject(certificateJson)
            db.trustedDeviceDao().update(
                device.copy(
                    certificateJson = certificateJson,
                    certificateSignature = cert.optString("rootSignature", ""),
                    updatedAt = now
                )
            )
            val statementId = java.util.UUID.randomUUID().toString()
            val statement = buildStatement(
                op = TrustStatementOutboxEntity.OP_DEVICE_APPROVED,
                statementId = statementId,
                deviceId = deviceId,
                trustSequence = device.trustSequence,
                capabilities = capabilitiesFrom(device.capabilitiesJson),
                historyGrant = device.historyGrant,
                certificateJson = certificateJson
            )
            db.trustStatementOutboxDao().enqueue(
                TrustStatementOutboxEntity(
                    statementId = statementId,
                    trustSequence = device.trustSequence,
                    operation = TrustStatementOutboxEntity.OP_DEVICE_APPROVED,
                    deviceId = deviceId,
                    payload = statement.toString(),
                    rootSignature = statement.optString("rootSignature", ""),
                    state = TrustStatementOutboxEntity.STATE_PENDING,
                    attemptCount = 0,
                    createdAt = now,
                    ackedAt = null
                )
            )
        }
    }

    suspend fun recordApproval(
        context: Context,
        device: ApprovedDevice,
        accountId: String = "default",
    ): Int {
        val db = MessagesDatabase.get(context.applicationContext)
        val now = System.currentTimeMillis()
        return db.withTransaction {
            // P0-6: ONE authoritative trustSequence from the registry — the
            // certificate and the statement MUST carry the same value.
            val seq = db.trustStatementOutboxDao().maxTrustSequence() + 1
            val statementId = java.util.UUID.randomUUID().toString()
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
            val statement = buildStatement(
                op = TrustStatementOutboxEntity.OP_DEVICE_APPROVED,
                statementId = statementId,
                deviceId = device.deviceId,
                trustSequence = seq,
                capabilities = device.capabilities,
                historyGrant = device.historyGrant,
                certificateJson = device.certificateJson,
                accountId = accountId
            )
            db.trustStatementOutboxDao().enqueue(
                TrustStatementOutboxEntity(
                    statementId = statementId,
                    trustSequence = seq,
                    operation = TrustStatementOutboxEntity.OP_DEVICE_APPROVED,
                    deviceId = device.deviceId,
                    payload = statement.toString(),
                    rootSignature = statement.optString("rootSignature", ""),
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
            val statementId = java.util.UUID.randomUUID().toString()
            val statement = buildStatement(
                op = TrustStatementOutboxEntity.OP_DEVICE_CAPABILITIES_CHANGED,
                statementId = statementId,
                deviceId = deviceId,
                trustSequence = seq,
                capabilities = capabilities,
                historyGrant = device.historyGrant,
                certificateJson = device.certificateJson
            )
            db.trustStatementOutboxDao().enqueue(
                TrustStatementOutboxEntity(
                    statementId = statementId,
                    trustSequence = seq,
                    operation = TrustStatementOutboxEntity.OP_DEVICE_CAPABILITIES_CHANGED,
                    deviceId = deviceId,
                    payload = statement.toString(),
                    rootSignature = statement.optString("rootSignature", ""),
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
            val statementId = java.util.UUID.randomUUID().toString()
            val statement = buildStatement(
                op = TrustStatementOutboxEntity.OP_DEVICE_REVOKED,
                statementId = statementId,
                deviceId = deviceId,
                trustSequence = seq,
                capabilities = emptyList(),
                historyGrant = device.historyGrant,
                certificateJson = device.certificateJson
            )
            db.trustStatementOutboxDao().enqueue(
                TrustStatementOutboxEntity(
                    statementId = statementId,
                    trustSequence = seq,
                    operation = TrustStatementOutboxEntity.OP_DEVICE_REVOKED,
                    deviceId = deviceId,
                    payload = statement.toString(),
                    rootSignature = statement.optString("rootSignature", ""),
                    state = TrustStatementOutboxEntity.STATE_PENDING,
                    attemptCount = 0,
                    createdAt = now,
                    ackedAt = null
                )
            )
            seq
        }
    }

    private fun buildStatement(
        op: String,
        statementId: String,
        deviceId: String,
        trustSequence: Int,
        capabilities: List<String>,
        historyGrant: String,
        certificateJson: String?,
        accountId: String = "default"
    ): org.json.JSONObject {
        val obj = org.json.JSONObject()
        obj.put("version", PrimaryTrustRoot.STATEMENT_VERSION)
        obj.put("accountId", accountId)
        obj.put("statementId", statementId)
        obj.put("operation", op)
        obj.put("deviceId", deviceId)
        obj.put("trustSequence", trustSequence.toLong())
        obj.put("capabilities", org.json.JSONArray(capabilities))
        obj.put("historyGrant", historyGrant)
        obj.put("issuedAt", System.currentTimeMillis())
        if (certificateJson != null) obj.put("certificate", certificateJson)
        // Sign the canonical form, then embed the signature INSIDE the same
        // object — the publisher sends exactly this object.
        val rootSignature = PrimaryTrustRoot.signTrustStatement(obj)
        obj.put("rootSignature", rootSignature)
        return obj
    }

private fun capabilitiesJson(capabilities: List<String>): String =
    org.json.JSONArray().apply { capabilities.forEach { put(it) } }.toString()

    private fun capabilitiesFrom(json: String): List<String> {
        val arr = org.json.JSONArray(json)
        return (0 until arr.length()).map { arr.getString(it) }
    }
}
