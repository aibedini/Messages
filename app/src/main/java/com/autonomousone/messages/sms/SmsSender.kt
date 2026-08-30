package com.autonomousone.messages.sms

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import com.autonomousone.messages.event.SmsEventBus
import com.autonomousone.messages.messaging.MessagingPreferences
import com.autonomousone.messages.messaging.SimManager
import com.autonomousone.messages.utils.DiagnosticLog

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
        val sentId = persistToSent(phone, text)
        // Tell the app (Home list) instantly: this thread now has a newer
        // message. Works even while the chat screen is still on top.
        SmsEventBus.emitOutgoingSent(
            threadId = 0L, // resolved by Home via phone match
            phone = phone,
            message = text,
            date = System.currentTimeMillis()
        )
        dispatch(sentId, phone, text, subscriptionIdOverride, smscOverride, showToast = true)
        return sentId
    }

    /**
     * v2.6.10: explicit outcome for machine callers (REST gateway).
     *
     * The old [send] returned a row id even when `SmsManager.sendTextMessage`
     * threw — the gateway then answered HTTP 200 "success" for a message that
     * never reached the modem. [dispatch] returns false on that path (and
     * marks the row STATUS_FAILED); this API surfaces it so callers can
     * return 503 instead of lying.
     *
     * Note on semantics: `Accepted` still means "handed to telephony", not
     * "delivered" — SENT/DELIVERED arrive via the status receiver later.
     */
    fun sendWithOutcome(
        phone: String,
        text: String,
        subscriptionIdOverride: Int? = null,
        smscOverride: String? = null
    ): SendOutcome {
        val sentId = persistToSent(phone, text)
        SmsEventBus.emitOutgoingSent(
            threadId = 0L, // resolved by Home via phone match
            phone = phone,
            message = text,
            date = System.currentTimeMillis()
        )
        val dispatched = dispatch(sentId, phone, text, subscriptionIdOverride, smscOverride, showToast = false)
        return if (dispatched) {
            SendOutcome.Accepted(rowId = sentId)
        } else {
            SendOutcome.Rejected(rowId = sentId, reason = "modem rejected send (see message STATUS_FAILED)")
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
     */
    fun sendForResult(phone: String, text: String): Long? {
        val sentId = persistToSent(phone, text)
        SmsEventBus.emitOutgoingSent(
            threadId = 0L,
            phone = phone,
            message = text,
            date = System.currentTimeMillis()
        )
        return if (dispatch(sentId, phone, text, null, null, showToast = false)) sentId else null
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

        try {
            // Split long messages into multi-part SMS if needed
            val parts = manager.divideMessage(text)
            DiagnosticLog.event(
                "SMS_SEND",
                "dispatch row=$sentId phone=${DiagnosticLog.phoneToken(phone)} " +
                    "sub=$effectiveSubId parts=${parts.size} reports=$wantReports " +
                    "smsc=${if (scAddress == null) "sim-default" else "manual"}"
            )
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
            if (showToast) {
                Toast.makeText(context, e.message ?: "Failed to send SMS", Toast.LENGTH_LONG).show()
            }
            return false
        }
    }

    /**
     * Returns an SmsManager bound to the given SIM subscription ([override]
     * first, then the user's saved selection), or the platform default when
     * neither is set.
     */
    private fun resolveSmsManager(override: Int? = null): SmsManager {
        val subId = override ?: prefs.sendSubscriptionId
        val hasSelection = subId != null && subId != MessagingPreferences.SUBSCRIPTION_UNSET
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
    }
}
