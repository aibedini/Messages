package com.autonomousone.messages.trash

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.autonomousone.messages.repository.TrashRepository
import com.autonomousone.messages.utils.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * The ONE scheduler for TRASH permanent deletion (v3.4.0 FEATURE 8).
 *
 * Same contract as the repo's other single-unique-work schedulers
 * (`OtpCleanupScheduler`, `GatewayScheduler`, `ScheduledSms`); it is NOT a second
 * scheduling engine, just a second queue on the existing WorkManager.
 *
 *  - Exactly ONE one-time work named [WORK_NAME] exists at any moment, pointing
 *    at the NEXT due `purgeAt`. Trashing 500 conversations still leaves exactly
 *    one pending job.
 *  - There is NO periodic full-table scan: the due query
 *    (`TrashedThreadDao.dueForPurge`) is index-backed on `purgeAt` and
 *    LIMIT-bounded.
 *  - Durable by construction: the deadline lives in the `trashed_threads` ROW
 *    (not in memory) and the request lives in WorkManager's own store, so both
 *    process death and reboot are safe. The worker re-reads the due set from Room
 *    when it runs, so a job that fires late purges exactly what is due.
 *  - Nothing to purge → the pending job is CANCELLED rather than left to run and
 *    do nothing.
 *
 * [ExistingWorkPolicy.REPLACE] follows the repo's convention: the newest request
 * always holds the single true "next due" time. Every state change is committed
 * in Room BEFORE the reschedule, so the only work a replaced request could lose
 * is its own Result, which is cancellation-safe.
 */
object TrashPurgeScheduler {

    /** The one unique work name. Never parameterised — there is only one queue. */
    const val WORK_NAME = "trash_purge"

    /**
     * Upper bound on one worker run — the SAME batch the repository uses, so a
     * long Trash backlog never becomes one unbounded piece of provider work.
     */
    const val BATCH_LIMIT: Int = TrashRepository.PURGE_BATCH_LIMIT

    /**
     * Wait before retrying a purge whose provider write FAILED.
     *
     * A failed purge keeps its tombstone with a `purgeAt` in the past, so
     * scheduling from the raw deadline would retry it immediately and hammer the
     * provider. A bounded backoff keeps the retry (the state is never dropped)
     * without a spin loop. A provider that is permanently unavailable (the app is
     * not the default SMS app) simply retries at this cadence until it is fixed.
     */
    const val RETRY_BACKOFF_MS: Long = 15L * 60 * 1000

    /**
     * The PURE scheduling decision: when the next purge run must happen, or null
     * when there is nothing to schedule.
     *
     *  - a failed purge → now + [RETRY_BACKOFF_MS] (never "now", see above);
     *  - a deadline already in the past → now (run as soon as WorkManager can);
     *  - a future deadline → that deadline;
     *  - nothing in Trash and nothing failed → null (cancel the pending job).
     */
    fun nextRunAt(earliestPurgeAt: Long?, failed: Int, now: Long): Long? {
        if (failed > 0) return now + RETRY_BACKOFF_MS
        val earliest = earliestPurgeAt ?: return null
        return if (earliest <= now) now else earliest
    }

    /**
     * Points the single unique [WORK_NAME] at the currently persisted earliest
     * deadline, or cancels it when Trash is empty. Cheap: one indexed MIN read.
     *
     * Called when a conversation is trashed (a new deadline exists), when Trash is
     * emptied or a row is restored (the deadline may be gone), and as a
     * process-start safety net so a purge can never depend on the user reopening
     * the Recently Deleted screen.
     */
    suspend fun scheduleNext(context: Context, now: Long = System.currentTimeMillis()) {
        val appContext = context.applicationContext
        val earliest = try {
            TrashRepository.get(appContext).earliestPurgeAt()
        } catch (error: Throwable) {
            DiagnosticLog.event("TRASH", "schedule-read-failed", error)
            return
        }
        val nextAt = nextRunAt(earliest, failed = 0, now = now)
        if (nextAt == null) {
            cancelPending(appContext)
        } else {
            enqueue(appContext, nextAt)
        }
    }

    /**
     * Re-arms after a worker run from the outcome that run produced: a failure
     * delays the next attempt instead of retrying in a tight loop.
     */
    suspend fun rescheduleAfterRun(
        context: Context,
        failed: Int,
        now: Long = System.currentTimeMillis()
    ) {
        val appContext = context.applicationContext
        val earliest = try {
            TrashRepository.get(appContext).earliestPurgeAt()
        } catch (error: Throwable) {
            DiagnosticLog.event("TRASH", "reschedule-read-failed", error)
            return
        }
        val nextAt = nextRunAt(earliest, failed, now)
        if (nextAt == null) cancelPending(appContext) else enqueue(appContext, nextAt)
    }

    /** Cancels the pending purge. Called when Trash becomes empty. */
    fun cancelPending(context: Context) {
        runCatching { WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME) }
            .onFailure { DiagnosticLog.event("TRASH", "cancel-failed", it) }
    }

    private fun enqueue(context: Context, runAt: Long) {
        val delay = (runAt - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<TrashPurgeWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .addTag(WORK_NAME)
            .build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }.onFailure {
            DiagnosticLog.event("TRASH", "enqueue-failed", it)
        }
        DiagnosticLog.event("TRASH", "scheduled next=$runAt delayMs=$delay work=$WORK_NAME")
    }
}

/**
 * The ONE trash purge worker.
 *
 * It asks [TrashRepository] for the bounded due set (index-backed on `purgeAt`),
 * purges provider-first, and reschedules from the freshly persisted deadline.
 * Process death at any point is safe: everything it acts on is durable Room
 * state, and WorkManager re-runs a persisted request.
 */
class TrashPurgeWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        return try {
            val now = System.currentTimeMillis()
            val (purged, failed) = withContext(Dispatchers.IO) {
                TrashRepository.get(context).purgeDue(now, TrashPurgeScheduler.BATCH_LIMIT)
            }
            DiagnosticLog.event("TRASH", "worker purged=$purged failed=$failed")
            TrashPurgeScheduler.rescheduleAfterRun(context, failed, now)
            Result.success()
        } catch (error: Throwable) {
            // This run already replaced the pending unique work, so a transient
            // failure must put a schedule back or nothing would run again.
            DiagnosticLog.event("TRASH", "worker-failed", error)
            if (runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                runCatching { TrashPurgeScheduler.scheduleNext(context) }
                Result.success()
            }
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
