package com.autonomousone.messages.data

/**
 * CLAIM / ACK / NACK accumulator for reconcile work.
 *
 * Two separate correctness properties live here.
 *
 * 1) Requests are NOT interchangeable, so they are merged by SEMANTIC UNION —
 *    never by last-value-wins. A conflated channel of typed requests silently
 *    dropped work:
 *
 *      ForThread(12), ForThread(99), TailDelta -> TailDelta
 *      startup FullSync, provider TailDelta    -> TailDelta
 *
 * 2) Receiving work is NOT the same as completing it. The previous
 *    drainSnapshot() REMOVED work before it ran, so
 *
 *      threads = [1..8] -> claim -> thread 2 throws -> the rest are gone
 *      FullSync + TailDelta -> claim -> FullSync throws -> both are gone
 *
 *    That is a silent data-loss bug. Work now moves PENDING -> IN_FLIGHT and is
 *    only cleared by an explicit ACK:
 *
 *      claim(now) -> Claim
 *          fullSync   : ackFullSync(claim, success, now)
 *          tail       : ackTail(claim, success, now)
 *          thread(id) : ackThread(id, success, now)
 *
 *    Anything not ACKed (exception, process death before the ack) is still known
 *    to the accumulator and is retried after a bounded backoff.
 *
 * Pure by design (no coroutines, no Android) so all of this is unit-testable.
 */
class PendingReconciles(
    /** Thread repairs returned by one claim. Bounds a claim, never drops. */
    private val threadChunkSize: Int = 8,
    /** Attempts before a failing unit is abandoned (observable, not retried). */
    private val maxAttempts: Int = 5,
    /** Bounded exponential backoff: 1s, 2s, 4s, 8s, 16s. */
    private val backoffMs: (attempt: Int) -> Long = { a -> 1_000L shl (a - 1).coerceIn(0, 5) }
) {

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

    // ── pending (not yet claimed) ──
    private var fullSync = false
    private var fullSyncRetryAt = 0L
    private var fullSyncAttempts = 0

    private var tailDelta = false
    private var tailEpoch = 0L
    private var tailRetryAt = 0L
    private var tailAttempts = 0

    private val pendingThreads = LinkedHashSet<Long>()

    // ── in flight (claimed, awaiting ack) ──
    private var fullSyncInFlight = false
    private var tailInFlight = false
    private val inFlightThreads = LinkedHashSet<Long>()

    private val threadAttempts = HashMap<Long, Int>()
    private val threadRetryAt = HashMap<Long, Long>()

    /** Units abandoned after [maxAttempts]; observable so it is never silent. */
    private val abandoned = LinkedHashSet<String>()
    private val abandonedThreads = LinkedHashSet<Long>()

    fun add(request: ReconcileRequest) {
        synchronized(lock) {
            when (request) {
                is ReconcileRequest.FullSync -> fullSync = true
                is ReconcileRequest.TailDelta -> {
                    // A NEW tail gets a new epoch: a tail that arrives while a
                    // full sync is executing must not be treated as covered by it.
                    tailDelta = true
                    tailEpoch++
                }
                is ReconcileRequest.ForThread -> {
                    val id = request.threadId
                    if (id > 0L && id !in abandonedThreads && id !in inFlightThreads) {
                        pendingThreads.add(id)
                    }
                }
            }
        }
    }

    /**
     * Claims the work that is due at [now].
     *
     * Claimed units are held IN_FLIGHT and will not be claimed again until they
     * are acked/nacked, so a second claim cannot duplicate work in progress and a
     * failure cannot drop it.
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
     * ACK/NACK the full sync.
     *
     * A claimed tail is only acknowledged when the full sync SUCCEEDED and no
     * newer tail arrived meanwhile. On failure both the full sync and the tail
     * return to pending — nothing is consumed before success is proven.
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
                nackFullSyncLocked(now)
                // The tail was NOT covered: requeue it too.
                claim.tailEpoch?.let { ackTailLocked(it, false, now) }
            }
        }
    }

    private fun nackFullSyncLocked(now: Long) {
        val attempt = fullSyncAttempts + 1
        if (attempt >= maxAttempts) {
            abandoned += "fullsync"
            fullSync = false
            fullSyncAttempts = 0
            fullSyncRetryAt = 0L
        } else {
            fullSyncAttempts = attempt
            fullSyncRetryAt = now + backoffMs(attempt)
        }
    }

    /** ACK/NACK a claimed tail (only needed when no full sync was in the claim). */
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
        val attempt = tailAttempts + 1
        if (attempt >= maxAttempts) {
            abandoned += "taildelta"
            tailDelta = false
            tailAttempts = 0
            tailRetryAt = 0L
        } else {
            tailAttempts = attempt
            tailRetryAt = now + backoffMs(attempt)
        }
    }

    /**
     * ACK/NACK one thread repair.
     *
     * A failing thread is REQUEUED with backoff; after [maxAttempts] it is
     * abandoned so one poison thread can never starve the others. The remaining
     * ids of the same claim are unaffected — the consumer acks each id
     * individually.
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
            if (attempt >= maxAttempts) {
                abandonedThreads.add(threadId)
                threadAttempts.remove(threadId)
                threadRetryAt.remove(threadId)
                return
            }
            threadAttempts[threadId] = attempt
            threadRetryAt[threadId] = now + backoffMs(attempt)
            pendingThreads.add(threadId) // REQUEUE — never silently lost
        }
    }

    /** True when anything is pending or in flight (including backoff). */
    fun hasWork(): Boolean = synchronized(lock) {
        fullSync || tailDelta || pendingThreads.isNotEmpty() || inFlightThreads.isNotEmpty() ||
            fullSyncInFlight || tailInFlight
    }

    /**
     * Milliseconds until the earliest scheduled retry, or 0 when there is no
     * pending work at all (the consumer then waits for the next nudge). Prevents
     * both a busy retry loop and a stranded backoff.
     */
    fun nextWakeUpInMs(now: Long): Long = synchronized(lock) {
        var earliest = Long.MAX_VALUE
        if (fullSync) earliest = minOf(earliest, fullSyncRetryAt)
        if (tailDelta) earliest = minOf(earliest, tailRetryAt)
        pendingThreads.forEach { earliest = minOf(earliest, threadRetryAt[it] ?: 0L) }
        if (earliest == Long.MAX_VALUE) return 0L
        (earliest - now).coerceAtLeast(1L)
    }

    // ── observability ──

    internal fun pendingThreadCount(): Int = synchronized(lock) { pendingThreads.size }

    internal fun inFlightThreadCount(): Int = synchronized(lock) { inFlightThreads.size }

    internal fun abandonedThreadIds(): Set<Long> = synchronized(lock) { abandonedThreads.toSet() }

    internal fun abandonedKinds(): Set<String> = synchronized(lock) { abandoned.toSet() }
}
