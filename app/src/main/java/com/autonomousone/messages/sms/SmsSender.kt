package com.autonomousone.messages.sms

import android.app.Activity
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.RemoteCommandEntity
import com.autonomousone.messages.data.SegmentCallbackState
import com.autonomousone.messages.data.SendSegmentEntity
import com.autonomousone.messages.event.SmsEventBus
import com.autonomousone.messages.messaging.MessagingPreferences
import com.autonomousone.messages.messaging.SimManager
import com.autonomousone.messages.utils.DiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sends SMS honouring the user's Messaging preferences:
 *  - which SIM subscription sends (Settings > Messaging > SIM line);
 *  - a custom SMSC address (empty = network default);
 *  - delivery reports (SENT/DELIVERED broadcasts persist status back into the
 *    Telephony.Sms provider so the conversation UI can show Delivered/Failed).
 *
 * Nothing is enabled by default: until the user opts in, behaviour matches the
 * platform default (system subscription, network SMSC, no delivery reports).
 */
class SmsSender(
    private val context: Context
) {

    private val prefs by lazy { MessagingPreferences(context) }

    /**
     * Sends an SMS and persists it to Telephony.Sms.Sent immediately.
     * Returns the persisted row ID (or a timestamp fallback).
     *
     * On Android, SmsManager.sendTextMessage() does NOT automatically save sent
     * messages for non-default SMS apps. We must write to Sent manually so the
     * ConversationViewModel's DB-reload (triggered by SmsContentObserver) finds it.
     */
    fun send(phone: String, text: String): Long = send(phone, text, null, null)

    /**
     * Same as [send] but with per-call overrides used by the REST gateway:
     *  - [subscriptionIdOverride]: explicit SIM subscription for this message
     *    only (null = fall back to the user's Messaging preference);
     *  - [smscOverride]: explicit SMSC address for this message only
     *    (null/blank = fall back to the user's preference).
     *
     * PR-03: delegates to [sendWithOutcome] — the single funnel — so every
     * send source shares one pipeline; the row id (or fallback timestamp) is
     * recovered from the outcome.
     */
    fun send(phone: String, text: String, subscriptionIdOverride: Int?, smscOverride: String?): Long {
        // Respect the user's send rate limit (protects the SIM from throttling).
        val prefs2 = prefs
        if (prefs2.rateLimitEnabled) {
            com.autonomousone.messages.sms.SendRateLimiter.enabled = true
            com.autonomousone.messages.sms.SendRateLimiter.maxMessages = prefs2.rateLimitCount
            com.autonomousone.messages.sms.SendRateLimiter.windowMillis =
                prefs2.rateLimitWindowMin * 60_000L
            val waitMs = com.autonomousone.messages.sms.SendRateLimiter.acquireSlot()
            if (waitMs > 0) {
                try { Thread.sleep(waitMs) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                com.autonomousone.messages.sms.SendRateLimiter.record()
            }
        }
        return when (val outcome = sendWithOutcome(phone, text, subscriptionIdOverride, smscOverride, showToast = true)) {
            is SendOutcome.Accepted -> outcome.rowId
            is SendOutcome.Rejected -> outcome.rowId ?: -1L
        }
    }

    /** Legacy direct path: the ONLY place SmsManager is ever touched. */
    private fun directSend(
        phone: String,
        text: String,
        subscriptionIdOverride: Int?,
        smscOverride: String?,
        showToast: Boolean,
        originCommandId: String? = null,
        clientMessageId: String? = null,
    ): SendOutcome {
        val sentId = persistToSent(phone, text)
        com.autonomousone.messages.data.TelephonySyncCoordinator.get(context)
            .providerRowChanged("sms", sentId, originCommandId, clientMessageId)
        // Tell the app (Home list) instantly: this thread now has a newer
        // message. Works even while the chat screen is still on top. Emitted
        // exactly once per executed command (every funnel path runs through
        // here exactly once).
        SmsEventBus.emitOutgoingSent(
            threadId = 0L, // resolved by Home via phone match
            phone = phone,
            message = text,
            date = System.currentTimeMillis(),
            providerRowId = sentId
        )
        val dispatched = dispatch(sentId, phone, text, subscriptionIdOverride, smscOverride, showToast)
        return if (dispatched) {
            SendOutcome.Accepted(rowId = sentId)
        } else {
            SendOutcome.Rejected(rowId = sentId, reason = "modem rejected send (see message STATUS_FAILED)")
        }
    }

    /**
     * Explicit outcome for machine callers (REST gateway, EVE queue).
     *
     * This entry point is NON-BLOCKING by construction: it takes the direct
     * path and never waits on a coroutine. The durable remote_commands path
     * needs a coroutine context and lives in [sendWithOutcomeSuspend].
     *
     * History: this method used to launch on a throwaway CoroutineScope and
     * then `CountDownLatch.await(10s)` on the caller's thread. That is exactly
     * the ANR pattern this project forbids, it is banned by the engineering
     * rules, and it was unreachable anyway (GatewayOutgoingPipeline.
     * ENQUEUE_ALL_SENDS is false in every shipped configuration).
     */
    fun sendWithOutcome(
        phone: String,
        text: String,
        subscriptionIdOverride: Int? = null,
        smscOverride: String? = null,
        showToast: Boolean = false,
        threadId: Long = 0L,
        originCommandId: String? = null,
        clientMessageId: String? = null,
    ): SendOutcome {
        requireOffMainThread()
        return directSend(
            phone, text, subscriptionIdOverride, smscOverride, showToast,
            originCommandId, clientMessageId
        )
    }

    /**
     * Structured-async variant of [sendWithOutcome].
     *
     * Suspends instead of blocking: no ad-hoc CoroutineScope, no CountDownLatch
     * and no caller thread is ever parked waiting for another coroutine.
     * When the durable pipeline is enabled the remote_commands row is committed
     * before the physical send, so a crash between the two is recoverable.
     */
    suspend fun sendWithOutcomeSuspend(
        phone: String,
        text: String,
        subscriptionIdOverride: Int? = null,
        smscOverride: String? = null,
        showToast: Boolean = false,
        threadId: Long = 0L,
        originCommandId: String? = null,
        clientMessageId: String? = null,
    ): SendOutcome = withContext(Dispatchers.IO) {
        if (!GatewayOutgoingPipeline.ENQUEUE_ALL_SENDS) {
            return@withContext directSend(
                phone, text, subscriptionIdOverride, smscOverride, showToast,
                originCommandId, clientMessageId
            )
        }
        // ── Durable path: remote_commands row → execute → mark ─────────────
        val idempotencyKey = clientMessageId ?: java.util.UUID.randomUUID().toString()
        try {
            val plan = GatewayOutgoingPipeline.enqueueSendSms(
                phone = phone,
                body = text,
                threadId = threadId,
                subscriptionId = subscriptionIdOverride,
                idempotencyKey = idempotencyKey,
                clientMessageId = clientMessageId
            )
            val repo = com.autonomousone.messages.repository.GatewaySyncRepository(
                com.autonomousone.messages.data.MessagesDatabase.get(
                    com.autonomousone.messages.Holders.appContext
                )
            )
            if (repo.markCommandAcceptedIfReceived(plan.commandId)) {
                val outcome = directSend(
                    phone, text, subscriptionIdOverride, smscOverride, showToast,
                    plan.commandId, idempotencyKey
                )
                repo.markCommandState(
                    plan.commandId,
                    if (outcome is SendOutcome.Accepted) RemoteCommandEntity.STATE_COMPLETED
                    else RemoteCommandEntity.STATE_FAILED,
                    listOf(RemoteCommandEntity.STATE_ACCEPTED, RemoteCommandEntity.STATE_EXECUTING)
                )
                outcome
            } else {
                // Redelivery of a live idempotency key: do not execute twice.
                SendOutcome.Rejected(rowId = null, reason = "duplicate command")
            }
        } catch (e: Exception) {
            Log.e(TAG, "pipeline enqueue failed", e)
            SendOutcome.Rejected(rowId = null, reason = "enqueue failed")
        }
    }

    /**
     * Main-thread guard that is enforced in EVERY build, not just DEBUG: a
     * blocking provider/Room/radio call on Main is an ANR, and the assertion
     * must not exist only in the build users do not run.
     */
    private fun requireOffMainThread() {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            "SmsSender must never run on the main thread"
        }
    }

    /**
     * The result of an IDEMPOTENT send (mission §78).
     *
     * A third outcome exists here rather than in [SendOutcome] on purpose. "Already sent" is not
     * "accepted now" and it is certainly not "failed", but widening [SendOutcome] would change the
     * meaning of every existing `when` over it — on the send path — for the benefit of one caller.
     * The safety-critical paths keep their two-valued model and this endpoint gets an exact answer.
     */
    sealed interface IdempotentSendOutcome {
        /** This call handed the message to telephony. */
        data class Sent(val rowId: Long) : IdempotentSendOutcome

        /**
         * A previous call with the same idempotency key already sent it, so NOTHING was sent now.
         *
         * [rowId] is null because the earlier call's provider row is not recorded on the command row;
         * the caller's real need is to know that retrying is pointless and safe, which this answers.
         */
        data object AlreadySent : IdempotentSendOutcome

        /** Nothing was sent and nothing will be by this key. */
        data class Rejected(val reason: String) : IdempotentSendOutcome
    }

    /**
     * Durable, idempotent send: the ONE web-facing send that can be safely retried.
     *
     * `POST /api/v1/sms/send` had no dedupe of any kind. A caller whose connection dropped after the
     * phone accepted the message had no way to retry safely — a retry was a second physical SMS — which
     * is the mission's second absolute invariant, on a remotely callable surface.
     *
     * Correctness comes from the SAME unique `idempotencyKey` index every other durable send uses:
     * the row is inserted with `INSERT OR IGNORE`, and the claim that follows is a guarded
     * `RECEIVED → ACCEPTED` update. Whoever wins the claim sends; a redelivery of the key loses it and
     * reports [IdempotentSendOutcome.AlreadySent]. No new table, no new index, and the same audit row
     * as every other send.
     *
     * @param idempotencyKey the caller's identity for this request. Required: without one there is
     *   nothing to dedupe on, which is why the caller must supply it rather than have the app invent it.
     */
    suspend fun sendIdempotent(
        phone: String,
        text: String,
        subscriptionIdOverride: Int? = null,
        smscOverride: String? = null,
        showToast: Boolean = false,
        threadId: Long = 0L,
        idempotencyKey: String,
        clientMessageId: String? = null,
    ): IdempotentSendOutcome = withContext(Dispatchers.IO) {
        requireOffMainThread()
        if (idempotencyKey.isBlank()) {
            return@withContext IdempotentSendOutcome.Rejected("idempotency key required")
        }
        try {
            val plan = GatewayOutgoingPipeline.enqueueSendSms(
                phone = phone,
                body = text,
                threadId = threadId,
                subscriptionId = subscriptionIdOverride,
                idempotencyKey = idempotencyKey,
                clientMessageId = clientMessageId,
            )
            val repo = com.autonomousone.messages.repository.GatewaySyncRepository(
                com.autonomousone.messages.data.MessagesDatabase.get(
                    com.autonomousone.messages.Holders.appContext
                )
            )
            if (!repo.markCommandAcceptedIfReceived(plan.commandId)) {
                // The key is already known: either it was sent, or it is in flight, or it failed. In
                // every case THIS call must not hand anything to the radio.
                return@withContext IdempotentSendOutcome.AlreadySent
            }
            val outcome = directSend(
                phone, text, subscriptionIdOverride, smscOverride, showToast,
                plan.commandId, clientMessageId
            )
            repo.markCommandState(
                plan.commandId,
                if (outcome is SendOutcome.Accepted) RemoteCommandEntity.STATE_COMPLETED
                else RemoteCommandEntity.STATE_FAILED,
                listOf(RemoteCommandEntity.STATE_ACCEPTED, RemoteCommandEntity.STATE_EXECUTING)
            )
            when (outcome) {
                is SendOutcome.Accepted -> IdempotentSendOutcome.Sent(outcome.rowId)
                is SendOutcome.Rejected -> IdempotentSendOutcome.Rejected(outcome.reason)
            }
        } catch (e: Exception) {
            Log.e(TAG, "idempotent send failed", e)
            IdempotentSendOutcome.Rejected("enqueue failed")
        }
    }

    /**
     * Worker-thread blocking form of [sendIdempotent].
     *
     * The local REST gateway handles a request on its own executor thread and is synchronous end to
     * end, so it cannot call a suspend function. This mirrors [sendWithOutcome]'s contract: blocking is
     * fine off the main thread, and [requireOffMainThread] enforces that in every build.
     *
     * Deliberately NOT given a timeout. A timeout that fires mid-hand-off cannot report truthfully —
     * the SMS may have left — and the existing direct path on this same thread has no timeout either.
     * What protects the caller is the idempotency key: if this call's result is unknown, retrying it is
     * safe by construction.
     */
    fun sendIdempotentBlocking(
        phone: String,
        text: String,
        subscriptionIdOverride: Int? = null,
        smscOverride: String? = null,
        showToast: Boolean = false,
        threadId: Long = 0L,
        idempotencyKey: String,
        clientMessageId: String? = null,
    ): IdempotentSendOutcome {
        requireOffMainThread()
        return kotlinx.coroutines.runBlocking {
            sendIdempotent(
                phone = phone,
                text = text,
                subscriptionIdOverride = subscriptionIdOverride,
                smscOverride = smscOverride,
                showToast = showToast,
                threadId = threadId,
                idempotencyKey = idempotencyKey,
                clientMessageId = clientMessageId,
            )
        }
    }

    /** Explicit result of a send hand-off to telephony. */
    sealed interface SendOutcome {
        /** Handed to SmsManager successfully; SENT/DELIVERED callbacks follow. */
        data class Accepted(val rowId: Long) : SendOutcome

        /** Telephony refused the send (SIM unavailable, radio off, ...). */
        data class Rejected(val rowId: Long?, val reason: String) : SendOutcome
    }

    /**
     * Silent variant for machine callers (e.g. the EVE send queue):
     * persists + dispatches without user-facing toasts.
     * @return persisted row id on successful hand-off to telephony,
     *         or null when dispatch failed.
     *
     * PR-03: through the single funnel (durable queue when the flag is on).
     */
    fun sendForResult(phone: String, text: String): Long? =
        sendForResult(phone, text, null)

    /**
     * Same as [sendForResult] with an explicit SIM for this message only.
     *
     * v3.4.0 (Send delay): a message held for N seconds is dispatched in a NEW
     * process, so the in-chat SIM choice has to be carried into the durable
     * request and handed back here — otherwise the message would leave on the
     * global default line instead of the one the user picked.
     *
     * @param subscriptionIdOverride null = the user's Messaging preference.
     */
    fun sendForResult(phone: String, text: String, subscriptionIdOverride: Int?): Long? =
        sendForResult(phone, text, subscriptionIdOverride, clientMessageId = null)

    /**
     * Same as [sendForResult], carrying the REQUESTER's correlation key through to the event pipeline
     * (mission §49).
     *
     * The legacy pull bridge knows the key GMweb attached to the task it pulled, and until this
     * parameter existed there was nowhere to put it: the queue's sender seam took `(to, text)` and
     * nothing else, so a web-requested send reached GMweb as an uncorrelated message and its later
     * DELIVERED/FAILED transition could not be matched to the bubble the web had drawn. The key
     * travelled as far as `EveSmsQueue.Record.correlationId` and then stopped.
     *
     * It is passed as `clientMessageId` — the same payload field the strategic path already sets —
     * and deliberately NOT as `originCommandId`: that field means "a durable `remote_commands` row
     * caused this", and on this path no such row exists. Inventing one would make the event claim a
     * command that cannot be looked up.
     */
    fun sendForResult(
        phone: String,
        text: String,
        subscriptionIdOverride: Int?,
        clientMessageId: String?
    ): Long? =
        when (
            val outcome = sendWithOutcome(
                phone, text, subscriptionIdOverride, clientMessageId = clientMessageId
            )
        ) {
            is SendOutcome.Accepted -> outcome.rowId
            is SendOutcome.Rejected -> null
        }

    /** Dispatches via the selected SIM/SMSC; updates STATUS on failure. */
    private fun dispatch(
        sentId: Long,
        phone: String,
        text: String,
        subscriptionIdOverride: Int?,
        smscOverride: String?,
        showToast: Boolean
    ): Boolean {
        val bound = resolveSmsManager(subscriptionIdOverride)

        // v2.6.14 — Effective SMSC, strictly user intent:
        //   per-request override → this SIM's manual override → global manual
        //   override → null (= "use the SMSC saved on the SIM", Android's
        //   documented default). v2.6.13's hidden carrier-directory seeding is
        //   GONE: an address the user never chose must not override what the
        //   (U)SIM itself carries — a mismatch with the SIM's real SMSC can
        //   itself cause radio-side GENERIC_FAILURE.
        //
        // Resolved from the REQUEST, because the SMSC is chosen for the line the user meant to use.
        val requestedSubId = subscriptionIdOverride ?: prefs.sendSubscriptionId
            .takeIf { it != MessagingPreferences.SUBSCRIPTION_UNSET }

        // Mission §50: the id the user asked for and the id the manager is bound to are two facts,
        // and only the second belongs in the ledger. Deciding here — before anything is handed to the
        // radio — is what makes the refusal possible at all.
        val decision = SendSimPolicy.decide(
            requested = requestedSubId,
            actual = bound.actualSubscriptionId,
            requestedSimActive = requestedSimActive(requestedSubId)
        )
        // A refusal based on a PROVABLE mismatch. Letting it through would put the message on a line
        // the user did not choose, and a sent SMS cannot be recalled.
        if (decision is SimDecision.Refuse) {
            return rejectForSimMismatch(sentId, phone, requestedSubId, bound.actualSubscriptionId, showToast)
        }
        val manager = bound.manager
        // The line that will actually carry the message (or an explicit "unknown"), never the request.
        val recordedSubId = (decision as SimDecision.Send).recordedSubscriptionId
        val scAddress = smscOverride?.trim()?.takeIf { it.isNotBlank() }
            ?: prefs.smscForSim(requestedSubId ?: MessagingPreferences.SUBSCRIPTION_UNSET)
            ?: prefs.smscAddress.trim().takeIf { it.isNotBlank() }
        val wantReports = prefs.deliveryReportsEnabled

        // Kept outside the try so a synchronous rejection can report how many
        // parts were about to be submitted.
        var attemptedParts = 1
        try {
            // Split long messages into multi-part SMS if needed
            val parts = manager.divideMessage(text)
            attemptedParts = parts.size
            DiagnosticLog.event(
                "SMS_SEND",
                "dispatch row=$sentId phone=${DiagnosticLog.phoneToken(phone)} " +
                    "sub=$recordedSubId parts=${parts.size} reports=$wantReports " +
                    "smsc=${if (scAddress == null) "sim-default" else "manual"}"
            )
            // Use the exact modem split for both callbacks and accounting.
            // A SENT callback resolves local hand-off telemetry. Delivery
            // callbacks request the network SMS-STATUS-REPORT PDU and are ON by
            // default; the user may opt out in Messaging settings.
            //
            // `recordedSubId` and not the request: these callbacks write the per-SIM segment ledger,
            // and passing the requested id there is how a message came to be attributed to a line
            // that never carried it.
            val sentIntents = ArrayList<PendingIntent>(parts.size).apply {
                repeat(parts.size) { part ->
                    add(buildStatusPendingIntent(
                        SmsStatusReceiver.ACTION_SMS_SENT, sentId, part, parts.size, recordedSubId
                    ))
                }
            }
            val deliveredIntents = if (wantReports) {
                ArrayList<PendingIntent>(parts.size).apply {
                    repeat(parts.size) { part ->
                        add(buildStatusPendingIntent(
                            SmsStatusReceiver.ACTION_SMS_DELIVERED, sentId, part, parts.size, recordedSubId
                        ))
                    }
                }
            } else null
            if (parts.size > 1) {
                manager.sendMultipartTextMessage(
                    phone,
                    scAddress,
                    parts,
                    sentIntents,
                    deliveredIntents
                )
            } else {
                manager.sendTextMessage(
                    phone, scAddress, text, sentIntents.single(), deliveredIntents?.single()
                )
            }

            // The radio accepted the submit (no synchronous dispatch exception)
            // → every part is carrier-billable NOW. This writes the IMMUTABLE
            // submission fact the Home counter reads; the SENT callback only
            // annotates the row and can never move or remove it.
            recordSegmentSubmissions(sentId, parts.size, recordedSubId)

            Log.d(
                TAG,
                "SMS queued to $phone (id=$sentId, subId=$recordedSubId, " +
                    "smsc=${if (scAddress != null) "custom" else "network"}, reports=$wantReports)"
            )
            DiagnosticLog.event("SMS_SEND", "accepted row=$sentId parts=${parts.size}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error sending SMS to $phone", e)
            DiagnosticLog.event(
                "SMS_SEND",
                "dispatch-exception row=$sentId phone=${DiagnosticLog.phoneToken(phone)}",
                e
            )
            updateStatus(sentId, Telephony.Sms.STATUS_FAILED)
            // A synchronous rejection must never disappear into Logcat: persist a
            // typed reason (and the provider STATUS_FAILED above) so the bubble
            // stays Failed across restarts instead of silently looking queued.
            recordDispatchRejection(sentId, attemptedParts, recordedSubId, SmsSendFailure.DispatchRejected(null), e)
            if (showToast) {
                Toast.makeText(context, e.message ?: "Failed to send SMS", Toast.LENGTH_LONG).show()
            }
            return false
        }
    }

    /**
     * Refuse a send the app can PROVE would leave on the wrong line (mission §50).
     *
     * Reached when the resolved `SmsManager` reports a subscription other than the one the user
     * chose. The alternative — sending anyway — puts a message on a line they did not pick, and an
     * SMS cannot be recalled, so refusing is the only reversible option.
     *
     * The failure is made durable through the SAME path as any other dispatch rejection: the provider
     * row is marked `STATUS_FAILED` (so the bubble shows Failed across restarts rather than looking
     * queued), and the segment ledger records `SIM_UNAVAILABLE` with a NULL `submittedAt`, so the
     * daily submission counter cannot count a message that never left the device.
     *
     * @return false, so every caller treats it exactly like any other failed send.
     */
    private fun rejectForSimMismatch(
        sentId: Long,
        phone: String,
        requestedSubId: Int?,
        actualSubId: Int,
        showToast: Boolean
    ): Boolean {
        updateStatus(sentId, Telephony.Sms.STATUS_FAILED)
        // One part is the floor for a ledger row: the message was never even split, so there is no
        // modem part count to record, and 0 would make the ledger write a no-op.
        recordDispatchRejection(sentId, 1, actualSubId, SmsSendFailure.SimUnavailable, null)
        DiagnosticLog.event(
            "SMS_SEND",
            "sim-mismatch-refused row=$sentId phone=${DiagnosticLog.phoneToken(phone)} " +
                "requested=$requestedSubId actual=$actualSubId"
        )
        Log.w(
            TAG,
            "Refusing to send on SIM $actualSubId when the user selected $requestedSubId (mission §50)"
        )
        if (showToast) {
            Toast.makeText(
                context,
                "Selected SIM is unavailable — message not sent. Check the SIM setting.",
                Toast.LENGTH_LONG
            ).show()
        }
        return false
    }

    /**
     * Records the immutable native-submission fact for every part of a submit
     * the radio accepted.
     *
     * Counting must not depend on the SENT broadcast arriving, and a later
     * callback must never be able to change WHEN the segment was submitted:
     * submittedAt is written here, once, and is never overwritten.
     *
     * A callback can reach the ledger first — it runs on the receiver's own
     * coroutine. Then this insert is ignored and
     * [SendSegmentDao.fillSubmittedAtIfMissing] completes the row, so both
     * orderings converge to the same final row.
     */
    private fun recordSegmentSubmissions(rowId: Long, partCount: Int, subId: Int?) {
        if (rowId <= 0L || partCount <= 0) return
        val submittedAt = System.currentTimeMillis()
        ledgerScope.launch {
            try {
                val dao = MessagesDatabase.get(context.applicationContext).sendSegmentDao()
                for (part in 0 until partCount) {
                    val inserted = dao.insertSubmission(
                        SendSegmentEntity(
                            rowId = rowId,
                            partIndex = part,
                            partCount = partCount,
                            submittedAt = submittedAt,
                            subscriptionId = subId ?: -1
                        )
                    )
                    if (inserted == -1L) {
                        // A callback reached the ledger first: complete the
                        // submission fact without disturbing the callback half.
                        dao.fillSubmittedAtIfMissing(rowId, part, submittedAt)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "segment ledger submission-write failed id=" + rowId, e)
            }
        }
    }


    /**
     * Records a synchronous dispatch rejection (the SmsManager call itself
     * threw) as a durable, typed failure on the ledger.
     *
     * submittedAt is NULL on purpose: the segment was NEVER submitted, so it
     * must never be counted by the daily submission counter — it exists purely
     * to carry the reason. This is the same NULL-submission semantics the
     * callback-first race uses, so the "SMS today" ledger cannot regress.
     *
     * [failure] is supplied by the caller rather than hardcoded, because the reason is not always
     * "the platform threw": a send refused for a SIM mismatch (mission §50) never reached the
     * platform at all, and recording it as `DISPATCH_REJECTED` would name the wrong cause. That is
     * also what constructs [SmsSendFailure.SimUnavailable], which existed and was never used.
     */
    private fun recordDispatchRejection(
        rowId: Long,
        partCount: Int,
        subId: Int?,
        failure: SmsSendFailure,
        error: Exception?
    ) {
        if (rowId <= 0L || partCount <= 0) return
        val at = System.currentTimeMillis()
        val code = failure.code
        ledgerScope.launch {
            try {
                val dao = MessagesDatabase.get(context.applicationContext).sendSegmentDao()
                for (part in 0 until partCount) {
                    dao.insertSubmission(
                        SendSegmentEntity(
                            rowId = rowId,
                            partIndex = part,
                            partCount = partCount,
                            submittedAt = null,
                            subscriptionId = subId ?: -1,
                            callbackAt = at,
                            callbackResult = Activity.RESULT_CANCELED,
                            callbackState = SegmentCallbackState.FAILED,
                            callbackFailureCode = code
                        )
                    )
                    dao.applyCallback(
                        rowId = rowId,
                        partIndex = part,
                        callbackAt = at,
                        callbackResult = Activity.RESULT_CANCELED,
                        callbackState = SegmentCallbackState.FAILED,
                        callbackFailureCode = code
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "dispatch-rejection ledger write failed id=" + rowId, e)
            }
        }
        DiagnosticLog.event("SMS_SEND", "dispatch-rejected row=" + rowId + " code=" + code, error)
    }

    /**
     * The `SmsManager` to send through, and the subscription it is ACTUALLY bound to.
     *
     * [actualSubscriptionId] is read back from the manager rather than assumed from the request,
     * because the two are the whole subject of mission §50: the ledger must record the line that
     * carried the message, and the id the user asked for is not evidence of that.
     */
    private data class BoundSim(val manager: SmsManager, val actualSubscriptionId: Int)

    /**
     * Returns an `SmsManager` bound to the given SIM subscription ([override] first, then the user's
     * saved selection), or the platform default when neither is set.
     *
     * **The pre-Android-12 branch used to ignore the selection entirely**, on the stated grounds that
     * "the per-subscription manager API is no longer exposed by current SDK stubs". That was wrong:
     * `getSmsManagerForSubscriptionId(int)` is public since API 22 and present in the SDK this app
     * compiles against, and `minSdk` here is 26 — so on every supported device below Android 12 a
     * chosen line was silently ignored. It is deprecated from API 31, not removed, which is why it
     * carries a suppression rather than a fallback.
     */
    /**
     * Whether the chosen SIM is in the device's active list (mission §50).
     *
     * Three-valued on purpose. `false` is proof the line is gone and licenses a refusal; `null` means
     * the app could not find out — `READ_PHONE_STATE` not granted, or the platform refused — and must
     * never be read as absence, or every send would be refused on a device that withholds the list.
     * The only place that distinction can be lost is at the boundary between this and the policy, so
     * the boundary carries all three states.
     */
    private fun requestedSimActive(requested: Int?): Boolean? {
        if (requested == null) return null
        val actives = runCatching { SimManager(context).getActiveSims() }.getOrDefault(emptyList())
        if (actives.isEmpty()) return null
        return actives.any { it.subscriptionId == requested }
    }

    private fun resolveSmsManager(override: Int? = null): BoundSim {        val subId = override ?: prefs.sendSubscriptionId
        val hasSelection = subId != MessagingPreferences.SUBSCRIPTION_UNSET
        val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = context.getSystemService(SmsManager::class.java)
            if (hasSelection) base.createForSubscriptionId(subId) else base
        } else {
            @Suppress("DEPRECATION")
            if (hasSelection) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
        }
        // Read back rather than assume. A manager created for an inactive subscription can be bound
        // to nothing, and on some platforms the call is simply refused; either way the answer here is
        // the only fact available about which line will carry the message.
        val actual = runCatching { manager.subscriptionId }
            .getOrDefault(SendSimPolicy.UNKNOWN_SUBSCRIPTION_ID)
        return BoundSim(manager, actual)
    }

    private fun buildStatusPendingIntent(
        action: String,
        rowId: Long,
        partIndex: Int,
        partCount: Int,
        subscriptionId: Int? = null
    ): PendingIntent {
        val intent = Intent(context, SmsStatusReceiver::class.java)
            .setAction(action)
            .putExtra(SmsStatusReceiver.EXTRA_ROW_ID, rowId)
            .putExtra(SmsStatusReceiver.EXTRA_PART_INDEX, partIndex)
            .putExtra(SmsStatusReceiver.EXTRA_PART_COUNT, partCount)
            .putExtra(SmsStatusReceiver.EXTRA_SEND_ATTEMPT_ID, rowId)
            // Which SIM actually carried this part — feeds the per-SIM send
            // ledger (null = platform default; recorded as -1/unknown).
            .putExtra(SmsStatusReceiver.EXTRA_SUBSCRIPTION_ID, subscriptionId ?: -1)
        val requestCode = 31 * (31 * action.hashCode() + rowId.hashCode()) + partIndex
        // SmsManager fills callback-only extras (delivery "pdu" and optional
        // SENT "errorCode") when firing this PendingIntent. FLAG_IMMUTABLE
        // discards those fill-in extras, which made delivery reports
        // unparseable. The intent is explicit to our non-exported receiver,
        // keeping the required mutability tightly scoped. It is deliberately
        // not one-shot: a temporary TP-Status may later advance to delivered.
        val mutability = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutability
        )
    }

    private fun updateStatus(rowId: Long, status: Int) {
        try {
            val values = ContentValues().apply { put(Telephony.Sms.STATUS, status) }
            context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                "${Telephony.Sms._ID} = ?",
                arrayOf(rowId.toString())
            )
            Log.d(TAG, "SMS status updated: id=$rowId status=$status")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist SMS status for id=$rowId", e)
        }
    }

    /**
     * Persist the sent SMS to Telephony.Sms.Sent immediately BEFORE sending,
     * so ContentObserver reload always finds it in the DB.
     */
    private fun persistToSent(phone: String, text: String): Long {
        return try {
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, phone)
                put(Telephony.Sms.BODY, text)
                put(Telephony.Sms.DATE, now)
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_PENDING)
                // ── THREAD_ID is NOT optional ────────────────────────────────
                // Without it the row is an orphan: Telephony.Threads keeps its
                // old SNIPPET/DATE, so the Home list (built from Threads) shows
                // a stale preview and stale sort position while the chat screen
                // (which queries by ADDRESS) shows the new message. That is the
                // "list and chat disagree" bug. MmsSender already did this via
                // getOrCreateThreadId; SMS never did.
                resolveThreadId(phone)?.let { put(Telephony.Sms.THREAD_ID, it) }
            }
            val uri = context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
            val id = uri?.lastPathSegment?.toLongOrNull() ?: now
            Log.d("SMS_SENDER", "Persisted to Sent: id=$id phone=$phone")
            id
        } catch (e: Exception) {
            Log.e(TAG, "Error persisting sent SMS to DB", e)
            DiagnosticLog.event(
                "SMS_PROVIDER",
                "insert-failed phone=${DiagnosticLog.phoneToken(phone)}",
                e
            )
            System.currentTimeMillis()
        }
    }

    /**
     * Resolves (or creates) the canonical thread id for [phone] so the sent row
     * is correctly associated and the platform updates the Threads table.
     * Returns null when the provider refuses, in which case we insert without
     * it rather than losing the message.
     */
    private fun resolveThreadId(phone: String): Long? = try {
        Telephony.Threads.getOrCreateThreadId(context, phone)
    } catch (e: Exception) {
        Log.w(TAG, "getOrCreateThreadId failed for $phone", e)
        null
    }

    companion object {
        private const val TAG = "SMS_SENDER"
        private val ledgerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
