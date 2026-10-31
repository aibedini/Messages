package com.autonomousone.messages.sms

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.provider.Telephony
import android.telephony.TelephonyManager
import com.autonomousone.messages.data.MessageEntity
import com.autonomousone.messages.data.MessageMutation
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.SegmentCallbackState
import com.autonomousone.messages.data.SendSegmentDao
import com.autonomousone.messages.data.SendSegmentEntity
import com.autonomousone.messages.data.TelephonySyncCoordinator
import com.autonomousone.messages.utils.DiagnosticLog
import org.json.JSONObject

/**
 * SMS and a circuit-switched voice call share one radio.
 *
 * On 2G/3G (and LTE with CS fallback, the common case on Iranian networks) the modem refuses or
 * mangles an SMS submitted while a call is up: the framework retries `SMS_SEND_FAIL_RETRY` only 3 times
 * 2 s apart, then reports `GENERIC_FAILURE` / `BLOCKED_DUE_TO_CALL`, and a status report that arrives
 * during the CS→LTE return is often lost. Stock messaging apps avoid this by queueing the message and
 * sending it once the radio is free; this is that queue.
 *
 * Two paths, both duplicate-safe:
 *  - HOLD: a call is active and cannot carry SMS alongside it → nothing is handed to the radio; the
 *    message waits (shown as Sending) and is dispatched when the call ends.
 *  - RETRY: the radio definitively refused every part because of the call (a REJECTED verdict — the
 *    message did not leave) → it is resent on the same row after the call, a bounded number of times.
 * Ambiguous results are never resent automatically.
 */
object SmsCallHoldPolicy {
    /** A hold never outlives this: a stuck call signal must not keep a message forever. */
    const val HOLD_CAP_MS = 60 * 60_000L

    /** A refused message is retried only within this window after it was refused. */
    const val RETRY_WINDOW_MS = 30 * 60_000L

    const val MAX_RETRIES = 3

    /** Radio needs a moment after hang-up to return from CS fallback before it can take an SMS. */
    const val SETTLE_MS = 3_000L

    const val MARKER_TTL_MS = 2 * 60 * 60_000L

    private val IMS_VOICE_TYPES = setOf(
        TelephonyManager.NETWORK_TYPE_LTE,
        TelephonyManager.NETWORK_TYPE_NR,
        TelephonyManager.NETWORK_TYPE_IWLAN
    )

    /** Voice on LTE/NR/Wi-Fi is IMS, where SMS travels over IMS alongside the call. */
    fun isImsVoice(voiceNetworkType: Int?): Boolean = voiceNetworkType in IMS_VOICE_TYPES

    /**
     * Hold when a call is up unless it is an IMS call on the sending SIM itself. A call on the OTHER
     * SIM of a single-radio dual-SIM phone blocks this SIM whatever its network type.
     */
    fun shouldHold(call: CallSnapshot): Boolean =
        call.inCall && (call.callOnOtherSim || !isImsVoice(call.voiceNetworkType))

    private val CALL_CODES = setOf(SmsSendFailure.BlockedDueToCall.code)

    /** Refusals a call (or the CS-fallback transition around it) produces; all are pre-acceptance. */
    private val TRANSIENT_CODES = setOf(
        SmsSendFailure.RilRetryRequired.code,
        SmsSendFailure.NoService.code,
        SmsSendFailure.NetworkNotReady.code,
        SmsSendFailure.RadioUnavailable.code
    )

    data class Part(val state: SegmentCallbackState, val failureCode: String?, val callbackAt: Long?)

    /**
     * Resend only when EVERY part of the latest attempt reported a refusal that the call explains.
     * One confirmed or ambiguous part means something may have left the phone, and a resend could
     * duplicate it.
     */
    fun shouldRetryAfterCall(
        parts: List<Part>,
        partCount: Int,
        callContext: Boolean,
        retriesSoFar: Int,
        lastDispatchAt: Long
    ): Boolean {
        if (retriesSoFar >= MAX_RETRIES) return false
        if (parts.size < partCount || parts.isEmpty()) return false
        if (parts.any { it.state != SegmentCallbackState.FAILED }) return false
        if (parts.any { (it.callbackAt ?: 0L) < lastDispatchAt }) return false
        return parts.all { part ->
            part.failureCode in CALL_CODES || (callContext && part.failureCode in TRANSIENT_CODES)
        }
    }
}

