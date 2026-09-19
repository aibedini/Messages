package com.autonomousone.messages.messaging

import android.content.Context
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.PendingDelayedSendEntity
import com.autonomousone.messages.sms.DelayPlan
import com.autonomousone.messages.sms.DelayedSendExecutor
import com.autonomousone.messages.sms.DelayedSendGate
import com.autonomousone.messages.sms.DelayedSendSink
import com.autonomousone.messages.sms.DelayedSendState
import com.autonomousone.messages.sms.DelayedSendStateMachine
import com.autonomousone.messages.sms.PendingDelayedSend
import com.autonomousone.messages.sms.ScheduledSms
import com.autonomousone.messages.sms.SendSource
import com.autonomousone.messages.utils.DiagnosticLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * v3.4.0 FEATURE 11 — the composer's door to the send pipeline.
 *
 * One entry point, [send], decides between the two and only two ways a message
 * can leave a composed chat:
 *
 *  * DELAY OFF (the default) → [DelayedSendGate] returns `Immediate` and the
 *    caller's direct [DelayedSendSink] is used. No ledger row is written, no
 *    WorkManager job is enqueued: this is the exact pre-3.4.0 code path.
 *  * DELAY ON → one durable row plus ONE timer job, handed to the app's
 *    existing scheduler ([ScheduledSms]). The caller returns to the composer
 *    immediately; the timer, not the UI, owns delivery.
 *
 * The gate is consulted here rather than at each call site so that a source
 * which must never be delayed (gateway, scheduled, automation, notification
 * reply) cannot inherit the delay by being routed through this class — it is
 * rejected by [SendSource].
 *
 * ── Why the schedule happens AFTER the insert ────────────────────────────────
 * If the timer were enqueued first, a process death between the two writes would
 * leave an armed job with no ledger row — a message that sends with nothing able
 * to cancel it. The reverse order fails SAFE: a row with no timer is a message
 * that does not send (visible, and retried on the next app start), never one
 * that sends when the user believes it was cancelled.
 */
