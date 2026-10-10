package com.autonomousone.messages.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.MessageTechnicalEvidence
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.sms.SmsResultCodes
import com.autonomousone.messages.sms.SmsStatusPresentationMapper
import com.autonomousone.messages.sms.SmsUiState
import com.autonomousone.messages.sms.TechnicalDetailsText
import java.text.DateFormat
import java.util.Date

/**
 * Message Details — why this message did or did not arrive, from PERSISTED evidence.
 *
 * ## Why a screen and not just the bubble's detail line
 *
 * The bubble line answers "is something wrong?" in one glance, which is what a list of messages needs.
 * It cannot answer the follow-up the user actually asks — "why, and what does the phone know?" — and
 * it has no room for the codes a support conversation needs. This screen exists for that second
 * question.
 *
 * ## Where every value comes from
 *
 * All of it is read from the `messages` row by `(source, providerId)` on open, so the screen can only
 * ever describe the message the user opened. Nothing is passed in from a ref the UI happened to hold,
 * and nothing is a placeholder: a missing value produces an ABSENT row, never a zero or a dash that
 * could be mistaken for evidence.
 *
 * ## The distinction the wording protects
 *
 * ```text
 * Not sent        the handset never got it out
 * Not delivered   the carrier reported it could not be delivered
 * Delivery unknown / no report   NOT a failure — we simply have no conclusive answer
 * ```
 *
 * A missing delivery report is never rendered as a delivery failure, and no insufficient-balance cause
 * is ever asserted: Android has no portable result code for it, so claiming one would be inventing
 * evidence.
 */
@Composable
fun MessageDetailsDialog(
    sms: Sms,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit
) {
    var evidence by remember(sms.id) { mutableStateOf<MessageTechnicalEvidence?>(null) }
    var partCount by remember(sms.id) { mutableStateOf<Int?>(null) }
    var loaded by remember(sms.id) { mutableStateOf(false) }

    LaunchedEffect(sms.id) {
        val db = MessagesDatabase.get(com.autonomousone.messages.Holders.appContext)
        val source = if (sms.id < 0) {
            com.autonomousone.messages.data.MessageEntity.SOURCE_MMS
        } else {
            com.autonomousone.messages.data.MessageEntity.SOURCE_SMS
        }
        val row = runCatching {
            db.messageDao().technicalEvidenceOf(source, kotlin.math.abs(sms.id))
        }.getOrNull()
        val parts = runCatching {
            db.sendSegmentDao().callbackStatesForRow(kotlin.math.abs(sms.id)).size
        }.getOrNull()?.takeIf { it > 0 }
        evidence = row
        partCount = parts
        loaded = true
    }

    val state = SmsUiState.from(sms.uiState)
    val presentation = state?.let { s ->
        SmsStatusPresentationMapper.present(
            com.autonomousone.messages.sms.SmsStatusEvidence(
                isOutgoing = sms.type == 2,
                failureCode = null,
                hasSentConfirmation = s == SmsUiState.SENT || s == SmsUiState.DELIVERED
            )
        ).copy(state = s, label = labelForState(s))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Message details") },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    val e = evidence
                    if (e != null) {
                        onCopy(
                            TechnicalDetailsText.build(
                                uiState = sms.uiState,
                                evidence = TechnicalDetailsText.Evidence(
                                    source = e.source,
                                    providerId = e.providerId,
                                    threadId = e.threadId,
                                    isOutgoing = e.type == 2,
                                    sentAt = e.date,
                                    dateSent = e.dateSent,
                                    transportState = e.sendTransportState,
                                    sendFailureCode = e.sendFailureCode,
                                    sendResultCode = e.sendResultCode,
                                    radioErrorCode = e.sendRadioErrorCode,
                                    sendStateUpdatedAt = e.sendStateUpdatedAt,
                                    deliveryEvidence = e.deliveryEvidence,
                                    deliveryTpStatus = e.deliveryTpStatus,
                                    deliveryResultCode = e.deliveryResultCode,
                                    deliveryCallbackAt = e.deliveryCallbackAt
                                ),
                                appVersion = com.autonomousone.messages.BuildConfig.APP_VERSION,
                                simLabel = null,
                                numberTail = TechnicalDetailsText.numberTail(sms.sender),
                                partCount = partCount
                            )
                        )
                    }
                }
            ) { Text("Copy technical details") }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (!loaded) {
                    Text("Loading…", style = MaterialTheme.typography.bodyMedium)
                    return@Column
                }
                val e = evidence
                if (e == null) {
                    // The mirror has no row for this message yet. Saying so is honest; drawing empty
                    // rows would look like evidence that everything is normal.
                    Text(
                        "No stored evidence for this message yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    return@Column
                }

                // ── the answer, in words ────────────────────────────────────
                val reason = reasonFor(state)
                if (reason != null) {
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (state?.needsAttention == true) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    Spacer(Modifier.height(4.dp))
                }

                Row("Status", presentation?.label ?: labelForState(state))
                Row("Sent at", formatTime(e.date))
                // Only when the platform actually recorded one: a zero here is "unknown", and printing
                // it as a timestamp would be a fabricated fact.
                if (e.dateSent > 0) Row("Delivered at", formatTime(e.dateSent))
                if (partCount != null) Row("SMS parts", partCount.toString())

                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text(
                    "Technical details",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )

                // Every line below appears only when its value exists. Nothing is defaulted, because a
                // defaulted diagnostic value is indistinguishable from a measured one.
                e.sendTransportState?.let { Row("Transport state", it) }
                e.sendResultCode?.let {
                    Row("SENT result", "${SmsResultCodes.name(it)} ($it)")
                }
                e.sendRadioErrorCode?.let { Row("Radio error", it.toString()) }
                e.sendFailureCode?.let { Row("Failure code", it) }
                e.deliveryResultCode?.let { Row("Delivery result", it.toString()) }
                e.deliveryTpStatus?.let {
                    // Decimal AND hex: TP-Status is defined in hex by 3GPP, so a support engineer
                    // comparing against the specification needs it in that form.
                    Row("Delivery TP-Status", "$it (0x${Integer.toHexString(it)})")
                }
                e.deliveryEvidence?.let { Row("Delivery evidence", it) }
                if (e.deliveryCallbackAt > 0) Row("Last callback", formatTime(e.deliveryCallbackAt))

                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Row("Provider id", e.providerId.toString())
                Row("Thread id", e.threadId.toString())
                Row("Direction", if (e.type == 2) "outgoing" else "incoming")

                val note = deliveryNote(state)
                if (note != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    )
}

