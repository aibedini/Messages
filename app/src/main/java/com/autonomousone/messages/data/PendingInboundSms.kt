package com.autonomousone.messages.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * An SMS that arrived and could NOT be written to the provider (mission §16/§52).
 *
 * The default-SMS-app path inserts the incoming message itself and then reads it back. When that insert
 * fails persistently, the message exists nowhere: no other app is the default SMS app, so there is no row
 * for the ContentObserver to find. Before this table, the only trace was a log line — an inbound message
 * could be lost with nothing durable recording that it had ever arrived.
 *
 * A row here is the message held until it can be written, and it is deliberately NOT deleted when the
 * retries give up: a FAILED row is the record that a message was lost, which is the difference between a
 * loss that is known and one that is silent.
 *
 * **Stored in the clear, on purpose, and this is why.** [body] and [address] are the same bytes the
 * mirror's `messages` table holds moments later for every message that *does* arrive, so this is not a new
 * class of at-rest data. The one difference from [PendingDelayedSendEntity] — which stores a token rather
 * than the number — is instructive rather than accidental: a delayed send can carry the recipient in its
 * WorkManager job, and this one cannot, because the job that retries this insert carries no payload at all
 * (§41 forbids putting a recipient or a body in input data).
 */
@Entity(
    tableName = "pending_inbound_sms",
    indices = [
        // Idempotency: one row per RECEIVED BROADCAST. A redelivered intent carries the same PDU
        // fingerprint, so it can never enqueue the message twice — which matters because the retry
        // inserts into the provider, and a duplicate insert is a duplicate message.
        Index(value = ["pduFingerprint"], unique = true),
        // The worker's only read: the oldest pending work.
        Index("state", "createdAt")
    ]
)
data class PendingInboundSmsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Stable identity of the broadcast that carried this message. */
    val pduFingerprint: String,
    val address: String,
    val body: String,
    /** The provider timestamp the row will carry, taken from the PDU. */
    val dateMs: Long,
    /** The resolved thread, or 0 when it could not be resolved. */
    val threadId: Long,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0")
    val attempts: Int = 0,
    /** Why the last insert failed, for the diagnostic. Never the body. */
    val lastError: String? = null,
    /**
     * `@ColumnInfo(defaultValue = ...)` because a Kotlin default is NOT a SQL default.
     *
     * Without it the migration's `DEFAULT 'PENDING'` and Room's generated schema disagree by one
     * `dflt_value`, and Room refuses to open the database. Declaring it also means an insert that forgets
     * the state lands in a valid one instead of an empty string that no `WHERE state = 'PENDING'` would
     * ever match.
     */
    @ColumnInfo(defaultValue = STATE_PENDING)
    val state: String = STATE_PENDING
) {
    companion object {
        /** Held, and eligible for another insert attempt. */
        const val STATE_PENDING = "PENDING"

        /** The message is in the provider. Its job here is done. */
        const val STATE_DONE = "DONE"

        /**
         * Retries are exhausted. The row STAYS, as the durable record of a lost message.
         *
         * Deliberately not deleted and deliberately not a dead-letter queue with a purge: one row is one
         * message a human may want to know about, and there is no correct automatic action left.
         */
        const val STATE_FAILED = "FAILED"

        /** How many insert attempts before a row is called failed. */
        const val MAX_ATTEMPTS = 5
    }
}

@Dao
interface PendingInboundSmsDao {

    /** IGNORE on the fingerprint, so a redelivered broadcast is a no-op rather than a second message. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(row: PendingInboundSmsEntity): Long

    @Query(
        "SELECT * FROM pending_inbound_sms WHERE state = 'PENDING' " +
            "ORDER BY createdAt ASC LIMIT :limit"
    )
    suspend fun pending(limit: Int): List<PendingInboundSmsEntity>

    @Query("SELECT COUNT(*) FROM pending_inbound_sms WHERE state = 'PENDING'")
    suspend fun pendingDepth(): Int

    /** The durable record of messages that could not be stored. Reported, never silently dropped. */
    @Query("SELECT COUNT(*) FROM pending_inbound_sms WHERE state = 'FAILED'")
    suspend fun failedDepth(): Int

    @Query("SELECT * FROM pending_inbound_sms WHERE id = :id")
    suspend fun byId(id: Long): PendingInboundSmsEntity?

    @Query(
        "UPDATE pending_inbound_sms SET state = :state, attempts = :attempts, lastError = :error " +
            "WHERE id = :id"
    )
    suspend fun mark(id: Long, state: String, attempts: Int, error: String?): Int
}
