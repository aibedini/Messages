package com.autonomousone.messages.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.autonomousone.messages.navigation.Screen
import com.autonomousone.messages.ui.components.AppSearchBar
import com.autonomousone.messages.ui.components.EmptyView
import com.autonomousone.messages.ui.components.MainTopBar
import com.autonomousone.messages.ui.components.SmsItem
import com.autonomousone.messages.viewmodel.HomeViewModel
import java.util.Calendar

enum class ConversationFilter(val label: String) {
    All("All Messages"),
    Unread("Unread"),
    Archived("Archived")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    hasPermission: Boolean,
    isDefaultSmsApp: Boolean,
    onRequestDefaultApp: () -> Unit,
    navController: NavController
) {
    val viewModel: HomeViewModel = viewModel()
    var search by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(ConversationFilter.All) }

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

    val greeting = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when (hour) {
            in 4..11 -> "Good morning 👋"
            in 12..16 -> "Good afternoon ☀️"
            in 17..22 -> "Good evening 🌙"
            else -> "Hello 🌌"
        }
    }

    val filteredList by remember(search, selectedFilter) {
        derivedStateOf {
            smsList.filter { sms ->
                val matchesSearch = search.isBlank() ||
                        sms.sender.contains(search, ignoreCase = true) ||
                        sms.message.contains(search, ignoreCase = true)

                val matchesFilter = when (selectedFilter) {
                    ConversationFilter.All -> true
                    ConversationFilter.Unread -> sms.unread
                    ConversationFilter.Archived -> false
                }

                matchesSearch && matchesFilter
            }
        }
    }

    Scaffold(
        topBar = {
            MainTopBar(
                title = "Messages",
                onProfileClick = {},
                onSearchClick = {},
                onMarkAllReadClick = { viewModel.markAllAsRead() },
                onGatewayClick = { navController.navigate(Screen.Gateway.route) },
                onSettingsClick = {}
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
                    buttonText = null,
                    onButtonClick = null
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
                text = greeting,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
            )

            AppSearchBar(
                query = search,
                onQueryChange = { search = it },
                placeholderText = "Search messages & contacts..."
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
                                text = filter.label,
                                fontSize = 13.sp,
                                fontWeight = if (selectedFilter == filter) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (filteredList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyView(
                        title = if (search.isNotBlank()) "No Results Found" else "No Conversations",
                        subtitle = if (search.isNotBlank()) {
                            "No messages matching \"$search\" were found. Try another search."
                        } else {
                            "You don't have any messages yet. Start a new conversation below."
                        },
                        icon = if (search.isNotBlank()) Icons.Default.SearchOff else Icons.Default.MarkEmailUnread,
                        buttonText = "Start New Chat",
                        onButtonClick = {
                            navController.navigate(Screen.NewConversation.route)
                        }
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    state = listState,
                    contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)
                ) {
                    items(
                        items = filteredList,
                        key = { it.id }
                    ) { sms ->
                        SmsItem(
                            sms = sms,
                            onClick = {
                                navController.navigate(
                                    Screen.Conversation.createRoute(sms.threadId, sms.sender)
                                )
                            },
                            onArchive = {},
                            onDelete = {}
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DefaultSmsAppBanner(onSetDefault: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
        shadowElevation = 2.dp
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF6750A4),
                            Color(0xFF9C4BD4)
                        )
                    )
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Sms,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Set as Default SMS App",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Required for real-time message updates",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
                Button(
                    onClick = onSetDefault,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF6750A4)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Set Default",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}