data class CallSnapshot(
    val inCall: Boolean,
    val voiceNetworkType: Int?,
    val callOnOtherSim: Boolean
)

/** Reads call state from the device. READ_PHONE_STATE is granted to the default SMS app. */
object SmsCallGuard {

    private fun hasPhoneState(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    /** Any cellular call (ringing, dialing, active, held) on any SIM. VoIP apps are not counted. */
    @Suppress("DEPRECATION")
    fun isInCall(context: Context): Boolean {
        if (hasPhoneState(context)) {
            runCatching {
                context.getSystemService(TelephonyManager::class.java)?.callState
            }.getOrNull()?.let { return it != TelephonyManager.CALL_STATE_IDLE }
        }
        return context.getSystemService(AudioManager::class.java)?.mode == AudioManager.MODE_IN_CALL
    }

    fun snapshot(context: Context, subscriptionId: Int?): CallSnapshot {
        val inCall = isInCall(context)
        if (!inCall) return CallSnapshot(inCall = false, voiceNetworkType = null, callOnOtherSim = false)
        if (!hasPhoneState(context)) return CallSnapshot(inCall = true, voiceNetworkType = null, callOnOtherSim = false)
        val base = context.getSystemService(TelephonyManager::class.java)
            ?: return CallSnapshot(inCall = true, voiceNetworkType = null, callOnOtherSim = false)
        val tm = if (subscriptionId != null && subscriptionId >= 0) {
            runCatching { base.createForSubscriptionId(subscriptionId) }.getOrDefault(base)
        } else {
            base
        }
        val voiceType = runCatching { tm.voiceNetworkType }.getOrNull()
        val otherSim = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && subscriptionId != null && subscriptionId >= 0) {
            runCatching { tm.callStateForSubscription == TelephonyManager.CALL_STATE_IDLE }.getOrDefault(false)
        } else {
            false
        }
        return CallSnapshot(inCall = true, voiceNetworkType = voiceType, callOnOtherSim = otherSim)
    }
}

/** A message waiting for the radio. The body is never stored: it is read back from the provider row. */
data class HeldSend(
    val rowId: Long,
    val kind: Kind,
    val phone: String,
    val subscriptionIdOverride: Int?,
    val smscOverride: String?,
    val threadId: Long,
    val assertedSimRef: String?,
    val oneShotOverride: Boolean,
    val heldAt: Long,
    val failureCode: String? = null
) {
    enum class Kind { HOLD, RETRY }

    fun toJson(): String = JSONObject().apply {
        put("rowId", rowId)
        put("kind", kind.name)
        put("phone", phone)
        subscriptionIdOverride?.let { put("sub", it) }
        smscOverride?.let { put("smsc", it) }
        put("threadId", threadId)
        assertedSimRef?.let { put("simRef", it) }
        put("oneShot", oneShotOverride)
        put("heldAt", heldAt)
        failureCode?.let { put("code", it) }
    }.toString()

    companion object {
        fun fromJson(raw: String?): HeldSend? = runCatching {
            val o = JSONObject(raw ?: return null)
            HeldSend(
                rowId = o.getLong("rowId"),
                kind = Kind.valueOf(o.getString("kind")),
                phone = o.getString("phone"),
                subscriptionIdOverride = if (o.has("sub")) o.getInt("sub") else null,
                smscOverride = if (o.has("smsc")) o.getString("smsc") else null,
                threadId = o.optLong("threadId", 0L),
                assertedSimRef = if (o.has("simRef")) o.getString("simRef") else null,
                oneShotOverride = o.optBoolean("oneShot", false),
                heldAt = o.getLong("heldAt"),
                failureCode = if (o.has("code")) o.getString("code") else null
            )
        }.getOrNull()
    }
}

