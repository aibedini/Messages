package com.autonomousone.messages.media

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.utils.DiagnosticLog
import kotlinx.coroutines.yield
import java.util.concurrent.TimeUnit

/**
 * Checkpointed, bounded history sweep for the Media / Links / Files browser.
 *
 * WHY IT EXISTS
 * -------------
 * Ingest-time indexing covers every message that arrives or CHANGES after this
 * feature ships, and the existing sync backfill covers rows it happens to
 * re-ingest. It does NOT cover history that is already in Room and is never
 * touched again — after an upgrade, an old conversation's links and attachments
 * would simply be missing from the browser.
 *
 * WHAT IT GUARANTEES (the brief's contract, verbatim):
 *
 *  - **Never synchronous over 360K rows.** Each run does at most
 *    [AssetBackfillCursor.MAX_BATCHES_PER_RUN] batches of
 *    [AssetBackfillCursor.BATCH_SIZE] rows, then yields the worker so Android can
 *    stop it cleanly.
 *  - **Persisted checkpoint.** The keyset cursor is committed to
 *    [AssetBackfillCheckpoint] after EVERY batch, so the sweep resumes where it
 *    stopped instead of restarting.
 *  - **Resumable + single-flight.** `enqueueUniqueWork(KEEP)` means N opens of the
 *    Media screen are still one sweep.
 *  - **Yields between batches** (`yield()` + a bounded run), and stops early when
 *    WorkManager asks it to (`isStopped`).
 *  - **Idempotent.** Every row is written through the deterministic
 *    `MessageAssetKeys` identity, so re-covering a batch is an UPSERT.
 */
class MessageAssetBackfillWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val checkpoint = AssetBackfillCheckpoint(applicationContext)
        if (checkpoint.isComplete()) return Result.success()
        val indexer = MessageAssetIndexer.get(applicationContext)
        val dao = MessagesDatabase.get(applicationContext).messageAssetDao()
        var cursor = checkpoint.cursor()
        return try {
            var batches = 0
            var reachedEnd = false
            while (batches < AssetBackfillCursor.MAX_BATCHES_PER_RUN) {
                if (isStopped) return Result.retry()
                val rows = dao.backfillBatch(
                    afterDate = cursor.date,
                    afterSource = cursor.source,
                    afterProviderId = cursor.providerId,
                    limit = AssetBackfillCursor.BATCH_SIZE
                )
                if (rows.isEmpty()) {
                    reachedEnd = true
                    break
                }
                indexer.index(
                    rows.map {
                        IndexableMessage(it.source, it.providerId, it.threadId, it.body, it.date)
                    }
                )
                cursor = cursor.advance(rows.last())
                checkpoint.save(cursor)
                batches++
                if (rows.size < AssetBackfillCursor.BATCH_SIZE) {
                    reachedEnd = true
                    break
                }
                yield()
            }
            // Counts only: never a URL, never a body.
            DiagnosticLog.event(
                "ASSETS",
                "backfill batches=$batches cursorDate=${cursor.date} done=$reachedEnd"
            )
            if (reachedEnd) {
                checkpoint.markComplete()
                // Orphans can only exist for identities whose message row was
                // removed by a path that never reached the asset index.
                indexer.deleteOrphans()
                Result.success()
            } else {
                Result.retry()
            }
        } catch (t: Throwable) {
            DiagnosticLog.event("ASSETS", "backfill_failed attempt=$runAttemptCount", t)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val UNIQUE_WORK = "message_asset_backfill"
        private const val MAX_ATTEMPTS = 5
        private const val TAG = "message_assets"

        /**
         * One sweep, ever (until it completes). Safe to call from every Media
         * screen open and from the indexer's overflow path.
         */
        fun scheduleOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<MessageAssetBackfillWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request)
        }
    }
}
