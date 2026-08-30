package com.autonomousone.messages.sms

import android.app.Activity
import android.telephony.SmsManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import android.telephony.SmsMessage
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.MessageEntity
import com.autonomousone.messages.data.MessageMutation
import com.autonomousone.messages.data.SendSegmentEntity
import com.autonomousone.messages.data.TelephonySyncCoordinator
import com.autonomousone.messages.event.SmsEventBus
import com.autonomousone.messages.utils.DiagnosticLog
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
        // Capture BroadcastReceiver.resultCode before leaving onReceive, then
        // keep the broadcast alive until provider + ledger persistence finish.
        val pendingResult = goAsync()
        val callbackResultCode = resultCode
        appScope.launch {
            try {
                processStatusIntent(context.applicationContext, intent, callbackResultCode)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun processStatusIntent(context: Context, intent: Intent, callbackResultCode: Int) {
        val rowId = intent.getLongExtra(EXTRA_ROW_ID, -1L)
        if (rowId <= 0L) return

        val partIndex = intent.getIntExtra(EXTRA_PART_INDEX, 0).coerceAtLeast(0)
        val partCount = intent.getIntExtra(EXTRA_PART_COUNT, 1).coerceAtLeast(1)
        val subscriptionId = intent.getIntExtra(EXTRA_SUBSCRIPTION_ID, -1)
        val delivered = intent.action == ACTION_SMS_DELIVERED
        val phase = if (delivered) SmsStatusPolicy.Phase.DELIVERED else SmsStatusPolicy.Phase.SENT
        val ok = callbackResultCode == Activity.RESULT_OK
        // A SENT callback is the modem's local transport ACK, not a delivery
        // verdict. Affected Samsung/RIL combinations return multiple non-OK
        // codes after the SMSC has accepted and delivered the message. Never
        // write those vendor results into Telephony.Sms.STATUS as FAILED: that
        // poisons the shared provider and makes every SMS app show a false
        // "Not delivered". SmsSender still marks synchronous dispatch
        // exceptions as real failures before any callback exists.
        val deliveryEvidence = if (phase == SmsStatusPolicy.Phase.DELIVERED) {
            parseDeliveryEvidence(intent, ok)
        } else {
            SmsStatusPolicy.DeliveryEvidence.UNKNOWN
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        DiagnosticLog.event(
            "SMS_CALLBACK",
            "row=$rowId phase=$phase part=${partIndex + 1}/$partCount sub=$subscriptionId " +
                "result=$callbackResultCode/${resultCodeName(callbackResultCode)} evidence=$deliveryEvidence " +
                "radioError=${intent.getIntExtra("errorCode", 0)} " +
                "pduBytes=${intent.getByteArrayExtra("pdu")?.size ?: 0}"
        )

        // v2.6.12: exact diagnostic for the "red ! but message WAS delivered"
        // report. RESULT_ERROR_GENERIC_FAILURE is modem-level and ambiguous:
        // the SMSC may still accept and deliver a UCS-2 (Persian) submit.
        // Logging the precise code + part lets us tune retry policy per code.
        if (!ok) {
            val verdict = if (phase == SmsStatusPolicy.Phase.SENT) {
                "TRANSPORT_UNCONFIRMED"
            } else {
                "DELIVERY_REPORT_GAP"
            }
            Log.w(
                TAG,
                "sms callback $verdict rowId=$rowId part=${partIndex + 1}/$partCount " +
                    "phase=${if (delivered) "DELIVERED" else "SENT"} resultCode=$callbackResultCode " +
                    "codeName=${resultCodeName(callbackResultCode)}"
            )
        }

        // ── Send-segment ledger (SENT callbacks only) ──────────────────────
        // ONE row per successfully-confirmed part: a 3-part message that got
        // 2× RESULT_OK counts 2, not 3 and not 1 "send". The composite PK
        // (rowId, partIndex) dedupes redelivered broadcasts. Gateway, manual,
        // EVE and scheduled sends all dispatch through SmsSender, so every
        // path lands here automatically. Failures are recorded too — they
        // never count, but they make "3 of 4 parts sent" diagnosable later.
        if (!delivered) {
            val appContext = context.applicationContext
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
                DiagnosticLog.event(
                    "SMS_LEDGER",
                    "write-failed row=$rowId part=${partIndex + 1}/$partCount",
                    e
                )
            }
        }

        val nextStatus = synchronized(LOCK) {
            // SENT transport results never poison provider delivery state. A
            // delivery-report error is only a reporting gap and likewise
            // cannot downgrade a sent message.
            // Versioned keys deliberately ignore callback state written by the
            // pre-PDU policies in v2.6.13..16.
            val sentDoneKey = "v2617_${rowId}_sent_parts"
            val dlvDoneKey = "v2617_${rowId}_dlv_parts"
            val dlvPendingKey = "v2617_${rowId}_dlv_pending"
            val dlvFailedKey = "v2617_${rowId}_dlv_failed"
            val edit = prefs.edit()

            if (phase == SmsStatusPolicy.Phase.DELIVERED && !ok) {
                Log.w(
                    TAG,
                    "delivery-report gap: rowId=$rowId part=${partIndex + 1}/$partCount " +
                        "resultCode=$callbackResultCode codeName=${resultCodeName(callbackResultCode)} — " +
                        "message stays SENT (delivery may have happened; reports are lossy)"
                )
            }

            val sentDone = prefs.getStringSet(sentDoneKey, emptySet()).orEmpty().toMutableSet()
            val dlvDone = prefs.getStringSet(dlvDoneKey, emptySet()).orEmpty().toMutableSet()
            val dlvPending = prefs.getStringSet(dlvPendingKey, emptySet()).orEmpty().toMutableSet()
            val dlvFailed = prefs.getStringSet(dlvFailedKey, emptySet()).orEmpty().toMutableSet()
            if (phase == SmsStatusPolicy.Phase.SENT) {
                sentDone += partIndex.toString()
                edit.putStringSet(sentDoneKey, sentDone)
            } else {
                // Per-part evidence is monotonic: DELIVERED is strongest and
                // can never be downgraded by a duplicate/stale report;
                // TEMPORARY may advance to FAILED or DELIVERED. UNKNOWN leaves
                // prior evidence untouched.
                val part = partIndex.toString()
                when (deliveryEvidence) {
                    SmsStatusPolicy.DeliveryEvidence.DELIVERED -> {
                        dlvPending.remove(part)
                        dlvFailed.remove(part)
                        dlvDone += part
                    }
                    SmsStatusPolicy.DeliveryEvidence.FAILED -> if (part !in dlvDone) {
                        dlvPending.remove(part)
                        dlvFailed += part
                    }
                    SmsStatusPolicy.DeliveryEvidence.TEMPORARY ->
                        if (part !in dlvDone && part !in dlvFailed) dlvPending += part
                    SmsStatusPolicy.DeliveryEvidence.UNKNOWN -> Unit
                }
                edit.putStringSet(dlvDoneKey, dlvDone)
                    .putStringSet(dlvPendingKey, dlvPending)
                    .putStringSet(dlvFailedKey, dlvFailed)
            }
            edit.apply()

            SmsStatusPolicy.nextStatus(
                sentPartsDone = sentDone.size,
                dlvPartsDone = dlvDone.size,
                dlvPartsPending = dlvPending.size,
                dlvPartsFailed = dlvFailed.size,
                partCount = partCount
            )
        }


        updateProvider(context, rowId, nextStatus, delivered && nextStatus == Telephony.Sms.STATUS_COMPLETE)
        DiagnosticLog.event("SMS_STATE", "row=$rowId providerStatus=$nextStatus evidence=$deliveryEvidence")

        // v2.6.13: targeted status mutation — only refresh this message.
        TelephonySyncCoordinator.get(context).mutate(
            MessageMutation.RefreshStatus(
                source = MessageEntity.SOURCE_SMS,
                providerId = rowId
            )
        )
    }

    /** Human-readable name for a SmsManager result code (diagnostics only). */
    private fun resultCodeName(code: Int): String = when (code) {
        Activity.RESULT_OK -> "RESULT_OK"
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "RESULT_ERROR_GENERIC_FAILURE"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "RESULT_ERROR_NO_SERVICE"
        SmsManager.RESULT_ERROR_NULL_PDU -> "RESULT_ERROR_NULL_PDU"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "RESULT_ERROR_RADIO_OFF"
        else -> "CUSTOM_$code"
    }

    /**
     * deliveryIntent carries the raw SMS-STATUS-REPORT PDU. Parse TP-Status;
     * only fall back to the successful delivery callback when a vendor omits
     * or mangles the PDU. A failed callback without a parseable report is
     * UNKNOWN and must never manufacture STATUS_FAILED.
     */
    private fun parseDeliveryEvidence(
        intent: Intent,
        callbackOk: Boolean
    ): SmsStatusPolicy.DeliveryEvidence {
        val pdu = intent.getByteArrayExtra("pdu")
        val declaredFormat = intent.getStringExtra("format")
        if (pdu != null && pdu.isNotEmpty()) {
            val formats = listOfNotNull(
                declaredFormat,
                SmsMessage.FORMAT_3GPP,
                SmsMessage.FORMAT_3GPP2
            ).distinct()
            for (format in formats) {
                val report = kotlin.runCatching { SmsMessage.createFromPdu(pdu, format) }.getOrNull()
                if (report != null && report.isStatusReportMessage) {
                    val evidence = if (format == SmsMessage.FORMAT_3GPP2) {
                        SmsStatusPolicy.classify3gpp2Status(report.status)
                    } else {
                        SmsStatusPolicy.classify3gppTpStatus(report.status)
                    }
                    DiagnosticLog.event(
                        "SMS_DELIVERY_PDU",
                        "format=$format tpStatus=${report.status} evidence=$evidence bytes=${pdu.size}"
                    )
                    return evidence
                }
            }
        }
        return if (callbackOk) SmsStatusPolicy.DeliveryEvidence.DELIVERED
        else SmsStatusPolicy.DeliveryEvidence.UNKNOWN
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
            DiagnosticLog.event("SMS_PROVIDER", "status-update-failed row=$rowId status=$status", error)
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
