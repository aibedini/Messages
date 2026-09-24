package com.autonomousone.messages.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.autonomousone.messages.sync.ControlPlaneAuthState

/**
 * The control plane's standing verdict on this device (mission §44/§56).
 *
 * **Why this is durable and not a field in the health registry.** `GatewayHealthRecorder` is
 * deliberately in-memory ("one process-wide, lock-guarded registry"), so an `AuthVerification
 * .REJECTED` is forgotten when the process dies. For a transient problem that is fine. For "the
 * server has revoked this device" it is not: the app would come back up, report no auth blocker,
 * re-upload everything and re-offer enrollment, and the owner would never learn that a human needs to
 * un-revoke the device.
 *
 * **One row.** This is a property of the device, not a log, so the table holds exactly one row with
 * [SINGLETON_ID] as its key and every write replaces it. A row per event would be a second log to
 * bound, prune and get wrong; the counters on the single row already say how strong the evidence is.
 *
 * Nothing here is sensitive: no message, no phone number, no credential — only statuses, counts and
 * timestamps.
 */
@Entity(tableName = "control_plane_auth_state")
data class ControlPlaneAuthStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    /** 0 = not revoked. */
    @ColumnInfo(defaultValue = "0")
    val revokedAt: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val consecutiveRejections: Int = 0,
    val lastRejectionStatus: Int? = null,
    @ColumnInfo(defaultValue = "0")
    val lastAcceptedAt: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val clearedAt: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = 0,
) {
    /** The pure projection the policy works on. */
    fun state(): ControlPlaneAuthState = ControlPlaneAuthState(
        revokedAt = revokedAt,
        consecutiveRejections = consecutiveRejections,
        lastRejectionStatus = lastRejectionStatus,
        lastAcceptedAt = lastAcceptedAt,
        clearedAt = clearedAt,
    )

    companion object {
        const val SINGLETON_ID = 1

        fun of(state: ControlPlaneAuthState, now: Long): ControlPlaneAuthStateEntity =
            ControlPlaneAuthStateEntity(
                id = SINGLETON_ID,
                revokedAt = state.revokedAt,
                consecutiveRejections = state.consecutiveRejections,
                lastRejectionStatus = state.lastRejectionStatus,
                lastAcceptedAt = state.lastAcceptedAt,
                clearedAt = state.clearedAt,
                updatedAt = now,
            )
    }
}

@Dao
interface ControlPlaneAuthStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ControlPlaneAuthStateEntity)

    @Query("SELECT * FROM control_plane_auth_state WHERE id = :id")
    suspend fun get(id: Int = ControlPlaneAuthStateEntity.SINGLETON_ID): ControlPlaneAuthStateEntity?
}
