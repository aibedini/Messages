package com.autonomousone.messages.data

import android.content.Context
import android.util.Log
import com.autonomousone.messages.diagnostics.DiagnosticsBreadcrumbs
import com.autonomousone.messages.observer.ProviderChangeBatch
import com.autonomousone.messages.repository.SmsRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Routes an accumulated ContentObserver burst into the NARROWEST repair it can
 * justify:
 *
 *   exact row id         -> O(1) exact mutation (read that row, upsert/delete)
 *   known thread id      -> ForThread repair of that thread only
 *   nothing identifiable -> TailDelta (bounded newest-window repair)
 *
 * A full reconcile is NOT reachable from this router. It used to be: the
 * observer dispatched a trailing null, and every null became
 * ReconcileRequest.FullSync. What FullSync costs depends on shadow state: on an
 * un-bootstrapped shadow it reads the newest FIRST_BATCH rows per source; in
 * steady state syncSource() is already incremental (readNewerThan on the durable
 * newestDate/newestId). The REAL damage of the escalation was that a fresh row
 * makes projectionStale true and therefore triggered fullRebuildConversations()
 * - a global projection rebuild for one incoming SMS.
 *
 * [route] is invoked from the ContentObserver, which fires on the MAIN looper.
 * It must never block there, and it performs no provider read at all any more:
 * exact identities are ENQUEUED into the durable ProviderRepairQueue and the
 * repair itself runs on [scope] under a claim/ack/nack protocol.
 *
 * The queue is driven by its own timer. A provider burst is a useful EARLY
 * NUDGE, but it is not the only retry source - that was the core defect of the
 * old in-process map, where a read that failed during a quiet period was never
 * retried.
 */
object ChangeRouter {

    private const val TAG = "CHANGE_ROUTER"

    /** Safety poll: never depend on an external event to make progress. */
    private const val IDLE_POLL_MS = 30_000L

    /** Never sleep longer than this, even if the queue says the next work is far away. */
    private const val MAX_WAIT_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Conflated: a burst of nudges collapses into one wake-up. */
    private val nudge = Channel<Unit>(Channel.CONFLATED)

    private val schedulerStarted = AtomicBoolean(false)

    /**
     * The mutation reached the coordinator but its Room transaction did NOT
     * commit. The repair must be retained, never ACKed: ACKing here would drop
     * correctness work on the floor exactly when durability is what failed.
     */
    internal const val MUTATION_NOT_COMMITTED = "MUTATION_NOT_COMMITTED"

    /**
     * The pure routing decision, so the contract "an ordinary provider event can
     * never schedule a FullSync" is unit-testable without Android.
     *
     * There is deliberately no fullSync field: FullSync is reachable only from
     * bootstrap/recovery paths, never from a provider notification.
     */
    internal data class RepairPlan(
        val exactSmsIds: List<Long> = emptyList(),
        val exactMmsIds: List<Long> = emptyList(),
        val threadRepairs: List<Long> = emptyList(),
        val tailDelta: Boolean = false
    )

    internal fun planRepair(batch: ProviderChangeBatch, selfWriteThreadId: Long?): RepairPlan {
        // ALL thread ids are preserved. Bounding is the reconcile
        // accumulator's job (it unions ids and drains them in chunks); dropping
        // ids here silently lost real changes - 50 known threads became 8.
        val threads = batch.threadIds.toMutableList()
        if (selfWriteThreadId != null && threads.isEmpty()) threads += selfWriteThreadId

        // SAFETY INVARIANT: an unknown notification is NEVER dropped, and never
        // assumed to belong to something else in the same burst.
        //
        // The first version of this function returned as soon as it saw an exact
        // row, silently discarding unknownCount. A coalesced burst can legitimately
        // carry "exact SMS id" AND "generic provider notification" for DIFFERENT
        // rows, so ignoring the unknown would lose a real change.
        //
        // The same reasoning applies to the 2-second self-write token: a generic
        // event arriving inside the window may be an unrelated external change,
        // so it earns its own bounded delta in addition to the thread repair.
        // Correctness is worth one bounded watermark query.
        return RepairPlan(
            exactSmsIds = batch.smsIds.toList(),
            exactMmsIds = batch.mmsIds.toList(),
            threadRepairs = threads,
            tailDelta = batch.unknownCount > 0
        )
    }

