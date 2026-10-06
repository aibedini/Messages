package com.autonomousone.messages.sms

import android.telephony.SmsManager

/**
 * ONE exhaustive mapper from an Android SMS result code to its symbolic name.
 *
 * `resultCodeName()` used to recognise a handful of constants and print `CUSTOM_106` for everything
 * else. `106` is `RESULT_RIL_REQUEST_RATE_LIMITED` — "the radio rejected this because requests were too
 * frequent" — which is very likely the code this phone has been hitting, and printing it as an unknown
 * vendor value is exactly how a rate limit stays invisible and gets mistaken for a carrier or balance
 * problem.
 *
 * The mapping is a pure table so a test can assert every entry, and so the diagnostics screen and the
 * transport classifier cannot disagree about what a code means.
 *
 * API level: the `RESULT_RIL_*` family was added in API 30, and labels codes the platform may not even
 * be able to return on older devices. They are *constants* (inlined at compile time), so the table is
 * safe to hold on every API — nothing is reflected and nothing is invoked.
 */
object SmsResultCodes {

    private val NAMES: Map<Int, String> = buildMap {
        put(android.app.Activity.RESULT_OK, "RESULT_OK")
        put(SmsManager.RESULT_ERROR_GENERIC_FAILURE, "RESULT_ERROR_GENERIC_FAILURE")
        put(SmsManager.RESULT_ERROR_NO_SERVICE, "RESULT_ERROR_NO_SERVICE")
        put(SmsManager.RESULT_ERROR_NULL_PDU, "RESULT_ERROR_NULL_PDU")
        put(SmsManager.RESULT_ERROR_RADIO_OFF, "RESULT_ERROR_RADIO_OFF")
        put(SmsManager.RESULT_ERROR_LIMIT_EXCEEDED, "RESULT_ERROR_LIMIT_EXCEEDED")
        put(SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE, "RESULT_ERROR_FDN_CHECK_FAILURE")
        put(SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED, "RESULT_ERROR_SHORT_CODE_NOT_ALLOWED")
        put(SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED, "RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED")
        // ── the RIL family (API 30+) ────────────────────────────────────────
        put(SmsManager.RESULT_RIL_RADIO_NOT_AVAILABLE, "RESULT_RIL_RADIO_NOT_AVAILABLE")
        put(SmsManager.RESULT_RIL_SMS_SEND_FAIL_RETRY, "RESULT_RIL_SMS_SEND_FAIL_RETRY")
        put(SmsManager.RESULT_RIL_NETWORK_REJECT, "RESULT_RIL_NETWORK_REJECT")
        put(SmsManager.RESULT_RIL_INVALID_STATE, "RESULT_RIL_INVALID_STATE")
        put(SmsManager.RESULT_RIL_INVALID_ARGUMENTS, "RESULT_RIL_INVALID_ARGUMENTS")
        put(SmsManager.RESULT_RIL_NO_MEMORY, "RESULT_RIL_NO_MEMORY")
        put(SmsManager.RESULT_RIL_REQUEST_RATE_LIMITED, "RESULT_RIL_REQUEST_RATE_LIMITED")
        put(SmsManager.RESULT_RIL_INVALID_SMS_FORMAT, "RESULT_RIL_INVALID_SMS_FORMAT")
        put(SmsManager.RESULT_RIL_SYSTEM_ERR, "RESULT_RIL_SYSTEM_ERR")
        put(SmsManager.RESULT_RIL_ENCODING_ERR, "RESULT_RIL_ENCODING_ERR")
        put(SmsManager.RESULT_RIL_INVALID_SMSC_ADDRESS, "RESULT_RIL_INVALID_SMSC_ADDRESS")
        put(SmsManager.RESULT_RIL_MODEM_ERR, "RESULT_RIL_MODEM_ERR")
        put(SmsManager.RESULT_RIL_NETWORK_ERR, "RESULT_RIL_NETWORK_ERR")
        put(SmsManager.RESULT_RIL_INTERNAL_ERR, "RESULT_RIL_INTERNAL_ERR")
        put(SmsManager.RESULT_RIL_REQUEST_NOT_SUPPORTED, "RESULT_RIL_REQUEST_NOT_SUPPORTED")
        put(SmsManager.RESULT_RIL_INVALID_MODEM_STATE, "RESULT_RIL_INVALID_MODEM_STATE")
        put(SmsManager.RESULT_RIL_NETWORK_NOT_READY, "RESULT_RIL_NETWORK_NOT_READY")
        put(SmsManager.RESULT_RIL_OPERATION_NOT_ALLOWED, "RESULT_RIL_OPERATION_NOT_ALLOWED")
        put(SmsManager.RESULT_RIL_NO_RESOURCES, "RESULT_RIL_NO_RESOURCES")
        put(SmsManager.RESULT_RIL_CANCELLED, "RESULT_RIL_CANCELLED")
        put(SmsManager.RESULT_RIL_SIM_ABSENT, "RESULT_RIL_SIM_ABSENT")
        put(SmsManager.RESULT_RIL_SIMULTANEOUS_SMS_AND_CALL_NOT_ALLOWED, "RESULT_RIL_SIMULTANEOUS_SMS_AND_CALL_NOT_ALLOWED")
        put(SmsManager.RESULT_RIL_ACCESS_BARRED, "RESULT_RIL_ACCESS_BARRED")
        put(SmsManager.RESULT_RIL_BLOCKED_DUE_TO_CALL, "RESULT_RIL_BLOCKED_DUE_TO_CALL")
        put(SmsManager.RESULT_INVALID_SMSC_ADDRESS, "RESULT_INVALID_SMSC_ADDRESS")
    }

    /** The symbolic Android constant, never `CUSTOM_<n>` for a code the platform defines. */
    fun name(resultCode: Int): String =
        NAMES[resultCode] ?: "UNKNOWN_RESULT_$resultCode"

    /** True when this code is a recognised platform constant rather than a vendor value. */
    fun isKnown(resultCode: Int): Boolean = NAMES.containsKey(resultCode)

    /** Every recognised (code, name) pair — used by the diagnostics report and by tests. */
    fun entries(): List<Pair<Int, String>> = NAMES.entries.map { it.key to it.value }.sortedBy { it.first }
}
