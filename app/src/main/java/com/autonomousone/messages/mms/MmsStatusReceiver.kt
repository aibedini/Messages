package com.autonomousone.messages.mms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.autonomousone.messages.data.TelephonySyncCoordinator
import com.autonomousone.messages.utils.DiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The MMS send result, which used to have nowhere to arrive.
 *
 * `MmsSender.triggerSend` passed a **null** `PendingIntent`, so the platform's outcome was discarded:
 * a failed MMS sat in `MESSAGE_BOX_OUTBOX` for ever looking "still sending", and neither the app nor
 * the gateway ever learned that it had not left. This receiver is the same shape as
 * [com.autonomousone.messages.sms.SmsStatusReceiver] and exists for the same reason — a manifest
 * receiver targeted by an explicit `PendingIntent` still receives its result after the sending
 * process, screen or app is gone.
 *
 * **What it does with the outcome, and what it deliberately does not.** It records the outcome and
 * publishes it as a gateway status change, so GMweb can show an MMS as sent or failed instead of
 * showing nothing. It does **not** rewrite the shared MMS provider row's box or status: the SMS path
 * learned that lesson expensively (writing vendor SENT failures into `Telephony.Sms.STATUS` made
 * every SMS app on the device claim "Not delivered"), and a half-understood provider write from here
 * could corrupt a row that other apps read.
 *
 * It also never touches the per-SMS segment ledger. That ledger is keyed by an SMS provider row id,
 * and an MMS row id is a different key space — writing one under the other's number would attribute a
 * picture message to an SMS row (see [MmsSendResultPolicy]).
 */
class MmsStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Captured before onReceive returns; the broadcast is kept alive until the durable work is
        // done, exactly as the SMS receiver does.
        val pendingResult = goAsync()
        val resultCode = resultCode
        appScope.launch {
            try {
                val mmsId = intent.getLongExtra(EXTRA_MMS_ID, -1L)
                val outcome = MmsSendResultPolicy.classify(resultCode)
                DiagnosticLog.event(
                    "MMS_SEND",
                    MmsSendResultPolicy.describe(mmsId, outcome)
                )
                if (outcome.verdict == MmsSendVerdict.SENT) {
                    Log.d(TAG, "MMS $mmsId handed off (${outcome.code})")
                } else {
                    // Logged at warning level because a failed MMS is invisible in the provider: the
                    // row stays in the OUTBOX and nothing else in the app records the reason.
                    Log.w(TAG, "MMS $mmsId not sent (${outcome.code}, ${outcome.verdict})")
                }
                if (mmsId > 0L) {
                    // Publish the outcome as a message status change so it reaches GMweb. Without
                    // this the gateway would replicate the MMS content and then never say whether it
                    // left the device — a message the web shows as pending for ever.
                    publishStatusChange(context.applicationContext, mmsId, outcome)
                }
            } catch (e: Exception) {
                Log.w(TAG, "MMS status processing failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun publishStatusChange(context: Context, mmsId: Long, outcome: MmsSendOutcome) {
        runCatching {
            TelephonySyncCoordinator.get(context).providerRowChanged(
                source = SOURCE_MMS,
                providerId = mmsId,
                // No command caused this and there is no web bubble key on this path, so both stay
                // null rather than being filled with a locally-invented identifier.
                originCommandId = null,
                clientMessageId = null
            )
        }.onFailure {
            // Never fatal: the MMS itself is already in the provider, and the reconciliation sweep
            // will pick the row up if this publication is lost.
            Log.w(TAG, "MMS status change not published for $mmsId (${outcome.code})", it)
            DiagnosticLog.event(
                "MMS_SEND",
                "status-change-publish-failed mms=$mmsId code=${outcome.code}"
            )
        }
    }

    companion object {
        private const val TAG = "MMS_STATUS"
        private const val SOURCE_MMS = "mms"

        const val ACTION_MMS_SENT = "com.autonomousone.messages.MMS_SENT"
        const val EXTRA_MMS_ID = "mmsId"

        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