/** Durable queue: survives process death so a held message is never silently dropped. */
class SmsCallHoldStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sms_call_hold", Context.MODE_PRIVATE)

    @Synchronized fun put(send: HeldSend) {
        prefs.edit().putString(HELD + send.rowId, send.toJson()).commit()
    }

    @Synchronized fun remove(rowId: Long) {
        prefs.edit().remove(HELD + rowId).commit()
    }

    @Synchronized fun all(): List<HeldSend> =
        prefs.all.filterKeys { it.startsWith(HELD) }.values.mapNotNull { HeldSend.fromJson(it as? String) }
            .sortedBy { it.heldAt }

    fun isEmpty(): Boolean = prefs.all.keys.none { it.startsWith(HELD) }

    @Synchronized fun markCallAtSubmit(rowId: Long, now: Long = System.currentTimeMillis()) {
        prune(now)
        prefs.edit().putLong(CALL_AT_SUBMIT + rowId, now).apply()
    }

    fun hadCallAtSubmit(rowId: Long): Boolean = prefs.contains(CALL_AT_SUBMIT + rowId)

    fun retries(rowId: Long): Int = prefs.getInt(RETRIES + rowId, 0)

    @Synchronized fun recordRetry(rowId: Long, now: Long = System.currentTimeMillis()) {
        prefs.edit().putInt(RETRIES + rowId, retries(rowId) + 1).putLong(STAMP + rowId, now).commit()
    }

    @Synchronized fun markDispatched(rowId: Long, now: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(DISPATCHED + rowId, now).putLong(STAMP + rowId, now).commit()
    }

    fun lastDispatchAt(rowId: Long): Long = prefs.getLong(DISPATCHED + rowId, 0L)

    /** Bookkeeping keys of finished sends are dropped once they can no longer matter. */
    private fun prune(now: Long) {
        val edit = prefs.edit()
        var changed = false
        prefs.all.forEach { (key, value) ->
            val stale = when {
                key.startsWith(CALL_AT_SUBMIT) -> (value as? Long ?: 0L) < now - SmsCallHoldPolicy.MARKER_TTL_MS
                key.startsWith(STAMP) -> (value as? Long ?: 0L) < now - SmsCallHoldPolicy.MARKER_TTL_MS
                else -> false
            }
            if (stale) {
                changed = true
                edit.remove(key)
                if (key.startsWith(STAMP)) {
                    val row = key.removePrefix(STAMP)
                    edit.remove(RETRIES + row).remove(DISPATCHED + row)
                }
            }
        }
        if (changed) edit.apply()
    }

    private companion object {
        const val HELD = "held_"
        const val CALL_AT_SUBMIT = "call_"
        const val RETRIES = "retries_"
        const val DISPATCHED = "dispatched_"
        const val STAMP = "stamp_"
    }
}

/** Receiver-side half: turns a call-caused refusal into a queued resend instead of "Not sent". */
object SmsCallRetry {

