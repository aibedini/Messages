package com.autonomousone.messages.sync

import com.autonomousone.messages.data.MirrorReconcileRow

/**
 * Decides which mirror rows have no durable event (mission §34/§35).
 *
 * WHY THIS EXISTS AT ALL: realtime callbacks are not sufficient for correctness. A message that
 * arrived while the process was dead, or whose notification was missed, leaves NO trace — no outbox
 * row, no log line, and a green gateway
 * (`docs/gateway-replication-audit.md`, Blockers 1 and 12). Reconciliation is the only thing that
 * compares "what this phone knows" against "what we have queued" and closes the gap.
 *
 * Pure: it takes the rows, a function that computes each row's canonical event id, and a predicate
 * that says whether that id is already durable. So every decision is testable without Room, and the
 * identity rule stays in one place — [com.autonomousone.messages.data.GatewayEventFactory].
 */
object MirrorReconcilePolicy {

    /**
     * The rows that need enqueuing.
     *
     * [existingIds] is passed in rather than a lookup predicate because the outbox query is
     * `suspend`, and keeping this function pure is what lets every decision be asserted without
     * Room or a coroutine. The lookup cannot be an SQL anti-join either: the canonical id is
     * COMPUTED (a UUID over source/providerId/date), not a column, so the database cannot join on
     * it. A bounded window is what makes per-row lookups affordable; an unbounded one would not.
     *
     * Rows with a non-positive provider id are skipped: `eventUuidFor` is only meaningful for a
     * real provider row, and enqueuing a synthetic one would create an event the server could
     * never match to a message.
     */
    fun missing(
        rows: List<MirrorReconcileRow>,
        existingIds: Set<String>
    ): List<MirrorReconcileRow> = rows.filter { row ->
        row.providerId > 0L && canonicalId(row) !in existingIds
    }

    /** The rows worth looking up at all — the eligible subset, before any outbox query. */
    fun candidates(rows: List<MirrorReconcileRow>): List<MirrorReconcileRow> =
        rows.filter { it.providerId > 0L }

    /**
     * The canonical event id for a mirror row (mission §33).
     *
     * One identity for one message, whichever path discovers it — which is what makes "is there
     * already a row for this?" a meaningful question.
     */
    fun canonicalId(row: MirrorReconcileRow): String =
        com.autonomousone.messages.data.GatewayEventFactory.eventUuidFor(
            com.autonomousone.messages.data.GatewayEventFactory.Types.MESSAGE_CREATED,
            row.source,
            row.providerId,
            row.date
        )
}

/**
 * The outcome of one reconciliation run, for diagnostics.
 *
 * Reported rather than silent: "reconciliation found nothing" and "reconciliation recovered 12
 * events" are very different facts about a device, and only one of them means the safety net
 * worked.
 */
data class ReconcileResult(
    val examined: Int,
    val recovered: Int,
    val skippedNoDirection: Int = 0,
    val windowSize: Int = examined
) {
    val foundGap: Boolean get() = recovered > 0
}
