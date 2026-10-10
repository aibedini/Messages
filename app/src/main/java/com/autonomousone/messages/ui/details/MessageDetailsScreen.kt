package com.autonomousone.messages.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonomousone.messages.sms.SmsUiState
import java.text.DateFormat
import java.util.Date

/**
 * Message Details — a full-screen destination.
 *
 * ## Shape of the screen
 *
 * The everyday answer is what you see: a preview of the message, a two-stage status, which SIM carried
 * it, and who it went to. The raw codes live behind `Technical details`, closed by default, so an
 * operator needing to compare `RESULT_RIL_REQUEST_RATE_LIMITED (106)` or a carrier TP-Status against a
 * spec can open one section rather than read a wall of diagnostics every time.
 *
 * ## Two stages, never one word
 *
 * The status card shows the network stage and the delivery stage SEPARATELY, because the most common
 * real outcome is that the first succeeded and the second was never answered. "Submitted to network"
 * plus "Delivery not confirmed" is the truth; "Failed" is not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageDetailsScreen(
    state: MessageDetailsUiState,
    onBack: () -> Unit,
    onCopyTechnicalDetails: () -> Unit
) {
    var technicalOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Message details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!state.loaded) {
                PlainCard { Text("Loading…", style = MaterialTheme.typography.bodyMedium) }
                return@Column
            }
            if (state.missing) {
                // The row is gone (deleted elsewhere, or not mirrored yet). Saying so is honest;
                // drawing empty sections would look like a clean bill of health.
                PlainCard {
                    Text(
                        "This message is no longer available on this device.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                return@Column
            }

            MessagePreviewCard(state)
            StatusCard(state)
            SimAndNetworkCard(state)
            RecipientCard(state)
            MessageInformationCard(state)

            // Only when there is a real reason. A failure card on a message with no recorded failure
            // would be inventing a problem.
            FailureReasonCard(state)

            TechnicalDetailsCard(
                state = state,
                open = technicalOpen,
                onToggle = { technicalOpen = !technicalOpen }
            )

            TextButton(
                onClick = {
                    // Copies the SAME persisted evidence the card shows, via the one exporter, so the
                    // clipboard can never disagree with the screen.
                    onCopyTechnicalDetails()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Copy technical details")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MessagePreviewCard(state: MessageDetailsUiState) {
    // The message is shown EXACTLY as it was sent: wrapped, not truncated, and never re-worded. A
    // details screen that presented a shortened or paraphrased body would be describing a different
    // message than the one the user is asking about.
    val bubbleColor = if (state.isOutgoing) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    // Alignment.Horizontal, matching Box's contentAlignment parameter — an Alignment.Vertical would
    // compile as the wrong type and silently centre the bubble.
    val alignment = if (state.isOutgoing) Alignment.CenterEnd else Alignment.CenterStart
    PlainCard {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth(), contentAlignment = alignment) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(bubbleColor)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = state.body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: MessageDetailsUiState) {
    SectionCard(title = "Message status") {
        // The headline names the OVERALL outcome in the user's terms.
        Text(
            text = state.headline,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (state.state?.needsAttention == true) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
        if (state.steps.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            state.steps.forEachIndexed { index, step ->
                StatusStepRow(step)
                if (index != state.steps.lastIndex) {
                    // A connector, so the two-stage structure reads as a sequence rather than a list.
                    Box(
                        Modifier
                            .padding(start = 11.dp)
                            .width(2.dp)
                            .height(12.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                }
            }
        }
        if (state.duplicateRisk) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Retrying may send the message twice.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun StatusStepRow(step: StatusStep) {
    val (icon, tint, cd) = when (step.kind) {
        StatusStep.Kind.DONE -> Triple(Icons.Default.Check, MaterialTheme.colorScheme.primary, "done")
        StatusStep.Kind.FAILED -> Triple(Icons.Default.Close, MaterialTheme.colorScheme.error, "failed")
        // Unknown is deliberately NOT red: no evidence is not the same as bad news.
        StatusStep.Kind.UNKNOWN -> Triple(Icons.Default.HelpOutline, MaterialTheme.colorScheme.onSurfaceVariant, "unknown")
        StatusStep.Kind.SKIPPED -> Triple(Icons.Default.Schedule, MaterialTheme.colorScheme.onSurfaceVariant, "not attempted")
    }
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Icon(
            imageVector = icon,
            contentDescription = cd,
            tint = tint,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = step.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                // Only a time the recorded evidence supports. A delivery callback's receipt time is
                // when the PHONE was told, not when the recipient received anything, so it is not
                // promoted to a "delivered at" claim.
                if (step.at != null) {
                    Text(
                        text = formatTime(step.at),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            step.detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SimAndNetworkCard(state: MessageDetailsUiState) {
    SectionCard(title = "SIM & network") {
        DetailRow(
            label = "Sent using",
            // The SEND-TIME line from the ledger, never a re-read of today's default SIM. A SIM chosen
            // today says nothing about a message sent last week, and showing it would be a plausible
            // fabrication.
            value = when {
                state.simEvidenceConflict -> "Conflicting SIM records"
                state.sentWithLabel != null -> state.sentWithLabel
                state.isOutgoing -> "Unknown"
                else -> "—"
            }
        )
        if (state.simEvidenceConflict) {
            Text(
                text = "Parts of this message record different SIMs, so the sending line cannot be " +
                    "stated with confidence.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (state.partCount != null) {
            DetailRow(label = "SMS parts", value = state.partCount.toString())
        }
    }
}

@Composable
private fun RecipientCard(state: MessageDetailsUiState) {
    SectionCard(title = if (state.isOutgoing) "To" else "From") {
        val primary = if (state.isOutgoing) state.recipient else state.sender
        DetailRow(label = if (state.isOutgoing) "Recipient" else "Sender", value = primary.ifBlank { "Unknown" })
    }
}

@Composable
private fun MessageInformationCard(state: MessageDetailsUiState) {
    SectionCard(title = "Message information") {
        DetailRow(label = "Type", value = "Text message")
        DetailRow(label = "Created at", value = formatTime(state.createdAt))
        // Only shown when the evidence recorded one. There is no "priority" row: nothing in the
        // provider or protocol stores it, and a generic "Normal" would be invented.
        state.submittedAt?.let { DetailRow(label = "Submitted at", value = formatTime(it)) }
        state.dateSent?.let { DetailRow(label = "Sent at", value = formatTime(it)) }
        state.deliveryCallbackAt?.let { DetailRow(label = "Delivery report", value = formatTime(it)) }
    }
}

@Composable
private fun FailureReasonCard(state: MessageDetailsUiState) {
    val copy = failureCopy(state) ?: return
    SectionCard(title = copy.first) {
        Text(
            text = copy.second,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        // The raw, actionable code, so the sentence above can be verified rather than trusted.
        state.failureCode?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        state.sentResultCode?.let {
            Text(
                text = com.autonomousone.messages.sms.SmsResultCodes.name(it) + " ($it)",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The reason a message did not arrive, in words, or null when there is nothing to report.
 *
 * A missing delivery report returns null on purpose: it is not a failure, so it must not produce a
 * failure card. And no cause ever mentions balance or credit — Android returns no portable code
 * distinguishing it, so asserting it would be inventing evidence.
 */
