package com.autonomousone.messages.sms

import android.content.Context
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.PendingDelayedSendDao
import com.autonomousone.messages.data.PendingDelayedSendEntity
import com.autonomousone.messages.event.SmsEventBus
import com.autonomousone.messages.utils.DiagnosticLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * v3.4.0 FEATURE 11 — the ONLY executor of a delayed send.
 *
 * ── The exactly-once argument, in full ───────────────────────────────────────
 * WorkManager guarantees a one-time request is delivered AT LEAST once. That is
 * the correct guarantee for a timer, and the wrong one for a chargeable SMS, so
 * the send is gated behind a second, stronger guarantee in the database:
 *
 *   1. [PendingDelayedSendDao.claim] is a single compare-and-set
 *      (`WHERE state = 'PENDING'`) that flips the row to SENDING. SQLite
 *      serialises it, so exactly one caller in the process — and, because the
 *      row is on disk, exactly one caller across processes, reboots and
 *      app versions — receives `1`.
 *   2. Every other caller receives `0` and returns WITHOUT touching the radio.
 *   3. Only after winning the claim is [DelayedSendSink.send] invoked, and the
 *      claim is never released or re-issued: a failed send is Terminal (FAILED),
 *      not retryable, because "retry" here means "submit it a second time".
 *
 * The consequence, stated plainly: a delayed message can be sent ZERO times (if
 * the process dies inside the radio call, or telephony refuses) or ONE time —
 * never twice. [ScheduledSms] owns the timer; this class owns the permission.
 *
 * ── Why the body is re-verified before sending ───────────────────────────────
 * The row's body and the worker's payload come from the same write, but the row
 * is the durable truth the UI is showing and the Undo copy is rendered from. If
 * they ever disagreed (a truncated InputData, a hand-edited preference), sending
 * the WORKER's copy would deliver something the user never saw in the pending
 * bubble. The row therefore wins.
 */
