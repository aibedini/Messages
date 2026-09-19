package com.autonomousone.messages.repository

import android.content.Context
import androidx.room.withTransaction
import com.autonomousone.messages.data.ExistingOtpCleanupCandidate
import com.autonomousone.messages.data.MessageClassificationDao
import com.autonomousone.messages.data.MessageClassificationEntity
import com.autonomousone.messages.data.MessageUserStateDao
import com.autonomousone.messages.data.MessageUserStateEntity
import com.autonomousone.messages.data.MessagesDatabase
import kotlinx.coroutines.flow.Flow

/**
 * The bounded storage surface GLOBAL OTP retention (FEATURE 14) needs.
 *
 * WHY A PORT AND NOT THE DAOs DIRECTLY
 * ------------------------------------
 * `UxDaos.kt` is shared with every other v3.4.0 workstream and grows as they land.
 * Depending on the DAO *interfaces* from the retention engine would (a) couple
 * one feature to every unrelated query added elsewhere and (b) force the JVM
 * tests to implement ~25 Room methods they never call. This port exposes only
 * the handful of operations the engine actually performs, so:
 *
 *  - the engine is unit-testable with a ~40-line in-memory fake, and stays
 *    testable while other workstreams keep editing `UxDaos.kt`;
 *  - every query is still the SAME index-backed SQL — the Room implementation
 *    below delegates 1:1 and adds no scanning.
 *
 * All methods run off the main thread (the engine calls them from
 * `Dispatchers.IO`).
 */
interface OtpRetentionStore {

    suspend fun classificationOf(source: String, providerId: Long): MessageClassificationEntity?

    /** Index-backed due set: `otpDeleteEligibleAt > 0 AND <= now`, LIMIT-bounded. */
    suspend fun dueForCleanup(now: Long, limit: Int): List<MessageClassificationEntity>

    /** Bounded enrolled set, oldest deadline first (triage). */
    suspend fun enrolledForTriage(limit: Int): List<MessageClassificationEntity>

    /** `MIN(otpDeleteEligibleAt)`, or null when nothing is enrolled. */
    suspend fun earliestEligibleAt(): Long?

    suspend fun enrolledCount(): Int

    /** Room nudge on any `message_classification` change. */
    fun observeEarliestEligibleAt(): Flow<Long>

    suspend fun setEligibleAt(source: String, providerId: Long, eligibleAt: Long)

    /**
     * Clears the deadline, creating an `isOtp = false` placeholder row when the
     * message has never been classified (an UPDATE alone would be a silent no-op,
     * and `setOtpDeleteEligibleAt` cannot express "unschedule a non-OTP").
     */
    suspend fun clearEligibleAt(
        source: String,
        providerId: Long,
        threadId: Long,
        category: String,
        now: Long
    )

    suspend fun userStateOf(source: String, providerId: Long): MessageUserStateEntity?

    suspend fun setStarred(
        source: String,
        providerId: Long,
        threadId: Long,
        starred: Boolean,
        starredAt: Long,
        now: Long
    )

    suspend fun setKeep(
        source: String,
        providerId: Long,
        threadId: Long,
        keep: Boolean,
        now: Long
    )

    /**
     * MOVES the given rows to Trash: `trashedAt`/`purgeAt` through the existing
     * `MessageUserStateDao.markTrashed` shape, and the deadline cleared, all in
     * ONE transaction. There is deliberately no permanent delete here — Trash
     * retention owns that later and the user can restore.
     */
    suspend fun moveToTrash(rows: List<MessageClassificationEntity>, now: Long, purgeAt: Long)

    /** Provider direction (`messages.type`); null when the mirror row is absent. */
    suspend fun messageTypeOf(source: String, providerId: Long): Int?
}

/**
 * The Room-backed [OtpRetentionStore] — the only production implementation.
 *
 * Every read is the same index-backed query the workstream brief requires; there
 * is no Kotlin-side filtering and no full-table load anywhere in this file.
 */
class RoomOtpRetentionStore(context: Context) : OtpRetentionStore {

    private val db: MessagesDatabase = MessagesDatabase.get(context.applicationContext)

    private val classification: MessageClassificationDao get() = db.messageClassificationDao()
    private val userState: MessageUserStateDao get() = db.messageUserStateDao()

    override suspend fun classificationOf(source: String, providerId: Long) =
        classification.get(source, providerId)

    override suspend fun dueForCleanup(now: Long, limit: Int) =
        classification.dueForOtpCleanup(now, limit)

    override suspend fun enrolledForTriage(limit: Int) = classification.enrolledOtpCleanup(limit)

    override suspend fun earliestEligibleAt(): Long? =
        classification.earliestOtpEligibleAt()?.takeIf { it > 0L }

    override suspend fun enrolledCount(): Int = classification.countEnrolledOtpCleanup()

    override fun observeEarliestEligibleAt(): Flow<Long> =
        classification.observeEarliestOtpEligibleAt()

    override suspend fun setEligibleAt(source: String, providerId: Long, eligibleAt: Long) {
        classification.setOtpDeleteEligibleAt(source, providerId, eligibleAt)
    }

    override suspend fun clearEligibleAt(
        source: String,
        providerId: Long,
        threadId: Long,
        category: String,
        now: Long
    ) {
        classification.clearOtpDeleteEligibleAt(source, providerId, threadId, category, now)
    }

    override suspend fun userStateOf(source: String, providerId: Long) =
        userState.get(source, providerId)

    override suspend fun setStarred(
        source: String,
        providerId: Long,
        threadId: Long,
        starred: Boolean,
        starredAt: Long,
        now: Long
    ) {
        userState.setStarred(source, providerId, threadId, starred, starredAt, now)
    }

    override suspend fun setKeep(
        source: String,
        providerId: Long,
        threadId: Long,
        keep: Boolean,
        now: Long
    ) {
        userState.setKeepFromOtpCleanup(source, providerId, threadId, keep, now)
    }

    override suspend fun moveToTrash(
        rows: List<MessageClassificationEntity>,
        now: Long,
        purgeAt: Long
    ) {
        db.withTransaction {
            for (row in rows) {
                // The ONE trash writer shape (field-scoped: it cannot lose a star).
                userState.markTrashed(
                    source = row.source,
                    providerId = row.providerId,
                    threadId = row.threadId,
                    trashedAt = now,
                    purgeAt = purgeAt,
                    now = now
                )
                // Unschedule inside the same transaction: a crash must never leave
                // a deadline on an already-trashed row, which would make the single
                // worker spin on it forever.
                classification.clearOtpDeleteEligibleAt(
                    source = row.source,
                    providerId = row.providerId,
                    threadId = row.threadId,
                    category = row.category,
                    now = now
                )
            }
        }
    }

    override suspend fun messageTypeOf(source: String, providerId: Long): Int? =
        db.messageDao().typeOf(source, providerId)
}

/**
 * Opt-in sweep source: the bounded keyset page behind "Apply to existing OTP
 * messages". Injected because it is the ONLY read that ever walks history and it
 * must stay obviously bounded.
 */
fun interface OtpExistingSweepSource {
    suspend fun page(afterDate: Long, afterProviderId: Long, limit: Int): List<ExistingOtpCleanupCandidate>
}

/** Reschedules the single unique OTP cleanup work. */
fun interface OtpReschedule {
    fun request()
}
