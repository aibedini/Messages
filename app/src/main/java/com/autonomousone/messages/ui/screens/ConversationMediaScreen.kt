package com.autonomousone.messages.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.autonomousone.messages.R
import com.autonomousone.messages.data.MessageAssetKind
import com.autonomousone.messages.utils.formatFullTimestamp
import com.autonomousone.messages.viewmodel.ConversationMediaViewModel
import com.autonomousone.messages.viewmodel.MediaItem
import com.autonomousone.messages.viewmodel.MediaTab
import kotlinx.coroutines.launch

/**
 * Media / Links / Files browser for ONE conversation (v3.4.0 FEATURE 6).
 *
 * Wired by the navigation coordinator:
 *
 * ```
 * composable(
 *     route = Screen.ConversationMedia.route,
 *     arguments = listOf(
 *         navArgument("threadId") { type = NavType.LongType },
 *         navArgument("name") { type = NavType.StringType }
 *     )
 * ) { entry ->
 *     ConversationMediaScreen(
 *         threadId = entry.arguments?.getLong("threadId") ?: 0L,
 *         conversationName = Screen.cleanArg(entry.arguments?.getString("name")),
 *         navController = navController
 *     )
 * }
 * ```
 *
 * Every tab is paged newest-first, and every tab has all four states
 * (Loading / Content / Empty / Error) — never a blank screen. Metadata only:
 * media is loaded from the provider `content://` URI by Coil, and no bytes are
 * ever copied into the app database.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationMediaScreen(
    threadId: Long,
    conversationName: String,
    navController: NavController,
    viewModel: ConversationMediaViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val openFailedMessage = stringResource(R.string.media_open_failed)
    val loadMoreFailedMessage = stringResource(R.string.media_load_more_failed)

    LaunchedEffect(threadId) { viewModel.open(threadId) }

    // A failure that still leaves content on screen (a "load more" failure) is
    // transient: report it, keep the list. A first-page failure uses the Error
    // state instead, with an explicit Retry — never a blank screen.
    LaunchedEffect(viewModel.errorMessage, viewModel.items.size) {
        if (viewModel.errorMessage != null && viewModel.items.isNotEmpty()) {
            snackbarHostState.showSnackbar(loadMoreFailedMessage)
            viewModel.consumeError()
        }
    }

    val openItem: (MediaItem) -> Unit = { item ->
        val uri = Uri.parse(item.value)
        val type = item.mimeType.takeIf { it.isNotBlank() }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(intent)
        } catch (error: Exception) {
            // No viewer installed / the provider refused the grant. The user gets
            // a real message instead of a dead tap.
            scope.launch { snackbarHostState.showSnackbar(openFailedMessage) }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = conversationName.ifBlank {
                            stringResource(R.string.media_screen_title)
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.media_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.media_refresh)
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
            TabRow(selectedTabIndex = viewModel.selectedTab.ordinal) {
                MediaTab.entries.forEach { tab ->
                    Tab(
                        selected = viewModel.selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = {
                            Text(
                                text = stringResource(
                                    R.string.media_tab_label_count,
                                    stringResource(tab.labelRes()),
                                    viewModel.counts[tab] ?: 0
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }

            val tab = viewModel.selectedTab
            when {
                viewModel.showLoading -> LoadingState()
                viewModel.errorMessage != null && viewModel.items.isEmpty() -> ErrorState(
                    onRetry = { viewModel.retry() }
                )
                viewModel.showEmpty -> EmptyState(tab)
                tab == MediaTab.MEDIA -> MediaGrid(
                    items = viewModel.items,
                    isLoadingMore = viewModel.isLoadingMore,
                    onOpen = openItem,
                    onLoadMore = { viewModel.loadMore() }
                )
                else -> AssetList(
                    items = viewModel.items,
                    isLoadingMore = viewModel.isLoadingMore,
                    onOpen = openItem,
                    onLoadMore = { viewModel.loadMore() }
                )
            }
        }
    }
}

private fun MediaTab.labelRes(): Int = when (this) {
    MediaTab.MEDIA -> R.string.media_tab_media
    MediaTab.LINKS -> R.string.media_tab_links
    MediaTab.FILES -> R.string.media_tab_files
}

private fun MediaTab.emptyRes(): Int = when (this) {
    MediaTab.MEDIA -> R.string.media_empty_media
    MediaTab.LINKS -> R.string.media_empty_links
    MediaTab.FILES -> R.string.media_empty_files
}

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorState(onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.media_error_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.media_retry))
            }
        }
    }
}

@Composable
private fun EmptyState(tab: MediaTab) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(tab.emptyRes()),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 3-column thumbnail grid, newest first. */
@Composable
private fun MediaGrid(
    items: List<MediaItem>,
    isLoadingMore: Boolean,
    onOpen: (MediaItem) -> Unit,
    onLoadMore: () -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        gridItemsIndexed(items, key = { _, item -> item.assetKey }) { index, item ->
            if (index >= items.size - 3) {
                LaunchedEffect(items.size) { onLoadMore() }
            }
            MediaThumb(item = item, onOpen = onOpen)
        }
        if (isLoadingMore) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
private fun MediaThumb(item: MediaItem, onOpen: (MediaItem) -> Unit) {
    val label = item.displayName.ifBlank { item.mimeType }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onOpen(item) }
    ) {
        AsyncImage(
            model = item.value,
            contentDescription = stringResource(R.string.media_item_media_desc, label),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/** LINKS and FILES rows: icon/title/snippet + date, newest first. */
@Composable
private fun AssetList(
    items: List<MediaItem>,
    isLoadingMore: Boolean,
    onOpen: (MediaItem) -> Unit,
    onLoadMore: () -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(items, key = { _, item -> item.assetKey }) { index, item ->
            if (index >= items.size - 3) {
                LaunchedEffect(items.size) { onLoadMore() }
            }
            if (item.kind == MessageAssetKind.LINK) {
                LinkRow(item = item, onOpen = onOpen)
            } else {
                FileRow(item = item, onOpen = onOpen)
            }
        }
        if (isLoadingMore) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
private fun LinkRow(item: MediaItem, onOpen: (MediaItem) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(item) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = Icons.Default.Description,
            contentDescription = stringResource(
                R.string.media_item_link_desc,
                item.displayName.ifBlank { item.value }
            ),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 2.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.displayName.ifBlank { item.value },
                style = MaterialTheme.typography.titleSmall.copy(
                    textDirection = TextDirection.Ltr
                ),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.snippet.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formatFullTimestamp(item.date),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FileRow(item: MediaItem, onOpen: (MediaItem) -> Unit) {
    val name = item.displayName.ifBlank { stringResource(R.string.media_unnamed_file) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(item) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = fileIcon(item.mimeType),
                contentDescription = stringResource(R.string.media_item_file_desc, name),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.mimeType.isNotBlank()) {
                Text(
                    text = item.mimeType,
                    style = MaterialTheme.typography.bodySmall.copy(
                        textDirection = TextDirection.Ltr
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = formatFullTimestamp(item.date),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun fileIcon(mimeType: String): ImageVector = when {
    mimeType == "application/pdf" -> Icons.Default.PictureAsPdf
    mimeType.startsWith("image/") -> Icons.Default.Image
    mimeType.startsWith("video/") -> Icons.Default.Videocam
    mimeType.startsWith("audio/") -> Icons.Default.Audiotrack
    mimeType.startsWith("text/") -> Icons.Default.Description
    else -> Icons.Default.InsertDriveFile
}