    fun route(context: Context, batch: ProviderChangeBatch) {
        val app = context.applicationContext
        val coordinator = TelephonySyncCoordinator.get(app)

        ensureRepairScheduler(app)

        // A self-write token narrows an unidentifiable burst to the thread we
        // wrote. NON-CONSUMING: one mark-read legitimately causes several
        // provider callbacks and every one of them must see the token.
        val selfWrite = LocalProviderWrites.activeMarkRead()
            ?: LocalProviderWrites.activeOperation()

        val plan = planRepair(batch, selfWrite?.threadId)

        // Enqueue, then wake the scheduler. Nothing here touches the provider.
        //
        // HONEST DURABILITY BOUNDARY: this launch has NOT committed anything yet.
        // Once the Room insert of the queue row commits, the exact work survives
        // process death. If the process dies BEFORE that commit, this event is
        // genuinely lost - a ContentObserver fires on the main looper and cannot
        // be blocked on Room I/O, so that window is real and not zero-width.
        // Recovery for it is the periodic two-sided integrity audit (NOT
        // implemented yet), which re-derives the provider fact from the provider
        // side rather than from this lost event.
        scope.launch {
            val queue = ProviderRepairQueue(app)
            var enqueued = false
            // A mature exact ContentObserver identity: absence from the provider
            // is a considered observation, so this intent may prove a delete.
            for (id in plan.exactSmsIds) {
                queue.enqueue(MessageEntity.SOURCE_SMS, id, ProviderRepairIntent.RECONCILE_EXACT)
                enqueued = true
            }
            for (id in plan.exactMmsIds) {
                queue.enqueue(MessageEntity.SOURCE_MMS, id, ProviderRepairIntent.RECONCILE_EXACT)
                enqueued = true
            }
            if (enqueued) {
                Log.i(TAG, "enqueued exact repair: " + plan.exactSmsIds.size + " sms, " +
                    plan.exactMmsIds.size + " mms")
            }
            // Nudge even when this burst carried no exact id: it may have arrived
            // while earlier work was backing off, and a reachable provider is the
            // single best moment to retry.
            nudge.trySend(Unit)
        }

        plan.threadRepairs.forEach { threadId ->
            coordinator.reconcile(ReconcileRequest.ForThread(threadId))
        }
        if (plan.tailDelta) {
            Log.i(TAG, "unknown provider burst -> TailDelta")
            coordinator.reconcile(ReconcileRequest.TailDelta)
        }
    }

    /**
     * Starts the durable exact-repair scheduler exactly once.
     *
     * Safe to call from any thread and any number of times; the first caller wins.
     */
    fun ensureRepairScheduler(context: Context) {
        val app = context.applicationContext
        if (!schedulerStarted.compareAndSet(false, true)) return
        scope.launch { repairLoop(app) }
    }

    /**
     * Wakes the durable queue now.
     *
     * Kept as the public entry point used by [route] and by startup wiring. It no
     * longer reads providers itself: the queue decides what is due, claims it, and
     * retries it. A burst is evidence that the provider is reachable again, which
     * is exactly the right moment to retry early.
     */
    fun retryPendingExactReads(context: Context) {
        ensureRepairScheduler(context)
        nudge.trySend(Unit)
    }

    /**
     * Durably records ONE exact identity for repair.
     *
     * Public because a caller that already KNOWS the identity must be able to hand
     * it over instead of losing it: a delivery-status callback whose first read
     * failed, an outgoing send whose row is not yet readable, or a thread repair
     * whose provider source failed. Those callers must NOT have to wait for an
     * unrelated provider event to trigger a retry.
     */
    fun enqueueExactRepair(
        context: Context,
        source: String,
        providerId: Long,
        intent: ProviderRepairIntent
    ) {
        val app = context.applicationContext
        ensureRepairScheduler(app)
        scope.launch {
            ProviderRepairQueue(app).enqueue(source, providerId, intent)
            nudge.trySend(Unit)
        }
    }

    /**
     * The ONE exact-repair consumer.
     *
     * Loop shape: drain everything due -> compute the next wake time -> sleep
     * until then (or until nudged, or at most [IDLE_POLL_MS] as a safety poll).
     * The safety poll is what guarantees progress without any external event.
     */
    private suspend fun repairLoop(context: Context) {
        val queue = ProviderRepairQueue(context)
        // Process death recovery: a lease that outlived its owner is reclaimed
        // before the first drain, so a crash mid-read cannot strand work.
        val reclaimed = queue.reclaimExpiredLeases()
        if (reclaimed > 0) {
            Log.w(TAG, "recovered " + reclaimed + " expired exact-repair lease(s) at startup")
        }
        while (currentCoroutineContext().isActive) {
            drainDueRepairs(context, queue)
            // Watchdog breadcrumb: a queue that keeps growing is the difference
            // between "quiet" and "stuck", which is exactly what a stall report
            // needs to distinguish.
            DiagnosticsBreadcrumbs.setExactRepairQueueDepth(queue.depth())
            val wakeAt = queue.nextWakeAt()
            val now = System.currentTimeMillis()
            val waitMs = when {
                wakeAt == null -> IDLE_POLL_MS
                else -> (wakeAt - now).coerceIn(0L, MAX_WAIT_MS)
            }
            if (waitMs > 0L) withTimeoutOrNull(waitMs) { nudge.receive() }
        }
    }

