package com.autonomousone.messages.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.autonomousone.messages.utils.DiagnosticLog

/**
 * Call ended → release held messages NOW instead of waiting for the worker's next poll.
 *
 * WorkManager is a durability mechanism, not a timing one: it may start the worker seconds or
 * minutes late under battery saver or app standby. The moment a call ends is exactly when the user
 * expects the held message to leave, so the call-state change itself wakes the queue. The worker
 * stays as the recovery path when this broadcast cannot be delivered (no READ_PHONE_STATE, process
 * killed, OEM that never sends the broadcast).
 */
class SmsCallStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        if (intent.getStringExtra(TelephonyManager.EXTRA_STATE) != TelephonyManager.EXTRA_STATE_IDLE) return
        val store = SmsCallHoldStore(context)
        if (store.isEmpty()) return
        val held = store.all().size
        DiagnosticLog.event("SMS_CALL_HOLD", "state=CALL_IDLE wake=enqueued held=$held")
        SmsCallHoldWorker.enqueue(context)
    }
}
