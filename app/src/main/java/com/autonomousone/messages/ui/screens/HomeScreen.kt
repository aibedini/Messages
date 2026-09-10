package com.autonomousone.messages.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.autonomousone.messages.R
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.navigation.ConversationLaunchStore
import com.autonomousone.messages.navigation.Screen
import com.autonomousone.messages.repository.ContactRepository
import com.autonomousone.messages.repository.DraftRepository
import com.autonomousone.messages.ui.components.AppSearchBar
import com.autonomousone.messages.ui.components.EmptyView
import com.autonomousone.messages.ui.components.MainTopBar
import com.autonomousone.messages.ui.home.ConversationFilter
import com.autonomousone.messages.ui.home.ConversationList
import com.autonomousone.messages.ui.home.ConversationListSkeleton
import com.autonomousone.messages.ui.home.DefaultSmsAppBanner
import com.autonomousone.messages.ui.home.HomeConfirmDialog
import com.autonomousone.messages.ui.home.HomeFab
import com.autonomousone.messages.ui.home.HomeFilterBar
import com.autonomousone.messages.ui.home.HomeRow
import com.autonomousone.messages.ui.home.HomeSearch
import com.autonomousone.messages.ui.home.SentTodayChip
import com.autonomousone.messages.ui.home.SyncBanner
import com.autonomousone.messages.viewmodel.HomeViewModel
import kotlinx.coroutines.launch

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

    // v2.6.19: swipe no longer mutates anything on its own. It parks the
    // row here and Home asks for confirmation first — a thumb grazing
    // the edge while scrolling must never archive or delete.
    var pendingArchive by remember { mutableStateOf<Sms?>(null) }
    var pendingDelete by remember { mutableStateOf<Sms?>(null) }

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

    // The base list to filter from depends on the selected tab.
    val sourceList by remember(selectedFilter) {
        derivedStateOf {
            if (selectedFilter == ConversationFilter.Archived) archivedList else smsList
        }
    }

    val filteredList by remember(search, selectedFilter, smsList, archivedList) {
        derivedStateOf {
            sourceList.filter { sms ->
                val searchMatch = HomeSearch.matches(sms, search, viewModel.contactNames)
                val filterMatch = when (selectedFilter) {
                    ConversationFilter.All -> true
                    ConversationFilter.Unread -> sms.unread
                    ConversationFilter.Archived -> true   // archivedList is already filtered
                }
                searchMatch && filterMatch
            }
        }
    }

    val searchLooksLikeNumber = remember(search) { HomeSearch.looksLikeNumber(search) }

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
                titleBadge = {
                    // Real per-SEGMENT today counter (3-part send = 3), fed by
                    // the send_segments ledger via Room Flow — bumps live on
                    // each RESULT_OK callback without a Home reload.
                    SentTodayChip(viewModel.sentSegmentsToday)
                },
                onProfileClick = {},
                onSearchClick = null,
                onMarkAllReadClick = { viewModel.markAllAsRead() },
                onGatewayClick = { navController.navigate(Screen.Gateway.route) },
                onSettingsClick = { navController.navigate(Screen.Settings.route) }
            )
        },
        floatingActionButton = {
            HomeFab(
                expanded = isExpanded,
                onClick = {
                    // v2.6.12: baseRoute, NOT route — the route pattern carries
                    // literal "{forward}/{draft}" placeholders which the nav
                    // library then delivered AS the argument value.
                    navController.navigate(Screen.NewConversation.baseRoute)
                }
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

            AppSearchBar(
                query = search,
                onQueryChange = { search = it },
                placeholderText = stringResource(R.string.home_search_hint)
            )

            HomeFilterBar(selected = selectedFilter, onSelect = { selectedFilter = it })

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
                            { navController.navigate(Screen.NewConversation.baseRoute) }
                        }
                    )
                }
            } else {
                // Resolve each visible row's lightweight UI fields once, outside
                // composition of the list item (RFP §8/§9). No I/O here.
                val blockedMsg = stringResource(R.string.home_snackbar_blocked)
                val rows = filteredList.map { sms ->
                    HomeRow(
                        sms = sms,
                        draftKey = DraftRepository.keyFor(sms.threadId, sms.sender),
                        draftText = draftMap[DraftRepository.keyFor(sms.threadId, sms.sender)].orEmpty(),
                        isPinned = sms.threadId in viewModel.pinnedIds,
                        isArchived = isInArchivedView,
                        showYouMarker = sms.type == 2,
                    )
                }

                ConversationList(
                    listState = listState,
                    rows = rows,
                    isRefreshing = viewModel.isRefreshing,
                    onRefresh = { viewModel.refreshNow() },
                    search = search,
                    resultCount = filteredList.size,
                    showDirectSend = searchLooksLikeNumber,
                    directNumber = search.trim(),
                    onDirectSend = {
                        navController.navigate(
                            Screen.Conversation.createNewRoute(
                                phone = search.trim(),
                                name = search.trim()
                            )
                        )
                    },
                    globalHeaderText = stringResource(R.string.home_search_global_header),
                    globalHits = viewModel.globalResults.map { it.sms },
                    onGlobalHitClick = { hit ->
                        val displayName = viewModel.contactNames[
                            ContactRepository.normalizePhone(hit.sender)
                        ] ?: hit.sender
                        ConversationLaunchStore.put(
                            ConversationLaunchStore.Snapshot(
                                threadId = hit.threadId,
                                phone = hit.sender,
                                name = displayName,
                                message = hit.message,
                                date = hit.date,
                                type = hit.type
                            )
                        )
                        navController.navigate(
                            Screen.Conversation.createRoute(hit.threadId, hit.sender)
                        )
                    },
                    onRowClick = { row ->
                        // v2.6.9 first-paint handoff: the row Home is showing
                        // right now IS the conversation's last bubble. Stash it
                        // (no IO) so Conversation's very first frame is never blank.
                        val displayName = viewModel.contactNames[
                            ContactRepository.normalizePhone(row.sms.sender)
                        ] ?: row.sms.sender
                        ConversationLaunchStore.put(
                            ConversationLaunchStore.Snapshot(
                                threadId = row.sms.threadId,
                                phone = row.sms.sender,
                                name = displayName,
                                message = row.sms.message,
                                date = row.sms.date,
                                type = row.sms.type
                            )
                        )
                        navController.navigate(
                            Screen.Conversation.createRoute(
                                threadId = row.sms.threadId,
                                phone = row.sms.sender,
                                name = displayName
                            )
                        )
                    },
                    onRowPin = { row -> viewModel.togglePin(row.sms) },
                    onRowBlock = { row ->
                        viewModel.blockConversation(row.sms)
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = blockedMsg,
                                duration = SnackbarDuration.Short
                            )
                        }
                    },
                    onRowArchive = { row -> pendingArchive = row.sms },
                    onRowDelete = { row -> pendingDelete = row.sms },
                    modifier = Modifier.weight(1f)
                )

                // v2.6.19: swipe parks the row here; nothing changes until the
                // user confirms. Archive and delete are two deliberate taps.
                pendingArchive?.let { target ->
                    val confirmArchiveMsg = stringResource(R.string.home_snackbar_archived)
                    val confirmUnarchiveMsg = stringResource(R.string.home_snackbar_unarchived)
                    val confirmUndo = stringResource(R.string.action_undo)
                    HomeConfirmDialog(
                        title = stringResource(
                            if (isInArchivedView) R.string.home_confirm_unarchive_title
                            else R.string.home_confirm_archive_title
                        ),
                        body = viewModel.contactNames[
                            ContactRepository.normalizePhone(target.sender)
                        ] ?: target.sender,
                        destructive = false,
                        onConfirm = {
                            pendingArchive = null
                            if (isInArchivedView) {
                                viewModel.unarchiveConversation(target)
                                scope.launch {
                                    val r = snackbarHostState.showSnackbar(
                                        message = confirmUnarchiveMsg,
                                        actionLabel = confirmUndo,
                                        duration = SnackbarDuration.Short)
                                    if (r == SnackbarResult.ActionPerformed)
                                        viewModel.archiveConversation(target)
                                }
                            } else {
                                viewModel.archiveConversation(target)
                                scope.launch {
                                    val r = snackbarHostState.showSnackbar(
                                        message = confirmArchiveMsg,
                                        actionLabel = confirmUndo,
                                        duration = SnackbarDuration.Long)
                                    if (r == SnackbarResult.ActionPerformed)
                                        viewModel.unarchiveConversation(target)
                                }
                            }
                        },
                        onDismiss = { pendingArchive = null }
                    )
                }

                pendingDelete?.let { target ->
                    val confirmDeletedMsg = stringResource(R.string.home_snackbar_deleted)
                    val confirmUndo = stringResource(R.string.action_undo)
                    HomeConfirmDialog(
                        title = stringResource(R.string.home_confirm_delete_title),
                        body = viewModel.contactNames[
                            ContactRepository.normalizePhone(target.sender)
                        ] ?: target.sender,
                        destructive = true,
                        onConfirm = {
                            pendingDelete = null
                            viewModel.deleteConversation(target)
                            scope.launch {
                                val r = snackbarHostState.showSnackbar(
                                    message = confirmDeletedMsg,
                                    actionLabel = confirmUndo,
                                    duration = SnackbarDuration.Long)
                                if (r == SnackbarResult.ActionPerformed)
                                    viewModel.undoDelete(target)
                            }
                        },
                        onDismiss = { pendingDelete = null }
                    )
                }
            }
        }
    }
}
