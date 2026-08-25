package com.autonomousone.messages.sms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.autonomousone.messages.event.SmsEventBus
import com.autonomousone.messages.messaging.MessagingPreferences

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

    // Process-wide status receivers, registered lazily once per instance and
    // keyed by the persisted Sent row id carried in the broadcast extras.
    @Volatile
    private var receiversRegistered = false

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

        // Effective SMSC: per-request override → user preference → network default.
        val scAddress = smscOverride?.trim()?.takeIf { it.isNotBlank() }
            ?: prefs.smscAddress.trim().takeIf { it.isNotBlank() }
        val wantReports = prefs.deliveryReportsEnabled

        ensureStatusReceivers()

        var sentPi: PendingIntent? = null
        var deliveredPi: PendingIntent? = null
        if (wantReports) {
            sentPi = buildStatusPendingIntent(ACTION_SMS_SENT, sentId)
            deliveredPi = buildStatusPendingIntent(ACTION_SMS_DELIVERED, sentId)
        }

        try {
            val effectiveSubId = subscriptionIdOverride ?: prefs.sendSubscriptionId
            // Split long messages into multi-part SMS if needed
            val parts = manager.divideMessage(text)
            if (parts.size > 1) {
                manager.sendMultipartTextMessage(
                    phone,
                    scAddress,
                    parts,
                    sentPi?.let { ArrayList(listOf(it)) },
                    deliveredPi?.let { ArrayList(listOf(it)) }
                )
            } else {
                manager.sendTextMessage(phone, scAddress, text, sentPi, deliveredPi)
            }

            Log.d(
                TAG,
                "SMS queued to $phone (id=$sentId, subId=$effectiveSubId, " +
                    "smsc=${if (scAddress != null) "custom" else "network"}, reports=$wantReports)"
            )
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error sending SMS to $phone", e)
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

    private fun buildStatusPendingIntent(action: String, rowId: Long): PendingIntent {
        val intent = Intent(action)
            .setPackage(context.packageName)
            .putExtra(EXTRA_ROW_ID, rowId)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(context, (action.hashCode() + rowId.toInt()), intent, flags)
    }

    private fun ensureStatusReceivers() {
        if (receiversRegistered) return
        synchronized(this) {
            if (receiversRegistered) return
            ContextCompat.registerReceiver(
                context,
                object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        handleStatusBroadcast(intent, resultCode, delivered = false)
                    }
                },
                IntentFilter(ACTION_SMS_SENT),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            ContextCompat.registerReceiver(
                context,
                object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        handleStatusBroadcast(intent, resultCode, delivered = true)
                    }
                },
                IntentFilter(ACTION_SMS_DELIVERED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiversRegistered = true
        }
    }

    private fun handleStatusBroadcast(intent: Intent, resultCode: Int, delivered: Boolean) {
        val rowId = intent.getLongExtra(EXTRA_ROW_ID, -1L)
        if (rowId <= 0L) return
        val ok = resultCode == Activity.RESULT_OK
        val status = when {
            delivered -> if (ok) Telephony.Sms.STATUS_COMPLETE else Telephony.Sms.STATUS_FAILED
            ok -> Telephony.Sms.STATUS_NONE // sent, awaiting delivery report (if any)
            else -> Telephony.Sms.STATUS_FAILED
        }
        updateStatus(rowId, status)
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
                put(Telephony.Sms.DATE_SENT, now)
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                if (prefs.deliveryReportsEnabled) {
                    put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_PENDING)
                }
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
        private const val ACTION_SMS_SENT = "com.autonomousone.messages.intent.SMS_SENT"
        private const val ACTION_SMS_DELIVERED = "com.autonomousone.messages.intent.SMS_DELIVERED"
        private const val EXTRA_ROW_ID = "row_id"
    }
}