    private suspend fun drainDueRepairs(context: Context, queue: ProviderRepairQueue) {
        val now = System.currentTimeMillis()
        queue.reclaimExpiredLeases(now)
        val due = queue.due(now)
        if (due.isEmpty()) return

        val repo = SmsRepository(context)
        val coordinator = TelephonySyncCoordinator.get(context)
        for (entry in due) {
            // Claim FIRST. due() is only an observation; two workers can see the
            // same row, but only one claim succeeds.
            if (!queue.claim(entry, now)) continue
            // A newer provider event may already have replaced this generation,
            // in which case that work owns the row and this read is obsolete.
            if (!queue.stillOwned(entry)) continue

            val outcome = when (entry.source) {
                MessageEntity.SOURCE_SMS -> applyExactSms(repo, coordinator, queue, entry)
                MessageEntity.SOURCE_MMS -> applyExactMms(repo, coordinator, queue, entry)
                else -> ExactRepairResult.Failed("UNSUPPORTED_SOURCE")
            }

            if (!queue.stillOwned(entry)) {
                // Superseded while reading. Do NOT ack (that would consume the
                // newer generation's work) and do NOT nack (also generation
                // scoped). The newer generation is due now and will converge.
                Log.i(TAG, "exact repair superseded by a newer generation: " + entry.source +
                    ":" + entry.providerId)
                continue
            }

            when (outcome) {
                is ExactRepairResult.Done -> queue.ack(entry)
                is ExactRepairResult.Failed -> {
                    Log.w(TAG, "exact repair failed " + entry.source + ":" + entry.providerId +
                        " reason=" + outcome.reason + " attempts=" + (entry.attempts + 1) +
                        " -> bounded backoff, work retained")
                    queue.nack(entry, outcome.reason)
                }
            }
        }
    }

    /** Claim outcome: [Done] may be acked, [Failed] must be nacked and retried. */
    internal sealed interface ExactRepairResult {
        data object Done : ExactRepairResult
        data class Failed(val reason: String) : ExactRepairResult
    }

    /**
     * A SUCCESSFUL provider absence. What it permits depends on the PERSISTED
     * intent plus the maturity policy - never on which caller happened to enqueue
     * the work.
     */
    private suspend fun resolveAbsence(
        entry: ProviderRepairEntity,
        coordinator: TelephonySyncCoordinator,
        queue: ProviderRepairQueue,
        localRowExists: Boolean,
        now: Long
    ): ExactRepairResult {
        val intent = ProviderRepairIntent.from(entry.intent)

        if (intent.absenceCanProveDelete) {
            // RECONCILE_EXACT (a mature exact observer identity) or
            // VERIFY_DELETE_CANDIDATE (already a candidate, and this is its second
            // independent successful absence). Both may delete - but only if there
            // is a local row to remove.
            if (!localRowExists) return ExactRepairResult.Done
            val committed = coordinator.mutateAndAwaitCommit(
                MessageMutation.Delete(entry.source, entry.providerId)
            )
            return if (committed) ExactRepairResult.Done
            else ExactRepairResult.Failed(MUTATION_NOT_COMMITTED)
        }

        // EXPECT_EXISTS / REFRESH_STATUS: the row is EXPECTED to exist, so an
        // absence is a visibility race until proven otherwise.
        //
        // The evidence is a durable count of SUCCESSFUL absences, recorded
        // atomically against the generation we still own. attempts is NOT evidence:
        // it also counts provider failures and backoff NACKs, so using it would let
        // three failures plus one absence mature into a delete candidate.
        val credited = queue.recordAbsence(entry, now)
        if (!credited) {
            // The generation moved (or the lease was lost) while we were reading.
            // Our absence proves nothing, and it must never become a delete.
            return ExactRepairResult.Failed("ABSENCE_NOT_CREDITED")
        }
        val absences = entry.absenceCount + 1
        val matured = (now - entry.intentSince) >= ProviderRepairQueue.VISIBILITY_GRACE_MS &&
            absences >= ProviderRepairQueue.ABSENCE_MIN_SUCCESSES
        if (!matured) {
            Log.i(TAG, "absence not yet mature " + entry.source + ":" + entry.providerId +
                " intent=" + intent + " absences=" + absences + "/" +
                ProviderRepairQueue.ABSENCE_MIN_SUCCESSES)
            return ExactRepairResult.Failed("ABSENCE_NOT_MATURED")
        }

        if (!localRowExists) {
            // Nothing local to remove, so the work is genuinely resolved. This is
            // the only way an EXPECT_EXISTS row leaves the queue on absence.
            return ExactRepairResult.Done
        }

        // Matured absence over a real local row: this intent is NOT allowed to
        // delete. Convert to a fresh VERIFY_DELETE_CANDIDATE generation, which must
        // prove absence independently before anything is removed. This generation
        // is finished, so Done is correct: the ACK is scoped to the OLD generation
        // and therefore cannot touch the new one.
        queue.rearm(entry, ProviderRepairIntent.VERIFY_DELETE_CANDIDATE, now)
        Log.w(TAG, "matured absence for " + entry.source + ":" + entry.providerId +
            " intent=" + intent + " -> VERIFY_DELETE_CANDIDATE generation")
        return ExactRepairResult.Done
    }

