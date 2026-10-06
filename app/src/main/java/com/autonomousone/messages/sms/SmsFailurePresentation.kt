package com.autonomousone.messages.sms

/**
 * The ONLY place an SMS transport failure becomes human text.
 *
 * Two rules are load-bearing here (mission §10, §33, §34, §59):
 *
 * 1. **Never claim a balance problem as fact.** Android has no portable "insufficient prepaid balance"
 *    result. A carrier refusing for balance looks like `GENERIC_FAILURE`/`NETWORK_REJECT`, and the same
 *    codes appear for account restrictions, barred access and plain outages. So the primary copy says
 *    what we actually know (the carrier/network rejected it) and the hint mentions balance as one
 *    *possible* cause — never "your balance is zero" unless verified carrier evidence exists.
 * 2. **Transport failure ≠ delivery failure.** "We could not hand this to the network" and "the network
 *    took it but the recipient did not get it" are different sentences, because they need different
 *    user action and they mean different things about duplicates.
 */
object SmsFailurePresentation {

    data class Copy(
        /** Short primary line, safe to show in a list row. */
        val primary: String,
        /** Optional one-line explanation for the bubble/details. */
        val detail: String? = null,
        /** What the user can actually do. Never a guess about their account. */
        val actionHint: String? = null,
        /** True when a retry might duplicate a message that already left the phone. */
        val duplicateRisk: Boolean = false
    )

