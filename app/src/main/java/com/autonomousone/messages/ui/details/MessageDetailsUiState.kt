package com.autonomousone.messages.ui.details

import com.autonomousone.messages.data.MessageTechnicalEvidence
import com.autonomousone.messages.data.SendSegmentEntity
import com.autonomousone.messages.data.SegmentCallbackState
import com.autonomousone.messages.sms.SmsResultCodes
import com.autonomousone.messages.sms.SmsStatusPresentationMapper
import com.autonomousone.messages.sms.SmsUiState
import com.autonomousone.messages.sms.SendStateDerivation

/**
 * One step of the delivery story, with an icon meaning and nothing more.
 *
 * Deliberately NOT a Compose type: the wording and the ordering are domain decisions, and a screen that
 * decided them itself is how two surfaces come to tell different stories about one message.
 */
data class StatusStep(
    val kind: Kind,
    val title: String,
    val detail: String? = null,
    /** Only ever a time that recorded evidence supports. Null means "no time is known". */
    val at: Long? = null
) {
    enum class Kind {
        /** Positively confirmed. */
        DONE,

        /** Definitively did not happen. */
        FAILED,

        /** Unknown: no evidence either way. NOT a failure. */
        UNKNOWN,

        /** Not attempted, because the message never got that far. */
        SKIPPED
    }
}

/**
 * Everything Message Details renders, derived once from persisted rows.
 *
 * ## The distinction this whole screen exists for
 *
 * Sending and delivery are TWO stages, and the common real-world outcome is that the first succeeded
 * while the second was never answered:
 *
 * ```text
 * submitted to network  ✓      the radio took it
 * sent                  ✓      the SMSC acknowledged the submit
 * delivery              ?      no report came back — this is NOT a failure
 * ```
 *
 * Collapsing those into one word is what makes an app claim "failed" for a message that arrived, or
 * "delivered" for one that never left. The four terminal states stay strictly apart:
 * `NOT_SENT` (transport refused), `NOT_DELIVERED` (carrier reported a negative report),
 * `SEND_STATUS_UNKNOWN` (ambiguous transport), `DELIVERY_UNKNOWN` (sent, no conclusive report).
 */
