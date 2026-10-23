package com.autonomousone.messages.evechat.push

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.autonomousone.messages.evechat.EveChatLog
import com.autonomousone.messages.evechat.EveChatSession
import java.util.concurrent.TimeUnit

/** Registers the FCM token with the EveChat server; survives process death and offline periods. */
class EveChatPushRegistrationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val outcome = runCatching { EveChatSession.get(applicationContext).push.registerNow() }
            .onFailure { EveChatLog.e("push", "registration worker crashed", it) }
            .getOrDefault(EveChatPushRegistrar.Outcome.RETRY)
        EveChatLog.d("push", "registration attempt", "outcome" to outcome, "attempt" to runAttemptCount)
        EveChatSession.get(applicationContext).onPushStatusChanged()
        return when (outcome) {
            EveChatPushRegistrar.Outcome.SUCCESS, EveChatPushRegistrar.Outcome.STOP -> Result.success()
            EveChatPushRegistrar.Outcome.RETRY -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val NAME = "evechat-push-register"
        private const val MAX_ATTEMPTS = 10

        fun enqueue(context: Context, replace: Boolean) {
            val request = OneTimeWorkRequestBuilder<EveChatPushRegistrationWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                NAME,
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
