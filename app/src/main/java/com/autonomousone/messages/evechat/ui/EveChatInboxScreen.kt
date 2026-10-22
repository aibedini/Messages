package com.autonomousone.messages.evechat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.autonomousone.messages.evechat.EveChatLoginSheet
import com.autonomousone.messages.evechat.EveChatViewModel
import com.autonomousone.messages.evechat.network.EveChatSocket
import com.autonomousone.messages.evechat.model.EveChatConversation
import com.autonomousone.messages.navigation.Screen
import com.autonomousone.messages.ui.components.MessagesBottomNav

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EveChatInboxScreen(
    navController: NavController,
    viewModel: EveChatViewModel
) {
    val loginState by viewModel.loginState.collectAsState()
    val serverUrl by viewModel.serverUrl.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val selectedFilter by viewModel.selectedFilter.collectAsState()
    val conversations by viewModel.filteredConversations.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    Scaffold(
        topBar = {
            if (loginState is EveChatViewModel.LoginState.Success) {
                TopAppBar(
                    title = { Text("پشتیبانی") },
                    actions = {
                        Icon(
                            imageVector = if (connectionState == EveChatSocket.State.Connected)
                                Icons.Default.Wifi else Icons.Default.WifiOff,
                            contentDescription = "وضعیت اتصال",
                            tint = if (connectionState == EveChatSocket.State.Connected)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                        IconButton(onClick = { viewModel.logout() }) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "خروج")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        },
        bottomBar = {
            MessagesBottomNav(navController = navController)
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (loginState) {
                is EveChatViewModel.LoginState.Success -> {
                    InboxContent(
                        conversations = conversations,
                        selectedFilter = selectedFilter,
                        isRefreshing = isRefreshing,
                        onFilterSelected = viewModel::selectFilter,
                        onRefresh = viewModel::refresh,
                        onConversationClick = { conv ->
                            navController.navigate("evechat_conversation/${conv.id}")
                        }
                    )
                }
                else -> {
                    EveChatLoginSheet(
                        loginState = loginState,
                        savedServerUrl = serverUrl,
                        onLogin = viewModel::login
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InboxContent(
    conversations: List<EveChatConversation>,
    selectedFilter: EveChatViewModel.InboxFilter,
    isRefreshing: Boolean,
    onFilterSelected: (EveChatViewModel.InboxFilter) -> Unit,
    onRefresh: () -> Unit,
    onConversationClick: (EveChatConversation) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(
            selectedTabIndex = EveChatViewModel.InboxFilter.entries.indexOf(selectedFilter),
            edgePadding = 8.dp,
            containerColor = MaterialTheme.colorScheme.surface,
            divider = {}
        ) {
            EveChatViewModel.InboxFilter.entries.forEach { filter ->
                FilterChip(
                    selected = filter == selectedFilter,
                    onClick = { onFilterSelected(filter) },
                    label = { Text(filter.label) },
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize()
        ) {
            if (conversations.isEmpty()) {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "گفتگویی یافت نشد",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(conversations, key = { it.id }) { conv ->
                        EveChatConversationRow(
                            conversation = conv,
                            onClick = { onConversationClick(conv) }
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