class DelayedSendCoordinator(
    private val context: Context,
    private val sink: DelayedSendSink
) {

    private val appContext = context.applicationContext

    private val dao by lazy {
        MessagesDatabase.get(appContext).pendingDelayedSendDao()
    }

    private val executor by lazy { DelayedSendExecutor(dao, sink) }

    /** The user's configured delay, in millis (0 = OFF). */
    fun delayMillis(): Int = SendDelayPreferences(appContext).delayMillis

    /**
     * Routes ONE composed message.
     *
     * @return what happened, so the caller can clear the composer and choose its
     *         UI (a Snackbar with Undo, or nothing at all).
     */
    suspend fun send(
        phone: String,
        body: String,
        threadId: Long,
        subscriptionId: Int?,
        now: Long = System.currentTimeMillis(),
        source: SendSource = SendSource.COMPOSER,
        /**
         * P0 v3.4.3 — caller-supplied durable id.
         *
         * A caller that must answer "did the ledger accept this?" AFTER a
         * bounded-wait timeout has to know the identity in advance. Letting the
         * coordinator invent it made that question unanswerable, so a timeout
         * that landed the row after the deadline became a second physical send.
         */
        intentId: String? = null
    ): SendResult {
        if (body.isBlank() || phone.isBlank()) return SendResult.Ignored

        return when (val plan = DelayedSendGate.plan(source, delayMillis().toLong(), now)) {
            is DelayPlan.Immediate -> {
                // The unchanged path. A message that is not delayed must not
                // create ANY delay state, or "OFF" would still leave a trace.
                SendResult.SentNow(sink.send(phone, body, subscriptionId))
            }

            is DelayPlan.Delayed -> {
                val durableIntentId = intentId ?: newIntentId()
                val token = DiagnosticLog.phoneToken(phone)
                val row = PendingDelayedSendEntity(
                    intentId = durableIntentId,
                    body = body,
                    phoneToken = token,
                    threadId = threadId,
                    state = DelayedSendState.PENDING.name,
                    dueAt = plan.dueAt,
                    createdAt = now
                )
                // 1. Durable state first (see the class doc for why).
                dao.insert(row)
                DiagnosticLog.event(
                    "SEND_DELAY",
                    "pending id=$durableIntentId token=$token thread=$threadId " +
                        "delay=${plan.delayMillis} due=${plan.dueAt} src=${source.name}"
                )

                // 2. Then the timer, under the intent's own unique name so the
                //    cancel can never touch another message's job.
                val workName = ScheduledSms.schedule(
                    context = appContext,
                    phone = phone,
                    body = body,
                    triggerAtMillis = plan.dueAt,
                    subscriptionId = subscriptionId,
                    workName = ScheduledSms.delayedSendWorkName(durableIntentId),
                    // The ledger row above already owns the pending UI; a second
                    // synthetic bubble here was a THIRD clock bubble on screen.
                    emitOptimistic = false
                )
                DiagnosticLog.event("SEND_DELAY", "armed id=$durableIntentId work=$workName")

                SendResult.DelayedSend(
                    row = row.domain(),
                    delaySeconds = (plan.delayMillis / 1000L)
                )
            }
        }
    }

    /**
     * P0 v3.4.3 — does the durable ledger already own [intentId]?
     *
     * The ONE question the composer asks after a bounded-wait timeout, because
     * it decides between "re-arm the durable timer" and "fall back to a direct
     * send". Answering it from the ledger (not from a wall-clock guess) is what
     * keeps a timeout from becoming a second chargeable submission.
     */
    suspend fun exists(intentId: String): Boolean = dao.byId(intentId) != null

    /**
     * P0 v3.4.3 — re-arm a PENDING intent whose timer enqueue was lost.
     *
     * A bounded-wait timeout can cancel between the durable insert and the
     * `ScheduledSms.schedule` call, leaving a PENDING row that nothing will
     * ever fire. Re-arming is idempotent in effect: `ScheduledSms` enqueues under
     * the intent's own unique name with `REPLACE`, and the initial delay is
     * recomputed as `dueAt - now`, so the deadline does not move.
     *
     * @return the re-armed row, or null when no PENDING row exists (in which
     *         case the caller is free to fall back — nothing was held).
     */
    suspend fun rearmIfPending(
        intentId: String,
        phone: String,
        subscriptionId: Int?
    ): PendingDelayedSend? {
        val row = dao.byId(intentId) ?: return null
        val domain = row.domain()
        if (domain.state != DelayedSendState.PENDING) return null
        ScheduledSms.schedule(
            context = appContext,
            phone = phone,
            body = row.body,
            triggerAtMillis = row.dueAt,
            subscriptionId = subscriptionId,
            workName = ScheduledSms.delayedSendWorkName(intentId),
            emitOptimistic = false
        )
        return domain
    }

    /**
     * UNDO. Flips the durable state, then cancels the timer.
     *
     * The order is the entire atomicity argument (see
     * [DelayedSendExecutor.undo]): if the deadline was already claimed, the UI
     * must be told the message is on its way rather than being allowed to
     * pretend it was stopped.
     */
    suspend fun undo(intentId: String): DelayedSendStateMachine.Undo =
        executor.undo(intentId) { id ->
            ScheduledSms.cancelWork(appContext, ScheduledSms.delayedSendWorkName(id))
        }

    /**
     * Undo the NEWEST undoable message.
     *
     * The Snackbar's Undo carries an explicit id; this exists for the
     * notification-style one-shot undo and for a caller that genuinely means
     * "whatever is currently waiting".
     */
    suspend fun undoNewest(): DelayedSendStateMachine.Undo? {
        val row = dao.undoable() ?: return null
        return undo(row.intentId)
    }

    /** Live (PENDING/SENDING) messages of one conversation, for the composer. */
    fun observeLive(threadId: Long): Flow<List<PendingDelayedSend>> =
        dao.observeLiveForThread(threadId).map { rows -> rows.map { it.domain() } }

    /** Every live message, newest first. */
    suspend fun live(): List<PendingDelayedSend> = dao.live().map { it.domain() }

    /**
     * Startup maintenance: fail sends stranded by a process death inside the
     * radio call, and prune terminal rows. Idempotent, so it is safe to call on
     * every process start.
     */
    suspend fun reconcileOnStartup(now: Long = System.currentTimeMillis()) =
        executor.reconcileOnStartup(now)

    /** Diagnostics: a body-free, number-free snapshot of the ledger. */
    suspend fun diagnosticsSnapshot(): String = executor.diagnosticsSnapshot()

    private fun newIntentId(): String =
        "dly_" + UUID.randomUUID().toString().replace("-", "").take(16)
}

/**
 * Domain view of a ledger row.
 *
 * The state NAME is decoded through [DelayedSendState.from], the single place
 * that owns the persisted vocabulary — an unknown value degrades to FAILED
 * ("never send"), never to PENDING.
 */
private fun PendingDelayedSendEntity.domain(): PendingDelayedSend = PendingDelayedSend(
    intentId = intentId,
    body = body,
    phoneToken = phoneToken,
    threadId = threadId,
    state = DelayedSendState.from(state),
    dueAt = dueAt,
    createdAt = createdAt,
    claimedAt = claimedAt,
    sentRowId = sentRowId,
    attempts = attempts,
    failureCode = failureCode
)

/** Outcome of routing one composed message. */
sealed interface SendResult {

    /** Blank recipient or body: nothing was sent and nothing was queued. */
    data object Ignored : SendResult

    /**
     * Delay OFF (or a non-delayed source): the message went out through the
     * direct pipeline. [rowId] is null when telephony refused it.
     */
    data class SentNow(val rowId: Long?) : SendResult

    /**
     * Delay ON: [row] is durable and its timer is armed. [delaySeconds] is what
     * the Snackbar counts down.
     */
    data class DelayedSend(val row: PendingDelayedSend, val delaySeconds: Long) : SendResult
}

/**
 * "Sending in N seconds" — the one-shot signal a screen renders as a Snackbar
 * with UNDO.
 *
 * [body] is carried so the Undo can put the text back in the composer: the user
 * who taps Undo has, by definition, just lost their draft off the send button.
 */
data class DelayedSendNotice(
    val intentId: String,
    val seconds: Long,
    val body: String
)
