package com.autonomousone.messages.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.MessageEntity
import com.autonomousone.messages.data.MessageMutation
import com.autonomousone.messages.data.SendSegmentEntity
import com.autonomousone.messages.data.TelephonySyncCoordinator
import com.autonomousone.messages.event.SmsEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Durable modem callbacks for outgoing SMS rows.
 *
 * This receiver is manifest-declared and targeted by an explicit PendingIntent,
 * so SENT/DELIVERED results still arrive after the screen or app process that
 * initiated the send has gone away. Multipart results are aggregated before a
 * message leaves PENDING or becomes DELIVERED.
 *
 * V2: Uses mutate(RefreshStatus) for O(1) targeted status update instead of
 * notifyResume() which triggered a full provider scan.
 */
class SmsStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // v2.6.10: goAsync + explicit finish(). The async ledger write and the
        // provider update below run past onReceive(); without goAsync the
        // system may kill the process as soon as onReceive returns and the
        // segment ledger / status update is lost.
        val pendingResult = goAsync()
        try {
            processStatusIntent(context, intent)
        } finally {
            pendingResult.finish()
        }
    }

    private fun processStatusIntent(context: Context, intent: Intent) {
        val rowId = intent.getLongExtra(EXTRA_ROW_ID, -1L)
        if (rowId <= 0L) return

        val partIndex = intent.getIntExtra(EXTRA_PART_INDEX, 0).coerceAtLeast(0)
        val partCount = intent.getIntExtra(EXTRA_PART_COUNT, 1).coerceAtLeast(1)
        val subscriptionId = intent.getIntExtra(EXTRA_SUBSCRIPTION_ID, -1)
        val delivered = intent.action == ACTION_SMS_DELIVERED
        val ok = resultCode == Activity.RESULT_OK
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = "${rowId}_${if (delivered) "delivered" else "sent"}"
        val failedKey = "${rowId}_failed"

        // ── Send-segment ledger (SENT callbacks only) ──────────────────────
        // ONE row per successfully-confirmed part: a 3-part message that got
        // 2× RESULT_OK counts 2, not 3 and not 1 "send". The composite PK
        // (rowId, partIndex) dedupes redelivered broadcasts. Gateway, manual,
        // EVE and scheduled sends all dispatch through SmsSender, so every
        // path lands here automatically. Failures are recorded too — they
        // never count, but they make "3 of 4 parts sent" diagnosable later.
        if (!delivered) {
            val appContext = context.applicationContext
            appScope.launch {
                try {
                    MessagesDatabase.get(appContext).sendSegmentDao().record(
                        SendSegmentEntity(
                            rowId = rowId,
                            partIndex = partIndex,
                            partCount = partCount,
                            sentAt = System.currentTimeMillis(),
                            subscriptionId = subscriptionId,
                            success = ok
                        )
                    )
                } catch (e: Exception) {
                    // Telemetry must never break status processing.
                    Log.w(TAG, "send ledger write failed id=$rowId part=$partIndex", e)
                }
            }
        }

        val nextStatus = synchronized(LOCK) {
            if (!ok) {
                prefs.edit().putBoolean(failedKey, true).apply()
                Telephony.Sms.STATUS_FAILED
            } else if (prefs.getBoolean(failedKey, false)) {
                // A later success from another multipart segment must never
                // overwrite an already-observed failure.
                Telephony.Sms.STATUS_FAILED
            } else {
                val completed = prefs.getStringSet(prefix, emptySet()).orEmpty().toMutableSet()
                completed += partIndex.toString()
                prefs.edit().putStringSet(prefix, completed).apply()
                if (completed.size >= partCount) {
                    if (delivered) Telephony.Sms.STATUS_COMPLETE else Telephony.Sms.STATUS_NONE
                } else {
                    Telephony.Sms.STATUS_PENDING
                }
            }
        }

        updateProvider(context, rowId, nextStatus, delivered && nextStatus == Telephony.Sms.STATUS_COMPLETE)

        // V2: Targeted status mutation — only refresh this specific message.
        // No full provider scan, no conversation rebuild.
        TelephonySyncCoordinator.get(context).mutate(
            MessageMutation.RefreshStatus(
                source = MessageEntity.SOURCE_SMS,
                providerId = rowId
            )
        )
    }

    private fun updateProvider(context: Context, rowId: Long, status: Int, delivered: Boolean) {
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.STATUS, status)
                if (delivered) put(Telephony.Sms.DATE_SENT, System.currentTimeMillis())
            }
            context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                "${Telephony.Sms._ID} = ?",
                arrayOf(rowId.toString())
            )
            Log.d(TAG, "SMS callback persisted: id=$rowId status=$status")
        } catch (error: Exception) {
            Log.w(TAG, "Unable to persist SMS callback for id=$rowId", error)
        }
    }

    companion object {
        const val ACTION_SMS_SENT = "com.autonomousone.messages.intent.SMS_SENT"
        const val ACTION_SMS_DELIVERED = "com.autonomousone.messages.intent.SMS_DELIVERED"
        const val EXTRA_ROW_ID = "row_id"
        const val EXTRA_PART_INDEX = "part_index"
        const val EXTRA_PART_COUNT = "part_count"
        const val EXTRA_SUBSCRIPTION_ID = "subscription_id"

        private const val PREFS = "sms_status_callbacks"
        private const val TAG = "SMS_STATUS"
        private val LOCK = Any()

        /** Ledger writes must survive the receiver's 10s window; a dropped
         *  telemetry write is repaired by the next callback (PK REPLACE). */
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