    suspend fun onSentCallback(
        context: Context,
        ledgerDao: SendSegmentDao,
        rowId: Long,
        partCount: Int,
        subscriptionId: Int
    ) {
        if (rowId <= 0L) return
        val store = SmsCallHoldStore(context)
        val segments: List<SendSegmentEntity> = runCatching { ledgerDao.segmentsForRow(rowId) }.getOrDefault(emptyList())
        val retries = store.retries(rowId)
        val callContext = store.hadCallAtSubmit(rowId) || SmsCallGuard.isInCall(context)
        val eligible = SmsCallHoldPolicy.shouldRetryAfterCall(
            parts = segments.map { SmsCallHoldPolicy.Part(it.callbackState, it.callbackFailureCode, it.callbackAt) },
            partCount = partCount,
            callContext = callContext,
            retriesSoFar = retries,
            lastDispatchAt = store.lastDispatchAt(rowId)
        )
        if (!eligible) return
        val row = SmsProviderRow.read(context, rowId) ?: return
        val code = segments.firstNotNullOfOrNull { it.callbackFailureCode }
        store.put(
            HeldSend(
                rowId = rowId,
                kind = HeldSend.Kind.RETRY,
                phone = row.address,
                // The line that actually carried the attempt, so the resend cannot change lines.
                subscriptionIdOverride = subscriptionId.takeIf { it >= 0 },
                smscOverride = null,
                threadId = row.threadId,
                assertedSimRef = null,
                oneShotOverride = false,
                heldAt = System.currentTimeMillis(),
                failureCode = code
            )
        )
        store.recordRetry(rowId)
        markSending(context, rowId)
        DiagnosticLog.event(
            "SMS_CALL_HOLD",
            "row=$rowId state=RETRY_QUEUED code=${code ?: "none"} retry=${retries + 1}/${SmsCallHoldPolicy.MAX_RETRIES} " +
                "parts=$partCount inCall=${SmsCallGuard.isInCall(context)}"
        )
        SmsCallHoldWorker.enqueue(context)
    }

    /** Back to "Sending": the message is queued, and saying "Not sent" would be untrue. */
    private suspend fun markSending(context: Context, rowId: Long) {
        runCatching {
            context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                ContentValues().apply { put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_PENDING) },
                "${Telephony.Sms._ID} = ?",
                arrayOf(rowId.toString())
            )
        }
        recordState(context, rowId, SendTransportState.SENT_PENDING, null)
    }

    /** The resend window closed while the radio stayed busy: show the refusal that really happened. */
    suspend fun giveUp(context: Context, send: HeldSend) {
        runCatching {
            context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                ContentValues().apply { put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_FAILED) },
                "${Telephony.Sms._ID} = ?",
                arrayOf(send.rowId.toString())
            )
        }
        recordState(
            context,
            send.rowId,
            SendTransportState.NOT_SENT,
            send.failureCode ?: SmsSendFailure.BlockedDueToCall.code
        )
        DiagnosticLog.event("SMS_CALL_HOLD", "row=${send.rowId} state=GAVE_UP code=${send.failureCode ?: "none"}")
    }

    private suspend fun recordState(context: Context, rowId: Long, state: SendTransportState, code: String?) {
        runCatching {
            val updated = MessagesDatabase.get(context.applicationContext).messageDao().recordSendState(
                source = MessageEntity.SOURCE_SMS,
                providerId = rowId,
                state = state.name,
                failureCode = code,
                resultCode = null,
                radioErrorCode = null,
                updatedAt = System.currentTimeMillis()
            )
            if (updated > 0) {
                TelephonySyncCoordinator.get(context).mutate(
                    MessageMutation.RefreshStatus(source = MessageEntity.SOURCE_SMS, providerId = rowId)
                )
            }
        }
    }
}

/** The outgoing row as the provider holds it now; null when the user deleted it meanwhile. */
internal data class SmsProviderRow(val address: String, val body: String, val threadId: Long) {
    companion object {
        fun read(context: Context, rowId: Long): SmsProviderRow? = runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.THREAD_ID, Telephony.Sms.TYPE),
                "${Telephony.Sms._ID} = ?",
                arrayOf(rowId.toString()),
                null
            )?.use { c ->
                if (!c.moveToFirst()) return@use null
                val type = c.getInt(3)
                if (type != Telephony.Sms.MESSAGE_TYPE_SENT && type != Telephony.Sms.MESSAGE_TYPE_OUTBOX &&
                    type != Telephony.Sms.MESSAGE_TYPE_FAILED && type != Telephony.Sms.MESSAGE_TYPE_QUEUED
                ) return@use null
                val address = c.getString(0)?.takeIf { it.isNotBlank() } ?: return@use null
                val body = c.getString(1) ?: return@use null
                SmsProviderRow(address, body, c.getLong(2))
            }
        }.getOrNull()
    }
}
