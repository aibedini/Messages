package com.autonomousone.messages.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.autonomousone.messages.R
import com.autonomousone.messages.navigation.Screen
import com.autonomousone.messages.ui.components.AppSearchBar
import com.autonomousone.messages.ui.components.EmptyView
import com.autonomousone.messages.ui.components.MainTopBar
import com.autonomousone.messages.ui.components.SmsItem
import com.autonomousone.messages.viewmodel.HomeViewModel
import kotlinx.coroutines.launch
import java.util.Calendar

enum class ConversationFilter(val labelRes: Int) {
    All(R.string.home_tab_all),
    Unread(R.string.home_tab_unread),
    Archived(R.string.home_tab_archived)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    hasPermission: Boolean,
    isDefaultSmsApp: Boolean,
    onRequestDefaultApp: () -> Unit,
    onRequestPermissions: () -> Unit,
    navController: NavController
) {
    val viewModel: HomeViewModel = viewModel()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var search by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(ConversationFilter.All) }

    // Global (all-messages) search: debounce 400 ms after typing stops.
    LaunchedEffect(search) {
        if (search.trim().length >= 2 && selectedFilter == ConversationFilter.All) {
            kotlinx.coroutines.delay(400)
            viewModel.searchAllMessages(search)
        } else {
            viewModel.clearGlobalSearch()
        }
    }

    val listState = rememberLazyListState()
    val isExpanded by remember {
        derivedStateOf { listState.firstVisibleItemIndex == 0 }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            viewModel.loadSms()
        }
    }

    val smsList = viewModel.conversations
    val archivedList = viewModel.archivedConversations

    // Live drafts (shared StateFlow) — the list updates the moment a chat
    // screen saves a draft, no refresh signal needed.
    val draftMap by viewModel.drafts.collectAsState()

    val greeting = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greetingText = when (greeting) {
        in 4..11 -> stringResource(R.string.home_greeting_morning)
        in 12..16 -> stringResource(R.string.home_greeting_afternoon)
        in 17..22 -> stringResource(R.string.home_greeting_evening)
        else -> stringResource(R.string.home_greeting_night)
    }

    // The base list to filter from depends on the selected tab
    val sourceList by remember(selectedFilter) {
        derivedStateOf {
            if (selectedFilter == ConversationFilter.Archived) archivedList else smsList
        }
    }

    val filteredList by remember(search, selectedFilter, smsList, archivedList) {
        derivedStateOf {
            sourceList.filter { sms ->
                val searchMatch = matchesSearch(sms, search, viewModel.contactNames)
                val filterMatch = when (selectedFilter) {
                    ConversationFilter.All -> true
                    ConversationFilter.Unread -> sms.unread
                    ConversationFilter.Archived -> true   // archivedList is already filtered
                }
                searchMatch && filterMatch
            }
        }
    }

    // Best-practice search UX:
    //  - number-like queries get a direct "Send to …" row (Google Messages style)
    //  - a results counter shows how many conversations matched
    val searchLooksLikeNumber = remember(search) {
        val digits = search.filter { it.isDigit() }
        search.isNotBlank() && digits.length >= 3 && digits.length >= search.length - 2
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    shape = RoundedCornerShape(12.dp),
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    actionColor = MaterialTheme.colorScheme.inversePrimary
                )
            }
        },
        topBar = {
            MainTopBar(
                title = stringResource(R.string.app_name),
                onProfileClick = {},
                onSearchClick = null,
                onMarkAllReadClick = { viewModel.markAllAsRead() },
                onGatewayClick = { navController.navigate(Screen.Gateway.route) },
                onSettingsClick = { navController.navigate(Screen.Settings.route) }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    navController.navigate(Screen.NewConversation.route)
                },
                expanded = isExpanded,
                icon = {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "New Conversation"
                    )
                },
                text = {
                    Text(
                        text = "Start chat",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(20.dp)
            )
        }
    ) { padding ->
        if (!hasPermission) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                EmptyView(
                    title = "SMS Permission Required",
                    subtitle = "Messages requires permission to access your SMS and contacts to display your conversations.",
                    icon = Icons.Default.MarkEmailUnread,
                    buttonText = "Grant Permissions",
                    onButtonClick = onRequestPermissions
                )
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Default SMS app banner
            AnimatedVisibility(
                visible = !isDefaultSmsApp,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                DefaultSmsAppBanner(onSetDefault = onRequestDefaultApp)
            }

            // Greeting sub-header
            Text(
                text = greetingText,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )

            AppSearchBar(
                query = search,
                onQueryChange = { search = it },
                placeholderText = stringResource(R.string.home_search_hint)
            )

            // Filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ConversationFilter.values().forEach { filter ->
                                    FilterChip(
                                        selected = selectedFilter == filter,
                                        onClick = { selectedFilter = filter },
                                        label = {
                                            Text(
                                                text = stringResource(filter.labelRes),
                                                fontSize = 13.sp,
                                                fontWeight = if (selectedFilter == filter) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                        leadingIcon = if (filter == ConversationFilter.Archived) {
                            {
                                Icon(
                                    imageVector = Icons.Default.Archive,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else null,
                        shape = RoundedCornerShape(16.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (viewModel.isLoading && filteredList.isNotEmpty()) {
                SyncBanner(
                    progress = viewModel.syncProgress,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            val isInArchivedView = selectedFilter == ConversationFilter.Archived

            // Skeleton ONLY when there is no cache to show at all (cold start).
            if (viewModel.isLoading && smsList.isEmpty() && archivedList.isEmpty()) {
                ConversationListSkeleton(
                    status = viewModel.loadStatus,
                    modifier = Modifier.weight(1f)
                )
            } else if (filteredList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyView(
                        title = when {
                            search.isNotBlank() -> stringResource(R.string.home_search_no_results)
                            isInArchivedView -> stringResource(R.string.home_empty_archived_title)
                            else -> stringResource(R.string.home_empty_title)
                        },
                        subtitle = when {
                            search.isNotBlank() -> stringResource(R.string.home_search_no_results_fmt, search)
                            isInArchivedView -> stringResource(R.string.home_empty_archived_subtitle)
                            else -> stringResource(R.string.home_empty_subtitle)
                        },
                        icon = when {
                            search.isNotBlank() -> Icons.Default.SearchOff
                            isInArchivedView -> Icons.Default.Archive
                            else -> Icons.Default.MarkEmailUnread
                        },
                        buttonText = if (isInArchivedView || search.isNotBlank()) null else stringResource(R.string.conv_start_title),
                        onButtonClick = if (isInArchivedView || search.isNotBlank()) null else {
                            { navController.navigate(Screen.NewConversation.route) }
                        }
                    )
                }
            } else {
                // ── Pull-to-refresh on the Home list: silent provider
                // reconcile (same path as resume), no clearing of the list.
                androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                    isRefreshing = viewModel.isRefreshing,
                    onRefresh = { viewModel.refreshNow() },
                    modifier = Modifier.weight(1f)
                ) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)
                ) {
                    // Direct "send to number" shortcut while searching (Google Messages style)
                    if (searchLooksLikeNumber) {
                        item(key = "direct_send") {
                            val directNumber = search.trim()
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                    .clickable {
                                        navController.navigate(
                                            Screen.Conversation.createNewRoute(
                                                phone = directNumber,
                                                name = directNumber
                                            )
                                        )
                                    },
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Send,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = stringResource(R.string.home_search_send_to, directNumber),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                    }

                    // Result counter while searching
                    if (search.isNotBlank()) {
                        item(key = "result_count") {
                            Text(
                                text = "${filteredList.size} conversation${if (filteredList.size == 1) "" else "s"} found",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                            )
                        }
                    }

                    // Global search results: messages deep inside conversations
                    // that don't match by name/snippet (Google Messages-style
                    // "search inside all messages").
                    if (viewModel.globalResults.isNotEmpty()) {
                        item(key = "global_header") {
                            Text(
                                text = stringResource(R.string.home_search_global_header),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                            )
                        }
                        items(
                            items = viewModel.globalResults,
                            key = { "global_${it.sms.id}" }
                        ) { hit ->
                            SmsItem(
                                sms = hit.sms.copy(
                                    message = "🔎 ${hit.sms.message.take(80)}"
                                ),
                                onClick = {
                                    navController.navigate(
                                        Screen.Conversation.createRoute(hit.sms.threadId, hit.sms.sender)
                                    )
                                }
                            )
                        }
                    }

                    items(
                        items = filteredList,
                        key = { "c${it.id}" },
                        contentType = { "conversation" }
                    ) { sms ->
                        val draftKey = com.autonomousone.messages.repository.DraftRepository
                            .keyFor(sms.threadId, sms.sender)
                        // Pre-resolve strings for callbacks (lambdas aren't composable).
                        val blockedMsg = stringResource(R.string.home_snackbar_blocked)
                        val archivedMsg = stringResource(R.string.home_snackbar_archived)
                        val unarchivedMsg = stringResource(R.string.home_snackbar_unarchived)
                        val deletedMsg = stringResource(R.string.home_snackbar_deleted)
                        val undoLabel = stringResource(R.string.action_undo)
                        SmsItem(
                            sms = sms,
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(durationMillis = 220),
                                placementSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessLow
                                ),
                                fadeOutSpec = tween(durationMillis = 160)
                            ),
                            isPinned = sms.threadId in viewModel.pinnedIds,
                            isArchived = isInArchivedView,
                            draftText = draftMap[draftKey].orEmpty(),
                            // Tiny "you" under the date: this conversation's
                            // latest message was sent by the user (type 2).
                            showYouMarker = sms.type == 2,
                            onClick = {
                                navController.navigate(
                                    Screen.Conversation.createRoute(sms.threadId, sms.sender)
                                )
                            },
                            onPin = { viewModel.togglePin(sms) },
                            onBlock = {
                                viewModel.blockConversation(sms)
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        message = blockedMsg,
                                        duration = SnackbarDuration.Short
                                    )
                                }
                            },
                            onArchive = {
                                if (isInArchivedView) {
                                    // Unarchive (Undo → re-archive)
                                    viewModel.unarchiveConversation(sms)
                                    scope.launch {
                                        val result = snackbarHostState.showSnackbar(
                                            message = unarchivedMsg,
                                            actionLabel = undoLabel,
                                            duration = SnackbarDuration.Short
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            viewModel.archiveConversation(sms)
                                        }
                                    }
                                } else {
                                    // Archive (Undo → unarchive)
                                    viewModel.archiveConversation(sms)
                                    scope.launch {
                                        val result = snackbarHostState.showSnackbar(
                                            message = archivedMsg,
                                            actionLabel = undoLabel,
                                            duration = SnackbarDuration.Long
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            viewModel.unarchiveConversation(sms)
                                        }
                                    }
                                }
                            },
                            onDelete = {
                                viewModel.deleteConversation(sms)
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = deletedMsg,
                                        actionLabel = undoLabel,
                                        duration = SnackbarDuration.Long   // ~4 s
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        viewModel.undoDelete(sms)
                                    }
                                }
                            }
                        )
                    }
                }
                } // PullToRefreshBox
            }
        }
    }
}