    /**
     * Exact SMS decision.
     *
     * A DELETE is only ever issued from a SUCCESSFUL read that proves absence; a
     * failed read keeps the identity for retry.
     */
    private suspend fun applyExactSms(
        repo: SmsRepository,
        coordinator: TelephonySyncCoordinator,
        queue: ProviderRepairQueue,
        entry: ProviderRepairEntity
    ): ExactRepairResult = when (val read = repo.readSmsExactStrict(entry.providerId)) {
        is ProviderRead.Success -> {
            val row = read.value
            if (row != null) {
                // ONE durable commit: the queue row may only be ACKed after Room
                // (and the outbox that commits with it) accepted this mutation.
                if (coordinator.mutateAndAwaitCommit(
                        MessageMutation.Upsert(MessageEntity.SOURCE_SMS, row)
                    )
                ) ExactRepairResult.Done else ExactRepairResult.Failed(MUTATION_NOT_COMMITTED)
            } else {
                // A successful ABSENCE. Deleting is NOT automatic: the persisted
                // intent decides, and an expected-to-exist row must mature first.
                resolveAbsence(
                    entry = entry,
                    coordinator = coordinator,
                    queue = queue,
                    localRowExists = coordinator.localRowExists(entry.source, entry.providerId),
                    now = System.currentTimeMillis()
                )
            }
        }
        is ProviderRead.Failure -> ExactRepairResult.Failed(read.reason.name)
    }

    /**
     * Exact MMS decision, in TWO questions instead of one.
     *
     * "Does this row exist?" and "what does it contain?" are different questions
     * with different failure modes:
     *
     *   existence  -> one query against content://mms; a failure is UNKNOWN
     *   content    -> the same row PLUS content://mms/addr and content://mms/part
     *
     * The old code asked only the second question through a reader that degraded
     * a failed Addr/Part lookup into the literals "Unknown" and "[MMS]", then
     * wrote that over the good Room row. Now:
     *
     *   existence Failure              -> keep Room, retry
     *   existence Absent               -> PROVEN delete, and no secondary read at all
     *   existence Exists + read Failure-> keep Room, retry (no placeholder write)
     *   existence Exists + row gone    -> PROVEN delete (the provider answered twice)
     *   existence Exists + row present -> authoritative upsert
     */
    private suspend fun applyExactMms(
        repo: SmsRepository,
        coordinator: TelephonySyncCoordinator,
        queue: ProviderRepairQueue,
        entry: ProviderRepairEntity
    ): ExactRepairResult = when (val existence = repo.readMmsExistenceStrict(entry.providerId)) {
        is ProviderRead.Failure -> ExactRepairResult.Failed(existence.reason.name)
        is ProviderRead.Success -> when (existence.value) {
            ProviderExistence.Absent -> resolveAbsence(
                entry = entry,
                coordinator = coordinator,
                queue = queue,
                localRowExists = coordinator.localRowExists(entry.source, entry.providerId),
                now = System.currentTimeMillis()
            )
            ProviderExistence.Exists -> when (
                val materialized = repo.readMmsMaterializedStrict(entry.providerId)
            ) {
                is ProviderRead.Failure -> ExactRepairResult.Failed(materialized.reason.name)
                is ProviderRead.Success -> {
                    val row = materialized.value
                    if (row != null) {
                        if (coordinator.mutateAndAwaitCommit(
                                MessageMutation.Upsert(MessageEntity.SOURCE_MMS, row)
                            )
                        ) ExactRepairResult.Done else ExactRepairResult.Failed(MUTATION_NOT_COMMITTED)
                    } else {
                        // Existence said Exists, materialization said gone: the
                        // provider answered twice, so this is a successful absence -
                        // still subject to the intent policy.
                        resolveAbsence(
                            entry = entry,
                            coordinator = coordinator,
                            queue = queue,
                            localRowExists = coordinator.localRowExists(entry.source, entry.providerId),
                            now = System.currentTimeMillis()
                        )
                    }
                }
            }
        }
    }

    /** Kept for callers/tests that only have a path. */
    internal fun extractRowIdFromPath(path: String?): Long? =
        ProviderChangeBatch.extractRowIdFromPath(path)
}
