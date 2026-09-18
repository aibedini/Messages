package com.autonomousone.messages.data

import android.content.Context
import android.provider.Telephony
import android.util.Log
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.diagnostics.DiagnosticsBreadcrumbs
import com.autonomousone.messages.diagnostics.PerfMetric
import com.autonomousone.messages.diagnostics.PerfTelemetry
import com.autonomousone.messages.diagnostics.TraceSections
import com.autonomousone.messages.messaging.VisibleConversationTracker
import com.autonomousone.messages.repository.ContactRepository
import com.autonomousone.messages.repository.SmsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import androidx.room.withTransaction
import java.util.concurrent.atomic.AtomicBoolean

internal fun cloudMessageDirection(type: Int): String? = when (type) {
    Telephony.Sms.MESSAGE_TYPE_INBOX -> "in"
    Telephony.Sms.MESSAGE_TYPE_SENT,
    Telephony.Sms.MESSAGE_TYPE_OUTBOX,
    Telephony.Sms.MESSAGE_TYPE_FAILED,
    Telephony.Sms.MESSAGE_TYPE_QUEUED -> "out"
    else -> null
}

enum class DiscoveryMode {
    REALTIME_EXACT,
    REALTIME_RECONCILE,
    STARTUP_DELTA,
    HISTORY_BACKFILL,
    INTEGRITY_AUDIT
}

private enum class ProviderTransition {
    NEW,
    CONTENT_CHANGED,
    STATUS_CHANGED,
    READ_CHANGED,
    UNCHANGED
}

/**
 * The SINGLE writer into Room. Two completely separate channels:
 *
 *  1. **Mutations** (exact, never conflated): every insert, delete, status
 *     change, and read-mark reaches Room exactly once. This is the realtime
 *     fast path — O(1) per message.
 *
 *  2. **Reconcile requests** (CONFLATED): N queued nudges collapse into
 *     exactly ONE bounded repair pass. Used for startup, crash recovery,
 *     and fallback when the observer cannot provide a specific URI/id.
 *
 * Hot path for an incoming SMS:
 *   Provider INSERT → providerId=348201, threadId=552
 *   → mutate(Upsert("sms", sms))
 *   → Room transaction { UPSERT message + UPDATE conversation }
 *   → Room Flow → UI
 *
 * No full scan. No rebuildConversations(). No countUnread(10K rows).
 */
class TelephonySyncCoordinator internal constructor(context: Context, private val databaseOverride: MessagesDatabase? = null) {


    private val appContext = context.applicationContext
    private val smsRepository = SmsRepository(appContext)
    private val messagingPrefs =
        com.autonomousone.messages.messaging.MessagingPreferences(appContext)
    private val db get() = databaseOverride ?: MessagesDatabase.get(appContext)

    /**
     * ADR-006 SyncEligibility gate (kill-switch + future firewall hook).
     * Default true = current behaviour (SYNC). The SensitiveMessageFirewall
     * PR replaces this boolean with the per-message classifier decision;
     * enqueueCloudEvent remains the single choke point either way.
     */
    @Volatile
    internal var syncAllowed: Boolean = true
        set(value) {
            if (field != value) {
                Log.i(TAG, "sync_allowed_changed previous=$field new=$value")
            }
            field = value
        }

    // ── Dual channels ──────────────────────────────────────────────────────

    /**
     * Exact mutations: sequential processing, NEVER drops events.
     *
     * A bounded channel + trySend could silently drop an event under a burst
     * (e.g. 100 SMS arriving in one second) — the row would then stay stale in
     * the shadow until the next reconcile. UNLIMITED trades bounded memory for
     * exactly-once delivery; each item is a small immutable value and the
     * consumer drains continuously, so the queue stays near-empty.
     */
    private val mutations = Channel<MutationWork>(capacity = Channel.UNLIMITED)

    /**
     * One unit of exact mutation work.
     *
     * [completion] is non-null only for a caller that must know the mutation was
     * DURABLY COMMITTED before it may consider its own work finished. The durable
     * exact-repair queue is exactly such a caller. A normal realtime caller passes
     * null and never waits.
     */
    private data class MutationWork(
        val mutation: MessageMutation,
        val completion: CompletableDeferred<Result<Unit>>? = null
    )

    /**
     * Reconcile NUEDGE channel: CONFLATED is correct here because it carries no
     * information — it is only a wakeup. The work itself lives in
     * [pendingReconciles], which merges by semantic union instead of last-value
     * wins (a conflated channel of typed requests silently dropped ForThread ids
     * and even a startup FullSync).
     */
    private val reconcileNudge = Channel<Unit>(Channel.CONFLATED)

    /**
     * In-process reliable reconcile accumulator.
     *
     * NOT durable: it is volatile and does not survive Android process death.
     * Process-death recovery depends on durable Room sync_state, not on this.
     */
    private val pendingReconciles = PendingReconciles()

    /**
     * FIX 2/3: one-shot cloud-history backfill requests — fired after a web
     * device is approved and once at startup for an enrolled-but-never-linked
     * install. CONFLATED (N approvals collapse into 1 pass — the runs are
     * idempotent: per-event cursors + eventUuid dedupe).
     */
    private val cloudBackfills = Channel<String>(Channel.CONFLATED)

    private val started = AtomicBoolean(false)

    /** P0: exactly ONE gateway-bootstrap FullSync per process lifecycle. */
    private val startupReconcileRequested = AtomicBoolean(false)

    /**
     * P0: single-flight boundary for reconciliation. syncNow() (manual) and the
     * CONFLATED reconciles consumer must never run applyReconcile concurrently
     * (that caused race windows and duplicate projection rebuilds). Ordinary
     * O(1) message mutations stay on their own channel and are NOT serialized
     * behind a full history crawl.
     */
    private val reconcileMutex = Mutex()

    /** Long-running work scope (mutations, reconcile, detached backfill). */
    private val syncScope = CoroutineScope(Dispatchers.IO)

