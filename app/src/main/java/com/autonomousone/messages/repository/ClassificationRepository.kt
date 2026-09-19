package com.autonomousone.messages.repository

import android.content.Context
import androidx.room.withTransaction
import com.autonomousone.messages.data.ConversationClassificationDao
import com.autonomousone.messages.data.ConversationClassificationEntity
import com.autonomousone.messages.data.ConversationPreferenceDao
import com.autonomousone.messages.data.MessageCategory
import com.autonomousone.messages.data.MessageClassificationDao
import com.autonomousone.messages.data.MessageClassificationEntity
import com.autonomousone.messages.data.MessageEntity
import com.autonomousone.messages.data.MessageForClassification
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.ThreadEffectiveCategory
import com.autonomousone.messages.messaging.KnownContactLookup
import com.autonomousone.messages.messaging.MessageClassifier
import com.autonomousone.messages.utils.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * FEATURE 12 — Smart Categories: the ONLY writer of the classification tables.
 *
 * Two durable products per classified message:
 *
 *  1. `message_classification` — the per-message verdict (category name,
 *     confidence, `isOtp`, OTP cleanup deadline);
 *  2. `conversation_classification` — the per-conversation PROJECTION, so Home
 *     filters by category with an indexed lookup instead of scanning messages.
 *
 * The projection is never authoritative over a user override: the override
 * lives in `conversation_preferences.categoryOverride` and is applied LIVE by
 * [threadIdsByCategory] / [observeThreadCategories], so a stale copy can never
 * beat a live user decision.
 *
 * Both writes happen in ONE Room transaction, off the main thread. Every public
 * entry point is fail-safe: a classifier or database failure is logged as a
 * category/count fact (never message content) and swallowed, because a
 * classification problem must never break or delay message ingest.
 */
