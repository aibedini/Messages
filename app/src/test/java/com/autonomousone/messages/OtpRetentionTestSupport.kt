package com.autonomousone.messages

import com.autonomousone.messages.data.MessageClassificationEntity
import com.autonomousone.messages.data.MessageUserStateEntity
import com.autonomousone.messages.messaging.CustomRetentionRange
import com.autonomousone.messages.messaging.OtpRetentionSettings
import com.autonomousone.messages.repository.OtpRetentionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.mapLatest

/**
 * In-memory [OtpRetentionStore] for the FEATURE 14 JVM tests.
 *
 * The engine's persistence needs are a narrow port on purpose, so this fake is
 * ~60 lines instead of ~25 DAO methods — and it cannot be broken by another
 * workstream adding a query to `UxDaos.kt`.
 *
 * The SQL semantics it mirrors are the ones asserted by the Room instrumentation
 * suite:
 *  - `dueForCleanup`  = `otpDeleteEligibleAt > 0 AND <= now`, ordered, LIMITed;
 *  - `earliestEligibleAt` = `MIN(otpDeleteEligibleAt)` over enrolled rows;
 *  - `enrolledCount` = `COUNT(*)` over enrolled rows;
 *  - `clearEligibleAt` = insert-with-defaults then patch the deadline to 0.
 */
internal class FakeRetentionStore : OtpRetentionStore {

    val classifications = linkedMapOf<Pair<String, Long>, MessageClassificationEntity>()
    val userStates = linkedMapOf<Pair<String, Long>, MessageUserStateEntity>()
    val trashed = mutableListOf<Pair<String, Long>>()
    val movesToTrash = mutableListOf<Triple<List<Pair<String, Long>>, Long, Long>>()

    /** Provider direction per message, keyed by (source, providerId). */
    val directions = mutableMapOf<Pair<String, Long>, Int>()

    private val invalidations = MutableStateFlow(0L)

    fun putClassification(row: MessageClassificationEntity) {
        classifications[row.source to row.providerId] = row
        invalidations.value = invalidations.value + 1
    }

    fun putUserState(state: MessageUserStateEntity) {
        userStates[state.source to state.providerId] = state
    }

    override suspend fun classificationOf(source: String, providerId: Long) =
        classifications[source to providerId]

    override suspend fun dueForCleanup(now: Long, limit: Int) =
        classifications.values
            .filter { it.otpDeleteEligibleAt > 0L && it.otpDeleteEligibleAt <= now }
            .sortedBy { it.otpDeleteEligibleAt }
            .take(limit)

    override suspend fun enrolledForTriage(limit: Int) =
        classifications.values
            .filter { it.otpDeleteEligibleAt > 0L }
            .sortedBy { it.otpDeleteEligibleAt }
            .take(limit)

    override suspend fun earliestEligibleAt(): Long? =
        classifications.values.filter { it.otpDeleteEligibleAt > 0L }
            .minOfOrNull { it.otpDeleteEligibleAt }

    override suspend fun enrolledCount(): Int =
        classifications.values.count { it.otpDeleteEligibleAt > 0L }

    override fun observeEarliestEligibleAt(): Flow<Long> =
        invalidations.mapLatest { earliestEligibleAt() ?: 0L }

    override suspend fun setEligibleAt(source: String, providerId: Long, eligibleAt: Long) {
        val existing = classifications[source to providerId] ?: return
        classifications[source to providerId] = existing.copy(otpDeleteEligibleAt = eligibleAt)
        invalidations.value = invalidations.value + 1
    }

    override suspend fun clearEligibleAt(
        source: String,
        providerId: Long,
        threadId: Long,
        category: String,
        now: Long
    ) {
        val existing = classifications[source to providerId]
        classifications[source to providerId] = existing?.copy(otpDeleteEligibleAt = 0L)
            ?: MessageClassificationEntity(
                source = source,
                providerId = providerId,
                threadId = threadId,
                category = category,
                confidence = 0f,
                isOtp = false,
                otpDeleteEligibleAt = 0L,
                classifiedAt = now
            )
        invalidations.value = invalidations.value + 1
    }

    override suspend fun userStateOf(source: String, providerId: Long) =
        userStates[source to providerId]

    override suspend fun setStarred(
        source: String,
        providerId: Long,
        threadId: Long,
        starred: Boolean,
        starredAt: Long,
        now: Long
    ) {
        val existing = userStates[source to providerId]
        userStates[source to providerId] =
            (existing ?: MessageUserStateEntity(source, providerId, threadId))
                .copy(starred = starred, starredAt = starredAt, updatedAt = now)
    }

    override suspend fun setKeep(
        source: String,
        providerId: Long,
        threadId: Long,
        keep: Boolean,
        now: Long
    ) {
        val existing = userStates[source to providerId]
        userStates[source to providerId] =
            (existing ?: MessageUserStateEntity(source, providerId, threadId))
                .copy(keepFromOtpCleanup = keep, updatedAt = now)
    }

    override suspend fun moveToTrash(
        rows: List<MessageClassificationEntity>,
        now: Long,
        purgeAt: Long
    ) {
        movesToTrash += Triple(rows.map { it.source to it.providerId }, now, purgeAt)
        for (row in rows) {
            trashed += row.source to row.providerId
            val existing = userStates[row.source to row.providerId]
            userStates[row.source to row.providerId] =
                (existing ?: MessageUserStateEntity(row.source, row.providerId, row.threadId))
                    .copy(trashedAt = now, purgeAt = purgeAt, updatedAt = now)
            // The store's real implementation clears the deadline in the same
            // transaction; the fake mirrors that so `nextEligibleAt` matches.
            classifications[row.source to row.providerId] =
                row.copy(otpDeleteEligibleAt = 0L)
        }
        invalidations.value = invalidations.value + 1
    }

    override suspend fun messageTypeOf(source: String, providerId: Long): Int? =
        directions[source to providerId]
}

/**
 * JVM stand-in for `OtpRetentionPreferences`.
 *
 * The engine only reads the two values, and the real class's validation lives in
 * `CustomRetentionRange` (unit-tested) plus its own read/write clamping (pinned
 * through those bounds). Defaults match production exactly: OFF, 24 hours.
 */
internal class FakeRetentionSettings(
    override var enabled: Boolean = false,
    override var retentionMillis: Long = 24L * CustomRetentionRange.HOUR_MS
) : OtpRetentionSettings