private fun failureCopy(state: MessageDetailsUiState): Pair<String, String>? = when (state.state) {
    SmsUiState.NOT_SENT -> {
        val rateLimited = state.sentResultCode == 106 || state.failureCode == "RIL_RATE_LIMITED"
        if (rateLimited) {
            "Sending temporarily limited" to
                "The Android radio rejected the send request because requests were being made too frequently."
        } else {
            "Not sent" to
                "The mobile network could not complete this request. Check network availability, " +
                "SIM service, balance, or carrier restrictions."
        }
    }
    SmsUiState.NOT_DELIVERED ->
        "Carrier delivery failure" to
            "The carrier returned a negative delivery status report for this message."
    SmsUiState.SEND_STATUS_UNKNOWN ->
        "Send status unknown" to
            "The radio returned an ambiguous result. The SMS may still have been accepted, which is why " +
                "no automatic resend is attempted."
    else -> null
}

@Composable
private fun TechnicalDetailsCard(
    state: MessageDetailsUiState,
    open: Boolean,
    onToggle: () -> Unit
) {
    SectionCard(title = "Technical details") {
        TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
            Text(if (open) "Hide" else "Show")
        }
        if (!open) return@SectionCard

        // Every row appears ONLY when its value exists. A null is never rendered as a zero: TP-Status 0
        // is a real positive delivery status, so printing it for "no report" would fabricate the
        // strongest evidence there is.
        state.transportState?.let { DetailRow("Transport state", it) }
        state.sentResultCode?.let { DetailRow("SENT result", "${state.sentResultName} ($it)") }
        state.radioErrorCode?.let { DetailRow("Radio errorCode", it.toString()) }
        state.failureCode?.let { DetailRow("Transport failure code", it) }
        state.deliveryResultCode?.let { DetailRow("Delivery result code", it.toString()) }
        state.deliveryTpStatus?.let { tp ->
            DetailRow("TP-Status", "$tp")
            DetailRow("TP-Status (hex)", "0x${Integer.toHexString(tp)}")
        }
        state.deliveryEvidence?.let { DetailRow("Delivery evidence", it) }
        state.deliveryCallbackAt?.let { DetailRow("Delivery evidence at", formatTime(it)) }
        DetailRow("Provider message ID", state.providerId.toString())
        DetailRow("Thread ID", state.threadId.toString())
        state.partCount?.let { DetailRow("SMS part count", it.toString()) }
        state.sentWithLabel?.let { DetailRow("Actual sending SIM", it) }
        DetailRow("Source", state.source)
        DetailRow("App version", state.appVersion)

        if (state.parts.size > 1) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                "Parts",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            state.parts.forEach { part ->
                val verdict = when (part.state) {
                    com.autonomousone.messages.data.SegmentCallbackState.CONFIRMED -> "SENT confirmed"
                    com.autonomousone.messages.data.SegmentCallbackState.AMBIGUOUS -> "SENT ambiguous"
                    com.autonomousone.messages.data.SegmentCallbackState.FAILED ->
                        "Rejected" + (part.failureCode?.let { " · $it" } ?: "")
                    com.autonomousone.messages.data.SegmentCallbackState.PENDING -> "Awaiting callback"
                }
                DetailRow("Part ${part.position}/${part.count}", verdict)
            }
        }
    }
}

// ── small building blocks ───────────────────────────────────────────────────

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun PlainCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(140.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun formatTime(epochMs: Long): String =
    runCatching {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(epochMs))
    }.getOrDefault(epochMs.toString())
