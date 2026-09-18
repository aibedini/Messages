package com.autonomousone.messages.data

import android.content.Context

/**
 * Capped exponential retry schedule for exact provider repairs.
 *
 * Pure arithmetic, deliberately not part of the Room class, so the schedule is
 * unit-testable without a database.
 *
 * There is a CAP and therefore no permanent abandonment: an exact provider
 * identity is NEVER given up on merely because it failed N times. A transient
 * provider outage (radio off, provider process restarting, no permission window)
 * must not turn into "this message stays wrong forever".
 */
object ProviderRepairBackoff {

    /** First retry: 1s. */
    const val BASE_MS = 1_000L

    /** Steady-state retry ceiling: 5 minutes, forever. */
    const val MAX_MS = 5L * 60_000L

    /**
     * Delay after [attempts] consecutive failures (1-based).
     *
     * 1s, 2s, 4s, 8s ... capped at [MAX_MS]. Always finite, never zero, so a
     * permanently broken provider cannot spin the scheduler.
     */
    fun delayMs(attempts: Int): Long {
        val a = attempts.coerceAtLeast(1)
        if (a >= 16) return MAX_MS
        return (BASE_MS shl (a - 1)).coerceAtMost(MAX_MS)
    }
}

/**
 * Durable exact-repair queue.
 *
 * Replaces the in-process PendingExactRepairs map. The properties that matter,
 * and the reason each one exists:
 *
 *  - DURABLE. Work is a Room row, so process death, an app update or a reboot
 *    cannot drop it. The provider still holds the truth, but "the next observer
 *    event will fix it" is not a guarantee: a row that changes while the app is
 *    dead produces no event.
 *  - SELF-DRIVEN. A timer wakes the queue; a provider burst merely nudges it
 *    earlier. Retrying only on another provider event meant a failed read during
 *    a quiet period was never retried.
 *  - CLAIMED. [due] does not claim; [claim] does, and is generation-scoped. Two
 *    workers cannot read the same identity at once.
 *  - GENERATION-SCOPED. A new provider event for the same identity bumps the
 *    generation, so an older in-flight read can neither consume newer work nor
 *    (thanks to the ownership check in the drain loop) act on it.
 *  - LEASED. An IN_FLIGHT row carries a lease; an expired lease is reclaimed at
 *    startup, so a crash mid-read cannot strand work forever.
 *  - UNCAPPED. No eviction, no capacity limit. Rate is bounded, state is not.
 */
class ProviderRepairQueue(context: Context) {

    private val dao = MessagesDatabase.get(context).providerRepairDao()

    companion object {
        /** Long enough for one bounded provider read, short enough to recover fast. */
        const val LEASE_MS = 30_000L

        /** Bounded processing rate per drain; NOT a bound on stored work. */
        const val MAX_CLAIM_PER_DRAIN = 16

        /** Diagnostics snapshot bound. */
        const val MAX_SNAPSHOT = 50

        /**
         * How long an EXPECT_EXISTS / REFRESH_STATUS absence may still mean
         * "created, not query-visible yet".
         *
         * This is a VISIBILITY grace, not a retry delay: a provider row written
         * moments ago is frequently not readable yet (provider transaction still
         * open, OEM provider page not refreshed). Within this window an absence is
         * never evidence, and after it an absence still only produces a
         * VERIFY_DELETE_CANDIDATE - never a direct delete.
         */
        const val VISIBILITY_GRACE_MS = 30_000L

        /**
         * SUCCESSFUL absence observations required before an absence is "mature".
         *
         * Evidence, not effort: retries, provider failures and backoffs NEVER count
         * toward this, only a provider that positively answered and did not have
         * the row.
         */
        const val ABSENCE_MIN_SUCCESSES = 3
    }

    /**
     * Records work for an exact identity, or bumps an existing entry's
     * generation. Idempotent for repeated notifications of the SAME change.
     */
    suspend fun enqueue(
        source: String,
        providerId: Long,
        intent: ProviderRepairIntent,
        now: Long = System.currentTimeMillis()
    ) {
        if (source.isBlank() || providerId <= 0L) return
        // A newer intent always wins: generation++ and intent are written together,
        // so an older in-flight worker fails stillOwned() and can neither delete nor
        // ACK on behalf of the newer work.
        dao.enqueue(source, providerId, now, intent.name)
    }

    /**
     * Re-arms the CLAIMED generation under a different intent.
     *
     * Scoped to the caller's generation, so it can never overwrite a newer intent.
     * Returns false when the generation was already superseded.
     */
    suspend fun rearm(
        entry: ProviderRepairEntity,
        intent: ProviderRepairIntent,
        now: Long = System.currentTimeMillis()
    ): Boolean =
        dao.rearm(entry.source, entry.providerId, entry.generation, intent.name, now) == 1

    /** Observations due for a retry. Non-claiming: see [claim]. */
    suspend fun due(now: Long, limit: Int = MAX_CLAIM_PER_DRAIN): List<ProviderRepairEntity> =
        dao.due(now, limit)

    /** @return true only when THIS caller now owns the work. */
    suspend fun claim(entry: ProviderRepairEntity, now: Long = System.currentTimeMillis()): Boolean =
        dao.claim(entry.source, entry.providerId, entry.generation, now + LEASE_MS, now) == 1

    /** True while this caller still owns the generation it claimed. */
    suspend fun stillOwned(entry: ProviderRepairEntity): Boolean =
        dao.stillOwned(entry.source, entry.providerId, entry.generation) > 0

    /** Success: the work is done and leaves the queue. */
    suspend fun ack(entry: ProviderRepairEntity): Boolean =
        dao.ack(entry.source, entry.providerId, entry.generation) == 1

    /** Failure: the work stays, with a capped backoff. It is never dropped. */
    suspend fun nack(entry: ProviderRepairEntity, reason: String, now: Long = System.currentTimeMillis()) {
        val attempts = entry.attempts + 1
        dao.nack(
            entry.source,
            entry.providerId,
            entry.generation,
            now + ProviderRepairBackoff.delayMs(attempts),
            reason,
            now
        )
    }

    /**
     * Records ONE successful absence for the caller's claimed generation.
     *
     * @return true when the observation was actually credited. False means the
     *         generation was superseded or the lease was lost, in which case the
     *         caller's evidence must not be used - and, because an absence is the
     *         only thing that can lead to a delete, it must not be used to DELETE
     *         either.
     */
    suspend fun recordAbsence(
        entry: ProviderRepairEntity,
        now: Long = System.currentTimeMillis()
    ): Boolean =
        dao.recordAbsence(entry.source, entry.providerId, entry.generation, now) == 1

    suspend fun reclaimExpiredLeases(now: Long = System.currentTimeMillis()): Int =
        dao.reclaimExpiredLeases(now)

    /**
     * When this queue could next have work.
     *
     * Considers BOTH the earliest pending retry and the earliest lease expiry, so
     * a crashed owner is recovered even if nobody ever sends a nudge. Null means
     * the queue is empty.
     */
    suspend fun nextWakeAt(): Long? {
        val pending = dao.minPendingRetryAt()
        val lease = dao.minLeaseUntil()
        return when {
            pending == null -> lease
            lease == null -> pending
            else -> minOf(pending, lease)
        }
    }

    /** Number of identities currently carrying unresolved work. */
    suspend fun depth(): Int = dao.count()

    suspend fun totalAttempts(): Int = dao.totalAttempts()

    /** Diagnostics only. */
    suspend fun snapshot(limit: Int = MAX_SNAPSHOT): List<ProviderRepairEntity> = dao.snapshot(limit)
}
