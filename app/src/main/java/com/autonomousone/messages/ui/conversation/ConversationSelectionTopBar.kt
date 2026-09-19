package com.autonomousone.messages.ui.conversation

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Forward
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autonomousone.messages.R

/**
 * FEATURE 9/10 — the conversation's selection top bar: `X   3 selected   [actions]`.
 *
 * Stateless: the screen owns the selection. Star/Unstar is one toggle driven by
 * the real starred state of the current selection, and Forward appears only when
 * exactly one message is selected — forwarding stays a single-message workflow,
 * it is never silently applied to a multi-selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationSelectionTopBar(
    selectedCount: Int,
    busy: Boolean,
    allSelectedStarred: Boolean,
    onClose: () -> Unit,
    onStar: () -> Unit,
    onUnstar: () -> Unit,
    onCopyText: () -> Unit,
    onForward: () -> Unit,
    onTrash: () -> Unit,
    modifier: Modifier = Modifier
) {
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
                // Progress indicator: starring / trashing a large selection is a
                // real Room + provider round-trip, never a frozen bar.
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .size(18.dp),
                        strokeWidth = 2.dp
                    )
                }
                if (allSelectedStarred) {
                    IconButton(onClick = onUnstar, enabled = enabled) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = stringResource(R.string.bulk_unstar)
                        )
                    }
                } else {
                    IconButton(onClick = onStar, enabled = enabled) {
                        Icon(
                            imageVector = Icons.Outlined.StarBorder,
                            contentDescription = stringResource(R.string.bulk_star)
                        )
                    }
                }
                IconButton(onClick = onCopyText, enabled = enabled) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.bulk_copy_text)
                    )
                }
                if (selectedCount == 1) {
                    IconButton(onClick = onForward, enabled = enabled) {
                        Icon(
                            imageVector = Icons.Default.Forward,
                            contentDescription = stringResource(R.string.action_forward)
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
