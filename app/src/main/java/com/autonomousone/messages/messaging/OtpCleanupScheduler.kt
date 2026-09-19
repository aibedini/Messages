package com.autonomousone.messages.messaging

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.autonomousone.messages.repository.OtpRetentionService
import com.autonomousone.messages.utils.DiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * The ONE scheduler for GLOBAL OTP cleanup (v3.4.0 FEATURE 14).
 *
 * CONTRACT
 * --------
 *  - Exactly ONE unique one-time work named [WORK_NAME] exists at any moment.
 *    It is the NEXT due deadline, not one job per OTP: a device with 100K OTPs
 *    still has exactly one pending job.
 *  - There is NO periodic full-table scan. The due query
 *    (`MessageClassificationDao.dueForOtpCleanup`) is index-backed on
 *    `otpDeleteEligibleAt` and LIMIT-bounded.
 *  - Scheduling is durable: WorkManager's own store survives process death and
 *    reboot, and the worker re-reads the due set from Room when it runs, so a job
 *    that fires "late" still cleans exactly what is due.
 *  - With the setting OFF (or nothing enrolled) any pending job is CANCELLED, so
 *    "off" means nothing can run — not "runs and then does nothing".
 *
 * RESCHEDULE TRIGGERS (all funnel into [reschedule] / [scheduleNext]):
 *  1. an OTP is enrolled or its deadline changes → [observeDeadlineChanges]
 *     (Room invalidation on `message_classification`; the authoritative MIN is
 *     re-read, never guessed);
 *  2. the user changes the setting or the retention duration → the Settings view
 *     model calls [reschedule];
 *  3. a message is starred/unstarred or keep-flagged/unflagged → the action calls
 *     [reschedule] (see `OtpRetentionService.onUserStateChanged`);
 *  4. the worker finishes, or triage moves a deadline → [scheduleNext] with the
 *     freshly persisted MIN.
 *
 * [ExistingWorkPolicy.REPLACE] is intentional and matches the repo's existing
 * unique-work convention (GatewayScheduler / ScheduledSms): the newest request
 * always holds the single true "next due" time. At the end of a worker run the
 * only remaining work is returning a Result, which is cancellation-safe, and
 * every state change is committed in Room BEFORE the reschedule.
 */
object OtpCleanupScheduler {

    /** The one unique work name. Never parameterised — there is only one queue. */
    const val WORK_NAME = "otp_cleanup"

    /**
     * Upper bound on one worker run. Keeps a 100K-OTP backlog from becoming one
     * unbounded transaction; whatever is left is picked up by the next run,
     * scheduled from the remaining MIN deadline.
     */
    const val BATCH_LIMIT = 200

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Scheduling ──────────────────────────────────────────────────────────

    /**
     * Points the single unique [WORK_NAME] at [eligibleAt], or cancels it when
     * there is nothing to do.
     *
     * A deadline already in the past is run as soon as WorkManager allows
     * (delay 0) — never silently dropped — because the worker re-validates every
     * row against the current setting and user state before touching anything.
     */
    fun scheduleNext(context: Context, eligibleAt: Long?) {
        val appContext = context.applicationContext
        if (eligibleAt == null || eligibleAt <= 0L) {
            cancelPending(appContext)
            return
        }
        enqueue(appContext, eligibleAt)
    }

    /**
     * Reschedules from the PERSISTED MIN deadline. Cheap: one indexed MIN read.
     *
     * Called when the setting changes, and after a star/keep change.
     */
    fun reschedule(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            val next = try {
                OtpRetentionService.get(appContext).nextEligibleAt()
            } catch (error: Throwable) {
                DiagnosticLog.event("OTP_RETENTION", "reschedule-read-failed", error)
                return@launch
            }
            scheduleNext(appContext, next)
        }
    }

    /** Cancels the pending job. Called when the feature is switched off. */
    fun cancelPending(context: Context) {
        runCatching { WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME) }
            .onFailure { DiagnosticLog.event("OTP_RETENTION", "cancel-failed", it) }
    }

    private fun enqueue(context: Context, eligibleAt: Long) {
        val delay = (eligibleAt - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<OtpCleanupWorker>()
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
            DiagnosticLog.event("OTP_RETENTION", "enqueue-failed", it)
        }
        DiagnosticLog.event(
            "OTP_RETENTION",
            "scheduled next=$eligibleAt delayMs=$delay work=$WORK_NAME"
        )
    }

    /**
     * Reschedules whenever the enrolled set changes.
     *
     * WHY AN OBSERVER AND NOT A CALL FROM THE INGEST PATH: `message_classification`
     * has several legitimate writers (immediate ingest classification, the
     * checkpointed backfill, the opt-in sweep), and a call site in each of them
     * would drift. Room invalidation is the ONE signal that covers all of them,
     * and the reschedule re-reads the persisted MIN rather than trusting a value
     * passed in, so a stale callback can never schedule a wrong time.
     *
     * Idempotent: [scheduleNext] REPLACEs the unique work, so a burst of
     * invalidations collapses onto one job at one time.
     */
    fun observeDeadlineChanges(context: Context) {
        val appContext = context.applicationContext
        val service = OtpRetentionService.get(appContext)
        scope.launch {
            service.observeEarliestEligibleAt()
                .catch { DiagnosticLog.event("OTP_RETENTION", "deadline-observer-failed", it) }
                .collectLatest {
                    // Triage first (it can only move deadlines EARLIER), then
                    // schedule from the authoritative persisted MIN.
                    runCatching { service.reconcileEnrolments() }
                        .onFailure {
                            DiagnosticLog.event("OTP_RETENTION", "triage-failed", it)
                        }
                    val next = runCatching { service.nextEligibleAt() }.getOrNull()
                    scheduleNext(appContext, next)
                }
        }
    }
}

/**
 * The ONE OTP cleanup worker.
 *
 * It never scans the whole table and never deletes permanently: it asks
 * [OtpRetentionService] (which owns the eligibility policy) for the bounded due
 * set, and the service MOVES those messages to Trash. Trash retention then owns
 * any later permanent deletion, and the user can still restore.
 */
class OtpCleanupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        return try {
            val outcome = OtpRetentionService.get(context)
                .runDueCleanup(limit = OtpCleanupScheduler.BATCH_LIMIT)
            DiagnosticLog.event(
                "OTP_RETENTION",
                "run scanned=${outcome.scanned} trashed=${outcome.trashed} " +
                    "deferred=${outcome.deferred} next=${outcome.nextEligibleAt ?: 0L}"
            )
            // Self-reschedule from the freshly persisted next deadline.
            OtpCleanupScheduler.scheduleNext(context, outcome.nextEligibleAt)
            Result.success()
        } catch (error: Throwable) {
            // A transient failure must not lose the schedule: this run already
            // replaced the pending unique work, so put it back and retry.
            DiagnosticLog.event("OTP_RETENTION", "run-failed", error)
            if (runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                runCatching { OtpCleanupScheduler.reschedule(context) }
                Result.success()
            }
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
