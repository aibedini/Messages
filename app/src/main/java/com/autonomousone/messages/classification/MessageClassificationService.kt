package com.autonomousone.messages.classification

import android.content.Context
import com.autonomousone.messages.data.MessageEntity
import com.autonomousone.messages.repository.ClassificationRepository
import com.autonomousone.messages.utils.DiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * FEATURE 12 — the IMMEDIATE classification hook for the ingest fast path.
 *
 * A brand-new or changed message must be categorised right away, but the
 * realtime path must not pay for it: [schedule] hands the already-persisted
 * messages to a dedicated background scope and RETURNS IMMEDIATELY. Ingest
 * therefore never waits on the classifier, the classifier never touches the
 * Compose main thread, and no classification failure can propagate back into
 * the mutation loop.
 *
 * Why a separate scope and not the ingest coroutine: classification runs
 * strictly AFTER the message transaction has committed. If the process dies
 * between the two, the message is simply left unclassified — the checkpointed
 * backfill worker picks it up, because `unclassifiedBatch` is exactly "rows with
 * no classification row". Losing a classification is therefore self-healing;
 * delaying or failing a message ingest would not be.
 */
object MessageClassificationService {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** One repository per process: its DAOs and contact cache are process-wide. */
    @Volatile
    private var repository: ClassificationRepository? = null

    private fun repository(context: Context): ClassificationRepository =
        repository ?: synchronized(this) {
            repository ?: ClassificationRepository(context).also { repository = it }
        }

    /**
     * Classify [messages] off the ingest thread. Safe to call for every batch;
     * already-classified rows are re-derived deterministically (an idempotent
     * upsert) and refreshed bodies are corrected.
     */
    fun schedule(context: Context, messages: List<MessageEntity>) {
        if (messages.isEmpty()) return
        val appContext = context.applicationContext
        val classifier = repository(appContext)
        scope.launch {
            for (message in messages) {
                try {
                    classifier.classifyAndStore(message.toForClassification())
                } catch (_: kotlinx.coroutines.CancellationException) {
                    return@launch
                } catch (error: Throwable) {
                    // Fail-safe: category/count diagnostics only, never content.
                    DiagnosticLog.event(
                        "CATEGORY",
                        "immediate-classify-failed source=${message.source} " +
                            "providerId=${message.providerId}"
                    )
                }
            }
        }
    }
}