class ClassificationRepository(
    context: Context,
    /**
     * The retention decision, owned by the global OTP-cleanup workstream. This
     * repository never enables a cleanup that the owner did not ask for: with
     * the default `0` nothing is ever eligible and
     * [MessageClassificationEntity.otpDeleteEligibleAt] stays 0 — exactly the
     * "OTP auto-delete OFF by default" invariant.
     */
    private val otpRetentionMillis: () -> Long = { OTP_RETENTION_DISABLED },
    private val classifier: MessageClassifier = MessageClassifier(
        knownContacts = KnownContactLookup { sender ->
            // Process-wide cached map: at most one provider scan per process,
            // never one per classified message.
            ContactRepository(context).getContactNameMap()
                .containsKey(ContactRepository.normalizePhone(sender))
        }
    )
) {

    private val appContext = context.applicationContext

    private val db get() = MessagesDatabase.get(appContext)

    private val messageDao: MessageClassificationDao get() = db.messageClassificationDao()
    private val conversationDao: ConversationClassificationDao
        get() = db.conversationClassificationDao()
    private val preferenceDao: ConversationPreferenceDao get() = db.conversationPreferenceDao()

    // ── Read side (Home filter + diagnostics) ───────────────────────────────

    /**
     * Thread ids whose EFFECTIVE category is one of [categories]. Indexed lookup
     * over the projection plus the live override — never a message scan.
     */
    suspend fun threadIdsByCategory(categories: Collection<MessageCategory>): Set<Long> =
        withContext(Dispatchers.IO) {
            if (categories.isEmpty()) return@withContext emptySet()
            runCatching {
                messageDao.threadIdsByEffectiveCategory(categories.map { it.name })
                    .mapTo(HashSet()) { it.threadId }
            }.getOrDefault(emptySet())
        }

    /** Live version of [threadIdsByCategory] for Home. */
    fun observeThreadCategories(): Flow<List<ThreadEffectiveCategory>> =
        conversationDao.observeThreadEffectiveCategories()

    /**
     * Effective category of ONE thread: the user override first, the automatic
     * projection second, UNKNOWN when neither exists.
     */
    suspend fun effectiveCategoryFor(threadId: Long): MessageCategory = withContext(Dispatchers.IO) {
        readCategoryOverride(threadId)
            ?: MessageCategory.from(conversationDao.get(threadId)?.category)
    }

    /** Content-free category histogram — the `CATEGORY` diagnostic surface. */
    suspend fun categoryCountsForThread(threadId: Long): Map<MessageCategory, Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                messageDao.categoryCountsForThread(threadId).associate { row ->
                    MessageCategory.from(row.category) to row.count
                }
            }.getOrDefault(emptyMap())
        }

    // ── Write side: one classified message ──────────────────────────────────

    /**
     * Classify and persist ONE message.
     *
     * Called from the ingest fast path AFTER the message transaction has
     * committed, and by the backfill worker per batch. Returns true when a
     * classification row was written.
     */
    suspend fun classifyAndStore(message: MessageForClassification): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val previous = messageDao.get(message.source, message.providerId)
                val result = classifier.classify(
                    sender = message.rawAddress,
                    body = message.body,
                    type = message.messageType
                )
                val row = MessageClassificationEntity(
                    source = message.source,
                    providerId = message.providerId,
                    threadId = message.threadId,
                    category = result.category.name,
                    confidence = result.confidence,
                    isOtp = result.isOtp,
                    otpDeleteEligibleAt = eligibleAtFor(
                        isOtp = result.isOtp,
                        messageType = message.messageType,
                        dateMs = message.date,
                        previousEligibleAt = previous?.otpDeleteEligibleAt ?: 0L
                    ),
                    classifiedAt = System.currentTimeMillis()
                )
                db.withTransaction {
                    messageDao.upsert(row)
                    projectionFor(message.threadId)
                }
                true
            } catch (error: Throwable) {
                // Fail-safe: classification must never break ingest. The
                // identity is safe to log; the body never is.
                DiagnosticLog.event(
                    "CATEGORY",
                    "classify-failed source=${message.source} providerId=${message.providerId}"
                )
                false
            }
        }

    /**
     * Classify and persist a batch (the backfill path): ONE transaction for the
     * whole batch, then each touched conversation projection refreshed once.
     * Returns the number of rows written.
     */
    suspend fun classifyBatch(messages: List<MessageForClassification>): Int =
        withContext(Dispatchers.IO) {
            if (messages.isEmpty()) return@withContext 0
            try {
                val now = System.currentTimeMillis()
                val rows = ArrayList<MessageClassificationEntity>(messages.size)
                for (message in messages) {
                    val result = classifier.classify(
                        sender = message.rawAddress,
                        body = message.body,
                        type = message.messageType
                    )
                    val previous = messageDao.get(message.source, message.providerId)
                    rows += MessageClassificationEntity(
                        source = message.source,
                        providerId = message.providerId,
                        threadId = message.threadId,
                        category = result.category.name,
                        confidence = result.confidence,
                        isOtp = result.isOtp,
                        otpDeleteEligibleAt = eligibleAtFor(
                            isOtp = result.isOtp,
                            messageType = message.messageType,
                            dateMs = message.date,
                            previousEligibleAt = previous?.otpDeleteEligibleAt ?: 0L
                        ),
                        classifiedAt = now
                    )
                }
                val touched = messages.map { it.threadId }.distinct()
                db.withTransaction {
                    messageDao.upsertAll(rows)
                    for (threadId in touched) {
                        if (threadId > 0L) projectionFor(threadId)
                    }
                }
                rows.size
            } catch (error: Throwable) {
                DiagnosticLog.event("CATEGORY", "batch-classify-failed size=${messages.size}")
                0
            }
        }

    /**
     * Classify ONE bounded backfill batch in keyset order (see
     * [MessageClassificationDao.unclassifiedBatch]) and report where the cursor
     * landed.
     *
     * `handled == 0` means the sweep is finished.
     */
    suspend fun classifyBackfillBatch(after: BackfillCursor, limit: Int): BackfillOutcome =
        withContext(Dispatchers.IO) {
            val bounded = limit.coerceIn(MIN_BATCH, MAX_BATCH)
            val batch = try {
                messageDao.unclassifiedBatch(
                    afterDate = after.date,
                    afterSource = after.source,
                    afterProviderId = after.providerId,
                    limit = bounded
                )
            } catch (error: Throwable) {
                DiagnosticLog.event("CATEGORY", "backfill-read-failed")
                return@withContext BackfillOutcome(cursor = after, handled = 0, finished = false)
            }
            if (batch.isEmpty()) {
                return@withContext BackfillOutcome(cursor = after, handled = 0, finished = true)
            }
            classifyBatch(batch)
            val last = batch.last()
            // The cursor is the LAST ROW OF THE BATCH, so the next query
            // continues strictly after it. Persisting it is what makes the sweep
            // resumable across process death.
            BackfillOutcome(
                cursor = BackfillCursor(
                    date = last.date,
                    source = last.source,
                    providerId = last.providerId
                ),
                handled = batch.size,
                finished = batch.size < bounded
            )
        }

    /** Re-derive ONE conversation's projection from its own classified rows. */
    suspend fun refreshProjection(threadId: Long) {
        if (threadId <= 0L) return
        withContext(Dispatchers.IO) {
            try {
                db.withTransaction { projectionFor(threadId) }
            } catch (error: Throwable) {
                DiagnosticLog.event("CATEGORY", "projection-failed thread=$threadId")
            }
        }
    }

    /**
     * Drop the classification of a message the provider has PROVEN gone, then
     * re-derive its conversation projection.
     */
    suspend fun deleteForMessage(source: String, providerId: Long, threadId: Long) {
        runCatching {
            withContext(Dispatchers.IO) {
                db.withTransaction {
                    messageDao.delete(source, providerId)
                    if (threadId > 0L) projectionFor(threadId)
                }
            }
        }
    }

    /** Explicit orphan cleanup for both classification tables (no FK CASCADE). */
    suspend fun pruneOrphans() {
        runCatching {
            withContext(Dispatchers.IO) {
                messageDao.deleteOrphans()
                val live = messageDao.distinctThreadIds().toHashSet()
                for (row in conversationDao.all()) {
                    if (row.threadId !in live) conversationDao.delete(row.threadId)
                }
            }
        }
    }

    // ── Internals (must run inside a transaction) ───────────────────────────

    /**
     * Upsert the conversation projection for [threadId].
     *
     * The newest classified message decides, falling back to the thread
     * histogram and finally to UNKNOWN — so a thread whose messages all ended up
     * UNKNOWN keeps an explicit UNKNOWN row instead of a stale category.
     *
     * The stored category is ALWAYS the automatic one; the user override is
     * applied at read time.
     */
    private suspend fun projectionFor(threadId: Long) {
        if (threadId <= 0L) return
        val newest = messageDao.newestForThread(threadId)
        val category = when {
            newest != null -> MessageCategory.from(newest.category)
            else -> messageDao.categoryCountsForThread(threadId).firstOrNull()?.let {
                MessageCategory.from(it.category)
            } ?: MessageCategory.UNKNOWN
        }
        conversationDao.upsert(
            ConversationClassificationEntity(
                threadId = threadId,
                category = category.name,
                confidence = newest?.confidence ?: 0f,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * The OTP cleanup deadline for one row, preserving a deadline that was
     * already scheduled so a re-classification never resets the clock.
     */
    private fun eligibleAtFor(
        isOtp: Boolean,
        messageType: Int,
        dateMs: Long,
        previousEligibleAt: Long
    ): Long {
        if (!isOtp) return 0L
        if (previousEligibleAt > 0L) return previousEligibleAt
        return otpDeleteEligibleAt(
            isOtp = true,
            messageType = messageType,
            dateMs = dateMs,
            retentionMillis = otpRetentionMillis()
        )
    }

    private suspend fun readCategoryOverride(threadId: Long): MessageCategory? =
        preferenceDao.get(threadId)?.categoryOverride?.let { MessageCategory.from(it) }

    companion object {
        /** Value meaning "the user has not enabled OTP cleanup". */
        const val OTP_RETENTION_DISABLED = 0L

        /** Backfill batch bounds required by the v3.4.0 spec (200-500 rows). */
        const val MIN_BATCH = 200
        const val MAX_BATCH = 500
        const val DEFAULT_BATCH = 400

        /** `Telephony.Sms.MESSAGE_TYPE_INBOX` without importing Android Telephony. */
        const val INBOX_MESSAGE_TYPE = 1

        /**
         * The OTP cleanup deadline for ONE message, or 0 when there is none.
         *
         * Pure and side-effect free, so it unit-tests on the JVM and the OTP
         * cleanup scheduler can compute eligibility without the classifier:
         *
         *  - a non-OTP is never eligible;
         *  - an OUTGOING message is never auto-removed (a user's own sent
         *    message is not "an OTP that arrived");
         *  - with retention disabled (`<= 0`) nothing is eligible — the v3.4.0
         *    default is OFF;
         *  - a row with no usable date is never eligible.
         */
        fun otpDeleteEligibleAt(
            isOtp: Boolean,
            messageType: Int,
            dateMs: Long,
            retentionMillis: Long
        ): Long {
            if (!isOtp) return 0L
            if (messageType != INBOX_MESSAGE_TYPE) return 0L
            if (retentionMillis <= 0L) return 0L
            return if (dateMs <= 0L) 0L else dateMs + retentionMillis
        }

        /** Convenience for a message handed in from the sync coordinator. */
        fun MessageEntity.toForClassification(): MessageForClassification =
            MessageForClassification(
                source = source,
                providerId = providerId,
                threadId = threadId,
                body = body,
                date = date,
                rawAddress = rawAddress.ifBlank { normalizedAddress },
                messageType = type
            )
    }
}

/**
 * Keyset cursor of the classification backfill.
 *
 * Mirrors the canonical `(date, source, providerId)` order of
 * `MessageClassificationDao.unclassifiedBatch`; [START] begins a fresh sweep.
 */
data class BackfillCursor(
    val date: Long,
    val source: String,
    val providerId: Long
) {
    companion object {
        val START = BackfillCursor(Long.MAX_VALUE, "", Long.MAX_VALUE)
    }
}

/** Result of one backfill batch: where the cursor moved, and whether to continue. */
data class BackfillOutcome(
    val cursor: BackfillCursor,
    val handled: Int,
    val finished: Boolean
)
