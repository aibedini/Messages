package com.autonomousone.messages.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.util.Patterns
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
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.autonomousone.messages.messaging.IphoneReactionParser
import com.autonomousone.messages.messaging.MessagingPreferences
import androidx.compose.ui.res.stringResource
import com.autonomousone.messages.R
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.ui.theme.FailedTint
import com.autonomousone.messages.utils.formatFullTimestamp
import com.autonomousone.messages.utils.formatMessageTime

/** Status icon + accessibility label for outgoing bubbles. */
private data class StatusVisual(val icon: ImageVector, val label: String)

/** A tappable entity (link or phone number) inside a message body. */
internal data class MessageEntity(val text: String, val start: Int, val end: Int, val isUrl: Boolean)

/** Finds URLs and phone numbers with their ranges; URLs take precedence. */
internal fun findMessageEntities(text: String): List<MessageEntity> {
    val entities = mutableListOf<MessageEntity>()
    val taken = mutableListOf<IntRange>()

    fun overlaps(range: IntRange) = taken.any { range.start <= it.endInclusive && range.endInclusive >= it.start }

    // Persian/Arabic digits map 1:1 to ASCII (DigitNormalizer), so indexes in
    // the mirrored string are IDENTICAL to the original text's. We run the
    // platform detectors on the mirror so Patterns.PHONE can see ۰۹۱۲… numbers,
    // then slice the ORIGINAL text with the same ranges (keeping Persian digits
    // for display and dialing).
    val asciiMirror = com.autonomousone.messages.utils.DigitNormalizer.toAsciiDigits(text)

    Patterns.WEB_URL.matcher(asciiMirror).let { m ->
        while (m.find()) {
            val range = m.start() until m.end()
            if (!overlaps(range)) {
                entities.add(MessageEntity(m.group(), m.start(), m.end(), isUrl = true))
                taken.add(range)
            }
        }
    }
    Patterns.PHONE.matcher(asciiMirror).let { m ->
        while (m.find()) {
            val candidateAscii = asciiMirror.substring(m.start(), m.end())
            val digits = candidateAscii.filter { it.isDigit() }
            // Skip year-like fragments and too-short groups.
            if (digits.length in 7..15) {
                val range = m.start() until m.end()
                if (!overlaps(range)) {
                    // Show/copy the number exactly as the sender wrote it.
                    val asWritten = text.substring(range)
                    entities.add(MessageEntity(asWritten, m.start(), m.end(), isUrl = false))
                    taken.add(range)
                }
            }
        }
    }
    return entities.sortedBy { it.start }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatBubble(
    sms: Sms,
    modifier: Modifier = Modifier,
    onForward: ((String) -> Unit)? = null,
    onPhoneClick: ((String) -> Unit)? = null
) {
    val incoming = sms.type == 1

    val context = LocalContext.current
    // iPhone tapback → emoji, only when the user enabled it in Settings > Messaging.
    val showReactionsAsEmoji = remember { MessagingPreferences(context).showIphoneReactionsAsEmoji }
    val reaction = if (incoming && showReactionsAsEmoji) {
        IphoneReactionParser.parse(sms.message)
    } else null

    // Long-press opens the action menu (copy / forward / link / details).
    var menuOpen by remember(sms.id) { mutableStateOf(false) }
    var showDetails by remember(sms.id) { mutableStateOf(false) }

    fun copyToClipboard(label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
    }

    // Parse image tag if present [IMAGE:uri]
    val imageUriString = if (sms.message.contains("[IMAGE:")) {
        sms.message.substringAfter("[IMAGE:").substringBefore("]")
    } else null

    val captionText = if (imageUriString != null) {
        sms.message.substringAfter("]", "").trim()
    } else {
        sms.message
    }

    val entities = remember(captionText) { findMessageEntities(captionText) }

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
    val linkColor = if (incoming) {
        MaterialTheme.colorScheme.primary
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
                            onLongClick = { menuOpen = true }
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
                            val bodyText = remember(captionText, linkColor) {
                                buildAnnotatedString {
                                    var cursor = 0
                                    entities.forEach { e ->
                                        append(captionText.substring(cursor, e.start))
                                        val entity = e
                                        if (entity.isUrl) {
                                            withLink(
                                                LinkAnnotation.Clickable(entity.text) {
                                                    runCatching {
                                                        context.startActivity(
                                                            Intent(Intent.ACTION_VIEW, Uri.parse(entity.text))
                                                        )
                                                    }
                                                }
                                            ) {
                                                withStyle(
                                                    SpanStyle(
                                                        color = linkColor,
                                                        textDecoration = TextDecoration.Underline
                                                    )
                                                ) { append(entity.text) }
                                            }
                                        } else {
                                            withLink(
                                                LinkAnnotation.Clickable(entity.text) {
                                                    onPhoneClick?.invoke(entity.text)
                                                }
                                            ) {
                                                withStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold)) {
                                                    append(entity.text)
                                                }
                                            }
                                        }
                                        cursor = e.end
                                    }
                                    append(captionText.substring(cursor))
                                }
                            }
                            Text(
                                text = bodyText,
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

                    // Long-press action menu
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_copy)) },
                            onClick = {
                                menuOpen = false
                                copyToClipboard("message", sms.message)
                            }
                        )
                        entities.firstOrNull()?.let { e ->
                            DropdownMenuItem(
                                text = { Text(if (e.isUrl) stringResource(R.string.conv_copy_link) else stringResource(R.string.conv_copy_number)) },
                                onClick = {
                                    menuOpen = false
                                    copyToClipboard(if (e.isUrl) "link" else "number", e.text)
                                }
                            )
                        }
                        if (onForward != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_forward)) },
                                onClick = {
                                    menuOpen = false
                                    onForward(sms.message)
                                }
                            )
                        }
                        if (!incoming) {
                            DropdownMenuItem(
                                text = { Text(if (showDetails) stringResource(R.string.conv_menu_hide_details) else stringResource(R.string.conv_menu_message_details)) },
                                onClick = {
                                    menuOpen = false
                                    showDetails = !showDetails
                                }
                            )
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
