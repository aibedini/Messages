package com.autonomousone.messages.data

/**
 * Reliable IN-PROCESS work accumulator for reconcile work, with
 * CLAIM / ACK / NACK and explicit unit states.
 *
 * ## What this is NOT
 *
 * This accumulator is VOLATILE. It lives in memory only. It is deliberately NOT
 * described as durable, and it must not be treated as a recovery mechanism:
 *
 *  - an exception / failed provider read while the process is alive  -> NACK,
 *    the work stays known and is retried with backoff;
 *  - PROCESS DEATH (low memory, force stop, crash, `kill -9`) -> this object is
 *    GONE and everything it held is lost.
 *
 * Process-death recovery is a different problem and is NOT solved here: it
 * depends on the durable Room sync state (sync_state watermarks,
 * initialWindowReady, backfill cursor) plus the startup/provider reconciliation
 * path. An earlier comment in this file wrongly claimed the accumulator survives
 * process death; that claim was false and has been removed.
 *
 * ## Two correctness properties
 *
 * 1) Requests are NOT interchangeable, so they are merged by SEMANTIC UNION —
 *    never last-writer-wins. A conflated channel of typed requests dropped work:
 *      ForThread(12), ForThread(99), TailDelta -> TailDelta
 *
 * 2) Receiving work is not completing it. Claimed units move to IN_FLIGHT and
 *    are only cleared by an explicit ACK, so one failing unit cannot discard its
 *    siblings and a transient failure is never a silent loss.
 *
 * ## Per-unit state
 *
 *   PENDING -> IN_FLIGHT -> (ACK | NACK)
 *                            NACK -> BACKOFF -> PENDING  (retry)
 *                                 |-> QUARANTINED       (thread repairs only)
 *
 * FullSync and TailDelta are never permanently abandoned: they are global
 * reconciliation and must keep retrying at a capped interval, staying observable
 * as degraded. A provably poisonous THREAD repair is quarantined instead of
 * retried forever — quarantine is observable, cannot starve healthy threads, and
 * a new provider event for that thread RE-ARMS it.
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

    /** Work a consumer has taken responsibility for and must ack or nack. */
    data class Claim(
        val fullSync: Boolean,
        /** Non-null when a tail was claimed; carries the epoch the claim covers. */
        val tailEpoch: Long?,
        val threadIds: List<Long>
    ) {
        val isEmpty: Boolean get() = !fullSync && tailEpoch == null && threadIds.isEmpty()
    }

    private val lock = Any()

    // ── global work (never abandoned) ──
    private var fullSync = false
    private var fullSyncInFlight = false
    private var fullSyncAttempts = 0
    private var fullSyncRetryAt = 0L

    private var tailDelta = false
    private var tailInFlight = false
    private var tailEpoch = 0L
    private var tailAttempts = 0
    private var tailRetryAt = 0L

    // ── thread repairs ──
    private val pendingThreads = LinkedHashSet<Long>()
    private val inFlightThreads = LinkedHashSet<Long>()
    private val quarantinedThreads = LinkedHashSet<Long>()
    private val threadAttempts = HashMap<Long, Int>()
    private val threadRetryAt = HashMap<Long, Long>()

    fun add(request: ReconcileRequest) {
        synchronized(lock) {
            when (request) {
                is ReconcileRequest.FullSync -> fullSync = true
                is ReconcileRequest.TailDelta -> {
                    // A NEW tail gets a new epoch: a tail arriving while a full
                    // sync executes must not be treated as covered by it.
                    tailDelta = true
                    tailEpoch++
                }
                is ReconcileRequest.ForThread -> {
                    val id = request.threadId
                    if (id <= 0L) return
                    // A fresh provider event RE-ARMS a quarantined thread: the
                    // world changed, so the old failure history is no longer a
                    // reason to stay silent about it.
                    if (quarantinedThreads.remove(id)) {
                        threadAttempts.remove(id)
                        threadRetryAt.remove(id)
                    }
                    if (id !in inFlightThreads) {
                        threadRetryAt.remove(id)
                        pendingThreads.add(id)
                    }
                }
            }
        }
    }

    /**
     * Claims the work that is due at [now]. Claimed units are held IN_FLIGHT and
     * are not handed out again until acked/nacked.
     */
    fun claim(now: Long): Claim? = synchronized(lock) {
        val claimFull = fullSync && !fullSyncInFlight && fullSyncRetryAt <= now
        val claimTail = tailDelta && !tailInFlight && tailRetryAt <= now
        val threadIds = pendingThreads
            .filter { it !in inFlightThreads && (threadRetryAt[it] ?: 0L) <= now }
            .take(threadChunkSize)

        if (!claimFull && !claimTail && threadIds.isEmpty()) return null

        if (claimFull) fullSyncInFlight = true
        if (claimTail) tailInFlight = true
        pendingThreads.removeAll(threadIds.toSet())
        inFlightThreads.addAll(threadIds)

        Claim(
            fullSync = claimFull,
            tailEpoch = if (claimTail) tailEpoch else null,
            threadIds = threadIds
        )
    }

    /**
     * ACK/NACK the full sync. A claimed tail is only consumed when the full sync
     * SUCCEEDED; on failure BOTH go back to retry.
     */
    fun ackFullSync(claim: Claim, success: Boolean, now: Long) {
        synchronized(lock) {
            if (!claim.fullSync) return
            fullSyncInFlight = false
            if (success) {
                fullSync = false
                fullSyncAttempts = 0
                fullSyncRetryAt = 0L
                claim.tailEpoch?.let { ackTailLocked(it, true, now) }
            } else {
                fullSyncAttempts++
                // Never abandoned: global reconciliation keeps retrying at a
                // capped interval and stays observable as degraded.
                fullSyncRetryAt = now + backoffMs(fullSyncAttempts)
                claim.tailEpoch?.let { ackTailLocked(it, false, now) }
            }
        }
    }

    /** ACK/NACK a claimed tail (when no full sync was in the same claim). */
    fun ackTail(claim: Claim, success: Boolean, now: Long) {
        synchronized(lock) { claim.tailEpoch?.let { ackTailLocked(it, success, now) } }
    }

    private fun ackTailLocked(epoch: Long, success: Boolean, now: Long) {
        tailInFlight = false
        if (success) {
            // Only clear the tail this claim covered: a tail added while we were
            // executing has a newer epoch and stays pending.
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
     * ACK/NACK one thread repair.
     *
     * A failing thread is requeued with backoff; after [maxThreadAttempts] it is
     * QUARANTINED (not deleted). Quarantine is observable, never blocks the
     * remaining ids of the same claim, and is re-armed by the next provider event
     * for that thread.
     */
    fun ackThread(threadId: Long, success: Boolean, now: Long) {
        if (threadId <= 0L) return
        synchronized(lock) {
            inFlightThreads.remove(threadId)
            if (success) {
                threadAttempts.remove(threadId)
                threadRetryAt.remove(threadId)
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
            inFlightThreads.isNotEmpty() || fullSyncInFlight || tailInFlight
    }

    /**
     * Milliseconds until the earliest scheduled retry, or 0 when there is no
     * scheduled retry at all. A caller must use this to ARM A TIMER, never to
     * sleep: parking the only consumer here would let a poison thread delay
     * unrelated realtime work by the whole backoff window.
     */
    fun nextWakeUpInMs(now: Long): Long = synchronized(lock) {
        var earliest = Long.MAX_VALUE
        if (fullSync && !fullSyncInFlight) earliest = minOf(earliest, fullSyncRetryAt)
        if (tailDelta && !tailInFlight) earliest = minOf(earliest, tailRetryAt)
        pendingThreads.forEach { earliest = minOf(earliest, threadRetryAt[it] ?: 0L) }
        if (earliest == Long.MAX_VALUE) return 0L
        (earliest - now).coerceAtLeast(1L)
    }

    // ── observability ──

    fun fullSyncState(now: Long): UnitState = synchronized(lock) {
        when {
            fullSyncInFlight -> UnitState.IN_FLIGHT
            !fullSync -> UnitState.ABSENT
            fullSyncRetryAt > now -> UnitState.BACKOFF
            else -> UnitState.PENDING
        }
    }

    fun tailState(now: Long): UnitState = synchronized(lock) {
        when {
            tailInFlight -> UnitState.IN_FLIGHT
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
