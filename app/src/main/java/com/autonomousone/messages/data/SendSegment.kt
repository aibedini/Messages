package com.autonomousone.messages.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.TypeConverter
import kotlinx.coroutines.flow.Flow

/**
 * Modem verdict for one submitted segment.
 *
 * PENDING   — native submission accepted, no SENT callback processed yet.
 * CONFIRMED — RESULT_OK for exactly this part.
 * AMBIGUOUS — RESULT_ERROR_GENERIC_FAILURE: the RIL returned a vendor error but
 *             the SMSC accepted and delivered the submit (v2.6.15 policy).
 * FAILED    — explicit radio-level refusal (NO_SERVICE / RADIO_OFF / NULL_PDU).
 */
enum class SegmentCallbackState { PENDING, CONFIRMED, AMBIGUOUS, FAILED }

/** Persists [SegmentCallbackState] by name; unknown values degrade to PENDING. */
class SegmentCallbackStateConverter {
    @TypeConverter
    fun toState(raw: String?): SegmentCallbackState =
        SegmentCallbackState.values().firstOrNull { it.name == raw } ?: SegmentCallbackState.PENDING

    @TypeConverter
    fun fromState(state: SegmentCallbackState?): String =
        (state ?: SegmentCallbackState.PENDING).name
}

/**
 * One carrier-billable outgoing SMS SEGMENT, appended to an immutable
 * submission ledger and later annotated by the modem callback.
 *
 * ── Why the row is split in two halves ──
 * Native submission and modem callback are two INDEPENDENT, ASYNCHRONOUS
 * writers that can interleave in any order. They must never race on one row:
 *
 *  * [submittedAt] is the SUBMISSION FACT. It is written once, by the native
 *    path, and never overwritten — not by a later callback, not by a
 *    redelivery. It is the ONLY column the Home "SMS today" counter reads.
 *    It is nullable for exactly one reason: a callback may reach the ledger
 *    before the submission write has committed. Such a row is inserted with
 *    submittedAt = null and the submission write then fills it in once
 *    (see [SendSegmentSql.FILL_SUBMITTED_AT]) — so both orderings converge to
 *    the identical final row, and a NULL row is simply not counted yet.
 *
 *  * [callbackAt] / [callbackResult] / [callbackState] are the modem verdict.
 *    A callback performs a TARGETED UPDATE of these three columns only; it
 *    never REPLACEs the row, so it can neither move a segment into another
 *    calendar day nor delete an already-counted submission.
 *
 * The composite primary key (rowId, partIndex) stays the unique identity, so a
 * redelivered callback is idempotent rather than additive.
 *
 * The Home counter counts ROWS: a 3-part multipart send contributes 3 — what
 * the carrier bills. A logical-message count would be COUNT(DISTINCT rowId)
 * and is deliberately a separate query.
 *
 * Kept OUT of the sync mirror on purpose: app-owned telemetry no Telephony
 * provider table stores, so no reconcile path may ever touch it.
 */
@Entity(
    tableName = "send_segments",
    primaryKeys = ["rowId", "partIndex"],
    indices = [Index("submittedAt")]
)
data class SendSegmentEntity(
    /** The Sent-row _id returned by the provider at insert time. */
    val rowId: Long,
    /** 0-based multipart index carried by the SENT PendingIntent. */
    val partIndex: Int,
    /** Total parts of the logical message. */
    val partCount: Int,
    /**
     * Immutable epoch millis at which the native SmsManager call accepted this
     * part. Null only until the submission write commits for a row a callback
     * reached first. Stored as UTC epoch millis; the local day window is
     * derived at query time.
     */
    val submittedAt: Long? = null,
    /** SIM that carried this segment (SubscriptionManager.INVALID = -1). */
    val subscriptionId: Int = -1,
    /** Epoch millis of the SENT callback, or null while still PENDING. */
    val callbackAt: Long? = null,
    /** Raw SmsManager/Activity result code of the SENT callback. */
    val callbackResult: Int? = null,
    /** Modem verdict; diagnostics only — never gates the submitted counter. */
    val callbackState: SegmentCallbackState = SegmentCallbackState.PENDING
)

/**
 * Ledger statements shared by the DAO and the SQL-level tests, so the tested
 * SQL is literally the SQL that ships.
 *
 * Portability note: these are plain INSERT-OR-IGNORE / UPDATE / COUNT
 * statements on purpose. The upsert form (ON CONFLICT ... DO UPDATE) needs
 * SQLite 3.24 and would break on API 26-29, where the platform ships older
 * SQLite. Every statement below is individually atomic and idempotent, so any
 * interleaving of the submission and callback writers converges.
 */
object SendSegmentSql {

