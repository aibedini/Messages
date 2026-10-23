package com.autonomousone.messages.evechat.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autonomousone.messages.R
import com.autonomousone.messages.evechat.model.EveChatConversation
import com.autonomousone.messages.evechat.model.EveChatSubscription
import com.autonomousone.messages.evechat.model.EveChatVisitor
import com.autonomousone.messages.evechat.model.EveChatVisitorSnapshot
import com.autonomousone.messages.utils.formatFullTimestamp

/**
 * Everything the server knows about the visitor, in the same order the dashboard presents it.
 *
 * The numbers and labels come from `GET /api/v1/conversations/{id}` → `visitor`; a field the
 * server did not send is omitted rather than rendered as a dash, so an empty sheet is honest
 * about what the server actually returned.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EveChatVisitorSheet(
    profile: EveChatVisitorSnapshot?,
    conversation: EveChatConversation?,
    syncing: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val visitor = profile?.visitor
    val visitorLabel = stringResource(R.string.eve_visitor)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp)
        ) {
            item {
                SheetHeader(
                    visitor = visitor,
                    fallbackName = visitorLabel,
                    conversationId = conversation?.id,
                    syncing = syncing,
                    onRefresh = onRefresh
                )
            }

            if (visitor == null) {
                item {
                    Text(
                        text = stringResource(R.string.eve_profile_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            } else {
                item {
                    Section(stringResource(R.string.eve_profile_identity)) {
                        InfoRow(stringResource(R.string.eve_field_name), visitor.name)
                        InfoRow(stringResource(R.string.eve_field_mobile), visitor.mobile)
                        InfoRow(stringResource(R.string.eve_field_email), visitor.email)
                        InfoRow(stringResource(R.string.eve_field_user_id), visitor.eveUserId)
                        InfoRow(stringResource(R.string.eve_field_reseller), visitor.eveResellerId)
                    }
                }

                item {
                    Section(stringResource(R.string.eve_profile_subscription)) {
                        SubscriptionRows(visitor.subscription)
                    }
                }

                item {
                    Section(stringResource(R.string.eve_profile_device)) {
                        InfoRow(stringResource(R.string.eve_field_device), visitor.deviceType)
                        InfoRow(stringResource(R.string.eve_field_os), visitor.os)
                        InfoRow(stringResource(R.string.eve_field_browser), visitor.browser)
                        InfoRow(stringResource(R.string.eve_field_language), visitor.language)
                    }
                }

                item {
                    Section(stringResource(R.string.eve_profile_network)) {
                        InfoRow(stringResource(R.string.eve_field_ip), visitor.ip)
                        InfoRow(stringResource(R.string.eve_field_country), visitor.country)
                    }
                }

                item {
                    Section(stringResource(R.string.eve_profile_page)) {
                        InfoRow(stringResource(R.string.eve_field_page_title), visitor.pageTitle)
                        InfoRow(stringResource(R.string.eve_field_referrer), visitor.referrer)
                        InfoRow(stringResource(R.string.eve_profile_page), visitor.pageUrl)
                    }
                }

                item {
                    Section(stringResource(R.string.eve_profile_activity)) {
                        InfoRow(
                            stringResource(R.string.eve_field_first_seen),
                            visitor.firstSeenAt?.let { formatFullTimestamp(it) }
                        )
                        InfoRow(
                            stringResource(R.string.eve_field_last_seen),
                            visitor.lastSeenAt?.let { formatFullTimestamp(it) }
                        )
                    }
                }

                if (visitor.metadata.isNotEmpty()) {
                    item {
                        Section(stringResource(R.string.eve_profile_extra)) {
                            visitor.metadata.forEach { (key, value) -> InfoRow(key, value) }
                        }
                    }
                }

                item {
                    Section(stringResource(R.string.eve_profile_reference)) {
                        InfoRow(stringResource(R.string.eve_field_visitor_id), visitor.id)
                        InfoRow(stringResource(R.string.eve_field_conversation_id), conversation?.id)
                        InfoRow(stringResource(R.string.eve_field_channel), visitor.channel)
                        InfoRow(stringResource(R.string.eve_field_agent), conversation?.assignedAgentName)
                    }
                }
            }

            profile?.let { snapshot ->
                item {
                    Text(
                        text = stringResource(
                            R.string.eve_profile_updated_fmt,
                            formatFullTimestamp(snapshot.fetchedAt)
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(
    visitor: EveChatVisitor?,
    fallbackName: String,
    conversationId: String?,
    syncing: Boolean,
    onRefresh: () -> Unit
) {
    val name = visitor?.displayName?.takeIf { it.isNotBlank() } ?: fallbackName
    Row(
        modifier = Modifier.padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        VisitorAvatar(name = name, size = 48)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (visitor?.isBanned == true) {
                    Spacer(Modifier.width(8.dp))
                    Pill(stringResource(R.string.eve_banned))
                }
            }
            val meta = listOfNotNull(
                visitor?.id?.takeIf { it.isNotBlank() }?.let { it.takeLast(8) },
                visitor?.channel?.takeIf { it.isNotBlank() },
                conversationId?.takeIf { it.isNotBlank() }?.let { it.takeLast(6) }
            ).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (syncing) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.eve_profile_refresh))
            }
        }
    }
}

@Composable
private fun SubscriptionRows(subscription: EveChatSubscription?) {
    if (subscription == null) {
        InfoRow(
            stringResource(R.string.eve_field_plan),
            stringResource(R.string.eve_no_subscription)
        )
        return
    }
    InfoRow(
        stringResource(R.string.eve_field_plan),
        subscription.plan ?: stringResource(R.string.eve_no_subscription)
    )
    InfoRow(
        stringResource(R.string.eve_field_expiry),
        subscription.expiryAt?.let { formatFullTimestamp(it) } ?: subscription.expiry
    )
    val days = subscription.daysLeft()
    if (days != null) {
        InfoRow(
            stringResource(R.string.eve_field_days_left),
            if (days < 0) stringResource(R.string.eve_expired) else stringResource(R.string.eve_days_left_fmt, days)
        )
    }
    subscription.extra.forEach { (key, value) -> InfoRow(key, value) }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), content = content)
        }
    }
}

/** A label/value pair; long-press copies the value, which is how agents move an ID or IP around. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InfoRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val copied = stringResource(R.string.eve_copied)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = { },
                onLongClick = {
                    clipboard.setText(AnnotatedString(value))
                    Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                }
            )
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(118.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun Pill(text: String) {
    Box(
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.errorContainer,
                RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}
