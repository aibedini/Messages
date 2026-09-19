package com.autonomousone.messages.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.autonomousone.messages.R
import com.autonomousone.messages.ui.components.EmptyView
import com.autonomousone.messages.utils.formatDate
import com.autonomousone.messages.viewmodel.TrashViewModel

/**
 * Recently Deleted (v3.4.0 FEATURE 8).
 *
 * Google-Messages-shaped: every trashed CONVERSATION with the date it was
 * deleted and the days left before permanent deletion, a Restore action, a
 * destructive Delete-forever action (both behind an explicit confirmation) and an
 * overflow "Empty Trash".
 *
 * The screen never owns truth: rows come from the durable `trashed_threads`
 * tombstones via [TrashViewModel], and each action reports its own outcome in a
 * snackbar — a provider failure is shown as a failure, never as success.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentlyDeletedScreen(navController: NavController) {
    val viewModel: TrashViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val busyIds = viewModel.busyThreadIds

    var menuOpen by remember { mutableStateOf(false) }
    var pendingPermanent by remember { mutableStateOf<TrashViewModel.TrashRow?>(null) }
    var pendingEmpty by remember { mutableStateOf(false) }

    // Snackbar copy is resolved here (stringResource is not callable from a
    // LaunchedEffect), then keyed by the one-shot notice.
    val restoredMsg = stringResource(R.string.trash_snackbar_restored)
    val restoreFailedMsg = stringResource(R.string.trash_snackbar_restore_failed)
    val deletedMsg = stringResource(R.string.trash_snackbar_deleted_permanently)
    val deleteFailedMsg = stringResource(R.string.trash_snackbar_delete_failed)
    val emptyDoneMsg = stringResource(R.string.trash_snackbar_emptied)
    val emptyPartialMsg = stringResource(R.string.trash_snackbar_emptied_partial)
    val notice = viewModel.notice
    LaunchedEffect(notice) {
        val current = notice ?: return@LaunchedEffect
        val message = when (current) {
            TrashViewModel.TrashNotice.Restored -> restoredMsg
            TrashViewModel.TrashNotice.RestoreFailed -> restoreFailedMsg
            TrashViewModel.TrashNotice.PermanentlyDeleted -> deletedMsg
            TrashViewModel.TrashNotice.PermanentDeleteFailed -> deleteFailedMsg
            is TrashViewModel.TrashNotice.Emptied ->
                if (current.failed == 0) emptyDoneMsg
                else emptyPartialMsg.format(current.purged, current.failed)
        }
        snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
        viewModel.consumeNotice()
    }

    val hasRows = state is TrashViewModel.TrashUiState.Content

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    shape = MaterialTheme.shapes.medium,
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    actionColor = MaterialTheme.colorScheme.inversePrimary
                )
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.trash_screen_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_close)
                        )
                    }
                },
                actions = {
                    // No clickable no-op: the overflow only exists when there is
                    // something it can actually do.
                    if (hasRows) {
                        IconButton(
                            onClick = { menuOpen = true },
                            enabled = !viewModel.emptying
                        ) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.trash_menu_more)
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.trash_empty_action)) },
                                leadingIcon = {
                                    Icon(Icons.Default.Delete, contentDescription = null)
                                },
                                onClick = {
                                    menuOpen = false
                                    pendingEmpty = true
                                }
                            )
                        }
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
            if (hasRows) {
                Text(
                    text = stringResource(R.string.trash_screen_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            when (val current = state) {
                TrashViewModel.TrashUiState.Loading -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                is TrashViewModel.TrashUiState.Error -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        EmptyView(
                            title = stringResource(R.string.trash_error_title),
                            subtitle = stringResource(R.string.trash_error_subtitle),
                            icon = Icons.Default.RestoreFromTrash,
                            buttonText = stringResource(R.string.action_retry),
                            onButtonClick = { viewModel.retry() }
                        )
                    }
                }

                TrashViewModel.TrashUiState.Empty -> {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        EmptyView(
                            title = stringResource(R.string.trash_empty_title),
                            subtitle = stringResource(R.string.trash_empty_subtitle),
                            icon = Icons.Default.Delete,
                            buttonText = null,
                            onButtonClick = null
                        )
                    }
                }

                is TrashViewModel.TrashUiState.Content -> {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 4.dp,
                            bottom = 24.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(current.rows, key = { it.threadId }) { row ->
                            TrashRowCard(
                                row = row,
                                busy = row.threadId in busyIds,
                                onRestore = { viewModel.restore(row.threadId) },
                                onDeletePermanently = { pendingPermanent = row }
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Destructive confirmations (Dialog, never a silent action) ───────────

    pendingPermanent?.let { row ->
        val label = row.displayName.ifBlank { stringResource(R.string.trash_unknown_contact) }
        AlertDialog(
            onDismissRequest = { pendingPermanent = null },
            title = { Text(stringResource(R.string.trash_delete_confirm_title)) },
            text = { Text(stringResource(R.string.trash_delete_confirm_body, label)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingPermanent = null
                    viewModel.deletePermanently(row.threadId)
                }) {
                    Text(
                        stringResource(R.string.trash_delete_forever),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPermanent = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (pendingEmpty) {
        AlertDialog(
            onDismissRequest = { pendingEmpty = false },
            title = { Text(stringResource(R.string.trash_empty_confirm_title)) },
            text = { Text(stringResource(R.string.trash_empty_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingEmpty = false
                    viewModel.emptyTrash()
                }) {
                    Text(
                        stringResource(R.string.trash_empty_action),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingEmpty = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun TrashRowCard(
    row: TrashViewModel.TrashRow,
    busy: Boolean,
    onRestore: () -> Unit,
    onDeletePermanently: () -> Unit
) {
    val name = row.displayName.ifBlank { stringResource(R.string.trash_unknown_contact) }
    // The address is only shown when it adds information (i.e. the title is a
    // contact name rather than the address itself).
    val showAddress = row.address.isNotBlank() && row.address != name
    val deletedLabel = formatDate(row.deletedAt, "MMM d, yyyy")
    val remainingLabel = if (row.daysRemaining <= 0L) {
        stringResource(R.string.trash_purge_pending)
    } else {
        stringResource(R.string.trash_days_left_fmt, row.daysRemaining)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (showAddress) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = row.address,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.trash_deleted_on_fmt, deletedLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = remainingLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onRestore, enabled = !busy) {
                    Icon(
                        Icons.Default.Restore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(stringResource(R.string.action_restore))
                }
                IconButton(onClick = onDeletePermanently, enabled = !busy) {
                    Icon(
                        Icons.Default.DeleteForever,
                        contentDescription = stringResource(R.string.trash_delete_forever),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
