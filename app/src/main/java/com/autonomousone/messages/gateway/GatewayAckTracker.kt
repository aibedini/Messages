package com.autonomousone.messages.gateway

import com.autonomousone.messages.eve.EveSmsQueue

/**
 * Locally-driven ACK ledger for tasks pulled from GMweb.
 *
 * A task whose final pre-send validation could not be obtained is parked
 * DEFERRED and retried by EveSmsQueue's own backoff/sweep — NOT by a server
 * redelivery. When that local retry finally produces a terminal outcome
 * (SENT, SUPERSEDED, FAILED or CANCELLED) the ACK must still reach GMweb, and
 * it must be emitted exactly once.
 *
 * Tracking is keyed on the GMweb gatewayRequestId, which also lives on the
 * durable queue record, so the ledger can be re-seeded after a process death or
 * reboot from [EveSmsQueue.outstandingGatewayRecords].
 *
 * Deliberately free of Android and networking: the caller supplies both the
 * local status lookup and the ACK transport, which keeps the whole flow
 * unit-testable.
 */
internal class GatewayAckTracker(
    private val statusOf: (localRequestId: String) -> EveSmsQueue.Record?,
    /**
     * Sends the report and says whether GMweb ACCEPTED it.
     *
     * It used to return nothing, and the tracker marked the task acknowledged before calling it — so a
     * transport failure was indistinguishable from a delivered report, and the outcome was never sent
     * again. The bridge's whole purpose is to tell GMweb what happened to a task; losing that report to
     * one dropped connection and calling it "the server will time out" makes the server show a
     * delivered message as unresolved for ever.
     */
    private val sendAck: (record: EveSmsQueue.Record, outcome: String, reason: String?) -> Boolean
) {

    companion object {
        private const val MAX_ACKED_IDS = 500

        /**
         * The detailed cause that accompanies a terminal outcome in the ACK
         * "reason" field. Returns null for a successful send.
         */
        fun reasonFor(rec: EveSmsQueue.Record): String? = when (rec.status) {
            EveSmsQueue.Status.SENT -> null
            EveSmsQueue.Status.SUPERSEDED ->
                rec.supersededReason?.ifBlank { null } ?: "superseded"
            EveSmsQueue.Status.FAILED ->
                rec.failedReason?.ifBlank { null } ?: "provider_error"
            EveSmsQueue.Status.CANCELLED -> EveSmsQueue.REASON_CANCELLED_LOCALLY
            else -> null
        }
    }

    // gatewayRequestId -> localRequestId
    private val awaiting = LinkedHashMap<String, String>()
    // Bounded "already acknowledged" set so a redelivered requestId can never
    // produce a second ACK inside one process lifetime.
    private val acked = LinkedHashSet<String>()

    val pendingCount: Int get() = awaiting.size

    fun isTracked(gatewayRequestId: String): Boolean = awaiting.containsKey(gatewayRequestId)

    fun wasAcked(gatewayRequestId: String): Boolean = acked.contains(gatewayRequestId)

    /** Starts tracking a pulled task. No-op when blank or already acknowledged. */
    fun track(gatewayRequestId: String, localRequestId: String) {
        if (gatewayRequestId.isBlank()) return
        if (acked.contains(gatewayRequestId)) return
        if (awaiting.containsKey(gatewayRequestId)) return
        awaiting[gatewayRequestId] = localRequestId
    }

    /**
     * Caller acknowledged this request through another path (e.g. a send that
     * never reached a terminal state inside the wait window). Stop tracking it
     * and remember that it was acked, so it is never acknowledged twice.
     */
    fun forget(gatewayRequestId: String) {
        awaiting.remove(gatewayRequestId)
        markAcked(gatewayRequestId)
    }

    /**
     * Emits the ACK for every tracked task that has reached a terminal outcome, exactly once each.
     *
     * **"Once" means once ACCEPTED.** A report GMweb refused, or one lost in transit, stays tracked and
     * is retried on the next cycle. That is safe precisely because this is a REPORT and not a send: a
     * duplicate ACK for the same `requestId` is idempotent at the server and cannot cause a second SMS.
     * The previous behaviour — marking the task acknowledged before the transmission was attempted —
     * made a failed report permanent, which is the one outcome a status bridge must not have.
     *
     * Non-terminal tasks (QUEUED, ACTIVE, DEFERRED) are left untouched and are never acknowledged early.
     *
     * @return how many ACKs GMweb accepted.
     */
    fun drain(): Int {
        if (awaiting.isEmpty()) return 0
        var emitted = 0
        for ((gatewayRequestId, localRequestId) in awaiting.entries.toList()) {
            val rec = statusOf(localRequestId)
            if (rec == null) {
                // The queue evicted the record (bounded persistence); there is
                // nothing left to acknowledge.
                awaiting.remove(gatewayRequestId)
                markAcked(gatewayRequestId)
                continue
            }
            if (!rec.terminal) continue
            if (!sendAck(rec, rec.outcome, reasonFor(rec))) continue
            awaiting.remove(gatewayRequestId)
            markAcked(gatewayRequestId)
            emitted++
        }
        return emitted
    }

    private fun markAcked(gatewayRequestId: String) {
        acked.add(gatewayRequestId)
        while (acked.size > MAX_ACKED_IDS) {
            val oldest = acked.firstOrNull() ?: break
            acked.remove(oldest)
        }
    }
}
