package com.autonomousone.messages.sms

/**
 * The `Copy technical details` payload, built from PERSISTED evidence only.
 *
 * ## Why the builder is pure and separate from the menu
 *
 * The copy action is the one place where diagnostics leave the app. Two properties have to hold and
 * neither is visible in a UI test: it must contain the REAL stored values (a placeholder copy action
 * is worse than none, because it looks like evidence), and it must contain no private content. Both
 * are asserted directly here.
 *
 * ## What is deliberately excluded, and why
 *
 * ```text
 * SMS body / OTP        the message content is not diagnostic data
 * full recipient number a number identifies a person; a short tail is enough to disambiguate
 * ICCID / IMSI / serial  privileged identifiers this app never reads on the identity path
 * HMAC key / simRef raw the key never leaves the keystore, and the ref is truncated like the UI's
 * ```
 *
 * Everything included is something the phone itself observed about the send, which is exactly what a
 * support conversation needs and what a user can consent to sharing.
 */
object TechnicalDetailsText {

    /**
     * The persisted diagnostic columns the copy action needs.
     *
     * Deliberately NOT a `MessageEntity`: a Compose bubble holds a `Sms` projection and has no business
     * knowing the mirror row's shape, and this narrow read makes it impossible to accidentally pull the
     * body into the copied text.
     */
    data class Evidence(
        val source: String,
        val providerId: Long,
        val threadId: Long,
        val isOutgoing: Boolean,
        val sentAt: Long,
        val dateSent: Long,
        val transportState: String?,
        val sendFailureCode: String?,
        val sendResultCode: Int?,
        val radioErrorCode: Int?,
        val sendStateUpdatedAt: Long,
        val deliveryEvidence: String?,
        val deliveryTpStatus: Int?,
        val deliveryResultCode: Int?,
        val deliveryCallbackAt: Long
    )

    /**
     * Build the copyable text.
     *
     * @param uiState the app-owned state name as the UI holds it, or null for a row with none.
     * @param appVersion the running build, so a report is attributable to a version.
     * @param simLabel the send-time SIM as displayed, or null when unknown.
     * @param preferredSimLabel the conversation's preferred SIM, or null when it has none.
     * @param numberTail the recipient's last 4 digits, already masked by [numberTail]; never a full
     *   number.
     * @param partCount the carrier segment count from the durable ledger, or null when unknown. Absent
     *   rather than defaulted: a guessed "1" on a 3-part message misreports the billing and the
     *   delivery semantics, and a copy action that invents numbers is worse than one that omits a line.
     */
    fun build(
        uiState: String?,
        evidence: Evidence,
        appVersion: String,
        simLabel: String?,
        preferredSimLabel: String? = null,
        numberTail: String? = null,
        partCount: Int? = null
    ): String {
        val state = SmsUiState.from(uiState)
        val lines = mutableListOf<String>()

        lines += "Messages $appVersion"
        lines += "status: ${state?.name ?: "no-app-owned-state"}"
        lines += "source: ${evidence.source}"
        lines += "providerId: ${evidence.providerId}"
        lines += "threadId: ${evidence.threadId}"
        lines += "outgoing: ${evidence.isOutgoing}"
        lines += "sentAt: ${evidence.sentAt}"
        if (evidence.dateSent > 0) lines += "dateSent: ${evidence.dateSent}"
        numberTail?.let { lines += "recipient: $it" }

        // ── transport evidence ───────────────────────────────────────────────
        evidence.transportState?.let { lines += "transport: $it" }
        evidence.sendFailureCode?.let { lines += "sendFailureCode: $it" }
        evidence.sendResultCode?.let {
            // Both the numeric code and its symbolic name: the number is the ground truth and the name
            // is what makes it readable without a lookup table.
            lines += "sendResultCode: $it (${SmsResultCodes.name(it)})"
        }
        evidence.radioErrorCode?.let { lines += "radioErrorCode: $it" }
        if (evidence.sendStateUpdatedAt > 0) lines += "sendStateUpdatedAt: ${evidence.sendStateUpdatedAt}"

        // ── delivery evidence ────────────────────────────────────────────────
        evidence.deliveryEvidence?.let { lines += "deliveryEvidence: $it" }
        evidence.deliveryTpStatus?.let {
            // Hex as well as decimal: TP-Status is specified in hex in 3GPP, and a support engineer
            // comparing against the spec needs the value in the form the spec uses.
            lines += "deliveryTpStatus: $it (0x${Integer.toHexString(it)})"
        }
        evidence.deliveryResultCode?.let { lines += "deliveryResultCode: $it" }
        if (evidence.deliveryCallbackAt > 0) lines += "deliveryCallbackAt: ${evidence.deliveryCallbackAt}"

        // ── context ──────────────────────────────────────────────────────────
        simLabel?.let { lines += "sendingSim: $it" }
        preferredSimLabel?.let { lines += "preferredSim: $it" }
        partCount?.let { lines += "parts: $it" }

        return lines.joinToString("\n")
    }

