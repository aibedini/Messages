package com.autonomousone.messages.evechat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AssignmentInd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autonomousone.messages.R
import com.autonomousone.messages.evechat.EveChatConversationViewModel
import com.autonomousone.messages.evechat.EveChatSession
import com.autonomousone.messages.evechat.messageRes
import com.autonomousone.messages.evechat.model.ConvStatus
import com.autonomousone.messages.evechat.model.EveChatMessage
import com.autonomousone.messages.evechat.model.EveChatVisitor
import com.autonomousone.messages.utils.formatDateHeader
import java.util.Calendar

private sealed interface ChatRow {
    val key: String

    data class Bubble(val message: EveChatMessage, val showSender: Boolean) : ChatRow {
        override val key get() = message.id
    }

    data class Day(val at: Long) : ChatRow {
        override val key get() = "day-$at"
    }
}

/** Newest first, for a reverse-layout list: day headers sit above (after) each day's messages. */
private fun buildRows(messages: List<EveChatMessage>): List<ChatRow> {
    val rows = ArrayList<ChatRow>(messages.size + 8)
    val cal = Calendar.getInstance()
    fun dayOf(t: Long): Long {
        cal.timeInMillis = t
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
    for (i in messages.indices.reversed()) {
        val m = messages[i]
        val prev = messages.getOrNull(i - 1)
        val showSender = !m.isSystem && (prev == null || prev.senderType != m.senderType || prev.senderId != m.senderId)
        rows += ChatRow.Bubble(m, showSender)
        val day = dayOf(m.createdAt)
        if (prev == null || dayOf(prev.createdAt) != day) rows += ChatRow.Day(day)
    }
    return rows
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EveChatConversationScreen(
    viewModel: EveChatConversationViewModel,
    onBack: () -> Unit
) {
    val conversation by viewModel.conversation.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val typing by viewModel.visitorTyping.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val loadingOlder by viewModel.loadingOlder.collectAsStateWithLifecycle()
    val busy by viewModel.busyAction.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val myAgentId by viewModel.myAgentId.collectAsStateWithLifecycle()
    val rows = remember(messages) { buildRows(messages) }
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    var input by rememberSaveable { mutableStateOf("") }
    var failedPrompt by remember { mutableStateOf<EveChatMessage?>(null) }
    var showProfile by rememberSaveable { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.onVisible()
                Lifecycle.Event.ON_PAUSE -> viewModel.onHidden()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) viewModel.onVisible()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onHidden()
        }
    }

    LaunchedEffect(Unit) { viewModel.events.collect { snackbar.showSnackbar(it) } }

    // Follow the conversation only when the user is already at the newest message.
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }
    LaunchedEffect(messages.lastOrNull()?.id) {
        if (atBottom || messages.lastOrNull()?.isOutgoing == true) listState.animateScrollToItem(0)
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to listState.layoutInfo.totalItemsCount }
            .collect { (last, total) -> if (last != null && total > 0 && last >= total - 3) viewModel.loadOlder() }
    }

    val status = conversation?.status
    val open = status != null && ConvStatus.isOpen(status)
    val assignedToOther = conversation?.assignedAgentId != null && conversation?.assignedAgentId != myAgentId

    // Who am I talking to? The visitor's own name when the server sent one, otherwise an
    // explicit "Visitor · 4f2a" so two anonymous chats are still distinguishable in the header.
    val visitorLabel = stringResource(R.string.eve_visitor)
    val namedVisitor = conversation?.displayName?.takeIf { it.isNotBlank() }
        ?: profile?.visitor?.displayName?.takeIf { it.isNotBlank() }
    val title = namedVisitor ?: run {
        val suffix = conversation?.visitorId?.takeLast(4)?.takeIf { it.isNotBlank() }
        if (suffix != null) "$visitorLabel · $suffix" else visitorLabel
    }
    val subtitle = when {
        typing -> stringResource(R.string.eve_typing)
        connection != EveChatSession.Connection.ONLINE -> stringResource(connection.messageRes())
        else -> statusText(status, conversation?.assignedAgentName, assignedToOther)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        VisitorAvatar(name = title)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (typing) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.eve_back)
                        )
                    }
                },
                actions = {
                    if (busy != null) {
                        CircularProgressIndicator(Modifier.size(20.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                    }
                    IconButton(onClick = { showProfile = true }) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = stringResource(R.string.eve_profile_open)
                        )
                    }
                    if (status == ConvStatus.PENDING && conversation?.assignedAgentId == null) {
                        IconButton(onClick = viewModel::claim, enabled = busy == null) {
                            Icon(
                                Icons.Default.AssignmentInd,
                                contentDescription = stringResource(R.string.eve_claim)
                            )
                        }
                    }
                    if (open) {
                        IconButton(onClick = viewModel::resolve, enabled = busy == null) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = stringResource(R.string.eve_resolve)
                            )
                        }
                    } else if (status != null) {
                        IconButton(onClick = viewModel::reopen, enabled = busy == null) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.eve_reopen)
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            if (syncing && messages.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
            // The profile the agent needs while answering, without leaving the conversation:
            // plan + days left, device, network. Tapping opens the full sheet.
            profile?.visitor?.takeIf { it.hasAnything }?.let { visitor ->
                VisitorStrip(visitor = visitor, onClick = { showProfile = true })
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (messages.isEmpty() && syncing) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else {
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(rows, key = { it.key }, contentType = { it::class }) { row ->
                            when (row) {
                                is ChatRow.Bubble -> EveChatBubble(
                                    message = row.message,
                                    showSender = row.showSender,
                                    onFailedClick = { failedPrompt = it },
                                    modifier = Modifier.animateItem()
                                )
                                is ChatRow.Day -> DayHeader(row.at)
                            }
                        }
                        if (loadingOlder) {
                            item(key = "older") {
                                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                }
                            }
                        }
                    }
                }
            }

            if (status != null && !open) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.eve_closed_banner),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = viewModel::reopen, enabled = busy == null) {
                            Text(stringResource(R.string.eve_reopen))
                        }
                    }
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = {
                            input = it
                            viewModel.onInputChanged(it)
                        },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                stringResource(
                                    if (assignedToOther) R.string.eve_input_locked else R.string.eve_input_hint
                                )
                            )
                        },
                        maxLines = 5,
                        shape = RoundedCornerShape(24.dp)
                    )
                    IconButton(
                        onClick = {
                            viewModel.send(input)
                            input = ""
                        },
                        enabled = input.isNotBlank()
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.eve_send),
                            tint = if (input.isNotBlank()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showProfile) {
        EveChatVisitorSheet(
            profile = profile,
            conversation = conversation,
            syncing = syncing,
            onRefresh = viewModel::refreshProfile,
            onDismiss = { showProfile = false }
        )
    }

    failedPrompt?.let { msg ->
        AlertDialog(
            onDismissRequest = { failedPrompt = null },
            title = { Text(stringResource(R.string.eve_send_failed_title)) },
            text = {
                Text(msg.lastError ?: stringResource(R.string.eve_send_failed_body))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.retry(msg)
                    failedPrompt = null
                }) { Text(stringResource(R.string.action_retry)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.discard(msg)
                    failedPrompt = null
                }) { Text(stringResource(R.string.action_delete)) }
            }
        )
    }
}

