package com.autonomousone.messages.classification

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.autonomousone.messages.data.MessageEntity
import com.autonomousone.messages.repository.BackfillCursor
import com.autonomousone.messages.repository.ClassificationRepository
import com.autonomousone.messages.utils.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * FEATURE 12 — checkpointed, bounded history backfill for Smart Categories.
 *
 * Invariants this worker exists to enforce:
 *
 *  - **Never classify the whole table synchronously.** One batch is 200-500
 *    rows ([ClassificationRepository.DEFAULT_BATCH]) read with KEYSET paging
 *    (`unclassifiedBatch`), never `OFFSET` and never `SELECT *`.
 *  - **Resumable across process death.** The cursor is persisted
 *    ([ClassificationCheckpoint]) immediately after each batch, so a kill
 *    mid-sweep resumes at the last handled row. Rows already classified are
 *    excluded by the query itself, which is what makes a re-run a no-op.
 *  - **Bounded CPU per wake-up.** A worker returns after ONE batch; the next
 *    batch is scheduled by the worker itself (`Result.retry` with backoff) so
 *    the OS stays in control. There is no periodic full-table scan and no
 *    `PeriodicWorkRequest` anywhere in this feature.
 *  - **Fail-safe.** A classifier failure is logged (counts/category names only)
 *    and never fails ingest; a persistent database failure ends the sweep
 *    instead of retrying forever.
 */
class ClassificationBackfillWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val source = inputData.getString(KEY_SOURCE) ?: MessageEntity.SOURCE_SMS
        val limit = inputData.getInt(KEY_BATCH, ClassificationRepository.DEFAULT_BATCH)

        // Fresh install / explicit restart: begin a new sweep.
        if (inputData.getBoolean(KEY_RESET, false)) {
            ClassificationCheckpoint.clear(applicationContext, source)
        }

        val repository = ClassificationRepository(applicationContext)
        val cursor = ClassificationCheckpoint.load(applicationContext, source)
        val outcome = repository.classifyBackfillBatch(after = cursor, limit = limit)

        if (outcome.handled == 0) {
            if (!outcome.finished) {
                // The read itself failed: retry the SAME cursor rather than
                // skipping rows. Bounded attempts keep this from looping.
                if (runAttemptCount < MAX_READ_ATTEMPTS) return@withContext Result.retry()
                DiagnosticLog.event("CATEGORY", "backfill-abandoned source=$source reason=read")
                return@withContext Result.failure()
            }
            ClassificationCheckpoint.markComplete(applicationContext, source)
            logProgress(source, outcome)
            return@withContext Result.success()
        }

        ClassificationCheckpoint.save(applicationContext, source, outcome.cursor)
        logProgress(source, outcome)

        if (outcome.finished) {
            ClassificationCheckpoint.markComplete(applicationContext, source)
            repository.pruneOrphans()
            Result.success()
        } else if (runAttemptCount >= MAX_ATTEMPTS) {
            // The cursor is durable, so an app restart resumes exactly here.
            DiagnosticLog.event("CATEGORY", "backfill-paused source=$source reason=attempts")
            Result.success()
        } else {
            Result.retry()
        }
    }

    /** Counts only — never a category's message content. */
    private fun logProgress(
        source: String,
        outcome: com.autonomousone.messages.repository.BackfillOutcome
    ) {
        DiagnosticLog.event(
            "CATEGORY",
            "backfill-batch source=$source handled=${outcome.handled} " +
                "finished=${outcome.finished} cursorDate=${outcome.cursor.date}"
        )
    }

    companion object {
        const val KEY_SOURCE = "classification_source"
        const val KEY_BATCH = "classification_batch"
        const val KEY_RESET = "classification_reset"

        /** Batches per work request before the worker rests (WorkManager retries). */
        private const val MAX_ATTEMPTS = 200
        private const val MAX_READ_ATTEMPTS = 3

        /**
         * Schedules the sweep for both provider sources. IDEMPOTENT: unique work
         * names + [ExistingWorkPolicy.KEEP] mean repeated calls (every app start)
         * collapse into the already-pending sweep instead of stacking duplicates.
         */
        fun enqueue(context: Context) {
            for (source in listOf(MessageEntity.SOURCE_SMS, MessageEntity.SOURCE_MMS)) {
                enqueueSource(context, source, reset = false)
            }
        }

        /** Start the sweep over again from the newest message (manual re-index). */
        fun reindex(context: Context) {
            for (source in listOf(MessageEntity.SOURCE_SMS, MessageEntity.SOURCE_MMS)) {
                enqueueSource(context, source, reset = true)
            }
        }

        private fun enqueueSource(context: Context, source: String, reset: Boolean) {
            val request = OneTimeWorkRequestBuilder<ClassificationBackfillWorker>()
                // Local, IO-light and fully resumable: it may wait for a quiet,
                // non-starved device rather than compete with the realtime path.
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setInitialDelay(INITIAL_DELAY_SECONDS, TimeUnit.SECONDS)
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.LINEAR,
                    BACKOFF_SECONDS,
                    TimeUnit.SECONDS
                )
                .setInputData(
                    workDataOf(
                        KEY_SOURCE to source,
                        KEY_BATCH to ClassificationRepository.DEFAULT_BATCH,
                        KEY_RESET to reset
                    )
                )
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName(source),
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun uniqueName(source: String) = "smart-categories-backfill-$source"

        const val TAG = "smart-categories"

        private const val INITIAL_DELAY_SECONDS = 30L
        private const val BACKOFF_SECONDS = 15L
    }
}

/**
 * Durable backfill cursor, one per provider source.
 *
 * Stored in `SharedPreferences` on purpose: Room is at schema v16 and the
 * v3.4.0 workstreams must not bump it. The value is a plain keyset tuple, and
 * the Room query is the real gate — a lost or corrupt cursor only re-runs a
 * sweep that is already idempotent.
 */
object ClassificationCheckpoint {

    private const val FILE = "smart_categories_backfill"
    private const val KEY_DATE = "cursor_date_"
    private const val KEY_SOURCE = "cursor_source_"
    private const val KEY_PROVIDER = "cursor_provider_"
    private const val KEY_DONE = "cursor_done_"

    fun load(context: Context, source: String): BackfillCursor {
        val prefs = context.applicationContext
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE + source, false)) {
            // A finished sweep restarts from the newest row: only rows that are
            // genuinely unclassified are returned, so this is a cheap no-op pass.
            return BackfillCursor.START
        }
        val date = prefs.getLong(KEY_DATE + source, Long.MIN_VALUE)
        if (date == Long.MIN_VALUE) return BackfillCursor.START
        return BackfillCursor(
            date = date,
            source = prefs.getString(KEY_SOURCE + source, "").orEmpty(),
            providerId = prefs.getLong(KEY_PROVIDER + source, Long.MAX_VALUE)
        )
    }

    fun save(context: Context, source: String, cursor: BackfillCursor) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_DATE + source, cursor.date)
            .putString(KEY_SOURCE + source, cursor.source)
            .putLong(KEY_PROVIDER + source, cursor.providerId)
            .putBoolean(KEY_DONE + source, false)
            .commit()
    }

    fun markComplete(context: Context, source: String) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DONE + source, true)
            .commit()
    }

    fun clear(context: Context, source: String) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_DATE + source)
            .remove(KEY_SOURCE + source)
            .remove(KEY_PROVIDER + source)
            .putBoolean(KEY_DONE + source, false)
            .commit()
    }
}
