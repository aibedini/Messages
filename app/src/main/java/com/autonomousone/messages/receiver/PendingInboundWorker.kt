package com.autonomousone.messages.receiver

import android.content.ContentValues
import android.content.Context
import android.provider.Telephony
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.PendingInboundSmsDao
import com.autonomousone.messages.data.PendingInboundSmsEntity
import com.autonomousone.messages.utils.DiagnosticLog
import java.util.concurrent.TimeUnit

/**
 * Writes held inbound messages to the provider, once they can be written (mission §16).
 *
 * The store is the durable hold; this is the thing that drains it. It exists because a message that could
 * not be stored must not merely be reported and forgotten — the report is for a human, and the message is
 * still recoverable automatically.
 *
 * **It never inserts without looking first.** See [PendingInboundPolicy]: `insert` can COMMIT and still
 * fail, so a held row may already be in the provider, and inserting again would put the same message
 * there twice. That lookup is the whole safety argument.
 *
 * It carries NO payload. The message lives in the database, and §41 forbids a recipient or a body in
 * WorkManager input data — which is also why the held row stores the address itself.
 */
class PendingInboundWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    /** What one row's pass produced. Distinct from the ACTION, so a failure cannot be counted as a store. */
    private sealed interface PassResult {
        data object Stored : PassResult
        data object Held : PassResult
        data object Lost : PassResult
        data object Unreadable : PassResult
    }

    override suspend fun doWork(): Result {
        val dao = MessagesDatabase.get(applicationContext).pendingInboundSmsDao()
        val rows = runCatching { dao.pending(MAX_PER_PASS) }.getOrElse {
            Log.w(TAG, "could not read held messages", it)
            return Result.retry()
        }
        if (rows.isEmpty()) return Result.success()

        val results = rows.map { attempt(it, dao) }
        val stored = results.count { it == PassResult.Stored }
        val lost = results.count { it == PassResult.Lost }
        val unreadable = results.count { it == PassResult.Unreadable }

        if (stored > 0) {
            DiagnosticLog.event(
                "INCOMING_PERSIST_RECOVERED",
                "recovered=$stored decision=held-messages-written-to-provider"
            )
        }
        if (lost > 0) {
            // Loud, AND durable on the rows themselves: these are messages that are genuinely lost, and
            // the row is the record a human can act on.
            Log.e(TAG, "$lost held inbound message(s) exhausted their retries")
            DiagnosticLog.event(
                "INCOMING_PERSIST_LOST",
                "lost=$lost decision=retries-exhausted — these messages are NOT in the provider"
            )
        }
        // A provider that could not be read is worth another pass; exhausted retries are not, and
        // returning retry() for them would spin a worker on work that can no longer succeed.
        return if (unreadable > 0 && stored == 0) Result.retry() else Result.success()
    }

    /**
     * One row, one decision, one durable outcome — recorded before the next row is touched.
     */
    private suspend fun attempt(row: PendingInboundSmsEntity, dao: PendingInboundSmsDao): PassResult {
        val found = try {
            providerHolds(row)
        } catch (e: Exception) {
            Log.w(TAG, "provider lookup failed for held message id=${row.id}", e)
            null
        }
        return when (
            val action = PendingInboundPolicy.decide(
                attempts = row.attempts,
                foundInProvider = found == true,
                providerReadable = found != null
            )
        ) {
            PendingInboundAction.AlreadyThere -> {
                // The earlier insert DID commit: there is nothing to write and nothing lost. Recorded as
                // done so this row stops being retried.
                dao.mark(row.id, PendingInboundSmsEntity.STATE_DONE, row.attempts, null)
                DiagnosticLog.event(
                    "INCOMING_PERSIST_RECOVERED",
                    "id=${row.id} decision=already-in-provider"
                )
                PassResult.Stored
            }

            PendingInboundAction.Insert -> {
                val insert = insert(row)
                when (
                    val outcome = PendingInboundPolicy.afterAttempt(
                        attemptsSoFar = row.attempts,
                        stored = PendingInboundPolicy.isStored(insert),
                        error = (insert as? InboxWriteAttempt.Threw)?.reason
                    )
                ) {
                    is PendingInboundPolicy.AttemptOutcome.Done -> {
                        dao.mark(row.id, PendingInboundSmsEntity.STATE_DONE, row.attempts + 1, null)
                        DiagnosticLog.event("INCOMING_PERSIST_RECOVERED", "id=${row.id} decision=inserted")
                        PassResult.Stored
                    }
                    is PendingInboundPolicy.AttemptOutcome.Retry -> {
                        dao.mark(
                            row.id, PendingInboundSmsEntity.STATE_PENDING, outcome.attempts, outcome.error
                        )
                        PassResult.Held
                    }
                    is PendingInboundPolicy.AttemptOutcome.Failed -> {
                        dao.mark(
                            row.id, PendingInboundSmsEntity.STATE_FAILED, outcome.attempts, outcome.error
                        )
                        DiagnosticLog.event(
                            "INCOMING_PERSIST_LOST",
                            "id=${row.id} attempts=${outcome.attempts} " +
                                "reason=${outcome.error ?: "unknown"} decision=retries-exhausted"
                        )
                        PassResult.Lost
                    }
                }
            }

            is PendingInboundAction.GiveUp -> {
                // Nothing is written for an unreadable provider: it proves nothing, so the row keeps its
                // attempt count and is retried on the next pass.
                if (action.reason == PendingInboundPolicy.REASON_PROVIDER_UNREADABLE) {
                    PassResult.Unreadable
                } else {
                    dao.mark(
                        row.id, PendingInboundSmsEntity.STATE_FAILED, row.attempts,
                        row.lastError ?: action.reason
                    )
                    PassResult.Lost
                }
            }
        }
    }

    /** Does the provider already hold this exact message? Throwing is handled by the caller as "unknown". */
    private fun providerHolds(row: PendingInboundSmsEntity): Boolean {
        val cursor = applicationContext.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID),
            "${Telephony.Sms.ADDRESS} = ? AND ${Telephony.Sms.BODY} = ? AND ${Telephony.Sms.DATE} = ?",
            arrayOf(row.address, row.body, row.dateMs.toString()),
            null
        ) ?: return false
        return cursor.use { it.moveToFirst() }
    }

    /** The same insert the receiver performs, addressed by the held row. */
    private fun insert(row: PendingInboundSmsEntity): InboxWriteAttempt {
        return try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, row.address)
                put(Telephony.Sms.BODY, row.body)
                put(Telephony.Sms.DATE, row.dateMs)
                put(Telephony.Sms.DATE_SENT, row.dateMs)
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.SEEN, 0)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
                if (row.threadId > 0L) put(Telephony.Sms.THREAD_ID, row.threadId)
            }
            val uri = applicationContext.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
            val id = uri?.lastPathSegment?.toLongOrNull() ?: -1L
            if (id <= 0L) InboxWriteAttempt.Threw("no-row-id") else InboxWriteAttempt.Wrote(id)
        } catch (e: Exception) {
            InboxWriteAttempt.Threw(e.message)
        }
    }

    companion object {
        private const val TAG = "INBOUND_RETRY"

        /** Bounded per pass: this is a repair, not a bulk import. */
        const val MAX_PER_PASS = 16

        internal const val WORK_NAME = "inbound-persist-retry"

        /** Long enough that a device that just ran a pass is not woken for nothing. */
        private const val SWEEP_INTERVAL_HOURS = 6L

        /**
         * Asks for a retry pass soon, from the receiver that just held a message.
         *
         * `REPLACE` rather than `KEEP`: this is called exactly when there is new work, and a pass already
         * queued is about to run anyway — replacing it collapses a burst of held messages into one pass
         * without losing any of them.
         */
        fun scheduleRetry(context: Context) {
            runCatching {
                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<PendingInboundWorker>()
                        .setInitialDelay(30, TimeUnit.SECONDS)
                        .addTag(WORK_NAME)
                        .build()
                )
            }.onFailure { Log.w(TAG, "could not schedule $WORK_NAME", it) }
        }

        /**
         * A periodic sweep, so a held message is retried even if the one-shot was never scheduled — the
         * enqueue can fail, and a message held by a receiver is exactly the case where relying on a single
         * opportunistic call would be worst.
         */
        fun ensureScheduled(context: Context) {
            runCatching {
                WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                    "$WORK_NAME-sweep",
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<PendingInboundWorker>(
                        SWEEP_INTERVAL_HOURS, TimeUnit.HOURS
                    ).addTag(WORK_NAME).build()
                )
            }.onFailure { Log.w(TAG, "could not schedule $WORK_NAME sweep", it) }
        }

        /** Runs a pass now — diagnostics and tests, not the normal path. */
        fun runNow(context: Context) {
            runCatching {
                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                    "$WORK_NAME-now",
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<PendingInboundWorker>().addTag(WORK_NAME).build()
                )
            }.onFailure { Log.w(TAG, "could not run $WORK_NAME now", it) }
        }
    }
}