/**
 * One line of context above the messages: what the customer pays for and where they are writing
 * from. Only fields the server actually sent are rendered.
 */
@Composable
private fun VisitorStrip(visitor: EveChatVisitor, onClick: () -> Unit) {
    val subscriptionText: String = run {
        val sub = visitor.subscription
        val days = sub?.daysLeft()
        when {
            sub == null -> stringResource(R.string.eve_no_subscription)
            days == null -> sub.plan.orEmpty()
            days < 0 -> listOfNotNull(sub.plan, stringResource(R.string.eve_expired)).joinToString(" · ")
            else -> listOfNotNull(sub.plan, stringResource(R.string.eve_days_left_fmt, days))
                .joinToString(" · ")
        }
    }
    val deviceText = listOfNotNull(visitor.os, visitor.browser).joinToString(" · ")
        .ifBlank { visitor.deviceType }
    val networkText = visitor.ip ?: visitor.country
    val chips = listOfNotNull(
        subscriptionText.takeIf { it.isNotBlank() },
        deviceText?.takeIf { it.isNotBlank() },
        networkText?.takeIf { it.isNotBlank() }
    )
    if (chips.isEmpty()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        chips.forEach { text ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
}

@Composable
internal fun VisitorAvatar(name: String, size: Int = 36) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun statusText(status: String?, agentName: String?, assignedToOther: Boolean): String = when {
    status == null -> ""
    status == ConvStatus.PENDING -> stringResource(R.string.eve_status_waiting)
    status == ConvStatus.RESOLVED -> stringResource(R.string.eve_status_resolved)
    status == ConvStatus.CLOSED -> stringResource(R.string.eve_status_closed)
    assignedToOther -> stringResource(
        R.string.eve_status_assigned_fmt,
        agentName ?: stringResource(R.string.eve_unknown_agent)
    )
    else -> stringResource(R.string.eve_status_active)
}

@Composable
private fun DayHeader(at: Long) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)) {
            Text(
                text = formatDateHeader(at),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}
