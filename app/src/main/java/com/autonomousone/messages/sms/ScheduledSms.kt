package com.autonomousone.messages.sms

import android.content.Context
import android.content.ContentValues
import android.os.Build
import android.provider.Telephony
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.autonomousone.messages.event.SmsEventBus
import com.autonomousone.messages.model.Sms
import java.util.concurrent.TimeUnit

/**
 * Scheduled SMS: persists the message now (so the user sees it as queued in
 * the conversation), then a [androidx.work.WorkManager] one-time job sends it
 * at [triggerAtMillis] — even if the process died in between (WorkManager
 * keeps the request in its own SQLite store).
 */
object ScheduledSms {

    private const val KEY_PHONE = "phone"
    private const val KEY_BODY = "body"
    private const val KEY_TRIGGER = "trigger"
    private const val KEY_THREAD_ID = "threadId"

    /** Unique-per-message WorkManager name so cancels are precise. */
    private fun workName(triggerAtMillis: Long) = "scheduled_sms_$triggerAtMillis"

    /**
     * Queues [body] to [phone] at [triggerAtMillis].
     * Writes an optimistic row into the UI bus so the user sees it immediately.
     * Returns the WorkManager tag used for cancellation.
     */
    fun schedule(context: Context, phone: String, body: String, triggerAtMillis: Long): String {
        val delayMs = (triggerAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val name = workName(triggerAtMillis)

        val request = OneTimeWorkRequestBuilder<SendWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(
                workDataOf(
                    KEY_PHONE to phone,
                    KEY_BODY to body,
                    KEY_TRIGGER to triggerAtMillis
                )
            )
            .addTag(TAG_SCHEDULED_SMS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)

        // Optimistic bubble with the SCHEDULED date so it sorts correctly.
        SmsEventBus.emitSms(
            Sms(
                id = triggerAtMillis,
                threadId = 0L,
                sender = phone,
                message = body,
                date = triggerAtMillis,
                unread = false,
                type = 2,
                status = 32 // pending
            )
        )
        return name
    }

    /** Cancels a pending scheduled send by its exact trigger time. */
    fun cancel(context: Context, triggerAtMillis: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(triggerAtMillis))
    }

    const val TAG_SCHEDULED_SMS = "scheduled_sms"

    /**
     * The worker that actually sends. Uses the same [SmsSender] pipeline as
     * normal sends (SIM/SMSC preferences + delivery reports apply).
     */
    class SendWorker(appContext: Context, params: WorkerParameters) :
        CoroutineWorker(appContext, params) {

        override suspend fun doWork(): Result {
            val phone = inputData.getString(KEY_PHONE) ?: return Result.failure()
            val body = inputData.getString(KEY_BODY) ?: return Result.failure()
            if (body.isBlank() || phone.isBlank()) return Result.failure()

            try {
                SmsSender(applicationContext).send(phone, body)

                // Notify any open Home screen to refresh the thread list.
                SmsEventBus.notifyResume()
                return Result.success()
            } catch (e: Exception) {
                Log.e("ScheduledSms", "Scheduled send failed for $phone", e)
                return if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
        }
    }
}
