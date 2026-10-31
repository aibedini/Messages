package com.autonomousone.messages.sms

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.autonomousone.messages.utils.DiagnosticLog
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/** Waits for the radio to be free of calls, then dispatches every held SMS in arrival order. */
class SmsCallHoldWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = SmsCallHoldStore(applicationContext)
        val runUntil = System.currentTimeMillis() + RUN_BUDGET_MS
        var settled = false
        while (true) {
            val held = store.all()
            if (held.isEmpty()) return Result.success()
            val now = System.currentTimeMillis()
            val inCall = SmsCallGuard.isInCall(applicationContext)
            if (inCall) settled = false

            for (send in held) {
                if (send.kind == HeldSend.Kind.RETRY && now - send.heldAt > SmsCallHoldPolicy.RETRY_WINDOW_MS) {
                    store.remove(send.rowId)
                    SmsCallRetry.giveUp(applicationContext, send)
                }
            }

            val capped = held.filter { it.kind == HeldSend.Kind.HOLD && now - it.heldAt >= SmsCallHoldPolicy.HOLD_CAP_MS }
            val due = if (inCall) capped else store.all()
            if (due.isNotEmpty()) {
                if (!inCall && !settled) {
                    delay(SmsCallHoldPolicy.SETTLE_MS)
                    settled = true
                    if (SmsCallGuard.isInCall(applicationContext)) continue
                }
                val sender = SmsSender(applicationContext)
                for (send in due) {
                    // Removed BEFORE dispatch: a crash in between leaves a stuck "Sending" bubble the
                    // user can resend, never a silent duplicate.
                    store.remove(send.rowId)
                    store.markDispatched(send.rowId)
                    val waitedMs = System.currentTimeMillis() - send.heldAt
                    DiagnosticLog.event(
                        "SMS_CALL_HOLD",
                        "row=${send.rowId} state=RELEASED kind=${send.kind} waitedMs=$waitedMs forced=$inCall"
                    )
                    runCatching { sender.releaseHeld(send, ignoreCall = inCall) }
                        .onFailure { DiagnosticLog.event("SMS_CALL_HOLD", "row=${send.rowId} state=RELEASE_FAILED", it) }
                }
                continue
            }

            if (System.currentTimeMillis() >= runUntil) return Result.retry()
            delay(POLL_MS)
        }
    }

    companion object {
        private const val NAME = "sms-call-hold"
        private const val POLL_MS = 2_000L

        /** Stays under WorkManager's 10-minute execution limit; a longer call continues on retry. */
        private const val RUN_BUDGET_MS = 8 * 60_000L

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SmsCallHoldWorker>()
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }

        /** After boot or an app update a held queue must resume on its own. */
        fun resumeIfNeeded(context: Context) {
            if (!SmsCallHoldStore(context).isEmpty()) enqueue(context)
        }
    }
}
