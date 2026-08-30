package com.autonomousone.messages.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One confirmed outgoing SMS SEGMENT (modem callback, not send() attempt),
 * written from SmsStatusReceiver when the per-part SENT PendingIntent fires.
 * v2.6.18: RESULT_OK and the AMBIGUOUS_ACCEPTED verdict (GENERIC_FAILURE —
 * SMSC-accepted on affected RILs, per v2.6.15 policy) count as success;
 * explicit radio errors (NO_SERVICE/RADIO_OFF/NULL_PDU) do not.
 *
 * The Home "N SMS today" counter counts ROWS, not logical messages: a 3-part
 * multipart send contributes 3 — what the carrier bills and what the user
 * wants to see. The composite primary key (rowId, partIndex) is the
 * UNIQUE(rowId, partIndex) the ledger needs: a redelivered callback inserts
 * over the existing row (REPLACE) and the COUNT never moves twice.
 *
 * Kept OUT of the sync mirror on purpose: it is app-owned telemetry that no
 * Telephony provider table stores, so no reconcile path may ever touch it.
 * success=false rows (failed parts) are retained for post-mortems and simply
 * never counted.
 */
@Entity(
    tableName = "send_segments",
    primaryKeys = ["rowId", "partIndex"],
    indices = [Index("sentAt")]
)
data class SendSegmentEntity(
    /** The Sent-row _id returned by the provider at insert time. */
    val rowId: Long,
    /** 0-based multipart index carried by the SENT PendingIntent. */
    val partIndex: Int,
    /** Total parts of the logical message (future per-message reports). */
    val partCount: Int,
    /** Epoch millis of the callback. */
    val sentAt: Long,
    /** SIM that carried this segment (SubscriptionManager.INVALID=-1). */
    val subscriptionId: Int = -1,
    /** RESULT_OK seen for exactly this part. */
    val success: Boolean
)

@Dao
interface SendSegmentDao {

    /** Idempotent per-part record — the PK dedupes redelivered callbacks. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun record(segment: SendSegmentEntity)

    /** Live today-window count feeding the Home chip (midnight-local). */
    @Query(
        "SELECT COUNT(*) FROM send_segments " +
            "WHERE success = 1 AND sentAt >= :dayStart AND sentAt < :dayEnd"
    )
    fun observeSuccessSince(dayStart: Long, dayEnd: Long): Flow<Int>

    /** Same window broken down per SIM — future SIM1/SIM2 stats. */
    @Query(
        "SELECT subscriptionId, COUNT(*) AS c FROM send_segments " +
            "WHERE success = 1 AND sentAt >= :dayStart AND sentAt < :dayEnd " +
            "GROUP BY subscriptionId"
    )
    suspend fun successBySubscription(dayStart: Long, dayEnd: Long): List<SubCount>

    /** Prune ledger rows older than [before] (call from a maintenance pass). */
    @Query("DELETE FROM send_segments WHERE sentAt < :before")
    suspend fun pruneBefore(before: Long): Int
}

/** Row shape for [SendSegmentDao.successBySubscription]. */
data class SubCount(val subscriptionId: Int, val c: Int)
