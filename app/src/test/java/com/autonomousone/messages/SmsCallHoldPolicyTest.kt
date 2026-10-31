package com.autonomousone.messages

import android.telephony.TelephonyManager
import com.autonomousone.messages.data.SegmentCallbackState.AMBIGUOUS
import com.autonomousone.messages.data.SegmentCallbackState.CONFIRMED
import com.autonomousone.messages.data.SegmentCallbackState.FAILED
import com.autonomousone.messages.data.SegmentCallbackState.PENDING
import com.autonomousone.messages.sms.CallSnapshot
import com.autonomousone.messages.sms.HeldSend
import com.autonomousone.messages.sms.SmsCallHoldPolicy
import com.autonomousone.messages.sms.SmsCallHoldPolicy.Part
import com.autonomousone.messages.sms.SmsSendFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsCallHoldPolicyTest {

    private fun call(inCall: Boolean = true, net: Int? = TelephonyManager.NETWORK_TYPE_UMTS, otherSim: Boolean = false) =
        CallSnapshot(inCall, net, otherSim)

    @Test
    fun `no call never holds`() {
        assertFalse(SmsCallHoldPolicy.shouldHold(call(inCall = false)))
    }

    @Test
    fun `circuit switched or unknown call holds`() {
        assertTrue(SmsCallHoldPolicy.shouldHold(call(net = TelephonyManager.NETWORK_TYPE_UMTS)))
        assertTrue(SmsCallHoldPolicy.shouldHold(call(net = TelephonyManager.NETWORK_TYPE_GSM)))
        assertTrue(SmsCallHoldPolicy.shouldHold(call(net = TelephonyManager.NETWORK_TYPE_UNKNOWN)))
        assertTrue(SmsCallHoldPolicy.shouldHold(call(net = null)))
    }

    @Test
    fun `IMS call on the sending SIM sends right away`() {
        assertFalse(SmsCallHoldPolicy.shouldHold(call(net = TelephonyManager.NETWORK_TYPE_LTE)))
        assertFalse(SmsCallHoldPolicy.shouldHold(call(net = TelephonyManager.NETWORK_TYPE_NR)))
        assertFalse(SmsCallHoldPolicy.shouldHold(call(net = TelephonyManager.NETWORK_TYPE_IWLAN)))
    }

    @Test
    fun `call on the other SIM holds even on LTE`() {
        assertTrue(SmsCallHoldPolicy.shouldHold(call(net = TelephonyManager.NETWORK_TYPE_LTE, otherSim = true)))
    }

    private val blocked = SmsSendFailure.BlockedDueToCall.code
    private val noService = SmsSendFailure.NoService.code
    private fun failed(code: String?, at: Long = 100L) = Part(FAILED, code, at)

    private fun retry(parts: List<Part>, partCount: Int = parts.size, callContext: Boolean = false, retries: Int = 0, last: Long = 0L) =
        SmsCallHoldPolicy.shouldRetryAfterCall(parts, partCount, callContext, retries, last)

    @Test
    fun `call refusal on every part is retried`() {
        assertTrue(retry(listOf(failed(blocked), failed(blocked))))
    }

    @Test
    fun `transient refusal is retried only with a call involved`() {
        assertFalse(retry(listOf(failed(noService))))
        assertTrue(retry(listOf(failed(noService)), callContext = true))
        assertTrue(retry(listOf(failed(SmsSendFailure.RilRetryRequired.code)), callContext = true))
    }

    @Test
    fun `anything that may have left the phone is never resent`() {
        assertFalse(retry(listOf(failed(blocked), Part(CONFIRMED, null, 100L))))
        assertFalse(retry(listOf(failed(blocked), Part(AMBIGUOUS, "MODEM_FAILURE", 100L))))
        assertFalse(retry(listOf(failed(SmsSendFailure.FdnRestricted.code)), callContext = true))
    }

    @Test
    fun `incomplete or stale attempts wait`() {
        assertFalse(retry(listOf(failed(blocked)), partCount = 2))
        assertFalse(retry(listOf(failed(blocked), Part(PENDING, null, null))))
        assertFalse(retry(listOf(failed(blocked, at = 50L)), last = 60L))
        assertTrue(retry(listOf(failed(blocked, at = 70L)), last = 60L))
    }

    @Test
    fun `retries are bounded`() {
        assertFalse(retry(listOf(failed(blocked)), retries = SmsCallHoldPolicy.MAX_RETRIES))
    }

    @Test
    fun `held send round trips and rejects garbage`() {
        val send = HeldSend(7L, HeldSend.Kind.HOLD, "+98912", 2, null, 11L, "sim:1", true, 1_000L, null)
        assertEquals(send, HeldSend.fromJson(send.toJson()))
        val retry = send.copy(kind = HeldSend.Kind.RETRY, subscriptionIdOverride = null, assertedSimRef = null, failureCode = blocked)
        assertEquals(retry, HeldSend.fromJson(retry.toJson()))
        assertNull(HeldSend.fromJson("{oops"))
        assertNull(HeldSend.fromJson(null))
    }
}
