package com.autonomousone.messages.repository

import android.content.Context
import com.autonomousone.messages.data.ExistingOtpCleanupCandidate
import com.autonomousone.messages.data.MessageCategory
import com.autonomousone.messages.data.MessageClassificationEntity
import com.autonomousone.messages.data.MessageKey
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.messaging.OtpCleanupScheduler
import com.autonomousone.messages.messaging.OtpDetector
import com.autonomousone.messages.messaging.OtpRetentionPolicy
import com.autonomousone.messages.messaging.OtpRetentionPreferences
import com.autonomousone.messages.messaging.OtpRetentionSettings
import com.autonomousone.messages.utils.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * GLOBAL OTP retention (v3.4.0 FEATURE 14) — the decision engine.
 *
 * WHAT THIS CLASS OWNS
 * --------------------
 *  - every eligibility decision, through the pure [OtpRetentionPolicy];
 *  - the bounded DUE run that MOVES messages to Trash (never a permanent delete —
 *    Trash retention owns that later and the user can still restore);
 *  - the triage pass that unschedules messages which became protected, and heals
 *    wrong-clock enrollments;
 *  - the two EXPLICIT user actions: "Keep this message" (opt out) and "Apply to
 *    existing OTP messages" (the only history sweep).
 *
 * WHAT IT DELIBERATELY DOES NOT OWN
 * ---------------------------------
 *  - OTP detection: [OtpDetector] is the single detector, consulted by the opt-in
 *    sweep. Newly arriving OTPs are classified by the Smart Categories ingest
 *    path, which already persists `otpDeleteEligibleAt` from this workstream's
 *    retention provider.
 *  - Trash semantics: the markTrashed shape lives in [OtpRetentionStore]'s Room
 *    implementation, which delegates to the existing field-scoped DAO writer, so
 *    cleanup cannot invent its own trash shape and never loses a star.
 *  - Any permanent delete. There is none in this file, by design.
 *
 * PRIVACY: nothing here logs a body, an OTP code or a phone number. Every
 * diagnostic is a count, a deadline or an eligibility reason ([DiagnosticLog]
 * category `OTP_RETENTION`).
 *
 * Dependencies are narrow ports ([OtpRetentionStore], [OtpExistingSweepSource],
 * [OtpReschedule]), so the whole engine unit-tests on the JVM with small fakes —
 * and stays testable while other workstreams keep editing `UxDaos.kt`.
 */