    private val BY_CODE: Map<String, Copy> = mapOf(
        "NO_SERVICE" to Copy(
            primary = "No mobile service",
            actionHint = "Move somewhere with signal and try again."
        ),
        "RADIO_OFF" to Copy(
            primary = "Mobile radio is off",
            detail = "The phone's radio is off, so nothing could be sent.",
            actionHint = "Turn off airplane mode or turn the radio back on."
        ),
        "RADIO_UNAVAILABLE" to Copy(
            primary = "Mobile radio unavailable",
            actionHint = "Wait a moment, or toggle airplane mode, then try again."
        ),
        "NULL_PDU" to Copy(
            primary = "Message could not be encoded",
            detail = "Android produced an empty message for the radio.",
            actionHint = "Edit the message and try again."
        ),
        "SIM_UNAVAILABLE" to Copy(
            primary = "SIM unavailable",
            detail = "The SIM this message was assigned to is not available.",
            actionHint = "Check the SIM is inserted and enabled."
        ),
        "NO_DEFAULT_SMS_SUBSCRIPTION" to Copy(
            primary = "No SMS line selected",
            actionHint = "Choose the SIM to send from in Settings."
        ),
        "QUEUE_LIMIT_EXCEEDED" to Copy(
            primary = "SMS queue is busy",
            detail = "Android refused the message because too many sends are queued.",
            actionHint = "Wait a few seconds — sending resumes automatically."
        ),
        "RIL_RATE_LIMITED" to Copy(
            primary = "Too many SMS requests",
            detail = "The radio rejected the message because requests were too frequent.",
            actionHint = "Wait a few seconds — sending resumes automatically."
        ),
        "RIL_RETRY_REQUIRED" to Copy(
            primary = "Radio asked for a retry",
            detail = "The radio reported the send failed and a retry is required.",
            actionHint = "Wait a moment, then try again."
        ),
        "NETWORK_REJECTED" to Copy(
            primary = "Network rejected the message",
            detail = "The carrier or network refused to accept this message.",
            actionHint = "Check your balance, account restrictions, or carrier service."
        ),
        "CARRIER_REJECTED" to Copy(
            primary = "Carrier rejected the message",
            detail = "The carrier refused to accept this message.",
            actionHint = "Check your balance, account restrictions, or carrier service."
        ),
        "CARRIER_FAILURE_UNKNOWN" to Copy(
            primary = "Carrier could not send the message",
            detail = "Android reported a generic failure, so the exact cause is unknown.",
            actionHint = "Check signal, your balance or carrier restrictions, then try again.",
            duplicateRisk = true
        ),
        "NETWORK_NOT_READY" to Copy(
            primary = "Mobile network isn't ready",
            actionHint = "Wait until the network registers, then try again."
        ),
        "NETWORK_ERROR" to Copy(
            primary = "Network error while sending",
            actionHint = "Try again when the connection stabilises.",
            duplicateRisk = true
        ),
        "MODEM_FAILURE" to Copy(
            primary = "Modem error",
            detail = "The phone's modem reported an error while sending.",
            actionHint = "Try again; if it keeps failing, restarting the phone can help.",
            duplicateRisk = true
        ),
        "MODEM_INVALID_STATE" to Copy(
            primary = "Modem is in an unexpected state",
            actionHint = "Wait a moment, or toggle airplane mode."
        ),
        "SYSTEM_ERROR" to Copy(
            primary = "Phone system error while sending",
            actionHint = "Try again.",
            duplicateRisk = true
        ),
        "INTERNAL_ERROR" to Copy(
            primary = "Internal sending error",
            actionHint = "Try again.",
            duplicateRisk = true
        ),
        "NO_RESOURCES" to Copy(
            primary = "Phone is out of sending resources",
            actionHint = "Wait a moment, then try again."
        ),
        "INVALID_SMSC" to Copy(
            primary = "SMS service centre is invalid",
            detail = "The SMSC address configured for this SIM was rejected.",
            actionHint = "Reset the message centre number in Settings or from the SIM."
        ),
        "INVALID_DESTINATION" to Copy(
            primary = "Invalid destination number",
            actionHint = "Check the number and try again."
        ),
        "INVALID_SMS_FORMAT" to Copy(
            primary = "Message format was rejected",
            actionHint = "Shorten the message or remove unusual characters."
        ),
        "ENCODING_ERROR" to Copy(
            primary = "Message could not be encoded for this network",
            actionHint = "Try removing emoji or unusual characters."
        ),
        "FDN_RESTRICTED" to Copy(
            primary = "Sending blocked by SIM restrictions",
            detail = "Fixed dialling numbers (FDN) are enabled on this SIM.",
            actionHint = "Adjust the SIM's fixed-dialling settings."
        ),
        "SHORT_CODE_NOT_ALLOWED" to Copy(
            primary = "Short codes are blocked",
            actionHint = "This SIM is not allowed to message short codes."
        ),
        "OPERATION_NOT_ALLOWED" to Copy(
            primary = "Sending is not allowed right now",
            actionHint = "Check the SIM's restrictions or try another SIM."
        ),
        "ACCESS_BARRED" to Copy(
            primary = "Carrier barred this sending line",
            actionHint = "Contact your carrier about the line's status."
        ),
        "BLOCKED_DUE_TO_CALL" to Copy(
            primary = "Sending was blocked by a call",
            detail = "The SIM cannot send SMS during an active call.",
            actionHint = "Try again after the call ends."
        ),
        "DISPATCH_REJECTED" to Copy(
            primary = "Sending failed before reaching the network",
            actionHint = "Try again.",
            duplicateRisk = true
        ),
        "SEND_CALLBACK_TIMEOUT" to Copy(
            primary = "Send result is unknown",
            detail = "The network did not confirm whether this message was sent.",
            actionHint = "Check with the recipient before sending it again.",
            duplicateRisk = true
        ),
        "DELIVERY_REPORT_TIMEOUT" to Copy(
            primary = "Sent · Delivery unknown",
            detail = "The message was sent, but no delivery report arrived.",
            actionHint = "Delivery reports are not guaranteed by every carrier."
        ),
        "DELIVERY_UNKNOWN" to Copy(
            primary = "Sent · Delivery unknown",
            detail = "The message left the phone; the carrier never confirmed delivery.",
            actionHint = "This is common with some carriers and does not mean it failed."
        )
    )

    /** The copy for a stable code; unknown codes fall back to an honest generic sentence. */
    fun forCode(code: String?): Copy =
        code?.let { BY_CODE[it] } ?: Copy(
            primary = "Message could not be sent",
            actionHint = "Try again, and check the technical details if it repeats."
        )

    /** Presentation straight from the classifier — the path every caller should use. */
    fun forVerdict(verdict: SmsTransportVerdict): Copy? =
        verdict.failure?.code?.let { BY_CODE[it] }

    /**
     * The durable/carrier distinction the mission requires:
     * "could not send" (transport) vs "sent but delivery failed" (carrier evidence).
     */
    fun deliveryFailedCopy(deliveryStatus: Int?): Copy = Copy(
        primary = "Sent — carrier reports delivery failed",
        detail = "The message reached the network but was not delivered" +
            (deliveryStatus?.let { " (status $it)" } ?: "") + ".",
        actionHint = "Check the number with the recipient before resending."
    )
}