    /**
     * Read the persisted evidence for [sms] and build its copyable text, or null when the row cannot
     * be found or read.
     *
     * The read is by `(source, providerId)`, the same composite identity the mirror uses, so the text
     * can only ever describe the message the user opened. Returns null rather than a partial string: a
     * copy action that silently omits the evidence would look like a clean bill of health.
     */
    suspend fun buildFromDao(
        sms: com.autonomousone.messages.model.Sms,
        appVersion: String,
        simLabel: String? = null,
        preferredSimLabel: String? = null
    ): String? = try {
        val db = com.autonomousone.messages.data.MessagesDatabase.get(
            com.autonomousone.messages.Holders.appContext
        )
        // The bubble's id is positive for SMS and NEGATED for MMS (the provider reader convention),
        // so the source is derived from its sign rather than assumed to be SMS.
        val source = if (sms.id < 0) {
            com.autonomousone.messages.data.MessageEntity.SOURCE_MMS
        } else {
            com.autonomousone.messages.data.MessageEntity.SOURCE_SMS
        }
        val row = db.messageDao().technicalEvidenceOf(source, kotlin.math.abs(sms.id))
            ?: return null
        val parts = runCatching { db.sendSegmentDao().callbackStatesForRow(row.providerId).size }
            .getOrNull()
            ?.takeIf { it > 0 }
        build(
            uiState = sms.uiState,
            evidence = Evidence(
                source = row.source,
                providerId = row.providerId,
                threadId = row.threadId,
                isOutgoing = row.type == android.provider.Telephony.Sms.MESSAGE_TYPE_SENT,
                sentAt = row.date,
                dateSent = row.dateSent,
                transportState = row.sendTransportState,
                sendFailureCode = row.sendFailureCode,
                sendResultCode = row.sendResultCode,
                radioErrorCode = row.sendRadioErrorCode,
                sendStateUpdatedAt = row.sendStateUpdatedAt,
                deliveryEvidence = row.deliveryEvidence,
                deliveryTpStatus = row.deliveryTpStatus,
                deliveryResultCode = row.deliveryResultCode,
                deliveryCallbackAt = row.deliveryCallbackAt
            ),
            appVersion = appVersion,
            simLabel = simLabel,
            preferredSimLabel = preferredSimLabel,
            numberTail = numberTail(sms.sender),
            partCount = parts
        )
    } catch (e: Exception) {
        null
    }

    /**
     * The largest share of a phone number that may appear: the last 4 digits.
     *
     * Enough for a user to recognise which conversation they are reporting, and not enough to identify
     * a person from the text alone.
     */
    fun numberTail(raw: String): String =
        raw.filter { it.isDigit() }.takeLast(4).takeIf { it.length == 4 }?.let { "***$it" } ?: "***"
}
