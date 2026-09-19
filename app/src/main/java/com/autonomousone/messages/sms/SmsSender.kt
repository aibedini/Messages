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
                idempotencyKey = idempotencyKey
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
        when (val outcome = sendWithOutcome(phone, text, subscriptionIdOverride)) {
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
        val manager = resolveSmsManager(subscriptionIdOverride)

        // v2.6.14 — Effective SMSC, strictly user intent:
        //   per-request override → this SIM's manual override → global manual
        //   override → null (= "use the SMSC saved on the SIM", Android's
        //   documented default). v2.6.13's hidden carrier-directory seeding is
        //   GONE: an address the user never chose must not override what the
        //   (U)SIM itself carries — a mismatch with the SIM's real SMSC can
        //   itself cause radio-side GENERIC_FAILURE.
        val effectiveSubId = subscriptionIdOverride ?: prefs.sendSubscriptionId
        val scAddress = smscOverride?.trim()?.takeIf { it.isNotBlank() }
            ?: prefs.smscForSim(effectiveSubId)
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
                    "sub=$effectiveSubId parts=${parts.size} reports=$wantReports " +
                    "smsc=${if (scAddress == null) "sim-default" else "manual"}"
            )
            // Use the exact modem split for both callbacks and accounting.
            // A SENT callback resolves local hand-off telemetry. Delivery
            // callbacks request the network SMS-STATUS-REPORT PDU and are ON by
            // default; the user may opt out in Messaging settings.
            val sentIntents = ArrayList<PendingIntent>(parts.size).apply {
                repeat(parts.size) { part ->
                    add(buildStatusPendingIntent(
                        SmsStatusReceiver.ACTION_SMS_SENT, sentId, part, parts.size, effectiveSubId
                    ))
                }
            }
            val deliveredIntents = if (wantReports) {
                ArrayList<PendingIntent>(parts.size).apply {
                    repeat(parts.size) { part ->
                        add(buildStatusPendingIntent(
                            SmsStatusReceiver.ACTION_SMS_DELIVERED, sentId, part, parts.size, effectiveSubId
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
            recordSegmentSubmissions(sentId, parts.size, effectiveSubId)

            Log.d(
                TAG,
                "SMS queued to $phone (id=$sentId, subId=$effectiveSubId, " +
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
            recordDispatchRejection(sentId, attemptedParts, effectiveSubId, e)
            if (showToast) {
                Toast.makeText(context, e.message ?: "Failed to send SMS", Toast.LENGTH_LONG).show()
            }
            return false
        }
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
     */
    private fun recordDispatchRejection(
        rowId: Long,
        partCount: Int,
        subId: Int?,
        error: Exception?
    ) {
        if (rowId <= 0L || partCount <= 0) return
        val at = System.currentTimeMillis()
        val code = SmsSendFailure.DispatchRejected(null).code
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
     * Returns an SmsManager bound to the given SIM subscription ([override]
     * first, then the user's saved selection), or the platform default when
     * neither is set.
     */
    private fun resolveSmsManager(override: Int? = null): SmsManager {
        val subId = override ?: prefs.sendSubscriptionId
        val hasSelection = subId != MessagingPreferences.SUBSCRIPTION_UNSET
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = context.getSystemService(SmsManager::class.java)
            if (hasSelection) base.createForSubscriptionId(subId) else base
        } else {
            // Pre-Android 12: the per-subscription manager API is no longer
            // exposed by current SDK stubs, so sending falls back to the
            // platform-default subscription.
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
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
