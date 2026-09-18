package com.autonomousone.messages.data

import android.content.Context
import android.provider.Telephony
import android.util.Log
import com.autonomousone.messages.observer.ProviderChangeBatch
import com.autonomousone.messages.repository.SmsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
 * — a global projection rebuild for one incoming SMS.
 *
 * [route] is invoked from the ContentObserver, which fires on the MAIN looper.
 * It must never block there — every provider read is offloaded to [scope].
 */
object ChangeRouter {

    private const val TAG = "CHANGE_ROUTER"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        // ids here silently lost real changes — 50 known threads became 8.
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
        val coordinator = TelephonySyncCoordinator.get(context)

        // A provider burst proves the provider is reachable again: this is the
        // right moment to retry exact reads that previously failed.
        retryPendingExactReads(context)

        // A self-write token narrows an unidentifiable burst to the thread we
        // wrote. NON-CONSUMING: one mark-read legitimately causes several
        // provider callbacks and every one of them must see the token.
        val selfWrite = LocalProviderWrites.activeMarkRead()
            ?: LocalProviderWrites.activeOperation()

        val plan = planRepair(batch, selfWrite?.threadId)

        if (plan.exactSmsIds.isNotEmpty() || plan.exactMmsIds.isNotEmpty()) {
            readExactRows(context, coordinator, plan.exactSmsIds, plan.exactMmsIds)
        }
        plan.threadRepairs.forEach { threadId ->
            coordinator.reconcile(ReconcileRequest.ForThread(threadId))
        }
        if (plan.tailDelta) {
            Log.i(TAG, "unknown provider burst -> TailDelta")
            coordinator.reconcile(ReconcileRequest.TailDelta)
        }
    }

    /** Reads the exact rows the burst identified, off the main thread. */
    private fun readExactRows(
        context: Context,
        coordinator: TelephonySyncCoordinator,
        smsIds: List<Long>,
        mmsIds: List<Long>
    ) {
        scope.launch {
            val repo = SmsRepository(context)
            for (id in smsIds) applyExactSms(repo, coordinator, id)
            for (id in mmsIds) applyExactMms(repo, coordinator, id)
        }
    }

    /**
     * Re-attempts exact reads that previously FAILED.
     *
     * Called from [route] on every provider burst: a burst is exactly the moment
     * the provider is demonstrably reachable again. Cheap no-op when nothing is
     * pending. Never fabricates absence.
     */
    fun retryPendingExactReads(context: Context) {
        val due = PendingExactRepairs.due(System.currentTimeMillis())
        if (due.isEmpty()) return
        scope.launch {
            val repo = SmsRepository(context)
            val coordinator = TelephonySyncCoordinator.get(context)
            due.forEach { entry ->
                when (entry.source) {
                    PendingExactRepairs.Source.SMS -> applyExactSms(repo, coordinator, entry.providerId)
                    PendingExactRepairs.Source.MMS -> applyExactMms(repo, coordinator, entry.providerId)
                }
            }
        }
    }

    /**
     * Exact SMS decision. A DELETE is only ever issued from a SUCCESSFUL read
     * that proves absence; a failed read keeps the identity for retry.
     */
    private fun applyExactSms(
        repo: SmsRepository,
        coordinator: TelephonySyncCoordinator,
        id: Long
    ) {
        when (val read = repo.readSmsExactStrict(id)) {
            is ProviderRead.Success -> {
                PendingExactRepairs.clear(PendingExactRepairs.Source.SMS, id)
                val row = read.value
                if (row != null) {
                    coordinator.mutate(MessageMutation.Upsert(MessageEntity.SOURCE_SMS, row))
                } else {
                    // PROVEN absence: the provider answered and has no such row.
                    coordinator.mutate(MessageMutation.Delete(MessageEntity.SOURCE_SMS, id))
                }
            }
            is ProviderRead.Failure -> {
                Log.w(
                    TAG,
                    "exact SMS read failed id=" + id + " reason=" + read.reason +
                        " -> keeping Room row, retry scheduled"
                )
                PendingExactRepairs.note(PendingExactRepairs.Source.SMS, id, System.currentTimeMillis())
            }
        }
    }

    /** Exact MMS decision. Same contract as [applyExactSms]. */
    private fun applyExactMms(
        repo: SmsRepository,
        coordinator: TelephonySyncCoordinator,
        id: Long
    ) {
        when (val read = repo.readMmsExactStrict(id)) {
            is ProviderRead.Success -> {
                PendingExactRepairs.clear(PendingExactRepairs.Source.MMS, id)
                val row = read.value
                if (row != null) {
                    coordinator.mutate(MessageMutation.Upsert(MessageEntity.SOURCE_MMS, row))
                } else {
                    coordinator.mutate(MessageMutation.Delete(MessageEntity.SOURCE_MMS, id))
                }
            }
            is ProviderRead.Failure -> {
                Log.w(
                    TAG,
                    "exact MMS read failed id=" + id + " reason=" + read.reason +
                        " -> keeping Room row, retry scheduled"
                )
                PendingExactRepairs.note(PendingExactRepairs.Source.MMS, id, System.currentTimeMillis())
            }
        }
    }

    /** Kept for callers/tests that only have a path. */
    internal fun extractRowIdFromPath(path: String?): Long? =
        ProviderChangeBatch.extractRowIdFromPath(path)
}
