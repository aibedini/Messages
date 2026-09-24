package com.autonomousone.messages.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.autonomousone.messages.sync.HistorySyncSession

/**
 * One history-sync session: a source's scan from cursor to exhaustion (mission §25), carrying the
 * eligibility arithmetic mission §70 requires.
 *
 * **Why one row and not two.** §25 asks for sessions to be first-class and §70 asks for
 * `Eligible = Enqueued + Skipped + Failed` to balance. Those are the same object seen from two
 * sides: the numbers ARE the session's outcome, and a session that could not state what it did with
 * every row it read would be a row that only records that something happened.
 *
 * **Why the counters are column-per-reason.** The mission's requirement is that every discrepancy is
 * *explainable*, not merely counted. A single `skipped` total would say "1,204 rows did not
 * replicate" without saying whether that is the user's LOCAL_ONLY policy working (fine) or a
 * defect (not fine). The four reasons are stored separately for exactly that reason, and the total is
 * derived rather than stored so the parts and the total cannot drift apart.
 *
 * **Why `finishedAt = 0` means open.** A scan is resumable across process deaths, so "this source is
 * still being scanned" has to be representable. A nullable timestamp would work too, but 0 keeps the
 * lookup index usable and matches the `expiresAt = 0` convention already used for commands.
 */
@Entity(
    tableName = "history_sync_sessions",
    indices = [Index("source", "startedAt"), Index("finishedAt")]
)
data class HistorySyncSessionEntity(
    @PrimaryKey val sessionId: String,
    val source: String,
    val generation: Long,
    val startedAt: Long,
    val updatedAt: Long,
    /** 0 while the scan of this source is still in progress. */
    @ColumnInfo(defaultValue = "0")
    val finishedAt: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val eligible: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val enqueued: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val failed: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val skippedLocalOnly: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val skippedAskPending: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val skippedNoDirection: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val skippedSyncOff: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val scanExhausted: Boolean = false,
) {
    /** The row-level projection the accounting policy works on. */
    fun counters(): HistorySyncSession = HistorySyncSession(
        sessionId = sessionId,
        source = source,
        generation = generation,
        startedAt = startedAt,
        finishedAt = finishedAt,
        eligible = eligible,
        enqueued = enqueued,
        failed = failed,
        skippedLocalOnly = skippedLocalOnly,
        skippedAskPending = skippedAskPending,
        skippedNoDirection = skippedNoDirection,
        skippedSyncOff = skippedSyncOff,
        scanExhausted = scanExhausted,
    )
}

@Dao
interface HistorySyncSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: HistorySyncSessionEntity)

    /** The session still scanning this source, if any. */
    @Query(
        "SELECT * FROM history_sync_sessions " +
            "WHERE source = :source AND finishedAt = 0 ORDER BY startedAt DESC LIMIT 1"
    )
    suspend fun openFor(source: String): HistorySyncSessionEntity?

    @Query("SELECT * FROM history_sync_sessions WHERE sessionId = :sessionId")
    suspend fun get(sessionId: String): HistorySyncSessionEntity?

    /** Most recently touched sessions first — what a diagnostic should show. */
    @Query("SELECT * FROM history_sync_sessions ORDER BY startedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<HistorySyncSessionEntity>

    /**
     * The newest CLOSED session for a source, or the open one if the scan has never finished.
     *
     * Deliberately one query for "what should I report about this source?" so a caller does not have
     * to decide between two sources of truth.
     */
    @Query(
        "SELECT * FROM history_sync_sessions WHERE source = :source " +
            "ORDER BY startedAt DESC LIMIT 1"
    )
    suspend fun latestFor(source: String): HistorySyncSessionEntity?

    /**
     * Apply a batch of outcomes in one statement.
     *
     * Increments rather than assigning, so two passes over the same session accumulate instead of
     * overwriting each other — a scan is many passes, and an assignment would silently report only
     * the last page.
     */
    @Query(
        "UPDATE history_sync_sessions SET " +
            "eligible = eligible + :eligible, enqueued = enqueued + :enqueued, " +
            "failed = failed + :failed, " +
            "skippedLocalOnly = skippedLocalOnly + :skippedLocalOnly, " +
            "skippedAskPending = skippedAskPending + :skippedAskPending, " +
            "skippedNoDirection = skippedNoDirection + :skippedNoDirection, " +
            "skippedSyncOff = skippedSyncOff + :skippedSyncOff, " +
            "updatedAt = :updatedAt " +
            "WHERE sessionId = :sessionId"
    )
    suspend fun accumulate(
        sessionId: String,
        eligible: Long,
        enqueued: Long,
        failed: Long,
        skippedLocalOnly: Long,
        skippedAskPending: Long,
        skippedNoDirection: Long,
        skippedSyncOff: Long,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE history_sync_sessions SET finishedAt = :finishedAt, scanExhausted = :exhausted, " +
            "updatedAt = :finishedAt WHERE sessionId = :sessionId AND finishedAt = 0"
    )
    suspend fun close(sessionId: String, finishedAt: Long, exhausted: Boolean): Int
}
