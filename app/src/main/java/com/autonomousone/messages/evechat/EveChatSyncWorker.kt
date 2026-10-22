package com.autonomousone.messages.evechat

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Without push (EveChat has no FCM integration) this is how support messages reach a closed
 * app: every 15 minutes, on any network, the inbox and unread threads are synced and new
 * visitor messages are notified.
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

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<EveChatSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
