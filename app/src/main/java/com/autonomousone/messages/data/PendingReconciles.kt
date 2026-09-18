package com.autonomousone.messages.data

/**
 * Reliable IN-PROCESS work accumulator for reconcile work, with
 * CLAIM / ACK / NACK, per-unit GENERATIONS and explicit unit states.
 *
 * ## What this is NOT
 *
 * VOLATILE. In memory only. Deliberately NOT described as durable: when the
 * Android process dies this object is gone and everything it held is lost.
 * Process-death recovery is a different, unfinished problem that depends on
 * durable Room sync_state plus the startup/provider reconcile path.
 *
 *  - failure while the process is alive -> NACK, work stays known and retries;
 *  - PROCESS DEATH -> accumulator lost (no claim of durability).
 *
 * ## Generations: an ACK may never consume NEWER work
 *
 * A boolean "is this unit pending" is not enough. If work for unit X is claimed
 * and a NEW event for X arrives while it executes, a naive ACK of the old claim
 * would clear the new event — a lost update:
 *
 *     ForThread(42) claimed -> provider repair reads its snapshot
 *     NEW provider event for 42 arrives            (ignored: 42 in flight)
 *     old repair ACKs success                      (clears 42)
 *     => the new mutation is LOST
 *
 * Every unit therefore carries a generation:
 *
 *     fullSyncEpoch, tailEpoch, threadEpoch[id]
 *
 * bumped on EVERY add(). A claim captures the generation it covered, and an ACK
 * only clears that generation:
 *
 *     ackThread(id, claimedEpoch, success)
 *        currentEpoch == claimedEpoch -> done
 *        currentEpoch >  claimedEpoch -> IMMEDIATELY re-pending (newer work)
 *
 * Invariant: ACK of old work can never acknowledge a newer event.
 *
 * ## Per-unit state
 *
 *   PENDING -> IN_FLIGHT -> (ACK | NACK)
 *                            NACK -> BACKOFF -> PENDING  (retry)
 *                                 |-> QUARANTINED       (thread repairs only)
 *
 * FullSync and TailDelta are never permanently abandoned (capped backoff,
 * observable, stays degraded). A provably poisonous THREAD repair is quarantined
 * instead of retried forever; quarantine is observable, cannot starve healthy
 * threads, and a fresh provider event RE-ARMS it.
 */
