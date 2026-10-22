package com.autonomousone.messages.evechat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.autonomousone.messages.evechat.model.SendState
import com.autonomousone.messages.utils.formatMessageTime

private val NoteBackground = Color(0xFFFFF4C2)
private val NoteText = Color(0xFF3A3000)
private val NoteLabel = Color(0xFF9A5B00)

@Composable
fun EveChatBubble(
    message: EveChatMessage,
    showSender: Boolean,
    onFailedClick: (EveChatMessage) -> Unit,
    modifier: Modifier = Modifier
) {
    if (message.isSystem) {
        Box(modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        return
    }

    val outgoing = message.isOutgoing
    val failed = message.sendState == SendState.FAILED
    val scheme = MaterialTheme.colorScheme
    val background = when {
        message.isInternal -> NoteBackground
        outgoing -> scheme.primary
        else -> scheme.surfaceVariant
    }
    val textColor = when {
        message.isInternal -> NoteText
        outgoing -> scheme.onPrimary
        else -> scheme.onSurfaceVariant
    }
    val shape = if (outgoing) RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
    else RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
    val maxWidth = (LocalConfiguration.current.screenWidthDp * 0.78f).dp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .clip(shape)
                .background(background)
                .then(if (failed) Modifier.clickable { onFailedClick(message) } else Modifier)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (message.isInternal) {
                Text("یادداشت داخلی", style = MaterialTheme.typography.labelSmall, color = NoteLabel)
            }
            if (showSender && message.senderName != null) {
                Text(
                    text = message.senderName,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (outgoing) textColor.copy(alpha = 0.8f) else scheme.primary
                )
            }
            Text(text = message.content, style = MaterialTheme.typography.bodyMedium, color = textColor)
            message.attachments.forEach {
                Text(
                    text = "📎 " + it.substringAfterLast('/'),
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor.copy(alpha = 0.85f)
                )
            }
            Row(
                modifier = Modifier.align(Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (failed) "ارسال نشد — برای تلاش دوباره بزنید" else formatMessageTime(message.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (failed) scheme.error else textColor.copy(alpha = 0.7f)
                )
                if (outgoing) {
                    Spacer(Modifier.width(3.dp))
                    val (icon, tint, label) = when (message.sendState) {
                        SendState.PENDING -> Triple(Icons.Default.Schedule, textColor.copy(alpha = 0.6f), "در حال ارسال")
                        SendState.SENT -> Triple(Icons.Default.Check, textColor.copy(alpha = 0.8f), "ارسال شد")
                        SendState.FAILED -> Triple(Icons.Default.ErrorOutline, scheme.error, "ارسال نشد")
                    }
                    Icon(icon, contentDescription = label, modifier = Modifier.size(14.dp), tint = tint)
                }
            }
        }
    }
}
