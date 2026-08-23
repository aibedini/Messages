package com.autonomousone.messages.ui.components

import android.net.Uri
import android.provider.Telephony
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.autonomousone.messages.messaging.IphoneReactionParser
import com.autonomousone.messages.messaging.MessagingPreferences
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.ui.theme.FailedTint
import com.autonomousone.messages.utils.formatFullTimestamp
import com.autonomousone.messages.utils.formatMessageTime

/** Status icon + accessibility label for outgoing bubbles. */
private data class StatusVisual(val icon: ImageVector, val label: String)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatBubble(
    sms: Sms,
    modifier: Modifier = Modifier
) {
    val incoming = sms.type == 1

    val context = LocalContext.current
    // iPhone tapback → emoji, only when the user enabled it in Settings > Messaging.
    val showReactionsAsEmoji = remember { MessagingPreferences(context).showIphoneReactionsAsEmoji }
    val reaction = if (incoming && showReactionsAsEmoji) {
        IphoneReactionParser.parse(sms.message)
    } else null

    // Long-press reveals sent/delivered details (Google Messages-style info line).
    var showDetails by remember(sms.id) { mutableStateOf(false) }

    // Parse image tag if present [IMAGE:uri]
    val imageUriString = if (sms.message.contains("[IMAGE:")) {
        sms.message.substringAfter("[IMAGE:").substringBefore("]")
    } else null

    val captionText = if (imageUriString != null) {
        sms.message.substringAfter("]", "").trim()
    } else {
        sms.message
    }

    // Asymmetrical rounded corner shape with bubble tail indicator
    val bubbleShape = if (incoming) {
        RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomEnd = 24.dp, bottomStart = 6.dp)
    } else {
        RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 6.dp)
    }

    // Flat Google Messages-style surfaces — no gradients.
    val bubbleColor = if (incoming) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = if (incoming) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    AnimatedVisibility(
        visible = true,
        enter = fadeIn(animationSpec = tween(300)) + scaleIn(initialScale = 0.92f, animationSpec = tween(300))
    ) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
            horizontalArrangement = if (incoming) Arrangement.Start else Arrangement.End
        ) {
            Column(horizontalAlignment = if (incoming) Alignment.Start else Alignment.End) {
                Box(
                    modifier = Modifier
                        .widthIn(min = 80.dp, max = 300.dp)
                        .clip(bubbleShape)
                        .background(bubbleColor)
                        .combinedClickable(
                            onClick = { if (!incoming) showDetails = !showDetails },
                            onLongClick = { showDetails = !showDetails }
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Column {
                        if (imageUriString != null) {
                            AsyncImage(
                                model = Uri.parse(imageUriString),
                                contentDescription = "Attached Image",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                                    .clip(RoundedCornerShape(16.dp))
                            )
                            if (captionText.isNotBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                        }

                        if (reaction != null) {
                            Text(
                                text = reaction.emoji,
                                fontSize = 30.sp,
                                lineHeight = 34.sp
                            )
                            reaction.quotedText?.let { quoted ->
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = "\u201C${quoted.take(80)}\u201D",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontStyle = FontStyle.Italic,
                                    color = contentColor.copy(alpha = 0.85f)
                                )
                            }
                        } else if (captionText.isNotBlank()) {
                            Text(
                                text = captionText,
                                color = contentColor,
                                style = MaterialTheme.typography.bodyLarge,
                                fontSize = 15.sp,
                                lineHeight = 21.sp
                            )
                        }

                        Row(
                            modifier = Modifier
                                .align(Alignment.End)
                                .padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = formatMessageTime(sms.date),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Normal,
                                color = contentColor.copy(alpha = 0.65f)
                            )

                            if (!incoming) {
                                Spacer(modifier = Modifier.width(4.dp))
                                val visual = statusVisualFor(sms.status)
                                Icon(
                                    imageVector = visual.icon,
                                    contentDescription = visual.label,
                                    tint = if (sms.status == Telephony.Sms.STATUS_FAILED) {
                                        FailedTint
                                    } else {
                                        contentColor.copy(alpha = 0.8f)
                                    },
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }

                // Detail line: when it was sent and (when reported) delivered.
                AnimatedVisibility(visible = showDetails && !incoming) {
                    val detail = buildString {
                        append("Sent ")
                        append(formatFullTimestamp(sms.date))
                        when {
                            sms.status == Telephony.Sms.STATUS_FAILED ->
                                append(" · Not delivered")
                            sms.dateSent > 0 -> {
                                append(" · Delivered ")
                                append(formatFullTimestamp(sms.dateSent))
                            }
                            sms.status == Telephony.Sms.STATUS_PENDING ->
                                append(" · Sending…")
                        }
                    }
                    Text(
                        text = detail,
                        fontSize = 11.sp,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

private fun statusVisualFor(status: Int): StatusVisual = when (status) {
    Telephony.Sms.STATUS_FAILED -> StatusVisual(Icons.Default.Error, "Failed")
    Telephony.Sms.STATUS_COMPLETE -> StatusVisual(Icons.Default.DoneAll, "Delivered")
    Telephony.Sms.STATUS_PENDING -> StatusVisual(Icons.Default.Schedule, "Sending")
    else -> StatusVisual(Icons.Default.Check, "Sent")
}