    companion object {
        const val FIRST_BATCH = 500
        const val BACKFILL_BATCH = 500

        /**
         * Bounded provider window for a single-thread repair.
         *
         * A repair must never materialize a 100k-message thread, and it must
         * never delete Room rows outside the window it actually covered.
         */
        const val THREAD_REPAIR_LIMIT = 200

        /** Conversations written per transaction by a full projection rebuild. */
        const val REBUILD_CHUNK = 500

        /**
         * Marker for "one provider source of this thread could not be read".
         *
         * Thrown so the reconcile consumer NACKs instead of ACKing; the message
         * prefix is stable so a test can assert on it without a typed exception
         * leaking across layers.
         */
        const val PARTIAL_THREAD_REPAIR = "PARTIAL_THREAD_REPAIR"
        const val TAG = "SYNC_COORD"

        @Volatile
        private var instance: TelephonySyncCoordinator? = null

        fun get(context: Context): TelephonySyncCoordinator =
            instance ?: synchronized(this) {
                instance ?: TelephonySyncCoordinator(context).also { instance = it }
            }
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /** Queue an exact mutation (insert/update/delete/status). O(1), never blocks. */
    fun mutate(mutation: MessageMutation) {
        ensureLoop()
        enqueue(MutationWork(mutation))
    }

    /**
     * Queue an exact mutation and WAIT until Room (and the durable outbox it
     * commits with) has actually accepted it.
     *
     * Why this exists: the durable exact-repair queue may only be ACKed once the
     * mutation it produced is DURABLE. Putting a mutation into an in-memory
     * channel is not durability - if the process died between the ACK and the
     * consumer running, the queue row would be gone and the repair lost, which is
     * precisely the failure the durable queue was built to prevent.
     *
     * @return true only when the mutation's Room transaction committed.
     */
    suspend fun mutateAndAwaitCommit(mutation: MessageMutation): Boolean {
        ensureLoop()
        val completion = CompletableDeferred<Result<Unit>>()
        mutations.send(MutationWork(mutation, completion))
        return completion.await().isSuccess
    }

    private fun enqueue(work: MutationWork) {
        val result = mutations.trySend(work)
        if (result.isFailure) {
            // Never silently drop correctness work on a closed channel.
            Log.e(TAG, "mutation channel rejected work: " + work.mutation)
            work.completion?.complete(
                Result.failure(IllegalStateException("mutation channel is closed"))
            )
        }
    }

    /** Exact O(1) provider-row nudge used immediately after an outgoing insert. */
    fun providerRowChanged(
        source: String,
        providerId: Long,
        originCommandId: String? = null,
        clientMessageId: String? = null,
    ) {
        ensureLoop()
        syncScope.launch {
            // P0-10: the identity is KNOWN here, so a transient read failure must
            // not simply lose the update. It goes to the durable repair queue and
            // converges on the queue's own timer, with no provider event needed.
            when (val read = readExactMessageStrict(source, providerId)) {
                is ProviderRead.Success -> {
                    val row = read.value
                    if (row != null) {
                        enqueue(
                            MutationWork(
                                MessageMutation.Upsert(source, row, originCommandId, clientMessageId)
                            )
                        )
                    } else {
                        // P0-2: an EMPTY exact read here is NOT a delete and must not
                        // drop the identity.
                        //
                        // This differs from a mature exact-observer delete on
                        // purpose: providerRowChanged is called immediately after an
                        // outgoing insert or a status callback, so the row is often
                        // not query-visible yet (provider transaction still open, OEM
                        // provider returning an empty page). Treating that instant as
                        // final would lose the update entirely. The durable queue
                        // re-reads and decides the stable state.
                        Log.i(TAG, "providerRowChanged row not visible yet " + source + ":" +
                            providerId + " -> durable EXPECT_EXISTS repair")
                        // EXPECT_EXISTS, not RECONCILE_EXACT: this path runs right
                        // after an outgoing insert, so absence must never delete.
                        ChangeRouter.enqueueExactRepair(
                            appContext, source, providerId, ProviderRepairIntent.EXPECT_EXISTS
                        )
                    }
                }
                is ProviderRead.Failure -> {
                    Log.w(TAG, "providerRowChanged read failed " + source + ":" + providerId +
                        " reason=" + read.reason + " -> durable EXPECT_EXISTS repair")
                    ChangeRouter.enqueueExactRepair(
                        appContext, source, providerId, ProviderRepairIntent.EXPECT_EXISTS
                    )
                }
            }
        }
    }

    /**
     * Strict counterpart of [readExactMessage].
     *
     * Success(null) is a PROOF OF ABSENCE; Failure is UNKNOWN. Used wherever the
     * outcome may influence a durable decision; the forgiving variant stays only
     * for non-destructive callers.
     */
    private suspend fun readExactMessageStrict(
        source: String,
        providerId: Long
    ): ProviderRead<Sms?> = withContext(Dispatchers.IO) {
        when (source) {
            MessageEntity.SOURCE_SMS -> smsRepository.readSmsExactStrict(providerId)
            MessageEntity.SOURCE_MMS -> smsRepository.readMmsMaterializedStrict(providerId)
            else -> ProviderRead.Failure(ProviderRead.Reason.UNEXPECTED)
        }
    }

    /** Queue reconcile work. Merged semantically — never last-value-wins. */
    fun reconcile(request: ReconcileRequest = ReconcileRequest.FullSync) {
        ensureLoop()
        pendingReconciles.add(request)
        reconcileNudge.trySend(Unit)
    }

    /** Start the shadow-sync loop without forcing a full reconcile.
     *  Called by ConnectionSupervisor once the gateway is online. */
    fun ensureLoopRunning() = ensureLoop()

    /**
     * P0 gateway bootstrap: starts the loop and queues exactly ONE
     * ReconcileRequest.FullSync per process lifecycle. ConnectionSupervisor
     * calls startSync() on every periodic reconcile — the AtomicBoolean
     * collapses all of them into that single initial mirror. A fresh process
     * gets a fresh FullSync so provider changes that happened while the
     * process was dead are re-mirrored and re-committed as durable events.
     */
    fun startGatewaySync() {
        ensureLoop()
        if (startupReconcileRequested.compareAndSet(false, true)) {
            syncScope.launch {
                // PHASE 6.2 / 6.3: the durable initial window decides. A process
                // RESTART is not a reason to re-crawl: the watermarks in
                // sync_state survived, so only the delta since them can be
                // missing. FullSync stays reachable for genuine bootstrap and
                // explicit recovery (ReconcileRequest.FullSync callers), but it
                // is no longer the automatic answer to "the app was reopened".
                val stateDao = db.syncStateDao()
                // P0-7: READY is a PER-SOURCE fact and BOTH sources must be ready.
                //
                // This used to be an OR. With SMS ready and MMS not, a restart
                // chose TailDelta mode - and TailDelta only covers rows newer than
                // the newest watermark, so the missing MMS initial window could
                // never be built. A half-bootstrapped install must never look READY.
                val smsState = try {
                    stateDao.forSource(MessageEntity.SOURCE_SMS)
                } catch (e: Exception) {
                    Log.e(TAG, "startup state read failed for sms", e)
                    null
                }
                val mmsState = try {
                    stateDao.forSource(MessageEntity.SOURCE_MMS)
                } catch (e: Exception) {
                    Log.e(TAG, "startup state read failed for mms", e)
                    null
                }
                val smsReady = smsState?.initialWindowReady == true
                val mmsReady = mmsState?.initialWindowReady == true

                if (smsReady && mmsReady) {
                    Log.i(TAG, "SYNC_BOOTSTRAP_SKIPPED reason=both_sources_ready mode=TAIL_DELTA")
                    reconcile(ReconcileRequest.TailDelta)

                    // P0-8: a restart must RESUME an interrupted history crawl.
                    //
                    // TailDelta covers rows NEWER than the newest watermark; it
                    // never schedules the older crawl. Nothing else did either, so
                    // a process death mid-backfill left historyBackfillComplete at
                    // false forever and the app quietly stopped converging on old
                    // history. This is deliberately a STARTUP decision: an ordinary
                    // provider TailDelta event must not start a history crawl.
                    val smsComplete = smsState?.historyBackfillComplete == true
                    val mmsComplete = mmsState?.historyBackfillComplete == true
                    if (!smsComplete || !mmsComplete) {
                        Log.i(TAG, "BACKFILL_RESUME scheduled smsComplete=" + smsComplete +
                            " mmsComplete=" + mmsComplete)
                        scheduleBackfill()
                    }
                } else {
                    Log.i(TAG, "SYNC_BOOTSTRAP_STARTED sources=sms,mms firstBatch=$FIRST_BATCH " +
                        "smsReady=" + smsReady + " mmsReady=" + mmsReady)
                    reconcile(ReconcileRequest.FullSync)
                }
                // Exact work that was in flight when the process died is recovered
                // by the queue's own lease reclaim; nudging it here just makes the
                // first retry immediate instead of waiting for the first timer tick.
                ChangeRouter.retryPendingExactReads(appContext)
                scheduleIntegrityAudit()
            }
        }
    }

    /** Backward compat during migration. */
    fun requestSync() = reconcile(ReconcileRequest.FullSync)

    /**
     * FIX 2 (auto-backfill on first real approve): re-runs the durable
     * encrypted cloud-history production for BOTH sources
     * (`encrypted-history-v3:<source>` cursors) and then publishes the v3
     * history key or bounded capability keyring. Idempotent and cheap when
     * there is nothing new: the cursor page is empty and inserts deduplicate.
     */
    fun requestCloudBackfillForLinkedDevice(deviceId: String) {
        ensureLoop()
        cloudBackfills.trySend(deviceId)
    }

    /** Re-scan history after a browser gains a sensitive-message capability. */
    suspend fun requestSensitiveHistoryReplayForLinkedDevice(deviceId: String) {
        db.withTransaction {
            db.cloudHistoryCheckpointDao().all().forEach { checkpoint ->
                db.cloudHistoryCheckpointDao().upsert(checkpoint.copy(
                    producerCursorDate = Long.MAX_VALUE,
                    producerCursorProviderId = Long.MAX_VALUE,
                    sourceExhausted = false,
                    updatedAt = System.currentTimeMillis(),
                ))
            }
        }
        requestCloudBackfillForLinkedDevice(deviceId)
    }

    /** Suspends until one full sync cycle completes (serialized). */
    suspend fun syncNow() = runReconcile(ReconcileRequest.FullSync)

    /**
     * Claims and executes reconcile work until nothing is due.
     *
     * Every unit is ACKed or NACKed individually:
     *  - a failure never discards the work (it is requeued with backoff);
     *  - one poison thread cannot lose the rest of its claim;
     *  - a claimed tail is only consumed when the full sync that covered it
     *    actually SUCCEEDED.
     */
    /** Single retry timer; re-armed after every drain. Never the consumer itself. */
    private var retryTimer: kotlinx.coroutines.Job? = null

    /**
     * Arms (or re-arms) the wake-up timer for the earliest scheduled retry.
     *
     * Cancelled and recomputed after every drain, so the timer always reflects
     * the current retry schedule. It only nudges the conflated channel; it never
     * executes work itself.
     */
    private fun armRetryTimer() {
        retryTimer?.cancel()
        retryTimer = null
        val waitMs = pendingReconciles.nextWakeUpInMs(System.currentTimeMillis())
        if (waitMs <= 0L) return
        retryTimer = syncScope.launch {
            kotlinx.coroutines.delay(waitMs)
            reconcileNudge.trySend(Unit)
        }
    }

    private suspend fun drainReconcileWork() {
        while (true) {
            val claim = pendingReconciles.claim(System.currentTimeMillis()) ?: break

            if (claim.fullSync) {
                var ok = false
                try {
                    DiagnosticsBreadcrumbs.setSyncOperation("full_sync")
                    runReconcile(ReconcileRequest.FullSync)
                    ok = true
                } catch (e: Exception) {
                    Log.e(TAG, "FullSync failed; requeued", e)
                }
                // Also acks the claimed tail — but only on success.
                pendingReconciles.ackFullSync(claim, ok, System.currentTimeMillis())
            } else if (claim.tailEpoch != null) {
                var ok = false
                val startedNanos = PerfTelemetry.mark()
                try {
                    // Bounded newest-window repair for an event with no usable
                    // identity. The trace section and the sync-operation
                    // breadcrumb exist so a stall report can say WHICH sync
                    // operation was running when the main thread stopped.
                    TraceSections.trace(TraceSections.TAIL_DELTA) {
                        DiagnosticsBreadcrumbs.setSyncOperation("tail_delta")
                        runReconcile(ReconcileRequest.TailDelta)
                    }
                    ok = true
                } catch (e: Exception) {
                    Log.e(TAG, "TailDelta failed; requeued", e)
                } finally {
                    // Duration of the repair itself, independent of whether it
                    // succeeded. No number is claimed unless samples exist.
                    PerfTelemetry.recordSince(PerfMetric.TAIL_DELTA, startedNanos)
                }
                pendingReconciles.ackTail(claim, ok, System.currentTimeMillis())
            }

            claim.threads.forEach { claimed ->
                var ok = false
                val startedNanos = PerfTelemetry.mark()
                try {
                    TraceSections.trace(TraceSections.FOR_THREAD) {
                        DiagnosticsBreadcrumbs.setSyncOperation("for_thread")
                        runReconcile(ReconcileRequest.ForThread(claimed.threadId))
                    }
                    ok = true
                } catch (e: Exception) {
                    Log.e(TAG, "ForThread failed; requeued id=" + claimed.threadId, e)
                } finally {
                    PerfTelemetry.recordSince(PerfMetric.FOR_THREAD, startedNanos)
                }
                // The ACK is scoped to the generation this claim covered, so a
                // provider event that arrived WHILE the repair was executing is
                // never consumed by it.
                pendingReconciles.ackThread(claimed, ok, System.currentTimeMillis())
            }
        }

        // Quarantine is observable, never silent: quarantined thread repairs are
        // not retried every cycle (so they cannot starve healthy threads) and are
        // re-armed by the next provider event for the same thread.
        val quarantined = pendingReconciles.quarantinedThreadIds()
        if (quarantined.isNotEmpty()) {
            Log.w(TAG, "reconcile_quarantined threads=" + quarantined.size)
        }
    }

    /**
     * P0: single-flight reconcile boundary. Both [syncNow] (manual) and the
     * reconcile-work consumer execute through here so two
     * reconciliations can never overlap (no double provider scans, no doubled
     * projection rebuilds). Mutations are untouched — they keep their own
     * exact, never-conflated channel.
     */
    private suspend fun runReconcile(request: ReconcileRequest) =
        reconcileMutex.withLock {
            applyReconcile(request)
        }

    /**
     * P0 local mirror bootstrap (used by the post-pairing flow): guarantees the
     * Room shadow has its initial window BEFORE cloud-history backfill is asked
     * to enqueue durable events — backfill must never run against an empty
     * mirror. Deliberately does NOT wait for the full historical crawl: the
     * initial window (FIRST_BATCH per source) lands synchronously here, older
     * history continues asynchronously on the detached low-priority crawl, and
     * each batch mirrored later enqueues its own durable events.
     */
    suspend fun ensureLocalMirrorReady() {
        val wasReady = withContext(Dispatchers.IO) {
            val stateDao = db.syncStateDao()
            listOf(MessageEntity.SOURCE_SMS, MessageEntity.SOURCE_MMS).all {
                stateDao.forSource(it)?.initialWindowReady == true
            }
        }
        if (!wasReady) {
            Log.i(TAG, "SYNC_BOOTSTRAP_STARTED sources=sms,mms reason=ensureLocalMirrorReady firstBatch=$FIRST_BATCH")
        }
        // Bounded initial window when not ready; steady-state catch-up when ready.
        runReconcile(ReconcileRequest.FullSync)
    }

    /**
     * Read-cutover gate: Room may serve the UI only once BOTH sources have
     * completed their initial window. Until then every read falls back to
     * the provider path.
     */
    suspend fun isShadowReady(): Boolean = withContext(Dispatchers.IO) {
        val stateDao = db.syncStateDao()
        listOf(MessageEntity.SOURCE_SMS, MessageEntity.SOURCE_MMS).all { source ->
            stateDao.forSource(source)?.initialWindowReady == true
        }
    }

    // ── Loop ───────────────────────────────────────────────────────────────

    private fun ensureLoop() {
        if (!started.compareAndSet(false, true)) return
        // The durable exact-repair queue has its OWN timer. It must run even when
        // no provider event ever arrives (that was the in-process map's fatal
        // defect: a failed read during a quiet period was never retried).
        ChangeRouter.ensureRepairScheduler(appContext)
        syncScope.launch {
            // Exact mutations: sequential, never conflated.
            launch {
                for (work in mutations) {
                    try {
                        applyMutation(work.mutation)
                        // DURABLE COMMIT: only now may a caller (the exact-repair
                        // queue) treat its mutation as done.
                        work.completion?.complete(Result.success(Unit))
                    } catch (e: Exception) {
                        Log.e(TAG, "mutation failed: " + work.mutation, e)
                        work.completion?.complete(Result.failure(e))
                    }
                }
            }
            // FIX 2/3 consumer: cloud-history backfill + key-grant drain after a
            // device approval (or an enrolled app start). Lives on the SAME
            // coroutine as reconciles so Room write transactions stay ordered.
            launch {
                for (deviceId in cloudBackfills) {
                    try {
                        val startedAt = System.currentTimeMillis()
                        Log.i(TAG, "backfill_triggered_after_approve deviceId=$deviceId")
                        // P0: never backfill cloud history against an empty mirror —
                        // land the local initial window / catch up first.
                    ensureLocalMirrorReady()
                    val before = db.gatewayEventOutboxDao().pendingDepth()
                    if (deviceId != "outbox-drain") publishHistoricalConversationSnapshots()
                    backfillCloudHistory()
                        com.autonomousone.messages.security.ConversationKeyRepository(db)
                            .drainHistoryGrants()
                        val queued = (db.gatewayEventOutboxDao().pendingDepth() - before).coerceAtLeast(0)
                        Log.i(
                            TAG,
                            "history_backfill_progress deviceId=$deviceId queued=$queued " +
                                "durationMs=${System.currentTimeMillis() - startedAt}"
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "cloud backfill after link failed deviceId=$deviceId", e)
                    }
                }
            }
            // Reconcile work. The conflated nudge is only a wakeup; the real
            // queue is the merging accumulator. Drain until empty so work that
            // arrives WHILE we execute is picked up in the same nudge.
            for (nudge in reconcileNudge) {
                drainReconcileWork()
                // Arm a SEPARATE timer for the next scheduled retry.
                //
                // The consumer must NEVER park on the retry backoff: it is the
                // only consumer, so sleeping here would delay unrelated realtime
                // work (a new SMS, a TailDelta, another ForThread, a FullSync) by
                // the whole backoff window of a poison thread. reconcile() always
                // sends an immediate nudge, so that work is processed at once
                // while the timer waits independently.
                armRetryTimer()
            }
        }
    }

    // ── Exact mutation fast path ───────────────────────────────────────────

    private suspend fun applyMutation(m: MessageMutation) = withContext(Dispatchers.IO) {
        when (m) {
            is MessageMutation.Upsert -> {
                ingestProviderRows(
                    source = m.source,
                    rows = listOf(m.message),
                    mode = DiscoveryMode.REALTIME_EXACT,
                    originCommandId = m.originCommandId,
                    clientMessageId = m.clientMessageId
                )
                m.providerObservedAtNanos?.let {
                    PerfTelemetry.recordSince(PerfMetric.PROVIDER_PERSISTED_TO_ROOM_COMMIT, it)
                }
            }

            is MessageMutation.Delete -> {
                val dao = db.messageDao()
                val convDao = db.conversationDao()
                // PHASE 3: ONE transaction for the whole delete transition.
                //
                // Before: the row was deleted in transaction A, the projection
                // was rebuilt in transaction B, and the MESSAGE_DELETED /
                // CONVERSATION_* events were enqueued in transactions C and D. A
                // process death between them left Room, the projection and the
                // outbox disagreeing, and Home could be observed in the window
                // where the message was already gone but the conversation still
                // showed it.
                //
                // Now the message, the projection and BOTH cloud events commit
                // together or not at all.
                db.withTransaction {
                    // 1. Composite identity is the only lookup key: SMS 123 and
                    //    MMS 123 are different rows and must never alias.
                    val deleted = dao.findByKey(m.source, m.providerId)
                    if (deleted == null) {
                        // Idempotent: a duplicate delete must not emit a second
                        // logical event. The transition it describes is already
                        // durable, so there is nothing left to do.
                        Log.i(TAG, "delete no-op, row already absent: " + m.source + ":" + m.providerId)
                        return@withTransaction
                    }
                    val threadId = m.threadId ?: deleted.threadId

                    // 2. Remove exactly that composite key.
                    dao.deleteBySourceAndId(m.source, m.providerId)

                    // 3. MESSAGE_DELETED commits with the delete. Its identity
                    //    comes from stable provider facts, so a re-notification of
                    //    the same deletion dedupes instead of manufacturing a new
                    //    logical event from the wall clock.
                    enqueueCloudEvent(
                        source = m.source,
                        providerId = m.providerId,
                        sender = deleted.normalizedAddress,
                        body = deleted.body
                    ) {
                        GatewayEventFactory.messageDeleted(
                            source = m.source,
                            providerId = m.providerId,
                            conversationId = conversationIdFor(threadId),
                            dateMs = deleted.date
                        )
                    }

                    if (threadId <= 0L) return@withTransaction

                    // 4. Recompute the projection from the canonical newest row
                    //    (date DESC, source DESC, providerId DESC). This is the
                    //    SAME order rebuildConversationProjection uses, so deleting
                    //    the newest message moves Home BACKWARDS to the true newest
                    //    surviving row, and mixed SMS/MMS fallback works.
                    val newest = dao.newestForThread(threadId)
                    val conversationId = conversationIdFor(threadId)
                    if (newest == null) {
                        // 5a. The thread is now empty: the conversation must
                        //     disappear from Home immediately instead of keeping a
                        //     stale snippet and date forever.
                        convDao.delete(threadId)
                        enqueueCloudEvent(
                            source = m.source,
                            providerId = m.providerId,
                            sender = deleted.normalizedAddress,
                            body = deleted.body
                        ) {
                            GatewayEventFactory.conversationDeleted(conversationId)
                        }
                        return@withTransaction
                    }

                    // 5b. A surviving row owns the projection. replace (not
                    //     upsert) so the projection can move BACKWARDS; pinned and
                    //     archived are INSERT-only and stay user-owned.
                    val existing = convDao.byThread(threadId)
                    convDao.replaceProjectionPreservingFlags(
                        threadId = threadId,
                        normalizedAddress = newest.normalizedAddress,
                        rawAddress = newest.rawAddress,
                        snippet = newest.body,
                        lastMessageDate = newest.date,
                        unreadCount = dao.countUnread(threadId),
                        pinnedOnInsert = existing?.pinned ?: (threadId in pinRepositoryIds()),
                        archivedOnInsert = existing?.archived ?: (threadId in archivedRepositoryIds()),
                        lastMessageType = newest.type
                    )

                    val direction = cloudMessageDirection(newest.type)
                    val projected = convDao.byThread(threadId)
                    if (direction != null && projected != null) {
                        enqueueCloudEvent(
                            source = newest.source,
                            providerId = newest.providerId,
                            sender = newest.normalizedAddress,
                            body = newest.body
                        ) {
                            GatewayEventFactory.conversationUpserted(
                                conversationId, contactNameFor(newest.normalizedAddress),
                                newest.normalizedAddress, newest.body, direction, newest.date,
                                projected.unreadCount, projected.pinned, projected.archived,
                            )
                        }
                    }
                }
            }

            is MessageMutation.RefreshStatus -> {
                when (val statusRead = readExactMessageStrict(m.source, m.providerId)) {
                    is ProviderRead.Success -> {
                        val fresh = statusRead.value
                        if (fresh != null) {
                            ingestProviderRows(
                                source = m.source,
                                rows = listOf(fresh),
                                mode = DiscoveryMode.REALTIME_EXACT
                            )
                        } else {
                            ChangeRouter.enqueueExactRepair(
                                appContext, m.source, m.providerId, ProviderRepairIntent.REFRESH_STATUS
                            )
                        }
                    }
                    is ProviderRead.Failure -> ChangeRouter.enqueueExactRepair(
                        appContext, m.source, m.providerId, ProviderRepairIntent.REFRESH_STATUS
                    )
                }
            }

            is MessageMutation.MarkThreadRead -> {
                db.withTransaction {
                    db.messageDao().markThreadRead(m.threadId)
                    db.conversationDao().markRead(m.threadId)
                    // Thread-read carries no message content — but if the
                    // thread CONTAINS a LOCAL_ONLY message, its very existence
                    // must stay untraceable. Gate on the latest message of the
                    // thread; content never leaves the device either way.
                    val latest = db.messageDao().newestWindowForThread(m.threadId, 1).firstOrNull()
                    enqueueCloudEvent(
                        source = "sms",
                        providerId = latest?.providerId ?: 0L,
                        sender = latest?.normalizedAddress ?: "",
                        body = latest?.body ?: ""
                    ) {
                        GatewayEventFactory.threadRead(
                            conversationId = conversationIdFor(m.threadId),
                            revisionKey = latest?.let { "${it.source}:${it.providerId}:${it.date}" } ?: "empty"
                        )
                    }
                    if (latest != null) {
                        val conversation = db.conversationDao().byThread(m.threadId) ?: return@withTransaction
                        val direction = cloudMessageDirection(latest.type) ?: return@withTransaction
                        enqueueCloudEvent(
                            source = latest.source,
                            providerId = latest.providerId,
                            sender = latest.normalizedAddress,
                            body = latest.body,
                        ) {
                            GatewayEventFactory.conversationUpserted(
                                conversationId = conversationIdFor(m.threadId),
                                displayName = contactNameFor(latest.normalizedAddress),
                                address = latest.normalizedAddress,
                                lastMessagePreview = latest.body,
                                lastMessageDirection = direction,
                                lastMessageAt = latest.date,
                                unreadCount = 0,
                                pinned = conversation.pinned,
                                archived = conversation.archived,
                            )
                        }
                    }
                }
            }

            is MessageMutation.DeleteThread -> {
                db.messageDao().deleteThread(m.threadId)
                db.conversationDao().delete(m.threadId)
            }
        }
    }

    private fun classifyTransition(old: MessageEntity?, next: MessageEntity): ProviderTransition = when {
        old == null -> ProviderTransition.NEW
        old.body != next.body || old.type != next.type ||
            old.normalizedAddress != next.normalizedAddress || old.rawAddress != next.rawAddress ->
            ProviderTransition.CONTENT_CHANGED
        old.status != next.status || old.dateSent != next.dateSent -> ProviderTransition.STATUS_CHANGED
        old.read != next.read -> ProviderTransition.READ_CHANGED
        else -> ProviderTransition.UNCHANGED
    }

    private fun stableRevision(seed: String): Long =
        java.util.UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8))
            .leastSignificantBits and Long.MAX_VALUE

    /**
     * ONE provider-fact ingest primitive for exact, delta, history and integrity paths.
     * Message state is canonical; batch callers repair each touched projection once.
     */
    private suspend fun ingestProviderRows(
        source: String,
        rows: List<Sms>,
        mode: DiscoveryMode,
        originCommandId: String? = null,
        clientMessageId: String? = null
    ): Set<Long> = withContext(Dispatchers.IO) {
        if (rows.isEmpty()) return@withContext emptySet()
        val touched = LinkedHashSet<Long>()
        val pinnedIds = pinRepositoryIds()
        val archivedIds = archivedRepositoryIds()
        TraceSections.begin(TraceSections.ROOM_INGEST)
        try {
            db.withTransaction {
                val dao = db.messageDao()
                val convDao = db.conversationDao()
                val transitions = ArrayList<Pair<ProviderTransition, MessageEntity>>()

                for (row in rows) {
                    val providerEntity = toEntity(row, source) ?: continue
                    val old = dao.findByKey(source, providerEntity.providerId)
                    // READ is monotonic in the local read model: once the user has
                    // read a row, a stale provider READ=0 cannot resurrect unread.
                    // A row arriving while its thread is visible is locally read in
                    // this same durable transaction.
                    val entity = if ((old?.read == true || VisibleConversationTracker.isVisible(providerEntity.threadId)) &&
                        !providerEntity.read
                    ) providerEntity.copy(read = true) else providerEntity
                    val transition = classifyTransition(old, entity)
                    if (transition == ProviderTransition.UNCHANGED) continue

                    dao.upsertAll(listOf(entity))
                    touched += entity.threadId
                    transitions += transition to entity

                    if (mode == DiscoveryMode.HISTORY_BACKFILL || mode == DiscoveryMode.STARTUP_DELTA) {
                        enqueueHistorical(entity)
                    } else {
                        val direction = cloudMessageDirection(entity.type) ?: continue
                        enqueueCloudEvent(
                            source = source,
                            providerId = entity.providerId,
                            sender = entity.normalizedAddress,
                            body = entity.body
                        ) {
                            when (transition) {
                                ProviderTransition.NEW -> GatewayEventFactory.messageCreated(
                                    source = source,
                                    providerId = entity.providerId,
                                    conversationId = conversationIdFor(entity.threadId),
                                    direction = direction,
                                    body = entity.body,
                                    dateMs = entity.date,
                                    status = entity.status,
                                    address = entity.normalizedAddress,
                                    contactName = contactNameFor(entity.normalizedAddress),
                                    read = entity.read,
                                    originCommandId = originCommandId,
                                    clientMessageId = clientMessageId,
                                    revision = stableRevision("new:$source:${entity.providerId}:${entity.date}")
                                )
                                ProviderTransition.CONTENT_CHANGED -> {
                                    val created = GatewayEventFactory.messageCreated(
                                        source = source,
                                        providerId = entity.providerId,
                                        conversationId = conversationIdFor(entity.threadId),
                                        direction = direction,
                                        body = entity.body,
                                        dateMs = entity.date,
                                        status = entity.status,
                                        address = entity.normalizedAddress,
                                        contactName = contactNameFor(entity.normalizedAddress),
                                        read = entity.read,
                                        originCommandId = originCommandId,
                                        clientMessageId = clientMessageId,
                                        revision = stableRevision("content:$source:${entity.providerId}:${entity.body.hashCode()}:${entity.type}:${entity.normalizedAddress}")
                                    )
                                    created.copy(
                                        eventType = GatewayEventFactory.Types.MESSAGE_UPDATED,
                                        eventUuid = java.util.UUID.nameUUIDFromBytes(
                                            "message-update:$source:${entity.providerId}:${entity.date}:${entity.body.hashCode()}:${entity.type}:${entity.normalizedAddress}"
                                                .toByteArray(Charsets.UTF_8)
                                        ).toString()
                                    )
                                }
                                ProviderTransition.STATUS_CHANGED,
                                ProviderTransition.READ_CHANGED -> GatewayEventFactory.messageStatusChanged(
                                    source = source,
                                    providerId = entity.providerId,
                                    conversationId = conversationIdFor(entity.threadId),
                                    status = entity.status,
                                    dateMs = entity.date,
                                    direction = direction,
                                    body = entity.body,
                                    address = entity.normalizedAddress,
                                    contactName = contactNameFor(entity.normalizedAddress),
                                    read = entity.read
                                ).copy(
                                    eventUuid = java.util.UUID.nameUUIDFromBytes(
                                        "message-state:$source:${entity.providerId}:${entity.date}:${entity.status}:${entity.dateSent}:${entity.read}"
                                            .toByteArray(Charsets.UTF_8)
                                    ).toString(),
                                    revision = stableRevision("state:$source:${entity.providerId}:${entity.status}:${entity.dateSent}:${entity.read}")
                                )
                                ProviderTransition.UNCHANGED -> error("unreachable")
                            }
                        }
                    }
                }

                TraceSections.begin(TraceSections.ROOM_PROJECTION)
                try {
                    for (threadId in touched) {
                        if (threadId <= 0L) continue
                        val newest = dao.newestForThread(threadId)
                        if (newest == null) {
                            convDao.delete(threadId)
                            continue
                        }
                        val unread = dao.countUnread(threadId)
                        val existing = convDao.byThread(threadId)
                        val pinned = existing?.pinned ?: (threadId in pinnedIds)
                        val archived = existing?.archived ?: (threadId in archivedIds)
                        val projectionChanged = existing == null ||
                            existing.normalizedAddress != newest.normalizedAddress ||
                            existing.rawAddress != newest.rawAddress ||
                            existing.snippet != newest.body ||
                            existing.lastMessageDate != newest.date ||
                            existing.unreadCount != unread ||
                            existing.lastMessageType != newest.type
                        convDao.replaceProjectionPreservingFlags(
                            threadId = threadId,
                            normalizedAddress = newest.normalizedAddress,
                            rawAddress = newest.rawAddress,
                            snippet = newest.body,
                            lastMessageDate = newest.date,
                            unreadCount = unread,
                            pinnedOnInsert = pinned,
                            archivedOnInsert = archived,
                            lastMessageType = newest.type
                        )
                        if (projectionChanged &&
                            mode != DiscoveryMode.HISTORY_BACKFILL &&
                            mode != DiscoveryMode.STARTUP_DELTA
                        ) {
                            val direction = cloudMessageDirection(newest.type) ?: continue
                            val conversationId = conversationIdFor(threadId)
                            enqueueCloudEvent(
                                source = newest.source,
                                providerId = newest.providerId,
                                sender = newest.normalizedAddress,
                                body = newest.body
                            ) {
                                val revision = stableRevision(
                                    "conversation:$conversationId:${newest.source}:${newest.providerId}:${newest.date}:$unread:$pinned:$archived:${newest.body.hashCode()}"
                                )
                                GatewayEventFactory.conversationUpserted(
                                    conversationId = conversationId,
                                    displayName = contactNameFor(newest.normalizedAddress),
                                    address = newest.normalizedAddress,
                                    lastMessagePreview = newest.body,
                                    lastMessageDirection = direction,
                                    lastMessageAt = newest.date,
                                    unreadCount = unread,
                                    pinned = pinned,
                                    archived = archived,
                                    revision = revision
                                )
                            }
                        }
                    }
                } finally {
                    TraceSections.end()
                }
            }
            if (touched.isNotEmpty()) PerfTelemetry.noteRoomCommit()
            touched
        } finally {
            TraceSections.end()
        }
    }

    // ── PR-02: cloud outbox helpers (called INSIDE Room transactions) ──────

    /**
     * Builds the event row via [build] and inserts it into the durable
     * outbox (INSERT OR IGNORE). Runs INSIDE the caller's Room transaction:
     * the event commits atomically with the mutation it describes (Rule 4 —
     * no critical fire-and-forget). A dropped insert means the SAME
     * deterministic eventUuid is already queued/ACKed — logged, never doubled.
     */
    /**
     * Best-effort contact name for an address. Returns null when READ_CONTACTS
     * is missing or the contact map is empty — the PWA then falls back to the
     * phone number. ContactRepository keeps a process-wide cache (one provider
     * scan at most); resolution is additive to the envelope payload only.
     */
    private fun contactNameFor(address: String?): String? {
        val phone = address ?: return null
        val contacts = ContactRepository(appContext).getContactNameMap()
        if (contacts.isEmpty()) return null
        val normalized = ContactRepository.normalizePhone(phone)
        return contacts[normalized] ?: contacts[phone]
    }


    private suspend fun enqueueCloudEvent(
        source: String,
        providerId: Long,
        sender: String,
        body: String,
        build: suspend () -> GatewayEventOutboxEntity
    ) {
        if (!syncAllowed) return
        // ADR-006: SensitiveMessageFirewall — the SINGLE choke point for cloud
        // event creation, and the ONLY classifier. A LOCAL_ONLY decision means
        // the event row is never built/inserted (not "inserted then deleted").
        // Local audit never logs message content (ADR-006 §21).
        val firewall = com.autonomousone.messages.security.SensitiveMessageFirewall
        val verdict = firewall.classify(sender, body)
        val category = when (verdict.category) {
            com.autonomousone.messages.security.SensitiveMessageFirewall.Category.NORMAL -> ""
            com.autonomousone.messages.security.SensitiveMessageFirewall.Category.OTP_SECURITY_CODE -> "READ_OTP"
            com.autonomousone.messages.security.SensitiveMessageFirewall.Category.BANK_SECURITY_CODE -> "READ_BANK_SECURITY"
            com.autonomousone.messages.security.SensitiveMessageFirewall.Category.PASSWORD_RESET_CODE -> "READ_PASSWORD_RESET"
            com.autonomousone.messages.security.SensitiveMessageFirewall.Category.AUTHENTICATION_CODE -> "READ_AUTH_CODES"
            com.autonomousone.messages.security.SensitiveMessageFirewall.Category.FINANCIAL_NOTIFICATION -> "READ_FINANCIAL_NOTIFICATIONS"
        }
        val policy = firewall.resolvePolicy(
            verdict = verdict,
            sender = sender,
            localOnlySenders = messagingPrefs.localOnlySenders,
            syncAllowlist = messagingPrefs.syncAllowlistSenders,
            financialPolicy = messagingPrefs.financialNotificationPolicy,
            ambiguityMode = messagingPrefs.ambiguityMode
        )
        val explicitlyAuthorized = category.isNotEmpty() &&
            com.autonomousone.messages.security.ConversationKeyRepository(db)
                .hasAuthorizedHistoryReader(category)
        if (policy == com.autonomousone.messages.security.SensitiveMessageFirewall.Policy.LOCAL_ONLY &&
            !explicitlyAuthorized
        ) {
            // ADR-006 §11: when the user's financial policy is ASK, surface a
            // per-message prompt (Sync once / Keep private) instead of a
            // silent keep-local. The DEFAULT is still local: until the user
            // answers (or if they swipe the prompt away) the message never
            // leaves the device — §16 fail-closed. A prior "Sync once" for
            // THIS exact message flips it to sync-eligible.
            if (verdict.category == com.autonomousone.messages.security.SensitiveMessageFirewall.Category.FINANCIAL_NOTIFICATION &&
                messagingPrefs.financialNotificationPolicy ==
                com.autonomousone.messages.security.SensitiveMessageFirewall.Policy.ASK
            ) {
                if (com.autonomousone.messages.security.AskPolicyLedger
                        .isSyncAllowed(appContext, source, providerId)
                ) {
                    // explicit per-message user grant → fall through to SYNC
                } else {
                    com.autonomousone.messages.security.AskPrompt.notifyFinancialAsk(
                        appContext, source, providerId, sender
                    )
                    android.util.Log.i(
                        TAG,
                        "SYNC_FIREWALL: $source/$providerId category=${verdict.category} " +
                            "policy=ASK_PENDING rule=${verdict.rule}"
                    )
                    return
                }
            }
            android.util.Log.w(
                TAG,
                "MESSAGE_BLOCKED_BY_FIREWALL source=$source providerId=$providerId " +
                    "category=${verdict.category} rule=${verdict.rule}"
            )
            android.util.Log.i(
                TAG,
                "SYNC_FIREWALL: $source/$providerId category=${verdict.category} " +
                    "policy=LOCAL_ONLY rule=${verdict.rule}"
            )
            return
        }
        run {
            val row = build()
            if (db.gatewayEventOutboxDao().idOf(row.eventUuid) != null) return
            val payload = org.json.JSONObject(GatewayEventFactory.decodePayloadEnvelope(row.ciphertext))
            val at = payload.optLong("dateMs", db.messageDao().findByKey(source, providerId)?.date ?: 0L)
            val encrypted = com.autonomousone.messages.security.ConversationKeyRepository(db).encrypt(row, category)
            val direction = payload.optString("direction", "unknown")
            val inserted = db.gatewayEventOutboxDao().insertOrIgnore(encrypted)
            if (inserted == -1L) {
                Log.d(TAG, "cloud event ${row.eventType}/${row.eventUuid} already queued/ACKed — deduped")
            } else {
                Log.i(
                    TAG,
                    "cloud_event_queued eventId=${row.eventUuid} type=${row.eventType} direction=$direction " +
                        "conversationId=${row.aggregateId} source=$source providerId=$providerId"
                )
            }
        }
    }

    /**
     * Opaque conversation UUID (TechSpec §12) for the thread — created on
     * first use in remote_conversation_map. Empty string = threadId 0
     * (unresolvable thread): the event still ships, addressable by its own id.
     */
    private suspend fun conversationIdFor(threadId: Long): String {
        if (threadId <= 0L) return ""
        val mapDao = db.remoteConversationMapDao()
        mapDao.getByThreadId(threadId)?.let { return it.conversationId }
        val uuid = java.util.UUID.randomUUID().toString()
        mapDao.insertOrIgnore(RemoteConversationMapEntity(uuid, threadId, System.currentTimeMillis()))
        // Lost an insert race → the winner's row is the mapping.
        return mapDao.getByThreadId(threadId)?.conversationId ?: uuid
    }

    // ── Reconcile path (repair/recovery) ───────────────────────────────────

    private suspend fun applyReconcile(request: ReconcileRequest) = withContext(Dispatchers.IO) {
        when (request) {
            is ReconcileRequest.ForThread -> {
                repairThreadInShadow(request.threadId)
            }
            is ReconcileRequest.TailDelta -> {
                val mark = PerfTelemetry.mark()
                TraceSections.begin(TraceSections.TAIL_DELTA)
                try {
                    val sms = syncSource(MessageEntity.SOURCE_SMS)
                    val mms = syncSource(MessageEntity.SOURCE_MMS)
                    if (sms.initialWindowLanded || mms.initialWindowLanded) fullRebuildConversations()
                    val now = System.currentTimeMillis()
                    val stateDao = db.syncStateDao()
                    if (sms.initialWindowLanded) stateDao.markInitialWindowReady(MessageEntity.SOURCE_SMS, now)
                    if (mms.initialWindowLanded) stateDao.markInitialWindowReady(MessageEntity.SOURCE_MMS, now)
                    // Generic/unknown notifications get BOTH the newest-watermark
                    // delta and a small recent overlap. Never a FullSync.
                    repairRecentOverlap()
                } finally {
                    TraceSections.end()
                    PerfTelemetry.recordSince(PerfMetric.TAIL_DELTA, mark)
                }
            }
            is ReconcileRequest.FullSync -> {
                // Ledger hygiene piggybacks the periodic full sync (it runs
                // on app start + pulls, never mid-conversation): the send
                // stats only power a "today" chip, so a 90-day horizon is
                // generous while keeping the table bounded.
                db.sendSegmentDao().pruneBefore(System.currentTimeMillis() - 90L * 24 * 3600 * 1000)
                val sms = syncSource(MessageEntity.SOURCE_SMS)
                val mms = syncSource(MessageEntity.SOURCE_MMS)
                // ── Cutover ordering (fixes "list showed, then went empty") ──
                // syncSource deliberately leaves initialWindowReady FALSE when
                // the window just landed. The flag may only flip AFTER the
                // conversations projection has been rebuilt from the mirrored
                // messages — isShadowReady() gates Room reads, so the UI can
                // never observe "ready" against an empty/missing projection.
                // If the process dies in between, the next reconcile redoes
                // the (idempotent) window + rebuild before marking again.
                if (sms.projectionStale || mms.projectionStale) {
                    fullRebuildConversations()
                }
                val now = System.currentTimeMillis()
                val stateDao = db.syncStateDao()
                if (sms.initialWindowLanded) stateDao.markInitialWindowReady(MessageEntity.SOURCE_SMS, now)
                if (mms.initialWindowLanded) stateDao.markInitialWindowReady(MessageEntity.SOURCE_MMS, now)
                // History backfill must NEVER block the caller (that was the
                // startup hang: 360K rows awaited inside the first syncNow).
                // ONE detached job crawls SMS then MMS and rebuilds the
                // projection a single time at the end — two concurrent
                // crawlers each calling fullRebuildConversations() doubled
                // provider load and made Home flicker through the crawl.
                scheduleBackfill()
            }
        }
    }

    /** One in-flight backfill per source; guarded by the DURABLE flag, not memory. */
    private val backfillInFlight = java.util.concurrent.ConcurrentHashMap<String, AtomicBoolean>()

    /**
     * The crawl runs on its own single thread at MIN_PRIORITY. Provider keyset
     * reads are synchronous repository calls and stay on this lane; no nested
     * Dispatchers.IO hop escapes the executor. Room projection work may use its
     * own dispatcher after each bounded batch, but provider history I/O stays
     * serialized and lower priority than interactive work.
     */
    private val backfillDispatcher by lazy {
        val factory = java.util.concurrent.ThreadFactory { r ->
            Thread(r, "sms-backfill").apply { priority = Thread.MIN_PRIORITY }
        }
        val executor = java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            java.util.concurrent.LinkedBlockingQueue(), factory
        )
        executor.asCoroutineDispatcher()
    }

    /**
     * Single detached crawl for BOTH sources, one projection rebuild at the
     * end. Durable per-source keyset cursors mean a kill resumes exactly
     * where each crawl stopped; the per-source AtomicBoolean keeps two
     * reconciles from launching overlapping crawls.
     */
    private fun scheduleBackfill() {
        syncScope.launch(backfillDispatcher) {
            val smsGuard = backfillInFlight.getOrPut(MessageEntity.SOURCE_SMS) { AtomicBoolean(false) }
            val mmsGuard = backfillInFlight.getOrPut(MessageEntity.SOURCE_MMS) { AtomicBoolean(false) }
            var didWork = false
            if (smsGuard.compareAndSet(false, true)) {
                try {
                    didWork = backfillOlderKeyset(MessageEntity.SOURCE_SMS) || didWork
                } catch (e: Exception) {
                    Log.e(TAG, "backfill failed for SMS", e)
                } finally {
                    smsGuard.set(false)
                }
            }
            if (mmsGuard.compareAndSet(false, true)) {
                try {
                    didWork = backfillOlderKeyset(MessageEntity.SOURCE_MMS) || didWork
                } catch (e: Exception) {
                    Log.e(TAG, "backfill failed for MMS", e)
                } finally {
                    mmsGuard.set(false)
                }
            }
            // Canonical batch ingest repairs each touched projection once per
            // batch, so a global projection rebuild is no longer needed here.
            if (didWork) Log.d(TAG, "history backfill changed local Room rows")
            publishHistoricalConversationSnapshots()
            // Also repairs installations whose provider history was already
            // mirrored before cloud history production existed.
            backfillCloudHistory()
        }
    }

    internal suspend fun enqueueHistorical(entity: MessageEntity) {
        val direction = cloudMessageDirection(entity.type) ?: return
        val generation = 4L
        val eventId = java.util.UUID.nameUUIDFromBytes(
            "evt:replica-v4:${entity.source}:${entity.providerId}:${entity.date}".toByteArray()
        ).toString()
        if (db.gatewayEventOutboxDao().idOf(eventId) != null) return
        val checkpoint = db.cloudHistoryCheckpointDao().get(entity.source)
            ?: CloudHistoryCheckpointEntity(
                source = entity.source,
                generation = generation,
                producerCursorDate = Long.MAX_VALUE,
                producerCursorProviderId = Long.MAX_VALUE,
                nextOrdinal = 1,
                ackedContiguousOrdinal = 0,
                ackedCursorDate = Long.MAX_VALUE,
                ackedCursorProviderId = Long.MAX_VALUE,
                sourceExhausted = false,
                updatedAt = System.currentTimeMillis(),
            )
        val ordinal = checkpoint.nextOrdinal
        enqueueCloudEvent(entity.source, entity.providerId, entity.normalizedAddress, entity.body) {
            GatewayEventFactory.messageCreated(
                source = entity.source,
                providerId = entity.providerId,
                conversationId = conversationIdFor(entity.threadId),
                direction = direction,
                body = entity.body,
                dateMs = entity.date,
                status = entity.status,
                address = entity.normalizedAddress,
                contactName = contactNameFor(entity.normalizedAddress),
                read = entity.read,
                revision = 1,
                priority = GatewayEventOutboxEntity.PRIORITY_BACKFILL,
            ).copy(
                historySource = entity.source,
                historyGeneration = generation,
                historyOrdinal = ordinal,
                historyDate = entity.date,
                historyProviderId = entity.providerId,
            )
        }
        if (db.gatewayEventOutboxDao().idOf(eventId) != null) {
            db.cloudHistoryCheckpointDao().upsert(checkpoint.copy(
                producerCursorDate = entity.date,
                producerCursorProviderId = entity.providerId,
                nextOrdinal = ordinal + 1,
                sourceExhausted = false,
                updatedAt = System.currentTimeMillis(),
            ))
        }
    }

    private suspend fun publishHistoricalConversationSnapshots() {
        var available = 2_000 - db.gatewayEventOutboxDao().pendingBackfillDepth()
        if (available <= 0) return
        for (conversation in db.conversationDao().all()) {
            if (available-- <= 0) break
            val direction = cloudMessageDirection(conversation.lastMessageType) ?: continue
            enqueueCloudEvent(
                source = MessageEntity.SOURCE_SMS,
                providerId = 0,
                sender = conversation.normalizedAddress,
                body = conversation.snippet,
            ) {
                GatewayEventFactory.conversationUpserted(
                    conversationId = conversationIdFor(conversation.threadId),
                    displayName = contactNameFor(conversation.normalizedAddress),
                    address = conversation.normalizedAddress,
                    lastMessagePreview = conversation.snippet,
                    lastMessageDirection = direction,
                    lastMessageAt = conversation.lastMessageDate,
                    unreadCount = conversation.unreadCount,
                    pinned = conversation.pinned,
                    archived = conversation.archived,
                    revision = 1,
                    priority = GatewayEventOutboxEntity.PRIORITY_BACKFILL,
                )
            }
        }
    }

    private suspend fun backfillCloudHistory() {
        val maxPendingBackfill = 2_000
        var eligible = 0
        var queued = 0
        for (source in listOf(MessageEntity.SOURCE_SMS, MessageEntity.SOURCE_MMS)) {
            // v3 intentionally starts a fresh cursor. v1 advanced its cursor
            // even when the old privacy-strict default rejected every normal
            // message, permanently skipping those rows on later retries.
            // v3 replays the phone source-of-truth with v3 event identities and
            // one full-history key. Run the server reset migration before
            // deploying this Android build so old and replacement events do not coexist.
            db.withTransaction {
                val checkpoint = db.cloudHistoryCheckpointDao().get(source)
                if (checkpoint != null && checkpoint.nextOrdinal > checkpoint.ackedContiguousOrdinal + 1) {
                    val first = db.gatewayEventOutboxDao().historyAfter(
                        source, checkpoint.generation, checkpoint.ackedContiguousOrdinal, 1
                    ).firstOrNull()
                    if (first == null || first.historyOrdinal != checkpoint.ackedContiguousOrdinal + 1) {
                        db.cloudHistoryCheckpointDao().upsert(checkpoint.copy(
                            producerCursorDate = checkpoint.ackedCursorDate,
                            producerCursorProviderId = checkpoint.ackedCursorProviderId,
                            nextOrdinal = checkpoint.ackedContiguousOrdinal + 1,
                            sourceExhausted = false,
                            updatedAt = System.currentTimeMillis(),
                        ))
                    }
                }
            }
            while (syncAllowed) {
                val available = maxPendingBackfill - db.gatewayEventOutboxDao().pendingBackfillDepth()
                if (available <= 0) break
                val count = db.withTransaction {
                    val cursor = db.cloudHistoryCheckpointDao().get(source)
                        ?: CloudHistoryCheckpointEntity(
                            source, 4L, Long.MAX_VALUE, Long.MAX_VALUE, 1,
                            0, Long.MAX_VALUE, Long.MAX_VALUE, false, System.currentTimeMillis()
                        ).also { db.cloudHistoryCheckpointDao().upsert(it) }
                    val beforeDate = cursor.producerCursorDate
                    val beforeId = cursor.producerCursorProviderId
                    val pageLimit = minOf(100, available)
                    val page = db.messageDao().cloudHistoryPage(source, beforeDate, beforeId, pageLimit)
                    eligible += page.size
                    page.forEach { enqueueHistorical(it) }
                    queued += page.size
                    val latest = db.cloudHistoryCheckpointDao().get(source)
                    if (page.isNotEmpty() && latest != null) {
                        db.cloudHistoryCheckpointDao().upsert(latest.copy(
                            producerCursorDate = page.last().date,
                            producerCursorProviderId = page.last().providerId,
                            sourceExhausted = page.size < pageLimit,
                            updatedAt = System.currentTimeMillis(),
                        ))
                    } else if (page.isEmpty() && latest != null) {
                        db.cloudHistoryCheckpointDao().upsert(latest.copy(
                            sourceExhausted = true,
                            updatedAt = System.currentTimeMillis(),
                        ))
                    }
                    page.size
                }
                if (count < minOf(100, available)) break
                yield()
            }
        }
        Log.i(TAG, "SYNC_REPORT eligible=$eligible queued=$queued directions=in,out sources=sms,mms")
    }

    /** What a per-source sync pass achieved this reconcile. */
    private data class SourceSyncResult(
        /** The initial window landed THIS pass → caller must rebuild the
         *  projection and only then flip initialWindowReady. */
        val initialWindowLanded: Boolean,
        /** Rows changed → the conversation projection needs a rebuild. */
        val projectionStale: Boolean,
        /**
         * Threads touched by rows mirrored in THIS pass. The realtime path
         * recomputes exactly these projections — it must never rebuild the
         * global projection for a small delta.
         */
        val touchedThreadIds: List<Long> = emptyList()
    )

    // ── Provider sync (reconcile path only) ────────────────────────────────

    private suspend fun syncSource(source: String): SourceSyncResult {
        val stateDao = db.syncStateDao()
        val state = stateDao.forSource(source)
            ?: SyncStateEntity(source = source, newestDate = 0L).also { stateDao.upsert(it) }

        return if (!state.initialWindowReady) {
            val read = readOlderPageStrict(source, Long.MAX_VALUE, Long.MAX_VALUE, FIRST_BATCH)
            val batch = when (read) {
                is ProviderRead.Success -> read.value
                is ProviderRead.Failure -> throw IllegalStateException("provider bootstrap failed $source: ${read.reason}", read.cause)
            }
            val touched = ingestProviderRows(source, batch, DiscoveryMode.STARTUP_DELTA)
            val now = System.currentTimeMillis()
            if (batch.isNotEmpty()) {
                val newest = batch.maxWithOrNull(compareBy<Sms> { it.date }.thenBy { providerId(it) })!!
                stateDao.advanceNewest(source, newest.date, providerId(newest), now)
                val oldest = batch.minWithOrNull(compareBy<Sms> { it.date }.thenBy { providerId(it) })!!
                stateDao.advanceOldest(source, oldest.date, providerId(oldest), now)
            }
            SourceSyncResult(true, touched.isNotEmpty(), touched.toList())
        } else {
            val read = readNewerThanStrict(source, state.newestDate, state.newestId, FIRST_BATCH)
            val fresh = when (read) {
                is ProviderRead.Success -> read.value
                is ProviderRead.Failure -> throw IllegalStateException("provider delta failed $source: ${read.reason}", read.cause)
            }
            val touched = ingestProviderRows(source, fresh, DiscoveryMode.REALTIME_RECONCILE)
            if (fresh.isNotEmpty()) {
                val newest = fresh.maxWithOrNull(compareBy<Sms> { it.date }.thenBy { providerId(it) })!!
                stateDao.advanceNewest(source, newest.date, providerId(newest), System.currentTimeMillis())
            } else stateDao.touchReconcile(source, System.currentTimeMillis())
            SourceSyncResult(false, touched.isNotEmpty(), touched.toList())
        }
    }

    private suspend fun backfillOlderKeyset(source: String): Boolean {
        val stateDao = db.syncStateDao()
        var cursor = stateDao.forSource(source) ?: return false
        if (cursor.historyBackfillComplete) return false
        var changedAny = false
        while (true) {
            val mark = PerfTelemetry.mark()
            TraceSections.begin(TraceSections.HISTORY_BACKFILL_BATCH)
            val batch = try {
                when (val read = readOlderPageStrict(source, cursor.oldestDate, cursor.oldestId, BACKFILL_BATCH)) {
                    is ProviderRead.Success -> read.value
                    is ProviderRead.Failure -> throw IllegalStateException("history provider read failed $source: ${read.reason}", read.cause)
                }
            } finally {
                TraceSections.end()
                PerfTelemetry.recordSince(PerfMetric.HISTORY_BACKFILL_BATCH, mark)
            }
            if (batch.isEmpty()) break
            val touched = ingestProviderRows(source, batch, DiscoveryMode.HISTORY_BACKFILL)
            changedAny = changedAny || touched.isNotEmpty()
            val oldest = batch.minWithOrNull(compareBy<Sms> { it.date }.thenBy { providerId(it) })!!
            stateDao.advanceOldest(source, oldest.date, providerId(oldest), System.currentTimeMillis())
            if (batch.size < BACKFILL_BATCH) break
            cursor = stateDao.forSource(source) ?: return changedAny
            yield()
        }
        stateDao.markHistoryComplete(source, System.currentTimeMillis())
        Log.d(TAG, "backfill complete for $source (strict keyset watermark cursor)")
        return changedAny
    }

    /**
     * Read exactly ONE message from the provider by its native ID.
     * O(1) — no scan, no offset, no window.
     */
    private suspend fun readExactMessage(source: String, providerId: Long): Sms? =
        withContext(Dispatchers.IO) {
            when (source) {
                MessageEntity.SOURCE_SMS -> {
                    smsRepository.querySmsRaw(
                        selection = "${Telephony.Sms._ID} = ?",
                        selectionArgs = arrayOf(providerId.toString()),
                        sortOrder = "${Telephony.Sms.DATE} DESC",
                        limit = 1
                    ).firstOrNull()
                }
                MessageEntity.SOURCE_MMS -> {
                    smsRepository.queryMmsRaw(
                        selection = "${Telephony.Mms._ID} = ?",
                        selectionArgs = arrayOf(providerId.toString()),
                        sortOrder = "${Telephony.Mms.DATE} DESC",
                        limit = 1
                    ).firstOrNull()
                }
                else -> null
            }
        }

    /**
     * Rows strictly newer than the (date,id) watermark — keyset, so a message
     * inserted with the same timestamp as the watermark is still picked up.
     */
    private fun readOlderPageStrict(
        source: String,
        beforeDate: Long,
        beforeId: Long,
        limit: Int
    ): ProviderRead<List<Sms>> = if (source == MessageEntity.SOURCE_SMS) {
        smsRepository.readSmsStrict(
            selection = "(${Telephony.Sms.DATE} < ?) OR (${Telephony.Sms.DATE} = ? AND ${Telephony.Sms._ID} < ?)",
            selectionArgs = arrayOf(beforeDate.toString(), beforeDate.toString(), beforeId.toString()),
            sortOrder = "${Telephony.Sms.DATE} DESC, ${Telephony.Sms._ID} DESC",
            limit = limit
        )
    } else {
        smsRepository.readMmsStrict(
            selection = "(${Telephony.Mms.DATE} < ?) OR (${Telephony.Mms.DATE} = ? AND ${Telephony.Mms._ID} < ?)",
            selectionArgs = arrayOf((beforeDate / 1000L).toString(), (beforeDate / 1000L).toString(), beforeId.toString()),
            sortOrder = "${Telephony.Mms.DATE} DESC, ${Telephony.Mms._ID} DESC",
            limit = limit
        )
    }

    private fun readNewerThanStrict(
        source: String,
        newestMs: Long,
        newestId: Long,
        limit: Int
    ): ProviderRead<List<Sms>> = if (source == MessageEntity.SOURCE_SMS) {
        smsRepository.readSmsStrict(
            selection = "(${Telephony.Sms.DATE} > ?) OR (${Telephony.Sms.DATE} = ? AND ${Telephony.Sms._ID} > ?)",
            selectionArgs = arrayOf(newestMs.toString(), newestMs.toString(), newestId.toString()),
            sortOrder = "${Telephony.Sms.DATE} ASC, ${Telephony.Sms._ID} ASC",
            limit = limit
        )
    } else {
        smsRepository.readMmsStrict(
            selection = "(${Telephony.Mms.DATE} > ?) OR (${Telephony.Mms.DATE} = ? AND ${Telephony.Mms._ID} > ?)",
            selectionArgs = arrayOf((newestMs / 1000L).toString(), (newestMs / 1000L).toString(), newestId.toString()),
            sortOrder = "${Telephony.Mms.DATE} ASC, ${Telephony.Mms._ID} ASC",
            limit = limit
        )
    }

    private fun toEntity(sms: Sms, source: String): MessageEntity? {
        val rawAddress = sms.sender.takeIf { it.isNotBlank() } ?: return null
        return MessageEntity(
            source = source,
            providerId = providerId(sms),
            threadId = sms.threadId,
            normalizedAddress = ContactRepository.normalizePhone(rawAddress),
            rawAddress = rawAddress,
            body = sms.message.orEmptyIfNull(),
            date = sms.date,
            type = sms.type,
            status = sms.status,
            dateSent = sms.dateSent,
            read = !sms.unread
        )
    }

    private fun String?.orEmptyIfNull() = this ?: ""

    /**
     * Native provider row id. SmsRepository encodes MMS rows with a NEGATIVE
     * `id` (id = -_id) to keep the UI's mixed list unambiguous — the
     * watermark and the Room `providerId` must use the same abs() mapping
     * toEntity writes, or the keyset predicates compare different id spaces.
     */
    private fun providerId(sms: Sms): Long = kotlin.math.abs(sms.id)

    // ── Mirror writes (app-initiated mutations) ────────────────────────────

    /**
     * Mirrors a conversation delete into Room.
     *
     * ONE transaction: the messages and the conversation projection must not be
     * observable in a half-deleted state (messages gone, conversation still on
     * Home) - a reader that catches that window renders a phantom conversation.
     */
    suspend fun deleteThreadFromShadow(threadId: Long) = withContext(Dispatchers.IO) {
        if (threadId <= 0L) return@withContext
        db.withTransaction {
            db.messageDao().deleteThread(threadId)
            db.conversationDao().delete(threadId)
        }
    }

    /**
     * Marks a thread read in Room.
     *
     * PHASE 10.2: ONE transaction. The per-message read flags and the
     * conversation unread counter are two views of the same fact; if they diverge,
     * Home can show a cleared badge over messages that are still unread (or the
     * reverse), and the divergence survives until the next repair.
     */
    suspend fun markThreadReadInShadow(threadId: Long) = withContext(Dispatchers.IO) {
        if (threadId <= 0L) return@withContext
        db.withTransaction {
            db.messageDao().markThreadRead(threadId)
            db.conversationDao().markRead(threadId)
        }
    }

    /**
     * Marks EVERY conversation read in Room, in ONE transaction.
     *
     * Home used to loop over the RENDERED conversations and run one shadow write
     * each: N transactions, and any conversation that was archived, filtered,
     * blocked or off-screen was silently skipped - leaving unread rows that
     * reappear as soon as the filter changes. Room is the authority, so this is
     * expressed over the whole table and the UI simply follows the Flow. The
     * provider write remains a separate, eventual bulk pass.
     */
    /**
     * Does Room still hold this exact identity?
     *
     * Used ONLY by the repair-intent maturity policy: a matured absence over an
     * EXPECT_EXISTS / REFRESH_STATUS row decides between "nothing to remove"
     * (resolve) and "a real local row may be stale" (become a delete CANDIDATE).
     * It is never used to justify a delete by itself.
     */
    suspend fun localRowExists(source: String, providerId: Long): Boolean =
        withContext(Dispatchers.IO) {
            db.messageDao().findByKey(source, providerId) != null
        }

    suspend fun markAllThreadsReadInShadow() = withContext(Dispatchers.IO) {
        db.withTransaction {
            db.messageDao().markAllRead()
            db.conversationDao().markAllRead()
        }
    }

    /** Remote MARK_READ: update the shadow and durably publish THREAD_READ once. */
    suspend fun markThreadReadAndPublish(threadId: Long) {
        if (threadId > 0L) applyMutation(MessageMutation.MarkThreadRead(threadId))
    }

    /**
     * Re-reads one thread's rows from the provider and repairs its shadow copy.
     * Cheap: one bounded window query.
     */
    suspend fun repairThreadInShadow(threadId: Long) = withContext(Dispatchers.IO) {
        if (threadId <= 0L) return@withContext
        val mark = PerfTelemetry.mark()
        TraceSections.begin(TraceSections.FOR_THREAD)
        try {
            val smsRead = smsRepository.querySmsThreadStrict(threadId, THREAD_REPAIR_LIMIT)
            val mmsRead = smsRepository.queryMmsThreadStrict(threadId, THREAD_REPAIR_LIMIT)
            val smsOk = smsRead is ProviderRead.Success
            val mmsOk = mmsRead is ProviderRead.Success
            val sms = (smsRead as? ProviderRead.Success)?.value.orEmpty()
            val mms = (mmsRead as? ProviderRead.Success)?.value.orEmpty()

            if (smsOk && mmsOk && sms.isEmpty() && mms.isEmpty()) {
                db.withTransaction {
                    db.messageDao().deleteThread(threadId)
                    db.conversationDao().delete(threadId)
                }
                return@withContext
            }

            if (smsOk) reconcileCoveredSource(threadId, MessageEntity.SOURCE_SMS, sms)
            if (mmsOk) reconcileCoveredSource(threadId, MessageEntity.SOURCE_MMS, mms)

            if (!smsOk || !mmsOk) {
                throw IllegalStateException(
                    PARTIAL_THREAD_REPAIR + " thread=" + threadId + " smsOk=" + smsOk + " mmsOk=" + mmsOk
                )
            }
        } finally {
            TraceSections.end()
            PerfTelemetry.recordSince(PerfMetric.FOR_THREAD, mark)
        }
    }

    private suspend fun reconcileCoveredSource(threadId: Long, source: String, providerRows: List<Sms>) {
        if (providerRows.isEmpty()) return
        ingestProviderRows(source, providerRows, DiscoveryMode.REALTIME_RECONCILE)
        val boundary = providerRows.last()
        val boundaryId = providerId(boundary)
        val localCovered = db.messageDao().rowsInCoveredThreadRange(
            source, threadId, boundary.date, boundaryId
        )
        val providerIds = providerRows.asSequence().map(::providerId).toHashSet()
        val queue = ProviderRepairQueue(appContext)
        var queued = false
        for (local in localCovered) {
            if (local.providerId !in providerIds) {
                queue.enqueue(source, local.providerId, ProviderRepairIntent.VERIFY_DELETE_CANDIDATE)
                queued = true
            }
        }
        if (queued) ChangeRouter.retryPendingExactReads(appContext)
    }

    private suspend fun repairRecentOverlap(limit: Int = 8) {
        var firstFailure: Throwable? = null
        for (threadId in db.messageDao().recentThreadIds(limit)) {
            try {
                repairThreadInShadow(threadId)
            } catch (t: Throwable) {
                if (firstFailure == null) firstFailure = t
            }
        }
        firstFailure?.let { throw it }
    }

    // ── Integrity audit ────────────────────────────────────────────────────

    private val integrityInFlight = AtomicBoolean(false)
    private val integrityDispatcher by lazy {
        val factory = java.util.concurrent.ThreadFactory { r ->
            Thread(r, "sms-integrity").apply { priority = Thread.MIN_PRIORITY }
        }
        java.util.concurrent.Executors.newSingleThreadExecutor(factory).asCoroutineDispatcher()
    }

    private fun scheduleIntegrityAudit() {
        if (!integrityInFlight.compareAndSet(false, true)) return
        syncScope.launch(integrityDispatcher) {
            try {
                runIntegrityAudit()
            } catch (e: Exception) {
                Log.e(TAG, "integrity audit failed; durable cursors retained", e)
            } finally {
                integrityInFlight.set(false)
                val states = kotlin.runCatching { db.integrityAuditDao().all() }.getOrDefault(emptyList())
                val now = System.currentTimeMillis()
                val delayMs = when {
                    states.any { it.state == IntegrityAuditStateEntity.STATE_RUNNING } -> 60_000L
                    states.isEmpty() -> 24L * 60L * 60L * 1000L
                    else -> states.map { (it.nextRunAt - now).coerceAtLeast(60_000L) }.minOrNull()
                        ?: 24L * 60L * 60L * 1000L
                }
                syncScope.launch {
                    kotlinx.coroutines.delay(delayMs)
                    scheduleIntegrityAudit()
                }
            }
        }
    }

    private suspend fun runIntegrityAudit() {
        for (source in listOf(MessageEntity.SOURCE_SMS, MessageEntity.SOURCE_MMS)) {
            auditRoomToProvider(source)
            auditProviderToRoom(source)
        }
    }

    private suspend fun beginAuditState(source: String, direction: IntegrityAuditDirection): IntegrityAuditStateEntity? {
        val dao = db.integrityAuditDao()
        val now = System.currentTimeMillis()
        val existing = dao.get(source, direction.name)
        if (existing != null && existing.state == IntegrityAuditStateEntity.STATE_IDLE && now < existing.nextRunAt) return null
        if (existing != null && existing.state == IntegrityAuditStateEntity.STATE_RUNNING) return existing
        val start = (existing ?: IntegrityAuditStateEntity(source, direction.name)).copy(
            cursorDate = Long.MAX_VALUE,
            cursorProviderId = Long.MAX_VALUE,
            cycleStartedAt = now,
            state = IntegrityAuditStateEntity.STATE_RUNNING,
            updatedAt = now
        )
        dao.upsert(start)
        return start
    }

    private suspend fun completeAuditState(state: IntegrityAuditStateEntity) {
        val now = System.currentTimeMillis()
        db.integrityAuditDao().upsert(state.copy(
            cursorDate = Long.MAX_VALUE,
            cursorProviderId = Long.MAX_VALUE,
            lastCompletedAt = now,
            nextRunAt = now + 24L * 60L * 60L * 1000L,
            state = IntegrityAuditStateEntity.STATE_IDLE,
            updatedAt = now
        ))
    }

    private suspend fun auditRoomToProvider(source: String) {
        var state = beginAuditState(source, IntegrityAuditDirection.ROOM_TO_PROVIDER) ?: return
        val repairQueue = ProviderRepairQueue(appContext)
        while (true) {
            val mark = PerfTelemetry.mark()
            TraceSections.begin(TraceSections.INTEGRITY_BATCH)
            try {
                val page = db.messageDao().auditPageForSource(source, state.cursorDate, state.cursorProviderId, 100)
                if (page.isEmpty()) {
                    completeAuditState(state)
                    return
                }
                var queued = false
                for (local in page) {
                    when (val read = readExactMessageStrict(source, local.providerId)) {
                        is ProviderRead.Success -> {
                            val provider = read.value
                            if (provider != null) {
                                // READ is a user-owned local fact once true. Repair the
                                // provider in the authoritative direction instead of
                                // letting a stale provider READ=0 resurrect unread.
                                if (local.read && provider.unread) {
                                    val write = smsRepository.markProviderRowReadStrict(source, local.providerId)
                                    if (write is com.autonomousone.messages.repository.SourceWriteResult.Failure) {
                                        repairQueue.enqueue(source, local.providerId, ProviderRepairIntent.EXPECT_EXISTS)
                                        queued = true
                                    }
                                }
                                ingestProviderRows(source, listOf(provider), DiscoveryMode.INTEGRITY_AUDIT)
                            } else {
                                // The page membership is first evidence; exact absence
                                // verification is the second independent observation.
                                repairQueue.enqueue(source, local.providerId, ProviderRepairIntent.VERIFY_DELETE_CANDIDATE)
                                queued = true
                            }
                        }
                        is ProviderRead.Failure -> {
                            // A provider failure is UNKNOWN, never delete evidence.
                            repairQueue.enqueue(source, local.providerId, ProviderRepairIntent.EXPECT_EXISTS)
                            queued = true
                        }
                    }
                }
                if (queued) ChangeRouter.retryPendingExactReads(appContext)
                val last = page.last()
                state = state.copy(cursorDate = last.date, cursorProviderId = last.providerId, updatedAt = System.currentTimeMillis())
                db.integrityAuditDao().upsert(state)
                yield()
            } finally {
                TraceSections.end()
                PerfTelemetry.recordSince(PerfMetric.INTEGRITY_BATCH, mark)
            }
        }
    }

    private suspend fun auditProviderToRoom(source: String) {
        var state = beginAuditState(source, IntegrityAuditDirection.PROVIDER_TO_ROOM) ?: return
        while (true) {
            val mark = PerfTelemetry.mark()
            TraceSections.begin(TraceSections.INTEGRITY_BATCH)
            try {
                val page = when (val read = readOlderPageStrict(source, state.cursorDate, state.cursorProviderId, 100)) {
                    is ProviderRead.Success -> read.value
                    is ProviderRead.Failure -> throw IllegalStateException("integrity provider page failed $source: ${read.reason}", read.cause)
                }
                if (page.isEmpty()) {
                    completeAuditState(state)
                    return
                }
                ingestProviderRows(source, page, DiscoveryMode.INTEGRITY_AUDIT)
                val last = page.last()
                state = state.copy(cursorDate = last.date, cursorProviderId = providerId(last), updatedAt = System.currentTimeMillis())
                db.integrityAuditDao().upsert(state)
                yield()
            } finally {
                TraceSections.end()
                PerfTelemetry.recordSince(PerfMetric.INTEGRITY_BATCH, mark)
            }
        }
    }

    // ── Conversation projection ────────────────────────────────────────────

    /**
     * Rebuilds ONE conversation row from the messages table.
     * Uses SQL COUNT for unread — O(unread_count) instead of O(total_messages).
     * Message + Conversation update in a single Room transaction.
     */
    private suspend fun rebuildConversationProjection(threadId: Long, preserveFlags: Boolean) {
        if (threadId <= 0L) return
        val database = db
        val dao = database.messageDao()
        val convDao = database.conversationDao()

        database.withTransaction {
            // ONE canonical newest order (date DESC, source DESC, providerId
            // DESC). pageForThread orders by date DESC, providerId DESC, so at an
            // equal timestamp SMS and MMS could disagree about which row is
            // newest depending on which query a caller happened to use.
            val newest = dao.newestForThread(threadId)
            if (newest == null) {
                // Last message in the thread was deleted → the conversation must
                // disappear from Home, not keep a stale snippet/date forever.
                convDao.delete(threadId)
                return@withTransaction
            }
            val unread = dao.countUnread(threadId)

            if (preserveFlags) {
                // A rebuild is authoritative: it must be able to move the
                // projection BACKWARDS after a delete. upsertPreservingFlags is
                // monotonic by design and is only for the realtime insert path.
                val existingForInsert = convDao.byThread(threadId)
                convDao.replaceProjectionPreservingFlags(
                    threadId = threadId,
                    normalizedAddress = newest.normalizedAddress,
                    rawAddress = newest.rawAddress,
                    snippet = newest.body,
                    lastMessageDate = newest.date,
                    unreadCount = unread,
                    // Same NOT NULL, no-default columns as the realtime path:
                    // a rebuild must satisfy them too or the whole thread
                    // vanishes. The row wins; the repositories only seed a
                    // genuine first insert.
                    pinnedOnInsert = existingForInsert?.pinned ?: (threadId in pinRepositoryIds()),
                    archivedOnInsert = existingForInsert?.archived ?: (threadId in archivedRepositoryIds()),
                    lastMessageType = newest.type
                )
            } else {
                val existing = convDao.byThread(threadId)
                convDao.upsertFull(
                    ConversationEntity(
                        threadId = threadId,
                        normalizedAddress = newest.normalizedAddress,
                        rawAddress = newest.rawAddress,
                        snippet = newest.body,
                        lastMessageDate = newest.date,
                        unreadCount = unread,
                        lastMessageType = newest.type,
                        pinned = existing?.pinned ?: (threadId in pinRepositoryIds()),
                        archived = existing?.archived ?: (threadId in archivedRepositoryIds())
                    )
                )
            }
        }
    }

    /**
     * Recomputes ONE thread's conversation projection using the canonical
     * projection rules.
     *
     * Shared by the recovery rebuild and the realtime tail so the two can never
     * diverge: a realtime delta must not need a different (or looser) copy of
     * these rules.
     */
    /**
     * Full rebuild: BOOTSTRAP / RECOVERY ONLY.
     *
     * Never on the realtime path — not from a ContentObserver event, not from
     * TailDelta for a small delta, not from marking read, not from resume.
     *
     * PHASE 5: the READ query count is CONSTANT, not linear in the number of
     * conversations.
     *
     * Before: rebuildConversationProjection() per thread, and that function is
     * newestForThread + countUnread + byThread, plus a pin/archive repository
     * round trip for every thread it had never seen - 3N+ reads.
     *
     * Now: two aggregate reads (newest row per thread, unread count per thread),
     * one projection read, and the pin/archive repositories consulted ONCE.
     * Only the WRITES remain per conversation, and no SQL shape avoids one row
     * per conversation.
     *
     * Each row still goes through the SAME
     * replaceProjectionPreservingFlags statement as the realtime and repair
     * paths, so a rebuild cannot diverge from them.
     */
    suspend fun fullRebuildConversations() = withContext(Dispatchers.IO) {
        val database = db
        val dao = database.messageDao()
        val convDao = database.conversationDao()

        val newestByThread = dao.newestPerThread()
        val unreadByThread = dao.unreadCountsByThread().associate { it.threadId to it.unreadCount }
        val existingByThread = convDao.all().associateBy { it.threadId }
        val pinnedIds = pinRepositoryIds()
        val archivedIds = archivedRepositoryIds()

        // P0-13: a recovery rebuild is AUTHORITATIVE. A conversation projection
        // whose thread no longer has any message is a phantom: nothing else ever
        // revisits it, so it would stay on Home forever. (Pin/archive live in
        // their own repositories and are deliberately untouched: an archived
        // thread that no longer exists is still the user's archive state.)
        val liveThreadIds = newestByThread.mapTo(HashSet()) { it.threadId }
        val staleProjectionIds = existingByThread.keys - liveThreadIds
        if (staleProjectionIds.isNotEmpty()) {
            Log.i(TAG, "projection rebuild removing " + staleProjectionIds.size +
                " stale conversation(s)")
            staleProjectionIds.toList().chunked(REBUILD_CHUNK).forEach { chunk ->
                database.withTransaction {
                    for (staleId in chunk) convDao.delete(staleId)
                }
                yield()
            }
        }

        // Chunked transactions: a full rebuild can cover every conversation, and
        // one giant transaction would hold a write lock while blocking every
        // realtime mutation behind it. Each chunk is independently idempotent.
        newestByThread.chunked(REBUILD_CHUNK).forEach { chunk ->
            database.withTransaction {
                for (m in chunk) {
                    val existing = existingByThread[m.threadId]
                    convDao.replaceProjectionPreservingFlags(
                        threadId = m.threadId,
                        normalizedAddress = m.normalizedAddress,
                        rawAddress = m.rawAddress,
                        snippet = m.body,
                        lastMessageDate = m.date,
                        unreadCount = unreadByThread[m.threadId] ?: 0,
                        pinnedOnInsert = existing?.pinned ?: (m.threadId in pinnedIds),
                        archivedOnInsert = existing?.archived ?: (m.threadId in archivedIds),
                        lastMessageType = m.type
                    )
                }
            }
            yield()
        }
    }

    private suspend fun pinRepositoryIds(): Set<Long> =
        com.autonomousone.messages.repository.PinRepository(appContext).getPinnedIds()

    private suspend fun archivedRepositoryIds(): Set<Long> =
        com.autonomousone.messages.repository.ArchiveRepository(appContext).getArchivedIds()

    // ── Provider readers (keyset) ──────────────────────────────────────────
    //
    // `beforeDate/beforeId` is the durable (date,id) watermark: strictly
    // OLDER rows, newest-first. A sentinel (MAX,MAX) reads from the top.
    // No OFFSET anywhere — a kill resumes from the persisted cursor.

    private suspend fun readSmsKeyset(beforeDate: Long, beforeId: Long, limit: Int): List<Sms> =
        smsRepository.querySmsRaw(
            selection = "(${Telephony.Sms.DATE} < ?) OR (${Telephony.Sms.DATE} = ? AND ${Telephony.Sms._ID} < ?)",
            selectionArgs = arrayOf(beforeDate.toString(), beforeDate.toString(), beforeId.toString()),
            sortOrder = "${Telephony.Sms.DATE} DESC, ${Telephony.Sms._ID} DESC",
            limit = limit
        )

    private suspend fun readMmsKeyset(beforeDate: Long, beforeId: Long, limit: Int): List<Sms> =
        smsRepository.queryMmsRaw(
            selection = "(${Telephony.Mms.DATE} < ?) OR (${Telephony.Mms.DATE} = ? AND ${Telephony.Mms._ID} < ?)",
            // Mms.DATE is SECONDS; watermark millis division is exact.
            selectionArgs = arrayOf(
                (beforeDate / 1000L).toString(),
                (beforeDate / 1000L).toString(),
                beforeId.toString()
            ),
            sortOrder = "${Telephony.Mms.DATE} DESC, ${Telephony.Mms._ID} DESC",
            limit = limit
        )
}
