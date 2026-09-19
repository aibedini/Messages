package com.autonomousone.messages.ui.home

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autonomousone.messages.R

/** Mute presets offered by the bulk top bar. */
enum class BulkMuteDuration(val millis: Long) {
    EIGHT_HOURS(8L * 60 * 60 * 1000),
    ONE_DAY(24L * 60 * 60 * 1000),

    /** The same "muted until explicitly unmuted" sentinel the entity uses. */
    FOREVER(com.autonomousone.messages.data.ConversationPreferenceEntity.MUTE_FOREVER)
}

/**
 * FEATURE 9/10 — Home's selection top bar: `X   3 selected   [actions]`.
 *
 * Stateless: Home owns the selection and every callback. Only the branches that
 * are VALID for the current selection are rendered (Pin/Unpin and Mute/Unmute
 * are mutually exclusive toggles, Archive/Unarchive follows the visible tab), so
 * the bar never shows a dead action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeSelectionTopBar(
    selectedCount: Int,
    busy: Boolean,
    archivedView: Boolean,
    allSelectedPinned: Boolean,
    allSelectedMuted: Boolean,
    onClose: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
    onMute: (Long) -> Unit,
    onUnmute: () -> Unit,
    onPin: () -> Unit,
    onUnpin: () -> Unit,
    onTrash: () -> Unit,
    modifier: Modifier = Modifier
) {
    var muteMenuOpen by remember { mutableStateOf(false) }
    val enabled = !busy && selectedCount > 0

    TopAppBar(
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.bulk_exit_selection)
                )
            }
        },
        title = {
            Text(
                text = stringResource(R.string.bulk_selected_count_fmt, selectedCount),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        actions = {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                // Progress indicator: a bulk action over a large selection does
                // real provider work, so it must never look frozen.
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .size(18.dp),
                        strokeWidth = 2.dp
                    )
                }
                IconButton(onClick = onMarkRead, enabled = enabled) {
                    Icon(
                        imageVector = Icons.Default.MarkEmailRead,
                        contentDescription = stringResource(R.string.bulk_mark_read)
                    )
                }
                IconButton(onClick = onMarkUnread, enabled = enabled) {
                    Icon(
                        imageVector = Icons.Default.MarkEmailUnread,
                        contentDescription = stringResource(R.string.bulk_mark_unread)
                    )
                }
                IconButton(
                    onClick = { if (archivedView) onUnarchive() else onArchive() },
                    enabled = enabled
                ) {
                    Icon(
                        imageVector = if (archivedView) Icons.Default.Unarchive else Icons.Default.Archive,
                        contentDescription = stringResource(
                            if (archivedView) R.string.bulk_unarchive else R.string.bulk_archive
                        )
                    )
                }
                if (allSelectedMuted) {
                    IconButton(onClick = onUnmute, enabled = enabled) {
                        Icon(
                            imageVector = Icons.Default.NotificationsActive,
                            contentDescription = stringResource(R.string.bulk_unmute)
                        )
                    }
                } else {
                    IconButton(onClick = { muteMenuOpen = true }, enabled = enabled) {
                        Icon(
                            imageVector = Icons.Default.NotificationsOff,
                            contentDescription = stringResource(R.string.bulk_mute)
                        )
                    }
                    DropdownMenu(
                        expanded = muteMenuOpen,
                        onDismissRequest = { muteMenuOpen = false }
                    ) {
                        BulkMuteDuration.entries.forEach { duration ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            when (duration) {
                                                BulkMuteDuration.EIGHT_HOURS -> R.string.bulk_mute_8h
                                                BulkMuteDuration.ONE_DAY -> R.string.bulk_mute_1d
                                                BulkMuteDuration.FOREVER -> R.string.bulk_mute_forever
                                            }
                                        )
                                    )
                                },
                                onClick = {
                                    muteMenuOpen = false
                                    onMute(duration.millis)
                                }
                            )
                        }
                    }
                }
                if (allSelectedPinned) {
                    IconButton(onClick = onUnpin, enabled = enabled) {
                        Icon(
                            imageVector = Icons.Outlined.PushPin,
                            contentDescription = stringResource(R.string.bulk_unpin)
                        )
                    }
                } else {
                    IconButton(onClick = onPin, enabled = enabled) {
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = stringResource(R.string.bulk_pin)
                        )
                    }
                }
                IconButton(onClick = onTrash, enabled = enabled) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.bulk_move_to_trash),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            navigationIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            actionIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer
        )
    )
}
