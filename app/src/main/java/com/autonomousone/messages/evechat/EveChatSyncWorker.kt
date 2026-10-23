package com.autonomousone.messages.evechat

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * The delivery safety net for a closed app: the inbox and unread threads are synced and new
 * visitor messages are notified. Every 15 minutes without push, every 30 when FCM is active
 * (push can be delayed or dropped, so polling never stops entirely).
 */
class EveChatSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ok = runCatching { EveChatSession.get(applicationContext).backgroundSync() }
            .onFailure { EveChatLog.e("worker", "background sync crashed", it) }
            .getOrDefault(false)
        EveChatLog.i("worker", "background sync", "ok" to ok, "attempt" to runAttemptCount)
        return if (ok || runAttemptCount >= 3) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "evechat-sync"
        private const val NAME_ONCE = "evechat-sync-once"

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** UPDATE keeps the schedule's phase while letting the interval follow push health. */
        fun schedule(context: Context, intervalMinutes: Long) {
            val request = PeriodicWorkRequestBuilder<EveChatSyncWorker>(intervalMinutes, TimeUnit.MINUTES)
                .setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** A catch-up as soon as the network allows, e.g. after a push that could not be handled in time. */
        fun runOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<EveChatSyncWorker>()
                .setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME_ONCE, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
            WorkManager.getInstance(context).cancelUniqueWork(NAME_ONCE)
        }
    }
}
