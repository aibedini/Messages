package com.autonomousone.messages.evechat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autonomousone.messages.R
import com.autonomousone.messages.evechat.model.ConvStatus
import com.autonomousone.messages.evechat.model.EveChatConversation
import com.autonomousone.messages.evechat.model.SenderType
import com.autonomousone.messages.utils.formatConversationDate

@Composable
fun EveChatConversationRow(
    conversation: EveChatConversation,
    myAgentId: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val name = conversation.displayName.ifBlank { stringResource(R.string.eve_visitor) }
    val hasUnread = conversation.unreadAgent > 0
    val preview = when {
        conversation.lastMessagePreview == null ->
            conversation.subject ?: stringResource(R.string.eve_row_no_message)
        conversation.lastMessageSenderType == SenderType.AGENT ->
            stringResource(R.string.eve_row_you_fmt, conversation.lastMessagePreview)
        else -> conversation.lastMessagePreview
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = name.trim().firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatConversationDate(conversation.activityAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasUnread) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.size(2.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (hasUnread) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                StatusChip(conversation, myAgentId)
                if (hasUnread) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (conversation.unreadAgent > 99) "99+" else conversation.unreadAgent.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(conversation: EveChatConversation, myAgentId: String?) {
    val scheme = MaterialTheme.colorScheme
    val (bg, fg, label) = when {
        conversation.status == ConvStatus.PENDING && conversation.assignedAgentId == null ->
            Triple(scheme.tertiaryContainer, scheme.onTertiaryContainer, stringResource(R.string.eve_status_waiting))
        conversation.status == ConvStatus.RESOLVED ->
            Triple(scheme.surfaceVariant, scheme.onSurfaceVariant, stringResource(R.string.eve_status_resolved))
        conversation.status == ConvStatus.CLOSED ->
            Triple(scheme.surfaceVariant, scheme.onSurfaceVariant, stringResource(R.string.eve_status_closed))
        conversation.assignedAgentId != null && conversation.assignedAgentId != myAgentId ->
            Triple(
                scheme.secondaryContainer,
                scheme.onSecondaryContainer,
                conversation.assignedAgentName ?: stringResource(R.string.eve_unknown_agent)
            )
        else -> return
    }
    Surface(shape = RoundedCornerShape(12.dp), color = bg, modifier = Modifier.padding(start = 6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .widthIn(max = 120.dp)
        )
    }
}
