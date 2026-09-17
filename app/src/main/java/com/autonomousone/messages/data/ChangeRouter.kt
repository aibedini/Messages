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

    /** Upper bound on thread-scoped repairs one burst may schedule. */
    private const val MAX_THREAD_REPAIRS = 8

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
        val threads = batch.threadIds.take(MAX_THREAD_REPAIRS).toMutableList()
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
            for (id in smsIds) {
                val fresh = repo.querySmsRaw(
                    selection = Telephony.Sms._ID + " = ?",
                    selectionArgs = arrayOf(id.toString()),
                    sortOrder = Telephony.Sms.DATE + " DESC",
                    limit = 1
                ).firstOrNull()
                if (fresh != null) {
                    coordinator.mutate(MessageMutation.Upsert(MessageEntity.SOURCE_SMS, fresh))
                } else {
                    // Row was deleted externally.
                    coordinator.mutate(MessageMutation.Delete(MessageEntity.SOURCE_SMS, id))
                }
            }
            for (id in mmsIds) {
                val fresh = repo.queryMmsRaw(
                    selection = Telephony.Mms._ID + " = ?",
                    selectionArgs = arrayOf(id.toString()),
                    sortOrder = Telephony.Mms.DATE + " DESC",
                    limit = 1
                ).firstOrNull()
                if (fresh != null) {
                    coordinator.mutate(MessageMutation.Upsert(MessageEntity.SOURCE_MMS, fresh))
                } else {
                    coordinator.mutate(MessageMutation.Delete(MessageEntity.SOURCE_MMS, id))
                }
            }
        }
    }

    /** Kept for callers/tests that only have a path. */
    internal fun extractRowIdFromPath(path: String?): Long? =
        ProviderChangeBatch.extractRowIdFromPath(path)
}