class PendingReconciles(
    /** Thread repairs returned by one claim. Bounds a claim, never drops. */
    private val threadChunkSize: Int = 8,
    /** Attempts before a THREAD repair is quarantined. Global work is exempt. */
    private val maxThreadAttempts: Int = 5,
    /** Capped exponential backoff: 1s, 2s, 4s ... capped at 64s. */
    private val backoffMs: (attempt: Int) -> Long = { a -> 1_000L shl (a - 1).coerceIn(0, 6) }
) {

    /** Observable lifecycle of one unit of work. */
    enum class UnitState { ABSENT, PENDING, IN_FLIGHT, BACKOFF, QUARANTINED }

    /** A thread repair together with the generation the claim actually covered. */
    data class ClaimedThread(val threadId: Long, val epoch: Long)

    /** Work a consumer has taken responsibility for and must ack or nack. */
    data class Claim(
        /** Non-null when a full sync was claimed; carries its covered generation. */
        val fullSyncEpoch: Long?,
        /** Non-null when a tail was claimed; carries its covered generation. */
        val tailEpoch: Long?,
        val threads: List<ClaimedThread>
    ) {
        val fullSync: Boolean get() = fullSyncEpoch != null
        val isEmpty: Boolean
            get() = fullSyncEpoch == null && tailEpoch == null && threads.isEmpty()
    }

    private val lock = Any()

    // ── global work (never abandoned) ──
    private var fullSync = false
    private var fullSyncEpoch = 0L
    private var fullSyncInFlightEpoch: Long? = null
    private var fullSyncAttempts = 0
    private var fullSyncRetryAt = 0L

    private var tailDelta = false
    private var tailEpoch = 0L
    private var tailInFlightEpoch: Long? = null
    private var tailAttempts = 0
    private var tailRetryAt = 0L

    // ── thread repairs ──
    private val pendingThreads = LinkedHashSet<Long>()
    /** threadId -> generation currently in flight. */
    private val inFlightThreads = LinkedHashMap<Long, Long>()
    private val quarantinedThreads = LinkedHashSet<Long>()
    /** threadId -> latest generation seen. Bumped on EVERY add(). */
    private val threadEpoch = HashMap<Long, Long>()
    private val threadAttempts = HashMap<Long, Int>()
    private val threadRetryAt = HashMap<Long, Long>()

    fun add(request: ReconcileRequest) {
        synchronized(lock) {
            when (request) {
                is ReconcileRequest.FullSync -> {
                    fullSync = true
                    fullSyncEpoch++
                }
                is ReconcileRequest.TailDelta -> {
                    tailDelta = true
                    tailEpoch++
                }
                is ReconcileRequest.ForThread -> {
                    val id = request.threadId
                    if (id <= 0L) return
                    // ALWAYS bump the generation, even while this thread is in
                    // flight. That bump is what makes the in-flight ACK refuse to
                    // clear the new event.
                    threadEpoch[id] = (threadEpoch[id] ?: 0L) + 1L
                    // A fresh provider event also RE-ARMS a quarantined thread and
                    // retries immediately instead of waiting out the old backoff.
                    quarantinedThreads.remove(id)
                    threadAttempts.remove(id)
                    threadRetryAt.remove(id)
                    pendingThreads.add(id)
                }
            }
        }
    }

    /**
     * Claims the work that is due at [now]. Claimed units are held IN_FLIGHT with
     * their covered generation and are not handed out again until acked/nacked.
     */
    fun claim(now: Long): Claim? = synchronized(lock) {
        val claimFull = fullSync && fullSyncInFlightEpoch == null && fullSyncRetryAt <= now
        val claimTail = tailDelta && tailInFlightEpoch == null && tailRetryAt <= now
        val ids = pendingThreads
            .filter { it !in inFlightThreads && (threadRetryAt[it] ?: 0L) <= now }
            .take(threadChunkSize)

        if (!claimFull && !claimTail && ids.isEmpty()) return null

        if (claimFull) fullSyncInFlightEpoch = fullSyncEpoch
        if (claimTail) tailInFlightEpoch = tailEpoch
        pendingThreads.removeAll(ids.toSet())
        val claimed = ids.map { id ->
            val epoch = threadEpoch[id] ?: 0L
            inFlightThreads[id] = epoch
            ClaimedThread(id, epoch)
        }

        Claim(
            fullSyncEpoch = if (claimFull) fullSyncEpoch else null,
            tailEpoch = if (claimTail) tailEpoch else null,
            threads = claimed
        )
    }

    /**
     * ACK/NACK the full sync. A claimed tail is only consumed when the full sync
     * SUCCEEDED; on failure BOTH return to retry.
     */
    fun ackFullSync(claim: Claim, success: Boolean, now: Long) {
        synchronized(lock) {
            val claimed = claim.fullSyncEpoch ?: return
            fullSyncInFlightEpoch = null
            if (success) {
                // Only clear the generation this claim actually covered: a
                // FullSync requested while this one ran stays pending.
                if (fullSyncEpoch == claimed) {
                    fullSync = false
                    fullSyncAttempts = 0
                    fullSyncRetryAt = 0L
                }
                claim.tailEpoch?.let { ackTailLocked(it, true, now) }
            } else {
                fullSyncAttempts++
                fullSyncRetryAt = now + backoffMs(fullSyncAttempts) // capped, never abandoned
                claim.tailEpoch?.let { ackTailLocked(it, false, now) }
            }
        }
    }

    /** ACK/NACK a claimed tail (when no full sync was in the same claim). */
    fun ackTail(claim: Claim, success: Boolean, now: Long) {
        synchronized(lock) { claim.tailEpoch?.let { ackTailLocked(it, success, now) } }
    }

    private fun ackTailLocked(epoch: Long, success: Boolean, now: Long) {
        tailInFlightEpoch = null
        if (success) {
            // Only clear the generation this claim covered.
            if (tailEpoch == epoch) {
                tailDelta = false
                tailAttempts = 0
                tailRetryAt = 0L
            }
            return
        }
        tailAttempts++
        tailRetryAt = now + backoffMs(tailAttempts) // capped, never abandoned
    }

    /**
     * ACK/NACK one thread repair, scoped to the generation the claim covered.
     *
     * success + same generation      -> done
     * success + newer generation     -> IMMEDIATELY pending again (lost-update fix)
     * failure                        -> requeue with backoff, or quarantine
     */
    fun ackThread(claimed: ClaimedThread, success: Boolean, now: Long) {
        val threadId = claimed.threadId
        if (threadId <= 0L) return
        synchronized(lock) {
            inFlightThreads.remove(threadId)
            val current = threadEpoch[threadId] ?: 0L

            if (success && current == claimed.epoch) {
                threadAttempts.remove(threadId)
                threadRetryAt.remove(threadId)
                return
            }
            if (success) {
                // A newer event for this thread arrived while the old claim was
                // executing. This ACK does NOT cover it.
                threadAttempts.remove(threadId)
                threadRetryAt.remove(threadId)
                pendingThreads.add(threadId)
                return
            }

            val attempt = (threadAttempts[threadId] ?: 0) + 1
            if (attempt >= maxThreadAttempts) {
                quarantinedThreads.add(threadId)
                threadAttempts.remove(threadId)
                threadRetryAt.remove(threadId)
                return
            }
            threadAttempts[threadId] = attempt
            threadRetryAt[threadId] = now + backoffMs(attempt)
            pendingThreads.add(threadId) // REQUEUE — never silently lost
        }
    }

    /** True when anything is pending, in flight, or in backoff. */
    fun hasWork(): Boolean = synchronized(lock) {
        fullSync || tailDelta || pendingThreads.isNotEmpty() ||
            inFlightThreads.isNotEmpty() || fullSyncInFlightEpoch != null ||
            tailInFlightEpoch != null
    }

    /**
     * Milliseconds until the earliest scheduled retry, or 0 when nothing is
     * scheduled. A caller must ARM A TIMER with this, never sleep on it: parking
     * the only consumer would let a poison thread delay unrelated realtime work.
     */
    fun nextWakeUpInMs(now: Long): Long = synchronized(lock) {
        var earliest = Long.MAX_VALUE
        if (fullSync && fullSyncInFlightEpoch == null) earliest = minOf(earliest, fullSyncRetryAt)
        if (tailDelta && tailInFlightEpoch == null) earliest = minOf(earliest, tailRetryAt)
        pendingThreads.forEach { earliest = minOf(earliest, threadRetryAt[it] ?: 0L) }
        if (earliest == Long.MAX_VALUE) return 0L
        (earliest - now).coerceAtLeast(1L)
    }

    // ── observability ──

    fun fullSyncState(now: Long): UnitState = synchronized(lock) {
        when {
            fullSyncInFlightEpoch != null -> UnitState.IN_FLIGHT
            !fullSync -> UnitState.ABSENT
            fullSyncRetryAt > now -> UnitState.BACKOFF
            else -> UnitState.PENDING
        }
    }

    fun tailState(now: Long): UnitState = synchronized(lock) {
        when {
            tailInFlightEpoch != null -> UnitState.IN_FLIGHT
            !tailDelta -> UnitState.ABSENT
            tailRetryAt > now -> UnitState.BACKOFF
            else -> UnitState.PENDING
        }
    }

    fun threadState(threadId: Long, now: Long): UnitState = synchronized(lock) {
        when {
            threadId in inFlightThreads -> UnitState.IN_FLIGHT
            threadId in quarantinedThreads -> UnitState.QUARANTINED
            threadId !in pendingThreads -> UnitState.ABSENT
            (threadRetryAt[threadId] ?: 0L) > now -> UnitState.BACKOFF
            else -> UnitState.PENDING
        }
    }

    internal fun pendingThreadCount(): Int = synchronized(lock) { pendingThreads.size }

    internal fun inFlightThreadCount(): Int = synchronized(lock) { inFlightThreads.size }

    fun quarantinedThreadIds(): Set<Long> = synchronized(lock) { quarantinedThreads.toSet() }
}