class DelayedSendExecutor(
    private val dao: PendingDelayedSendDao,
    private val sink: DelayedSendSink
) {

    /**
     * Runs the send for [intentId], at most once in the lifetime of that intent.
     *
     * @param phone the recipient, from the DURABLE WorkManager request. The
     *        ledger row deliberately stores only a token, so the number has to
     *        arrive with the job; the token cross-check then proves the two
     *        describe the same recipient.
     * @param subscriptionId the in-chat SIM choice, also carried by the durable
     *        request (`null` = the user's global Messaging preference). It must
     *        survive process death, or the message would leave on a different
     *        line than the user picked.
     * @param now injected for tests; production passes the wall clock.
     */
    suspend fun execute(
        intentId: String,
        phone: String,
        subscriptionId: Int? = null,
        now: Long = System.currentTimeMillis()
    ): DelayedSendStateMachine.Execution {
        assertVocabulary()

        val row = dao.byId(intentId)
            ?: return skipped(intentId, "no-such-intent").also {
                // The ledger row is gone (pruned, or a stale job from an older
                // install). Sending here would ship a message nothing can undo
                // and nothing can show, so it is deliberately dropped.
                DiagnosticLog.event(
                    "SEND_DELAY",
                    "execute skipped id=$intentId reason=no-such-intent"
                )
            }

        val state = DelayedSendState.from(row.state)
        if (!DelayedSendStateMachine.isIdempotentlySendable(row.toDomain(state), now)) {
            return skipped(intentId, "not-sendable state=${state.name}").also {
                DiagnosticLog.event(
                    "SEND_DELAY",
                    "execute skipped id=$intentId state=${state.name} due=${row.dueAt} now=$now"
                )
            }
        }

        // Cross-check: the token carried by the job must be the row's token. A
        // mismatch means the job and the ledger describe different recipients,
        // and the safe answer to that is not to send at all.
        val actualToken = DiagnosticLog.phoneToken(phone)
        if (row.phoneToken.isNotBlank() && row.phoneToken != actualToken) {
            dao.failPending(intentId, CODE_RECIPIENT_MISMATCH)
            DiagnosticLog.event(
                "SEND_DELAY",
                "recipient mismatch id=$intentId expected=${row.phoneToken} got=$actualToken"
            )
            return DelayedSendStateMachine.Execution.Failed(intentId, CODE_RECIPIENT_MISMATCH)
        }

        // ── THE CLAIM. At most one winner, ever. ────────────────────────────
        val won = dao.claim(intentId, now) == 1
        if (!won) {
            // Lost the race: a duplicate delivery, an Undo that committed
            // first, or an executor that already ran. None of those may send.
            DiagnosticLog.event("SEND_DELAY", "claim lost id=$intentId at=$now")
            return skipped(intentId, "claim-lost")
        }
        DiagnosticLog.event("SEND_DELAY", "claimed id=$intentId at=$now token=$actualToken")

        // The row won the claim, so the row's TEXT is the message of record: it
        // is what the pending bubble showed and what Undo would have restored.
        // An empty body would be a corrupt row; the sink refuses it anyway.
        val body = row.body
        return try {
            val sentRowId = sink.send(phone, body, subscriptionId)
            if (sentRowId == null) {
                dao.markFailed(intentId, "DISPATCH_REJECTED")
                DiagnosticLog.event("SEND_DELAY", "failed id=$intentId code=DISPATCH_REJECTED")
                DelayedSendStateMachine.Execution.Failed(intentId, "DISPATCH_REJECTED")
            } else {
                dao.markSent(intentId, sentRowId)
                DiagnosticLog.event("SEND_DELAY", "sent id=$intentId row=$sentRowId")
                DelayedSendStateMachine.Execution.Sent(intentId)
            }
        } catch (e: Exception) {
            val code = (e as? DelayedSendSink.SendRejectedException)?.code
                ?: SmsSendFailure.DispatchRejected(null).code
            dao.markFailed(intentId, code)
            DiagnosticLog.event("SEND_DELAY", "failed id=$intentId code=$code", e)
            DelayedSendStateMachine.Execution.Failed(intentId, code)
        }
    }

    /**
     * UNDO — PENDING -> CANCELLED, then cancel the timer.
     *
     * ORDER MATTERS and is the whole atomicity argument for the user's side of
     * the race: the database transition happens FIRST. If the worker already
     * won the claim, this returns [DelayedSendStateMachine.Undo.TooLate] and the
     * UI must say the message is on its way; if the transition succeeds, the
     * worker can never claim afterwards, so a WorkManager cancel that arrives
     * too late (or not at all) is harmless.
     *
     * Cancelling the job first would produce the one outcome that must never
     * exist: a user told "undone" while the radio submits the message anyway.
     */
    suspend fun undo(intentId: String, cancelTimer: (String) -> Unit): DelayedSendStateMachine.Undo {
        val row = dao.byId(intentId)
        val cancelled = dao.cancelPending(intentId) == 1
        if (!cancelled) {
            val state = row?.let { DelayedSendState.from(it.state) }
            DiagnosticLog.event(
                "SEND_DELAY",
                "undo refused id=$intentId state=${state?.name ?: "gone"}"
            )
            return DelayedSendStateMachine.Undo.TooLate(intentId, state)
        }
        DiagnosticLog.event("SEND_DELAY", "cancelled id=$intentId")
        // Best effort, and deliberately AFTER the durable state flip.
        runCatching { cancelTimer(intentId) }
            .onFailure { DiagnosticLog.event("SEND_DELAY", "cancel-timer failed id=$intentId", it) }
        return DelayedSendStateMachine.Undo.Cancelled(intentId, row?.body.orEmpty())
    }

    /**
     * Startup maintenance, run once per process.
     *
     *  * a row stranded in SENDING by a process death is marked FAILED — never
     *    re-claimed, because the radio may already have it;
     *  * terminal rows past their retention window are pruned, so the table
     *    stays a bounded work queue rather than a growing message archive.
     */
    suspend fun reconcileOnStartup(
        now: Long = System.currentTimeMillis(),
        staleAfterMillis: Long = STRANDED_AFTER_MILLIS,
        retentionMillis: Long = TERMINAL_RETENTION_MILLIS
    ) {
        val stranded = dao.failStrandedSending(
            DelayedSendSql.CODE_PROCESS_DIED,
            now - staleAfterMillis
        )
        if (stranded > 0) {
            DiagnosticLog.event("SEND_DELAY", "reconciled stranded=$stranded")
        }
        val pruned = dao.pruneTerminalBefore(now - retentionMillis)
        if (pruned > 0) {
            DiagnosticLog.event("SEND_DELAY", "pruned terminal=$pruned")
        }
    }

    /** Diagnostics: a compact, body- and number-free snapshot of the ledger. */
    suspend fun diagnosticsSnapshot(): String =
        dao.countByState().joinToString(",") { "${it.state}=${it.c}" }

    private fun skipped(intentId: String, reason: String) =
        DelayedSendStateMachine.Execution.Skipped(intentId, reason)

    private companion object {
        /**
         * A row still SENDING after this long was interrupted inside the radio
         * call. It is never re-sent; the delay only decides when the failure is
         * written down.
         */
        const val STRANDED_AFTER_MILLIS = 10 * 60 * 1000L

        /** Terminal rows are kept briefly so the UI can settle, then pruned. */
        const val TERMINAL_RETENTION_MILLIS = 24 * 60 * 60 * 1000L

        /** The job's recipient and the ledger's token disagreed. Never send. */
        const val CODE_RECIPIENT_MISMATCH = "RECIPIENT_MISMATCH"

        /** The persisted vocabulary is the enum's; asserted once, not per row. */
        private var vocabularyChecked = false

        fun assertVocabulary() {
            if (vocabularyChecked) return
            vocabularyChecked = true
            check(DelayedSendState.PENDING.name == com.autonomousone.messages.data.PENDING_STATE)
            check(DelayedSendState.SENDING.name == com.autonomousone.messages.data.SENDING_STATE)
            check(DelayedSendState.SENT.name == com.autonomousone.messages.data.SENT_STATE)
            check(DelayedSendState.FAILED.name == com.autonomousone.messages.data.FAILED_STATE)
            check(DelayedSendState.CANCELLED.name == com.autonomousone.messages.data.CANCELLED_STATE)
        }
    }
}

