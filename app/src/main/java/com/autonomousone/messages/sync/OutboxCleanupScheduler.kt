package com.autonomousone.messages.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.gateway.health.GatewayLog
import com.autonomousone.messages.gateway.health.GatewayLogSeverity
import com.autonomousone.messages.gateway.health.GatewayLogSubsystem
import com.autonomousone.messages.repository.GatewaySyncRepository
import java.util.concurrent.TimeUnit

/**
 * The `gmweb-outbox-cleanup` maintenance worker (mission §19/§36).
 *
 * ITS ONLY JOB is to remove acknowledged events past the retention window. It does not upload,
 * does not claim, does not scan, and cannot see a message: the only statement it runs is a DELETE
 * bounded to `state = 'ACKED'` and to rows the history watermark has already passed.
 *
 * Constraints are deliberately absent (mission §37: cleanup is low priority, not
 * network-gated). It is NOT a replacement for the uploader — `ExistingPeriodicWorkPolicy.KEEP`
 * means N scheduling calls leave exactly one job, and WorkManager persists it across reboots.
 */
class OutboxCleanupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val database = MessagesDatabase.get(applicationContext)
        val repository = GatewaySyncRepository(database)
        return try {
            val removed = repository.purgeAcknowledged(System.currentTimeMillis())
            if (removed > 0) {
                Log.i(TAG, "outbox cleanup removed $removed acknowledged event(s)")
                GatewayLog.record(
                    severity = GatewayLogSeverity.INFO,
                    subsystem = GatewayLogSubsystem.SYNC_UPLOAD,
                    code = "OUTBOX_CLEANUP",
                    title = "Outbox cleanup",
                    detail = "$removed acknowledged event(s) past retention"
                )
            }
            Result.success()
        } catch (e: Exception) {
            // A cleanup failure must never be fatal or noisy: the outbox simply keeps its rows,
            // which is the safe direction.
            Log.w(TAG, "outbox cleanup failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "OUTBOX_CLEANUP"
    }
}

/** Schedules the single unique cleanup job. */
object OutboxCleanupScheduler {

    const val WORK_NAME = "gmweb-outbox-cleanup"

    /** Once a day is ample for a 48-hour window; the point is that it happens at all. */
    private const val INTERVAL_HOURS = 12L

    /**
     * Enqueues the periodic cleanup.
     *
     * `KEEP` so a repeated call (every app start, every boot) cannot pile up jobs, and
     * `ExistingPeriodicWorkPolicy.KEEP` so an already-scheduled job is left alone rather than
     * restarting its interval on every launch.
     */
    fun ensureScheduled(context: Context) {
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<OutboxCleanupWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                    .setInitialDelay(10, TimeUnit.MINUTES)
                    .addTag(WORK_NAME)
                    .build()
            )
        }.onFailure { Log.w(TAG, "could not schedule $WORK_NAME", it) }
    }

    /** Runs the cleanup now — used by diagnostics and tests, not by the normal path. */
    fun runNow(context: Context) {
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$WORK_NAME-now",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<OutboxCleanupWorker>().addTag(WORK_NAME).build()
            )
        }.onFailure { Log.w(TAG, "could not run $WORK_NAME now", it) }
    }

    private const val TAG = "OUTBOX_CLEANUP"
}
