package com.autonomousone.messages.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

/**
 * LINKED DEVICE CONTROL — Android's local Trust Registry (authoritative).
 *
 * Android is the Trust Root: every approved web device gets a durable,
 * signed record here BEFORE the approval is published. Server telemetry
 * (last seen, session state, online) is cached separately and is NEVER the
 * trust authority.
 */
@Entity(tableName = "trusted_devices")
data class TrustedDeviceEntity(
    @PrimaryKey val deviceId: String,
    val accountId: String,
    val displayName: String,          // display-only ("Chrome on Windows")
    val deviceType: String,           // e.g. WEB_PWA
    val origin: String,               // https://gmweb...
    val signingPublicKey: String,     // SPKI DER Base64
    val encryptionPublicKey: String,  // SPKI DER Base64
    val capabilitiesJson: String,     // JSON array of capability names
    val historyGrant: String,         // FULL_HISTORY | FROM_NOW_ON
    val certificateJson: String,      // full signed DeviceCertificate
    val certificateSignature: String, // root signature (Base64)
    val trustSequence: Int,
    /** PENDING_PUBLICATION | ACTIVE | REVOKE_PENDING | REVOKED | EXPIRED */
    val status: String,
    val approvedAt: Long,
    val expiresAt: Long,
    val revokedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long
) {
    companion object {
        const val STATUS_PENDING_PUBLICATION = "PENDING_PUBLICATION"
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_REVOKE_PENDING = "REVOKE_PENDING"
        const val STATUS_REVOKED = "REVOKED"
        const val STATUS_EXPIRED = "EXPIRED"
    }
}

/**
 * Signed trust statements awaiting publication to GMweb. Durable BEFORE the
 * network call; retried until ACKed; monotonic trustSequence is unique.
 */
@Entity(tableName = "trust_statement_outbox", indices = [androidx.room.Index(value = ["trustSequence"], unique = true)])
data class TrustStatementOutboxEntity(
    @PrimaryKey val statementId: String,   // UUID
    val trustSequence: Int,                // monotonic per account
    /** DEVICE_APPROVED | DEVICE_REVOKED | DEVICE_CAPABILITIES_CHANGED | DEVICE_KEY_ROTATED */
    val operation: String,
    val deviceId: String,
    val payload: String,                   // canonical statement JSON
    val rootSignature: String,             // Trust Root signature over payload
    val state: String,                     // PENDING | PUBLISHED | FAILED
    val attemptCount: Int,
    val createdAt: Long,
    val ackedAt: Long?
) {
    companion object {
        const val STATE_PENDING = "PENDING"
        const val STATE_PUBLISHED = "PUBLISHED"
        const val STATE_FAILED = "FAILED"
        const val OP_DEVICE_APPROVED = "DEVICE_APPROVED"
        const val OP_DEVICE_REVOKED = "DEVICE_REVOKED"
        const val OP_DEVICE_CAPABILITIES_CHANGED = "DEVICE_CAPABILITIES_CHANGED"
        const val OP_DEVICE_KEY_ROTATED = "DEVICE_KEY_ROTATED"
    }
}

/** Server-observed telemetry cache — display only, never trust authority. */
@Entity(tableName = "device_telemetry")
data class DeviceTelemetryEntity(
    @PrimaryKey val deviceId: String,
    val sessionActive: Boolean,
    val lastSeenAt: Long?,
    val sessionExpiresAt: Long?,
    val onlineNow: Boolean,
    val telemetryFetchedAt: Long
)

@Dao
interface TrustedDeviceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: TrustedDeviceEntity)

    @Update
    suspend fun update(device: TrustedDeviceEntity)

    @Query("SELECT * FROM trusted_devices ORDER BY approvedAt DESC")
    suspend fun all(): List<TrustedDeviceEntity>

    @Query("SELECT * FROM trusted_devices WHERE deviceId = :deviceId")
    suspend fun byId(deviceId: String): TrustedDeviceEntity?

    @Query("UPDATE trusted_devices SET status = :status, updatedAt = :now WHERE deviceId = :deviceId")
    suspend fun setStatus(deviceId: String, status: String, now: Long)

    @Query("SELECT COUNT(*) FROM trusted_devices WHERE status IN ('ACTIVE','PENDING_PUBLICATION')")
    suspend fun countTrusted(): Int
}

@Dao
interface TrustStatementOutboxDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun enqueue(statement: TrustStatementOutboxEntity)

    @Query("SELECT * FROM trust_statement_outbox WHERE state = 'PENDING' ORDER BY trustSequence ASC LIMIT 50")
    suspend fun pendingBatch(): List<TrustStatementOutboxEntity>

    @Query("UPDATE trust_statement_outbox SET state = 'PUBLISHED', ackedAt = :at WHERE statementId = :id")
    suspend fun markPublished(id: String, at: Long)

    @Query("UPDATE trust_statement_outbox SET state = 'PENDING', attemptCount = attemptCount + 1 WHERE statementId = :id")
    suspend fun markRetry(id: String)

    @Query("SELECT COALESCE(MAX(trustSequence), 0) FROM trust_statement_outbox")
    suspend fun maxTrustSequence(): Int
}

@Dao
interface DeviceTelemetryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(telemetry: DeviceTelemetryEntity)

    @Query("SELECT * FROM device_telemetry WHERE deviceId = :deviceId")
    suspend fun byId(deviceId: String): DeviceTelemetryEntity?

    @Query("DELETE FROM device_telemetry WHERE deviceId = :deviceId")
    suspend fun delete(deviceId: String)
}
