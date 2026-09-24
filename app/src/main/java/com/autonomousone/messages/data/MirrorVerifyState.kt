package com.autonomousone.messages.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.autonomousone.messages.sync.MirrorVerifyCursor
import com.autonomousone.messages.sync.MirrorVerifyProgress

/**
 * How far the full-mirror verification sweep has walked each source (mission §35).
 *
 * **Why this is durable and not in memory.** The sweep covers the whole mirror, which on a large
 * install is hundreds of thousands of rows and therefore many bounded passes. A cursor held in memory
 * would restart from the newest row on every process start — and since a pass is deliberately small
 * relative to the mirror, a device that restarts daily would never reach the oldest rows at all. The
 * sweep would look like it was running forever while making no progress, which is the most expensive
 * kind of wrong: it looks like work.
 *
 * **One row per source.** Same reasoning as the history checkpoint: the walk is per-source, and a
 * single cursor across both would have to interleave two key spaces.
 */
@Entity(tableName = "mirror_verify_state")
data class MirrorVerifyStateEntity(
    @PrimaryKey val source: String,
    /** The keyset cursor; [MirrorVerifyCursor.START] means "begin at the newest row". */
    @ColumnInfo(defaultValue = "9223372036854775807")
    val cursorDate: Long = Long.MAX_VALUE,
    @ColumnInfo(defaultValue = "9223372036854775807")
    val cursorProviderId: Long = Long.MAX_VALUE,
    @ColumnInfo(defaultValue = "0")
    val startedAt: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = 0,
    /** 0 while the sweep is still running. */
    @ColumnInfo(defaultValue = "0")
    val completedAt: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val examined: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val alreadyReplicated: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val recovered: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val skippedNoDirection: Long = 0,
    /** Rows with no provider id: never looked up, so never claimable as replicated. */
    @ColumnInfo(defaultValue = "0")
    val skippedNoProviderId: Long = 0
) {
    /** The pure projection the policy works on. */
    fun progress(): MirrorVerifyProgress = MirrorVerifyProgress(
        source = source,
        cursor = MirrorVerifyCursor(date = cursorDate, providerId = cursorProviderId),
        startedAt = startedAt,
        updatedAt = updatedAt,
        completedAt = completedAt,
        examined = examined,
        alreadyReplicated = alreadyReplicated,
        recovered = recovered,
        skippedNoDirection = skippedNoDirection,
        skippedNoProviderId = skippedNoProviderId
    )

    companion object {
        fun of(progress: MirrorVerifyProgress, now: Long): MirrorVerifyStateEntity =
            MirrorVerifyStateEntity(
                source = progress.source,
                cursorDate = progress.cursor.date,
                cursorProviderId = progress.cursor.providerId,
                startedAt = progress.startedAt,
                updatedAt = now,
                completedAt = progress.completedAt,
                examined = progress.examined,
                alreadyReplicated = progress.alreadyReplicated,
                recovered = progress.recovered,
                skippedNoDirection = progress.skippedNoDirection,
                skippedNoProviderId = progress.skippedNoProviderId
            )
    }
}

@Dao
interface MirrorVerifyStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: MirrorVerifyStateEntity)

    @Query("SELECT * FROM mirror_verify_state WHERE source = :source")
    suspend fun get(source: String): MirrorVerifyStateEntity?

    @Query("SELECT * FROM mirror_verify_state ORDER BY source")
    suspend fun all(): List<MirrorVerifyStateEntity>
}
