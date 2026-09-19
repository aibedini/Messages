package com.autonomousone.messages.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.autonomousone.messages.R
import com.autonomousone.messages.repository.StarredMessageItem
import com.autonomousone.messages.viewmodel.StarredMessagesViewModel

/**
 * Starred messages (v3.4.0 FEATURE 7).
 *
 * One composable serves both entry points:
 *
 *  - [threadId] null → the GLOBAL starred browser ([Screen.StarredMessages]);
 *  - [threadId] set  → the in-conversation starred list
 *    ([Screen.ConversationStarred]).
 *
 * Both lists are newest-first, paged and ACTIVE-UI filtered by SQL, so a starred
 * message inside a trashed conversation is never shown here.
 *
 * TAPPING A ROW — the navigation contract this screen requires (owned by other
 * workstreams, hence a callback and not a route built here):
 *
 *   `onOpenMessage(threadId, source, providerId)`
 *
 * must open the conversation and LAND ON THE EXACT MESSAGE. The message is
 * identified by the composite `(source, providerId)` — SMS `_id` 100 and MMS
 * `_id` 100 are different messages — and the jump itself belongs to FEATURE 1's
 * search workstream (`ConversationViewModel.jumpToMessage`), which this
 * workstream must not edit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarredMessagesScreen(
    threadId: Long?,
    navController: NavController,
    /** Opens the conversation at the EXACT message. See the contract above. */
    onOpenMessage: (threadId: Long, source: String, providerId: Long) -> Unit
) {
    val viewModel: StarredMessagesViewModel = viewModel(
        key = "starred_messages_${threadId ?: "global"}",
        factory = StarredMessagesViewModel.factory(threadId)
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    val atEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= listState.layoutInfo.totalItemsCount - 1
        }
    }
    // Reaching the end asks for the next bounded page instead of loading the
    // whole history up front.
    LaunchedEffect(atEnd, state.items.size, state.done) {
        if (atEnd && !state.done) viewModel.loadMore()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.starred_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_close)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.isInitialLoading -> StarredLoading()

                state.error != null && state.items.isEmpty() -> StarredError(
                    message = state.error.orEmpty(),
                    onRetry = { viewModel.refresh() }
                )

                state.items.isEmpty() -> StarredEmpty()

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp,
                        vertical = 8.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.items, key = { it.listKey }) { item ->
                        StarredRow(
                            item = item,
                            onOpen = { onOpenMessage(item.threadId, item.key.source, item.key.providerId) },
                            onUnstar = { viewModel.unstar(item) }
                        )
                    }
                    if (state.loadingMore) {
                        item(key = "loading_more") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                    if (state.error != null) {
                        item(key = "error_footer") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = state.error.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                TextButton(onClick = { viewModel.clearError() }) {
                                    Text(stringResource(R.string.action_close))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StarredRow(
    item: StarredMessageItem,
    onOpen: () -> Unit,
    onUnstar: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.body,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.formattedDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onUnstar) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = stringResource(R.string.starred_unstar_cd, item.body),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun StarredLoading() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.starred_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StarredEmpty() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.StarBorder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(40.dp)
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.starred_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StarredError(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.starred_error),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onRetry) { Text(stringResource(R.string.starred_retry)) }
    }
}
