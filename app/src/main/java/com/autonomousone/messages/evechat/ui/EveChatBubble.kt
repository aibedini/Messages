package com.autonomousone.messages.evechat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.autonomousone.messages.evechat.model.EveChatMessage

@Composable
fun EveChatBubble(
    message: EveChatMessage,
    modifier: Modifier = Modifier
) {
    val isAgent = message.senderType == "agent"
    val isSystem = message.senderType == "system" || message.senderType == "bot"
    val isInternal = message.isInternal
    val maxWidth = (LocalConfiguration.current.screenWidthDp * 0.78f).dp

    when {
        isSystem -> {
            Box(
                modifier = modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
        else -> {
            val arrangement = if (isAgent) Arrangement.End else Arrangement.Start
            val bgColor = when {
                isInternal -> Color(0xFFFFF9C4)
                isAgent -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            val textColor = when {
                isInternal -> Color(0xFF333333)
                isAgent -> MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            val shape = if (isAgent) {
                RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
            } else {
                RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
            }

            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = arrangement
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = maxWidth)
                        .clip(shape)
                        .background(bgColor)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    if (!isAgent && message.senderName != null) {
                        Text(
                            text = message.senderName,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isInternal) Color(0xFF666666)
                                else MaterialTheme.colorScheme.primary
                        )
                    }

                    if (isInternal) {
                        Text(
                            text = "یادداشت داخلی",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFE65100)
                        )
                    }

                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val timeText = formatMessageTime(message.createdAt)
                        Text(
                            text = timeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.7f)
                        )

                        if (isAgent) {
                            val (icon, tint) = when (message.sendState) {
                                EveChatMessage.SendState.Sending -> Icons.Default.Schedule to textColor.copy(alpha = 0.5f)
                                EveChatMessage.SendState.Sent -> Icons.Default.Check to textColor.copy(alpha = 0.7f)
                                EveChatMessage.SendState.Failed -> Icons.Default.ErrorOutline to MaterialTheme.colorScheme.error
                            }
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp).padding(start = 2.dp),
                                tint = tint
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatMessageTime(isoTime: String): String {
    if (isoTime.isBlank()) return ""
    return try {
        val timePart = isoTime.substringAfter("T").substringBefore(".")
            .substringBefore("Z").substringBefore("+").substringBefore("-")
        val parts = timePart.split(":")
        if (parts.size >= 2) "${parts[0]}:${parts[1]}" else timePart
    } catch (_: Exception) {
        ""
    }
}
