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
    private const val KEY_SUBSCRIPTION_ID = "subscriptionId"
    private const val KEY_WORK_NAME = "workName"

    /**
     * Default WorkManager unique name: derived from the trigger time.
     *
     * Retained for the long-press "Schedule send" flow. The v3.4.0 delay feature
     * passes its own durable intent id instead, because two messages scheduled
     * for the same millisecond would otherwise collapse into one job under
     * `ExistingWorkPolicy.REPLACE` and one of them would never be sent.
     */
    private fun workName(triggerAtMillis: Long) = "scheduled_sms_$triggerAtMillis"

    /** Unique-work name for an explicit id. */
    fun workNameFor(id: String) = "scheduled_sms_$id"

    /** Prefix of a unique-work name that carries a delayed-send intent id. */
    private const val DELAYED_SEND_WORK_PREFIX = "scheduled_sms_delay_"

    /**
     * Unique-work name for a v3.4.0 delayed-send intent.
     *
     * Distinct from [workNameFor] on purpose: the worker tells the two jobs
     * apart by this prefix, and a name that merely looked like a trigger time
     * would silently route a long-press "Schedule send" through the delay
     * ledger.
     */
    fun delayedSendWorkName(intentId: String) = "$DELAYED_SEND_WORK_PREFIX$intentId"

    /**
     * Queues [body] to [phone] at [triggerAtMillis].
     * Writes an optimistic row into the UI bus so the user sees it immediately.
     * Returns the WorkManager tag used for cancellation.
     */
    fun schedule(context: Context, phone: String, body: String, triggerAtMillis: Long): String =
        schedule(context, phone, body, triggerAtMillis, null)

    /**
     * Same as [schedule], carrying the user's per-call SIM choice.
     *
     * [subscriptionId] is the in-chat SIM switcher's selection (`null` = the
     * global Messaging preference). It MUST travel with the request: the send
     * happens minutes later, possibly in a new process, so a choice that lived
     * only in a ViewModel field would be lost and the message would leave on the
     * wrong line.
     */
    fun schedule(
        context: Context,
        phone: String,
        body: String,
        triggerAtMillis: Long,
        subscriptionId: Int?
    ): String = schedule(context, phone, body, triggerAtMillis, subscriptionId, workName(triggerAtMillis))

    /**
     * Schedules under an EXPLICIT unique-work name.
     *
     * The v3.4.0 Send-delay path passes a stable intent id so that cancel is
     * precise even when several delayed messages share a trigger millisecond.
     */
    fun schedule(
        context: Context,
        phone: String,
        body: String,
        triggerAtMillis: Long,
        subscriptionId: Int?,
        workName: String
    ): String {
        val delayMs = (triggerAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val name = workName

        val request = OneTimeWorkRequestBuilder<SendWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(
                workDataOf(
                    KEY_PHONE to phone,
                    KEY_BODY to body,
                    KEY_TRIGGER to triggerAtMillis,
                    KEY_SUBSCRIPTION_ID to (subscriptionId ?: SUBSCRIPTION_UNSET),
                    KEY_WORK_NAME to name
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

    /**
     * Cancels a scheduled send by the unique name [schedule] returned.
     *
     * Cancellation is BEST EFFORT by construction — WorkManager cannot stop a
     * job that is already running. That is exactly why the Send-delay feature
     * treats this as a secondary step and makes the durable state transition
     * (`PENDING -> CANCELLED`) the authority instead of trusting the cancel.
     */
    fun cancelWork(context: Context, workName: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName)
    }

    /** Sentinel for "no explicit SIM was chosen". */
    const val SUBSCRIPTION_UNSET = -1

    const val TAG_SCHEDULED_SMS = "scheduled_sms"

    /**
     * The worker that actually sends. Uses the same [SmsSender] pipeline as
     * normal sends (SIM/SMSC preferences + delivery reports apply).
     *
     * ── v3.4.0: the delayed-send hand-off ────────────────────────────────────
     * When the request carries a delayed-send intent id, the worker does NOT
     * send: it hands off to [DelayedSendExecutor], which owns the at-most-once
     * claim. That indirection exists so this, the pre-existing scheduler, stays
     * the ONLY thing that ever enqueues a WorkManager send job — a second
     * scheduling engine would be a second place for a duplicate send to hide.
     *
     * `schedule()` always attaches the intent once the delay is durable, so no
     * probe of WorkManager state is needed here (and none is done: a probe would
     * be a race, and the claim is the authority anyway).
     */
    class SendWorker(appContext: Context, params: WorkerParameters) :
        CoroutineWorker(appContext, params) {

        override suspend fun doWork(): Result {
            val phone = inputData.getString(KEY_PHONE) ?: return Result.failure()
            val body = inputData.getString(KEY_BODY) ?: return Result.failure()
            if (body.isBlank() || phone.isBlank()) return Result.failure()

            val workName = inputData.getString(KEY_WORK_NAME).orEmpty()
            val intentId = workName
                .takeIf { it.startsWith(DELAYED_SEND_WORK_PREFIX) }
                ?.removePrefix(DELAYED_SEND_WORK_PREFIX)
                ?.takeIf { it.isNotEmpty() }
            if (intentId != null) {
                // `body` is validated non-blank above, but the LEDGER row is the
                // text of record: it is what the pending bubble showed.
                return DelayedSendWorkerBody.run(
                    context = applicationContext,
                    intentId = intentId,
                    phone = phone,
                    subscriptionId = inputData.getInt(KEY_SUBSCRIPTION_ID, SUBSCRIPTION_UNSET)
                        .takeIf { it != SUBSCRIPTION_UNSET }
                )
            }

            // Scheduled-send path (long-press "Schedule send"): unchanged.
            return try {
                val subscriptionId = inputData.getInt(KEY_SUBSCRIPTION_ID, SUBSCRIPTION_UNSET)
                    .takeIf { it != SUBSCRIPTION_UNSET }
                SmsSender(applicationContext)
                    .send(phone, body, subscriptionId, null)

                // Notify any open Home screen to refresh the thread list.
                SmsEventBus.notifyResume()
                Result.success()
            } catch (e: Exception) {
                Log.e("ScheduledSms", "Scheduled send failed for $phone", e)
                return if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
        }
    }
}
