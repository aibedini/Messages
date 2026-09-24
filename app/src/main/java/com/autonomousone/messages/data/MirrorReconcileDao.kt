package com.autonomousone.messages.data

import androidx.room.Dao
import androidx.room.Query

/**
 * A mirror row, projected down to only what reconciliation needs.
 *
 * Deliberately not a `MessageEntity`: this is a read-only projection over the `messages` table, so
 * it carries no `@Entity` and changing it cannot alter the schema.
 */
data class MirrorReconcileRow(
    val source: String,
    val providerId: Long,
    val threadId: Long,
    val normalizedAddress: String,
    val body: String,
    val date: Long,
    val type: Int,
    val status: Int,
    val read: Boolean
)

@Dao
interface MirrorReconcileDao {

    /**
     * The most recent mirror rows, newest first, bounded (mission §35).
     *
     * A TIME WINDOW rather than a row-id cursor, for a reason specific to this table: `messages`
     * has a composite primary key `(source, providerId)` and no autoincrement id, and rows are
     * inserted in arbitrary date order — the history backfill writes OLD messages long after they
     * were sent. A "rows after id N" cursor would therefore step over newly-backfilled older rows
     * entirely. A window that moves with the clock cannot: anything that arrives is inside it.
     *
     * Backed by the existing `Index("date")`, so the window is an indexed range scan.
     */
    @Query(
        "SELECT source, providerId, threadId, normalizedAddress, body, date, type, status, read " +
            "FROM messages WHERE date >= :since ORDER BY date DESC, providerId DESC LIMIT :limit"
    )
    suspend fun recentWindow(since: Long, limit: Int): List<MirrorReconcileRow>

    /** How many rows the window covers, for diagnostics — the bounded cost of a run. */
    @Query("SELECT COUNT(*) FROM messages WHERE date >= :since")
    suspend fun windowSize(since: Long): Int

    /**
     * One page of the full-mirror verification walk, strictly older than a keyset cursor (§35).
     *
     * The composite predicate is the point and cannot be simplified to `date < :beforeDate`. Rows
     * routinely share a date — a burst of messages in the same second, and multi-part SMS — so a
     * date-only cursor would either re-read the whole boundary group (infinite loop) or skip it
     * (`date < :beforeDate - 1`, silent loss). Comparing the pair mirrors the table's own primary
     * key, and the ORDER BY matches it so the walk is a single indexed range scan per source.
     *
     * Per-source, like the history checkpoint, because two key spaces cannot share one cursor.
     */
    @Query(
        "SELECT source, providerId, threadId, normalizedAddress, body, date, type, status, read " +
            "FROM messages WHERE source = :source " +
            "AND (date < :beforeDate OR (date = :beforeDate AND providerId < :beforeId)) " +
            "ORDER BY date DESC, providerId DESC LIMIT :limit"
    )
    suspend fun pageBefore(
        source: String,
        beforeDate: Long,
        beforeId: Long,
        limit: Int
    ): List<MirrorReconcileRow>
}
