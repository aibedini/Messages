package com.autonomousone.messages.evechat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.autonomousone.messages.evechat.EveChatLoginSheet
import com.autonomousone.messages.R
import com.autonomousone.messages.evechat.EveChatSession
import com.autonomousone.messages.evechat.EveChatSession.InboxFilter
import com.autonomousone.messages.evechat.EveChatViewModel
import com.autonomousone.messages.evechat.messageRes
import com.autonomousone.messages.navigation.Screen
import com.autonomousone.messages.ui.components.MessagesBottomNav

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EveChatInboxScreen(
    navController: NavController,
    viewModel: EveChatViewModel
) {
    val auth by viewModel.authState.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val loginState by viewModel.loginState.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val myAgentId by viewModel.myAgentId.collectAsStateWithLifecycle()
    val myAgentName by viewModel.myAgentName.collectAsStateWithLifecycle()
    val pushStatus by viewModel.pushStatus.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var showStatus by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    val signedIn = auth is EveChatSession.AuthState.SignedIn

    LaunchedEffect(Unit) {
        viewModel.errors.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            if (signedIn) {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.eve_title))
                            Text(
                                // Which support account is signed in, then the link state:
                                // "vpnmahna · Connected" — the header used to hide both.
                                text = listOfNotNull(
                                    myAgentName,
                                    stringResource(connection.messageRes())
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.eve_menu_more)
                                )
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.eve_menu_status)) },
                                    leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        showStatus = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.eve_menu_sign_out)) },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        confirmLogout = true
                                    }
                                )
                            }
                        }
                    }
                )
            }
        },
        bottomBar = { MessagesBottomNav(navController = navController) },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (!signedIn) {
                EveChatLoginSheet(
                    loginState = loginState,
                    savedServerUrl = viewModel.savedServer,
                    savedEmail = viewModel.savedEmail,
                    onLogin = viewModel::login
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    AnimatedVisibility(visible = connection == EveChatSession.Connection.OFFLINE) {
                        Text(
                            text = stringResource(R.string.eve_offline_banner),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                    NotificationsOffBanner()
                    FilterRow(filter, viewModel::selectFilter)
                    PullToRefreshBox(
                        isRefreshing = refreshing,
                        onRefresh = viewModel::refresh,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (conversations.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = stringResource(emptyLabelRes(filter)),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(conversations, key = { it.id }) { conv ->
                                    EveChatConversationRow(
                                        conversation = conv,
                                        myAgentId = myAgentId,
                                        onClick = {
                                            navController.navigate(Screen.EveChatConversation.createRoute(conv.id)) {
                                                launchSingleTop = true
                                            }
                                        },
                                        modifier = Modifier.animateItem()
                                    )
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 76.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showStatus && signedIn) {
        SyncStatusDialog(
            connection = connection,
            pushStatus = pushStatus,
            lastPushAt = viewModel.lastPushAt,
            server = viewModel.savedServer,
            email = viewModel.savedEmail,
            onDismiss = { showStatus = false }
        )
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(stringResource(R.string.eve_sign_out_title)) },
            text = { Text(stringResource(R.string.eve_sign_out_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    viewModel.logout()
                }) { Text(stringResource(R.string.eve_menu_sign_out)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

@Composable
private fun FilterRow(selected: InboxFilter, onSelect: (InboxFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        InboxFilter.entries.forEach { f ->
            FilterChip(
                selected = f == selected,
                onClick = { onSelect(f) },
                label = { Text(stringResource(filterLabelRes(f))) }
            )
        }
    }
}

@androidx.annotation.StringRes
private fun filterLabelRes(f: InboxFilter) = when (f) {
    InboxFilter.OPEN -> R.string.eve_filter_open
    InboxFilter.PENDING -> R.string.eve_filter_pending
    InboxFilter.MINE -> R.string.eve_filter_mine
    InboxFilter.RESOLVED -> R.string.eve_filter_resolved
}

@androidx.annotation.StringRes
private fun emptyLabelRes(f: InboxFilter) = when (f) {
    InboxFilter.OPEN -> R.string.eve_empty_open
    InboxFilter.PENDING -> R.string.eve_empty_pending
    InboxFilter.MINE -> R.string.eve_empty_mine
    InboxFilter.RESOLVED -> R.string.eve_empty_resolved
}
