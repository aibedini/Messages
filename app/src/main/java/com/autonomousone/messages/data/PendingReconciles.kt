package com.autonomousone.messages.data

/**
 * Merging accumulator for reconcile work.
 *
 * Reconcile requests are NOT interchangeable, so they must never travel through
 * a last-value-wins channel. `Channel<ReconcileRequest>(CONFLATED)` meant
 *
 *   ForThread(12), ForThread(99), TailDelta   ->  TailDelta            (12 and 99 LOST)
 *   startup FullSync, provider TailDelta      ->  TailDelta            (FullSync LOST)
 *
 * This type coalesces by SEMANTIC UNION instead:
 *
 *   FullSync     sticky until executed
 *   TailDelta    sticky until executed
 *   ForThread    ids UNION, dedupe, never replace each other
 *
 * It is deliberately pure (no coroutines, no Android) so the merge semantics and
 * the race behaviour are unit-testable.
 *
 * The caller pairs it with a CONFLATED Unit nudge channel: the channel is only a
 * wakeup, the accumulator is the work.
 */
class PendingReconciles(
    /** Thread repairs returned by one drain pass. Bounds a pass, never drops. */
    val threadChunkSize: Int = 8
) {

    /** One atomic snapshot of pending work. */
    data class Work(
        val fullSync: Boolean,
        val tailDelta: Boolean,
        val threadIds: List<Long>
    ) {
        val isEmpty: Boolean get() = !fullSync && !tailDelta && threadIds.isEmpty()
    }

    private val lock = Any()
    private var fullSync = false
    private var tailDelta = false
    private val threads = LinkedHashSet<Long>()

    fun add(request: ReconcileRequest) {
        synchronized(lock) {
            when (request) {
                is ReconcileRequest.FullSync -> fullSync = true
                is ReconcileRequest.TailDelta -> tailDelta = true
                is ReconcileRequest.ForThread ->
                    if (request.threadId > 0L) threads.add(request.threadId)
            }
        }
    }

    fun hasWork(): Boolean = synchronized(lock) { fullSync || tailDelta || threads.isNotEmpty() }

    internal fun pendingThreadCount(): Int = synchronized(lock) { threads.size }

    /**
     * Atomically takes the next chunk of work.
     *
     * FullSync dominates TailDelta: applyReconcile(FullSync) runs the same
     * per-source newest-window sync, so a pending tail is genuinely covered.
     *
     * FullSync does NOT swallow ForThread repairs: FullSync only rebuilds the
     * conversation projection when it actually mirrored fresh rows, whereas a
     * thread repair can be needed for a read-state change that touched no newest
     * row. Those ids are returned in the SAME snapshot (chunked) and are only
     * removed once they have actually been handed to the caller.
     */
    fun drainSnapshot(): Work? = synchronized(lock) {
        if (!fullSync && !tailDelta && threads.isEmpty()) return null

        val chunk = if (threads.isEmpty()) emptyList() else threads.take(threadChunkSize).toList()
        val work = Work(
            fullSync = fullSync,
            tailDelta = tailDelta && !fullSync,
            threadIds = chunk
        )

        // FullSync genuinely covers the tail semantics (same per-source
        // newest-window sync), so taking a FullSync CONSUMES a pending tail.
        // Thread repairs are NOT subsumed: they are removed only when returned.
        if (fullSync) {
            fullSync = false
            tailDelta = false
        } else if (tailDelta) {
            tailDelta = false
        }
        threads.removeAll(chunk.toSet())
        work
    }
}