data class MessageDetailsUiState(
    val loaded: Boolean,
    /** True when the mirror has no row for this message (deleted while open, or not synced yet). */
    val missing: Boolean,
    val isOutgoing: Boolean,

    // ── the message itself ──
    val body: String,
    val recipient: String,
    val sender: String,
    val createdAt: Long,

    // ── the two-stage status ──
    val state: SmsUiState?,
    val headline: String,
    val steps: List<StatusStep>,
    val canRetry: Boolean,
    val duplicateRisk: Boolean,

    // ── SIM and network ──
    /**
     * The SIM that ACTUALLY carried this message, read from the send-time ledger.
     *
     * Null means "not known", which is rendered as Unknown. It is never filled from the current default
     * SIM: a SIM selected today says nothing about a message sent last week, and showing it would be a
     * plausible fabrication. For the same reason [simEvidenceConflict] exists.
     */
    val sentWithLabel: String?,
    val sentSlotIndex: Int?,
    /** True when the ledger's parts disagree about the subscription, so the label is not trustworthy. */
    val simEvidenceConflict: Boolean,
    val partCount: Int?,

    // ── technical ──
    val transportState: String?,
    val sentResultCode: Int?,
    val radioErrorCode: Int?,
    val failureCode: String?,
    val deliveryResultCode: Int?,
    val deliveryEvidence: String?,
    val deliveryTpStatus: Int?,
    val deliveryCallbackAt: Long?,
    val submittedAt: Long?,
    val dateSent: Long?,
    val providerId: Long,
    val threadId: Long,
    val source: String,
    val parts: List<PartEvidence>,
    val appVersion: String
) {
    /** The canonical symbolic name for the SENT result, or null when no code was recorded. */
    val sentResultName: String? get() = sentResultCode?.let { SmsResultCodes.name(it) }

    companion object {

        /**
         * Build the state from the two persisted sources.
         *
         * @param evidence the `messages` row, or null when the mirror has none.
         * @param segments the durable send ledger for this message, possibly empty.
         */
        fun of(
            evidence: MessageTechnicalEvidence?,
            segments: List<SendSegmentEntity>,
            body: String,
            recipient: String,
            sender: String,
            appVersion: String,
            /** Resolves a subscription id to a display label, or null when it cannot be resolved. */
            simLabel: (Int) -> String?
        ): MessageDetailsUiState {
            if (evidence == null) {
                return missingState(body, recipient, sender, appVersion)
            }
            val isOutgoing = evidence.type == android.provider.Telephony.Sms.MESSAGE_TYPE_SENT

            // The state is derived from the SAME evidence mapper every other surface uses, so the
            // details screen cannot disagree with the bubble or the list about this message.
            val uiState = if (!isOutgoing) {
                null
            } else {
                SendStateDerivation.evidenceFromPersisted(
                    message = mirrorStub(evidence),
                    isOutgoing = true
                ).let { SmsStatusPresentationMapper.present(it) }.state
            }
            val presentation = uiState?.let { SmsStatusPresentationMapper.present(
                SendStateDerivation.evidenceFromPersisted(mirrorStub(evidence), true)
            ) }

            val submittedAt = segments.mapNotNull { it.submittedAt }.minOrNull()
            val partCount = segments.map { it.partCount }.maxOrNull()?.takeIf { it > 0 }
                ?: segments.size.takeIf { it > 0 }

            // Send-time SIM, with an explicit conflict signal. `-1` is the ledger's "unknown"
            // subscription marker (SubscriptionManager.INVALID_SUBSCRIPTION_ID), not a line.
            val subscriptions = segments.map { it.subscriptionId }.filter { it >= 0 }.distinct()
            val simConflict = subscriptions.size > 1
            val resolvedSubId = subscriptions.singleOrNull()
            val sentLabel = resolvedSubId?.let { simLabel(it) }

            return MessageDetailsUiState(
                loaded = true,
                missing = false,
                isOutgoing = isOutgoing,
                body = body,
                recipient = recipient,
                sender = sender,
                createdAt = evidence.date,
                state = uiState,
                headline = headlineFor(uiState, isOutgoing),
                steps = stepsFor(uiState, isOutgoing, submittedAt, evidence),
                canRetry = presentation?.canRetry ?: false,
                duplicateRisk = uiState == SmsUiState.SEND_STATUS_UNKNOWN,
                sentWithLabel = sentLabel,
                sentSlotIndex = null,
                simEvidenceConflict = simConflict,
                partCount = partCount,
                transportState = evidence.sendTransportState,
                sentResultCode = evidence.sendResultCode,
                radioErrorCode = evidence.sendRadioErrorCode,
                failureCode = evidence.sendFailureCode,
                deliveryResultCode = evidence.deliveryResultCode,
                deliveryEvidence = evidence.deliveryEvidence,
                deliveryTpStatus = evidence.deliveryTpStatus,
                deliveryCallbackAt = evidence.deliveryCallbackAt.takeIf { it > 0 },
                submittedAt = submittedAt,
                dateSent = evidence.dateSent.takeIf { it > 0 },
                providerId = evidence.providerId,
                threadId = evidence.threadId,
                source = evidence.source,
                parts = segments.sortedBy { it.partIndex }.map {
                    PartEvidence(
                        index = it.partIndex,
                        count = it.partCount,
                        state = it.callbackState,
                        failureCode = it.callbackFailureCode
                    )
                },
                appVersion = appVersion
            )
        }

        /**
         * A minimal mirror row for the evidence derivation.
         *
         * The derivation reads only the app-owned columns, so this carries exactly those and nothing
         * else — which keeps the details screen from ever depending on the message body to decide a
         * status.
         */
        private fun mirrorStub(e: MessageTechnicalEvidence) =
            com.autonomousone.messages.data.MessageEntity(
                source = e.source,
                providerId = e.providerId,
                threadId = e.threadId,
                normalizedAddress = "",
                rawAddress = "",
                body = "",
                date = e.date,
                type = e.type,
                status = 0,
                dateSent = e.dateSent,
                read = true,
                sendTransportState = e.sendTransportState,
                sendFailureCode = e.sendFailureCode,
                sendResultCode = e.sendResultCode,
                sendRadioErrorCode = e.sendRadioErrorCode,
                sendStateUpdatedAt = e.sendStateUpdatedAt,
                deliveryCallbackAt = e.deliveryCallbackAt,
                deliveryTpStatus = e.deliveryTpStatus,
                deliveryEvidence = e.deliveryEvidence,
                deliveryResultCode = e.deliveryResultCode
            )

        private fun headlineFor(state: SmsUiState?, isOutgoing: Boolean): String {
            if (!isOutgoing) return "Received"
            return when (state) {
                SmsUiState.SENDING -> "Sending…"
                SmsUiState.SENT -> "Sent"
                SmsUiState.DELIVERED -> "Delivered"
                SmsUiState.NOT_SENT -> "Not sent"
                SmsUiState.NOT_DELIVERED -> "Not delivered"
                SmsUiState.SEND_STATUS_UNKNOWN -> "Send status unknown"
                SmsUiState.DELIVERY_UNKNOWN -> "Delivery not confirmed"
                null -> "No send record"
            }
        }

        /**
         * The timeline.
         *
         * A step's `at` is filled ONLY from a timestamp the persisted evidence actually recorded. A
         * delivery callback's receipt time is the moment the PHONE was told, which is not the moment the
         * recipient received anything — so when the evidence is unknown the step carries no time at all
         * rather than the callback's.
         */
        private fun stepsFor(
            state: SmsUiState?,
            isOutgoing: Boolean,
            submittedAt: Long?,
            e: MessageTechnicalEvidence
        ): List<StatusStep> {
            if (!isOutgoing) return emptyList()
            val steps = mutableListOf<StatusStep>()

            // Stage 1a: did the radio take it?
            steps += when {
                submittedAt != null -> StatusStep(
                    StatusStep.Kind.DONE, "Submitted to network", at = submittedAt
                )
                // A NULL submittedAt means the native call was never accepted — the ledger records a
                // refusal that way so the daily counter cannot bill a message that never left.
                e.sendTransportState == null -> StatusStep(
                    StatusStep.Kind.UNKNOWN, "Submit record not available"
                )
                else -> StatusStep(StatusStep.Kind.SKIPPED, "Not submitted to the network")
            }

            // Stage 1b: did the SMSC acknowledge?
            //
            // NOT_DELIVERED belongs here, on the DONE side: a negative carrier report means the handset
            // DID send the message and the network then failed to deliver it. Listing it as a transport
            // failure would say the phone never got it out, which is the opposite of what happened, and
            // would point the user at the wrong thing to fix.
            steps += when (state) {
                SmsUiState.SENT, SmsUiState.DELIVERED, SmsUiState.DELIVERY_UNKNOWN,
                SmsUiState.NOT_DELIVERED ->
                    StatusStep(StatusStep.Kind.DONE, "Sent", at = e.sendStateUpdatedAt.takeIf { it > 0 })
                SmsUiState.NOT_SENT ->
                    StatusStep(StatusStep.Kind.FAILED, "Not sent")
                SmsUiState.SEND_STATUS_UNKNOWN ->
                    StatusStep(StatusStep.Kind.UNKNOWN, "Send status unknown", at = e.sendStateUpdatedAt.takeIf { it > 0 })
                SmsUiState.SENDING ->
                    StatusStep(StatusStep.Kind.UNKNOWN, "Awaiting network confirmation")
                null -> StatusStep(StatusStep.Kind.UNKNOWN, "No send record")
            }
            // An ambiguous submit is not a send: saying "Sent" here would assert something unproven.
            if (state == SmsUiState.SEND_STATUS_UNKNOWN) {
                steps += StatusStep(
                    StatusStep.Kind.UNKNOWN,
                    "Delivery not confirmed",
                    "The network did not confirm whether the SMS was sent. Retrying may send it twice."
                )
                return steps
            }

            // Stage 2: carrier delivery.
            steps += when (state) {
                SmsUiState.DELIVERED -> StatusStep(
                    StatusStep.Kind.DONE, "Delivered", at = e.deliveryCallbackAt.takeIf { it > 0 }
                )
                SmsUiState.NOT_DELIVERED -> StatusStep(
                    StatusStep.Kind.FAILED,
                    "Not delivered",
                    "The mobile network reported that delivery failed."
                )
                SmsUiState.DELIVERY_UNKNOWN, SmsUiState.SENT -> StatusStep(
                    StatusStep.Kind.UNKNOWN,
                    "Delivery not confirmed",
                    "No conclusive delivery report was received. This does not prove that delivery failed."
                )
                SmsUiState.NOT_SENT -> StatusStep(
                    StatusStep.Kind.SKIPPED, "Delivery", "The message never reached the network."
                )
                // The transport stage already reported the failure; nothing was delivered and there is
                // no carrier evidence to add, so the delivery stage is explicitly not attempted rather
                // than repeated as a second failure.
                SmsUiState.NOT_DELIVERED -> StatusStep(
                    StatusStep.Kind.SKIPPED, "Delivery"
                )
                else -> StatusStep(StatusStep.Kind.UNKNOWN, "Delivery not confirmed")
            }
            return steps
        }

        private fun missingState(
            body: String,
            recipient: String,
            sender: String,
            appVersion: String
        ) = MessageDetailsUiState(
            loaded = true,
            missing = true,
            isOutgoing = false,
            body = body,
            recipient = recipient,
            sender = sender,
            createdAt = 0L,
            state = null,
            headline = "",
            steps = emptyList(),
            canRetry = false,
            duplicateRisk = false,
            sentWithLabel = null,
            sentSlotIndex = null,
            simEvidenceConflict = false,
            partCount = null,
            transportState = null,
            sentResultCode = null,
            radioErrorCode = null,
            failureCode = null,
            deliveryResultCode = null,
            deliveryEvidence = null,
            deliveryTpStatus = null,
            deliveryCallbackAt = null,
            submittedAt = null,
            dateSent = null,
            providerId = 0L,
            threadId = 0L,
            source = "",
            parts = emptyList(),
            appVersion = appVersion
        )
    }
}

/** One carrier segment's verdict, for the compact multipart breakdown. */
data class PartEvidence(
    val index: Int,
    val count: Int,
    val state: SegmentCallbackState,
    val failureCode: String?
) {
    /** 1-based position as the user sees it. */
    val position: Int get() = index + 1
}