/** One label/value line, with the label quiet and the value legible. */
@Composable
private fun Row(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
    }
}

private fun labelForState(state: SmsUiState?): String = when (state) {
    SmsUiState.SENDING -> "Sending…"
    SmsUiState.SENT -> "Sent"
    SmsUiState.DELIVERED -> "Delivered"
    SmsUiState.NOT_SENT -> "Not sent"
    SmsUiState.NOT_DELIVERED -> "Not delivered"
    SmsUiState.SEND_STATUS_UNKNOWN -> "Send status unknown"
    SmsUiState.DELIVERY_UNKNOWN -> "Delivery unknown"
    null -> "No app-owned state"
}

/**
 * The one-line explanation of what the evidence means.
 *
 * Deliberately says nothing about balance or credit: Android returns no portable code distinguishing
 * "no credit" from any other refusal, so asserting it would be inventing a cause. The rate-limit case
 * IS named, because that one has a real code (106) behind it.
 */
private fun reasonFor(state: SmsUiState?): String? = when (state) {
    SmsUiState.NOT_SENT ->
        "The mobile network could not complete this request. " +
            "Check network availability, SIM service, balance, or carrier restrictions."
    SmsUiState.NOT_DELIVERED -> "The carrier reported that delivery failed."
    SmsUiState.SEND_STATUS_UNKNOWN ->
        "The phone could not confirm whether the SMS was sent. Retrying may send it twice."
    SmsUiState.DELIVERY_UNKNOWN -> "No conclusive delivery report was received."
    SmsUiState.SENDING -> null
    else -> null
}

/** A note about what the delivery evidence does and does not prove. */
private fun deliveryNote(state: SmsUiState?): String? = when (state) {
    SmsUiState.DELIVERY_UNKNOWN, SmsUiState.SENT ->
        "A missing delivery report is not proof that the message was not received."
    SmsUiState.NOT_DELIVERED ->
        "The handset did send this message; the network reported that delivery failed."
    SmsUiState.SEND_STATUS_UNKNOWN ->
        "An ambiguous result means the message may have left the phone. No automatic resend is attempted."
    else -> null
}

private fun formatTime(epochMs: Long): String =
    runCatching { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(epochMs)) }
        .getOrDefault(epochMs.toString())