    /**
     * Completes a row a callback reached before the submission committed.
     * Guarded by "IS NULL", so submittedAt is still written at most once and
     * never moves afterwards.
     */
    const val FILL_SUBMITTED_AT =
        "UPDATE `send_segments` SET `submittedAt` = :submittedAt " +
            "WHERE `rowId` = :rowId AND `partIndex` = :partIndex " +
            "AND `submittedAt` IS NULL"

    /**
     * Targeted callback update: touches ONLY the callback columns. It can never
     * REPLACE the row, so submittedAt survives and a stale callback cannot move
     * a segment into another day.
     */
    const val APPLY_CALLBACK =
        "UPDATE `send_segments` SET `callbackAt` = :callbackAt, " +
            "`callbackResult` = :callbackResult, `callbackState` = :callbackState " +
            "WHERE `rowId` = :rowId AND `partIndex` = :partIndex"

    /** The Home counter's source of truth: immutable submissions in the window. */
    const val COUNT_SUBMITTED_BETWEEN =
        "SELECT COUNT(*) FROM `send_segments` " +
            "WHERE `submittedAt` >= :start AND `submittedAt` < :end"

    /** Per-SIM aggregation over the same immutable window. */
    const val SUBMITTED_BY_SUBSCRIPTION =
        "SELECT `subscriptionId`, COUNT(*) AS `c` FROM `send_segments` " +
            "WHERE `submittedAt` >= :start AND `submittedAt` < :end " +
            "GROUP BY `subscriptionId`"

    /** Diagnostics only: callback verdicts in the window (never gates the counter). */
    const val CALLBACK_STATES_BETWEEN =
        "SELECT `callbackState`, COUNT(*) AS `c` FROM `send_segments` " +
            "WHERE `submittedAt` >= :start AND `submittedAt` < :end " +
            "GROUP BY `callbackState`"

    /** Retention prune. Rows without a submission fact are pruned with it. */
    const val PRUNE_BEFORE =
        "DELETE FROM `send_segments` WHERE `submittedAt` < :before"
}

@Dao
interface SendSegmentDao {

    // ── Native submission (the immutable half) ───────────────────────────────

    /**
     * Inserts the submission fact for one segment.
     *
     * @return the new rowid, or -1 when the row already exists — which means a
     *         callback reached the ledger first and the caller must complete it
     *         through [fillSubmittedAtIfMissing].
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSubmission(segment: SendSegmentEntity): Long

    /**
     * Fills [SendSegmentEntity.submittedAt] for a segment a callback got to
     * first. Guarded by "IS NULL": at most one writer wins and the value never
     * changes again afterwards.
     *
     * @return 1 when the fact was completed, 0 when it was already present.
     */
    @Query(SendSegmentSql.FILL_SUBMITTED_AT)
    suspend fun fillSubmittedAtIfMissing(rowId: Long, partIndex: Int, submittedAt: Long): Int

    // ── Modem callback (the mutable half) ────────────────────────────────────

    /**
     * Applies a SENT callback verdict without touching [SendSegmentEntity.submittedAt].
     *
     * @return 1 when the row existed, 0 when the callback arrived before the
     *         submission write — the caller then inserts a callback-first row
     *         and re-applies this update.
     */
    @Query(SendSegmentSql.APPLY_CALLBACK)
    suspend fun applyCallback(
        rowId: Long,
        partIndex: Int,
        callbackAt: Long,
        callbackResult: Int,
        callbackState: SegmentCallbackState
    ): Int

    // ── Counter ─────────────────────────────────────────────────────────────

    /** Live submitted-segment count for [start, end) — the Home chip. */
    @Query(SendSegmentSql.COUNT_SUBMITTED_BETWEEN)
    fun observeSubmittedBetween(start: Long, end: Long): Flow<Int>

    /** One-shot variant of [observeSubmittedBetween]. */
    @Query(SendSegmentSql.COUNT_SUBMITTED_BETWEEN)
    suspend fun countSubmittedBetween(start: Long, end: Long): Int

    /** Same window broken down per SIM. */
    @Query(SendSegmentSql.SUBMITTED_BY_SUBSCRIPTION)
    suspend fun submittedBySubscription(start: Long, end: Long): List<SubCount>

    /** Diagnostics: how the window's segments resolved, per callback state. */
    @Query(SendSegmentSql.CALLBACK_STATES_BETWEEN)
    suspend fun callbackStatesBetween(start: Long, end: Long): List<StateCount>

    /** Prune ledger rows submitted before [before] (maintenance pass). */
    @Query(SendSegmentSql.PRUNE_BEFORE)
    suspend fun pruneBefore(before: Long): Int
}

/** Row shape for [SendSegmentDao.submittedBySubscription]. */
data class SubCount(val subscriptionId: Int, val c: Int)

/** Row shape for [SendSegmentDao.callbackStatesBetween]. */
data class StateCount(val callbackState: SegmentCallbackState, val c: Int)