/**
 * The one way a delayed send reaches telephony.
 *
 * A functional interface so the executor can be unit-tested on the JVM with a
 * counting fake, and so neither the executor nor the sender is reachable from
 * the other without going through the claim.
 */
fun interface DelayedSendSink {
    /**
     * Hands the message to telephony.
     *
     * @param subscriptionId explicit SIM for this message, or null for the
     *        user's global Messaging preference.
     * @return the persisted provider row id, or null when telephony REFUSED the
     *         submit. Throwing is also allowed for a hard failure.
     */
    suspend fun send(phone: String, body: String, subscriptionId: Int?): Long?

    /** Stable failure code carried out of a throwing send. */
    class SendRejectedException(val code: String, cause: Throwable? = null) :
        RuntimeException(code, cause)
}

/** The production sink: the SAME [SmsSender] pipeline every other send uses. */
class SmsSenderDelayedSendSink(private val context: Context) : DelayedSendSink {
    override suspend fun send(phone: String, body: String, subscriptionId: Int?): Long? =
        SmsSender(context).sendForResult(phone, body, subscriptionId)
}

// ── Mapping helpers ──────────────────────────────────────────────────────────

/** Domain view of a row, for the pure state machine. */
fun PendingDelayedSendEntity.toDomain(
    state: DelayedSendState = DelayedSendState.from(this.state)
): PendingDelayedSend = PendingDelayedSend(
    intentId = intentId,
    body = body,
    phoneToken = phoneToken,
    threadId = threadId,
    state = state,
    dueAt = dueAt,
    createdAt = createdAt,
    claimedAt = claimedAt,
    sentRowId = sentRowId,
    attempts = attempts,
    failureCode = failureCode
)

/** Live intents of one thread, as domain objects. */
fun PendingDelayedSendDao.observeLiveDomain(threadId: Long): Flow<List<PendingDelayedSend>> =
    observeLiveForThread(threadId).map { rows -> rows.map { it.toDomain() } }

/**
 * The worker's hand-off into [DelayedSendExecutor].
 *
 * Kept as an object so [ScheduledSms.SendWorker] — the single WorkManager entry
 * point for every scheduled send — stays the only place that constructs the
 * production wiring, and so the "no send without a claim" rule has exactly one
 * implementation to audit.
 *
 * The recipient comes from the durable WorkManager request, NOT from the ledger
 * row: the row stores only `DiagnosticLog.phoneToken`, so the ledger can never
 * become a second, unredacted address book.
 */
internal object DelayedSendWorkerBody {

    /**
     * The job's `body` is deliberately NOT a parameter.
     *
     * The ledger row is the durable copy the pending bubble rendered and that
     * Undo would have restored, so it is the message of record; accepting the
     * worker's copy as well would only create a second candidate for "what the
     * user actually asked to send".
     */
    suspend fun run(
        context: Context,
        intentId: String,
        phone: String,
        subscriptionId: Int?
    ): androidx.work.ListenableWorker.Result {
        val dao = MessagesDatabase.get(context.applicationContext).pendingDelayedSendDao()
        val executor = DelayedSendExecutor(dao, SmsSenderDelayedSendSink(context.applicationContext))

        return when (val outcome = executor.execute(intentId, phone, subscriptionId)) {
            is DelayedSendStateMachine.Execution.Sent -> {
                // Tell any open screen a real message now exists.
                SmsEventBus.notifyResume()
                androidx.work.ListenableWorker.Result.success()
            }

            // A skipped run is a SUCCESS from WorkManager's point of view: the
            // work item is done, and retrying it could only ever re-lose the
            // claim. Reporting failure here would make WorkManager retry a
            // no-op forever.
            is DelayedSendStateMachine.Execution.Skipped ->
                androidx.work.ListenableWorker.Result.success()

            // A failed send is NOT retried: the claim is consumed, and retrying
            // means submitting the same message again.
            is DelayedSendStateMachine.Execution.Failed ->
                androidx.work.ListenableWorker.Result.failure()
        }
    }
}