class OtpRetentionService internal constructor(
    private val store: OtpRetentionStore,
    private val preferences: OtpRetentionSettings,
    private val sweep: OtpExistingSweepSource,
    private val reschedule: OtpReschedule,
    /** Injectable clock — tests must never depend on the wall clock. */
    private val clock: () -> Long = System::currentTimeMillis
) {

    // ── Reads (diagnostics + scheduling) ────────────────────────────────────

    /**
     * The next deadline the single worker should fire at, or null when nothing is
     * enrolled. Read straight from the index; no Kotlin-side filtering.
     */
    suspend fun nextEligibleAt(): Long? = store.earliestEligibleAt()

    /** How many messages are enrolled right now (Settings + diagnostics). */
    suspend fun enrolledCount(): Int = store.enrolledCount()

    /** Room nudge whenever an enrolled deadline changes. */
    fun observeEarliestEligibleAt(): Flow<Long> = store.observeEarliestEligibleAt()

    /** The single scheduling touchpoint for a user action. */
    fun requestReschedule() {
        reschedule.request()
    }

    // ── The due run ─────────────────────────────────────────────────────────

    /** What one worker run did. Counts and deadlines only — never content. */
    data class DueCleanupOutcome(
        val scanned: Int,
        val trashed: Int,
        val deferred: Int,
        val nextEligibleAt: Long?
    )

    /**
     * ONE bounded pass over the DUE set.
     *
     *  1. read at most `limit` rows with `otpDeleteEligibleAt <= now`
     *     (index-backed on `otpDeleteEligibleAt`, LIMIT-bounded);
     *  2. re-validate EVERY row through the policy — the durable deadline is a
     *     hint, the policy is the authority, because a message can be starred,
     *     keep-flagged or trashed AFTER it was enrolled;
     *  3. rows that are no longer eligible are UNSCHEDULED instead of trashed, so
     *     the loop can never spin on them;
     *  4. the survivors are moved to Trash in ONE transaction by the store;
     *  5. report the persisted next deadline so the caller reschedules.
     */
    suspend fun runDueCleanup(limit: Int): DueCleanupOutcome = withContext(Dispatchers.IO) {
        val now = clock()
        val bounded = limit.coerceAtLeast(1)

        if (!preferences.enabled) {
            // OFF means off: nothing is read, nothing is moved, and the caller
            // cancels the unique work, so nothing can run.
            DiagnosticLog.event("OTP_RETENTION", "run skipped reason=retention_disabled")
            return@withContext DueCleanupOutcome(0, 0, 0, null)
        }

        val due = try {
            store.dueForCleanup(now, bounded)
        } catch (error: Throwable) {
            DiagnosticLog.event("OTP_RETENTION", "due-read-failed", error)
            return@withContext DueCleanupOutcome(0, 0, 0, store.earliestEligibleAt())
        }
        if (due.isEmpty()) {
            return@withContext DueCleanupOutcome(0, 0, 0, store.earliestEligibleAt())
        }

        val survivors = ArrayList<MessageClassificationEntity>(due.size)
        var deferred = 0
        for (row in due) {
            val plan = planForRow(row, now)
            if (plan.eligible) {
                survivors += row
            } else {
                deferred++
                DiagnosticLog.event(
                    "OTP_RETENTION",
                    "deferred reason=${plan.reason.name} source=${row.source}"
                )
                unschedule(row, now)
            }
        }

        var trashed = 0
        if (survivors.isNotEmpty()) {
            try {
                store.moveToTrash(
                    rows = survivors,
                    now = now,
                    purgeAt = now + TrashRepository.RETENTION_MILLIS
                )
                trashed = survivors.size
            } catch (error: Throwable) {
                // The store's move is atomic: nothing was committed. Every deadline
                // is kept so the next run retries instead of losing the cleanup.
                DiagnosticLog.event("OTP_RETENTION", "trash-failed count=${survivors.size}", error)
            }
        }

        DiagnosticLog.event(
            "OTP_RETENTION",
            "run scanned=${due.size} trashed=$trashed deferred=$deferred"
        )
        DueCleanupOutcome(
            scanned = due.size,
            trashed = trashed,
            deferred = deferred,
            nextEligibleAt = store.earliestEligibleAt()
        )
    }

    /**
     * Triage: bring persisted deadlines back in line with the policy.
     *
     *  - an enrolled message that became PROTECTED (starred, keep-flagged,
     *    trashed) or is no longer an eligible OTP is UNSCHEDULED, so the single
     *    worker can never fire early for it;
     *  - an enrollment whose deadline is absurdly overdue (a wrong-clock
     *    enrollment) is RE-ANCHORED to now + retention — the healing half of
     *    `CLOCK_SKEW`;
     *  - with the setting OFF nothing is touched, so switching off never rewrites
     *    durable state.
     *
     * Bounded: one index-backed pass over the enrolled set, capped at [limit].
     */
    suspend fun reconcileEnrolments(limit: Int = TRIAGE_LIMIT): Int = withContext(Dispatchers.IO) {
        if (!preferences.enabled) return@withContext 0
        val now = clock()
        val enrolled = try {
            store.enrolledForTriage(limit)
        } catch (error: Throwable) {
            DiagnosticLog.event("OTP_RETENTION", "triage-read-failed", error)
            return@withContext 0
        }
        var changed = 0
        for (row in enrolled) {
            val plan = planForRow(row, now)
            when (plan.reason) {
                OtpRetentionPolicy.EligibilityReason.ELIGIBLE -> Unit
                OtpRetentionPolicy.EligibilityReason.CLOCK_SKEW -> {
                    // Re-anchor to NOW with the CURRENT retention: the stored
                    // deadline came from an unusable clock, so keeping it would
                    // mean "clean this the instant the user looks at it".
                    val healed = planFor(row) { state ->
                        OtpRetentionPolicy.plan(
                            state = state,
                            enabled = true,
                            retentionMillis = preferences.retentionMillis,
                            anchorMillis = now,
                            nowMillis = now
                        )
                    }
                    if (healed.eligible) {
                        store.setEligibleAt(row.source, row.providerId, healed.eligibleAt!!)
                        changed++
                    }
                }
                else -> {
                    unschedule(row, now)
                    changed++
                }
            }
        }
        if (changed > 0) {
            DiagnosticLog.event("OTP_RETENTION", "triage changed=$changed scanned=${enrolled.size}")
        }
        changed
    }

    // ── Explicit user actions ───────────────────────────────────────────────

    /**
     * "Keep this message" / "Use automatic cleanup".
     *
     * Keeping is a permanent opt-out for that message (starring protects it too).
     * Clearing the flag re-evaluates immediately and re-anchors retention to NOW —
     * never to the original arrival date, which would delete a message the user
     * deliberately kept for a month the instant they let go of it.
     */
    suspend fun setKeepFromOtpCleanup(
        key: MessageKey,
        threadId: Long,
        keep: Boolean
    ): OtpRetentionPolicy.Plan = withContext(Dispatchers.IO) {
        val now = clock()
        store.setKeep(
            source = key.source,
            providerId = key.providerId,
            threadId = threadId,
            keep = keep,
            now = now
        )
        val plan = refreshDeadline(key, threadId, now)
        reschedule.request()
        plan
    }

    /**
     * Star / unstar, from the single star action.
     *
     * PROTECTION IS THE POINT: a starred OTP is exempt, so starring must clear any
     * pending cleanup, and unstarring must make it eligible again. The star action
     * only has to call this method (see the workstream report for the exact hook).
     */
    suspend fun onStarChanged(
        key: MessageKey,
        threadId: Long,
        starred: Boolean
    ): OtpRetentionPolicy.Plan = withContext(Dispatchers.IO) {
        val now = clock()
        store.setStarred(
            source = key.source,
            providerId = key.providerId,
            threadId = threadId,
            starred = starred,
            starredAt = if (starred) now else 0L,
            now = now
        )
        val plan = refreshDeadline(key, threadId, now)
        reschedule.request()
        plan
    }

    /** Restored from Trash ⇒ cleanable again, with retention re-anchored to now. */
    suspend fun onRestoredFromTrash(key: MessageKey, threadId: Long): OtpRetentionPolicy.Plan =
        withContext(Dispatchers.IO) {
            val now = clock()
            val plan = refreshDeadline(key, threadId, now)
            reschedule.request()
            plan
        }

    /**
     * The EXPLICIT "Apply to existing OTP messages" action.
     *
     * Never triggered by the toggle. It re-detects with the single [OtpDetector]
     * and enrolls only rows satisfying the FULL policy — incoming,
     * high-confidence OTP, not starred, not kept, not trashed (the sweep query
     * already excludes the protected rows; the policy re-checks anyway).
     *
     * @param maxBatches hard stop for one tap; the remainder is reported
     *        ([SweepOutcome.finished] == false) so the UI offers to continue
     *        instead of silently dropping work.
     */
    suspend fun applyToExistingOtpMessages(maxBatches: Int = DEFAULT_SWEEP_BATCHES): SweepOutcome =
        withContext(Dispatchers.IO) {
            if (!preferences.enabled) {
                return@withContext SweepOutcome(0, 0, 0, finished = true, enabled = false)
            }
            val now = clock()
            val retention = preferences.retentionMillis
            var afterDate = Long.MAX_VALUE
            var afterProviderId = Long.MAX_VALUE
            var scanned = 0
            var enrolled = 0
            var skipped = 0
            var finished = false

            for (batch in 0 until maxBatches.coerceAtLeast(1)) {
                val page = try {
                    sweep.page(afterDate, afterProviderId, SWEEP_BATCH)
                } catch (error: Throwable) {
                    DiagnosticLog.event("OTP_RETENTION", "sweep-read-failed", error)
                    break
                }
                if (page.isEmpty()) {
                    finished = true
                    break
                }
                for (row in page) {
                    scanned++
                    val detection = OtpDetector.detect(sender = row.rawAddress, body = row.body)
                    val plan = OtpRetentionPolicy.plan(
                        state = OtpRetentionPolicy.MessageState(
                            isOtp = detection != null,
                            confidence = detection?.confidence ?: row.confidence ?: 0f,
                            isIncoming = row.messageType == OtpRetentionPolicy.TYPE_INCOMING,
                            // The sweep query returns only unprotected rows; the
                            // policy still owns the rule, so these are stated
                            // explicitly rather than assumed.
                            starred = false,
                            keepFromOtpCleanup = false,
                            trashed = false
                        ),
                        enabled = true,
                        retentionMillis = retention,
                        // Existing messages count from NOW: the user just asked for
                        // them to be cleaned, so "already long expired" must not
                        // mean "vanish on the next tick".
                        anchorMillis = now,
                        nowMillis = now
                    )
                    if (plan.eligible && row.eligibleAt != null) {
                        store.setEligibleAt(row.source, row.providerId, plan.eligibleAt!!)
                        enrolled++
                    } else {
                        skipped++
                        // Release a stale enrollment, and give a never-classified
                        // row a non-OTP placeholder so the bounded sweep does not
                        // re-detect the same ordinary message on every tap.
                        if (row.eligibleAt != null && row.eligibleAt > 0L) {
                            store.setEligibleAt(row.source, row.providerId, 0L)
                        }
                        persistNonOtpPlaceholder(row, detection?.confidence ?: 0f, now)
                    }
                }
                val last = page.last()
                afterDate = last.date
                afterProviderId = last.providerId
                if (page.size < SWEEP_BATCH) {
                    finished = true
                    break
                }
            }

            DiagnosticLog.event(
                "OTP_RETENTION",
                "sweep scanned=$scanned enrolled=$enrolled skipped=$skipped finished=$finished"
            )
            reschedule.request()
            SweepOutcome(scanned, enrolled, skipped, finished, enabled = true)
        }

    // ── Internals ───────────────────────────────────────────────────────────

    /** The policy decision for one PERSISTED classification row. */
    private suspend fun planForRow(
        row: MessageClassificationEntity,
        now: Long
    ): OtpRetentionPolicy.Plan = planFor(row) { state ->
        OtpRetentionPolicy.plan(
            state = state,
            enabled = preferences.enabled,
            retentionMillis = preferences.retentionMillis,
            anchorMillis = row.otpDeleteEligibleAt,
            nowMillis = now
        )
    }

    /** Shared plumbing: read direction + user state, then apply [policy]. */
    private suspend fun planFor(
        row: MessageClassificationEntity,
        policy: (OtpRetentionPolicy.MessageState) -> OtpRetentionPolicy.Plan
    ): OtpRetentionPolicy.Plan {
        val userStateRow = store.userStateOf(row.source, row.providerId)
        val messageType = store.messageTypeOf(row.source, row.providerId)
        return policy(OtpRetentionPolicy.MessageState.of(row, userStateRow, messageType))
    }

    /**
     * Recomputes ONE message's deadline from the CURRENT policy and user state.
     *
     * An unclassified message has no deadline to move, so the call is a no-op and
     * returns NOT_OTP; the checkpointed backfill classifies it and the next user
     * action (or the next arriving OTP) re-evaluates it.
     */
    private suspend fun refreshDeadline(
        key: MessageKey,
        threadId: Long,
        now: Long
    ): OtpRetentionPolicy.Plan {
        val row = store.classificationOf(key.source, key.providerId)
            ?: return OtpRetentionPolicy.Plan.refuse(OtpRetentionPolicy.EligibilityReason.NOT_OTP)
        val userStateRow = store.userStateOf(key.source, key.providerId)
        val messageType = store.messageTypeOf(key.source, key.providerId)
        val plan = OtpRetentionPolicy.plan(
            state = OtpRetentionPolicy.MessageState.of(row, userStateRow, messageType),
            enabled = preferences.enabled,
            retentionMillis = preferences.retentionMillis,
            // Re-anchor to NOW: retention restarts when a message becomes eligible
            // again, so un-starring an old OTP cannot delete it instantly.
            anchorMillis = now,
            nowMillis = now
        )
        if (OtpRetentionPolicy.needsReschedule(row.otpDeleteEligibleAt, plan)) {
            if (plan.eligible) {
                store.setEligibleAt(key.source, key.providerId, plan.eligibleAt!!)
            } else {
                store.clearEligibleAt(
                    source = key.source,
                    providerId = key.providerId,
                    threadId = threadId,
                    category = row.category,
                    now = now
                )
            }
        }
        return plan
    }

    /** Clears the deadline of a row the policy no longer accepts. */
    private suspend fun unschedule(row: MessageClassificationEntity, now: Long) {
        runCatching {
            store.clearEligibleAt(
                source = row.source,
                providerId = row.providerId,
                threadId = row.threadId,
                category = row.category,
                now = now
            )
        }.onFailure { DiagnosticLog.event("OTP_RETENTION", "unschedule-failed", it) }
    }

    /**
     * Persists a NON-OTP verdict for a row that has never been classified.
     *
     * This is not a second classifier: it records only "not an OTP", so the
     * bounded sweep stops re-detecting the same ordinary message on every tap. A
     * previously classified row is left completely alone — its category,
     * confidence and `isOtp` belong to the Smart Categories owner.
     */
    private suspend fun persistNonOtpPlaceholder(
        row: ExistingOtpCleanupCandidate,
        confidence: Float,
        now: Long
    ) {
        if (row.eligibleAt != null) return
        runCatching {
            store.clearEligibleAt(
                source = row.source,
                providerId = row.providerId,
                threadId = row.threadId,
                category = MessageCategory.UNKNOWN.name,
                now = now
            )
        }.onFailure {
            DiagnosticLog.event("OTP_RETENTION", "placeholder-write-failed source=${row.source}")
        }
    }

    /** What one opt-in sweep pass did. Counts only — never content. */
    data class SweepOutcome(
        val scanned: Int,
        val enrolled: Int,
        val skipped: Int,
        val finished: Boolean,
        val enabled: Boolean
    )

    companion object {
        /** Rows per opt-in sweep batch — bounded, keyset-paged. */
        const val SWEEP_BATCH = 300

        /** Batches per tap: 6000 rows, then the UI offers to continue. */
        const val DEFAULT_SWEEP_BATCHES = 20

        /** Enrolled rows re-validated per triage pass. */
        const val TRIAGE_LIMIT = 500

        @Volatile
        private var instance: OtpRetentionService? = null

        /**
         * Process singleton — one store, one preference reader.
         *
         * Creating it also starts the deadline observer (once per process), so
         * there is exactly ONE place that turns Room invalidations on
         * `message_classification` into a reschedule: the first use of this
         * service, whichever entry point it comes from (worker, settings, ingest).
         * Without that, a newly enrolled OTP would carry a deadline nothing had
         * scheduled a worker for.
         *
         * The field is assigned before the observer starts, so a concurrent caller
         * can never observe a half-built instance.
         */
        fun get(context: Context): OtpRetentionService {
            val appContext = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: build(appContext).also {
                    instance = it
                    OtpCleanupScheduler.observeDeadlineChanges(appContext)
                }
            }
        }

        private fun build(context: Context): OtpRetentionService = OtpRetentionService(
            store = RoomOtpRetentionStore(context),
            preferences = OtpRetentionPreferences(context),
            sweep = OtpExistingSweepSource { afterDate, afterProviderId, limit ->
                MessagesDatabase.get(context.applicationContext)
                    .messageClassificationDao()
                    .existingOtpCleanupCandidates(afterDate, afterProviderId, limit)
            },
            reschedule = OtpReschedule { OtpCleanupScheduler.reschedule(context) }
        )
    }
}