/**
 * Best-practice SMS search: matches contact display name, raw sender,
 * normalized digits (so "0912" finds "+98912…") and the message snippet.
 */
private fun matchesSearch(sms: com.autonomousone.messages.model.Sms, query: String, contactNames: Map<String, String>): Boolean {
    if (query.isBlank()) return true
    val q = query.trim()

    val displayName = contactNames[com.autonomousone.messages.repository.ContactRepository.normalizePhone(sms.sender)] ?: ""
    val nameMatch = displayName.contains(q, ignoreCase = true)
    if (nameMatch) return true

    val rawMatch = sms.sender.contains(q, ignoreCase = true) ||
            sms.message.contains(q, ignoreCase = true)
    if (rawMatch) return true

    // Digit-normalized match: search "0912" should hit "+98 912 …"
    val qDigits = q.filter { it.isDigit() }
    if (qDigits.length >= 3) {
        if (sms.sender.filter { it.isDigit() }.contains(qDigits)) return true
        if (sms.message.take(120).filter { it.isDigit() }.contains(qDigits)) return true
    }
    return false
}

/**
 * Determinate sync indicator: "Syncing messages… 120/340" with a real
 * progress bar. Falls back to an indeterminate bar when totals are unknown.
 */
@Composable
private fun SyncBanner(progress: HomeViewModel.SyncProgress?, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        val total = progress?.total ?: 0
        val loaded = progress?.loaded ?: 0
        if (total > 0) {
            val fraction = (loaded.toFloat() / total).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.home_syncing_fmt, loaded, total),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Updating conversations…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DefaultSmsAppBanner(onSetDefault: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            ) {
                Icon(
                    imageVector = Icons.Default.Sms,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(9.dp).size(22.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.home_default_sms_off_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.home_default_sms_off_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.76f),
                )
            }
            Button(
                onClick = onSetDefault,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(stringResource(R.string.home_default_sms_off_cta), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ConversationListSkeleton(status: String?, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(top = 8.dp)) {
        status?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        repeat(6) { index ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {}
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(if (index % 2 == 0) 0.48f else 0.62f).height(12.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {}
                    Surface(
                        modifier = Modifier.fillMaxWidth(if (index % 3 == 0) 0.78f else 0.9f).height(10.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                    ) {}
                }
            }
        }
    }
}
