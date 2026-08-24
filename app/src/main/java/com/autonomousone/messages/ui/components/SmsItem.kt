package com.autonomousone.messages.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.repository.ContactRepository
import com.autonomousone.messages.ui.theme.StatusError
import com.autonomousone.messages.ui.theme.UnreadBadgeColor
import com.autonomousone.messages.utils.formatConversationDate

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SmsItem(
    sms: Sms,
    onClick: () -> Unit,
    onArchive: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    /** Long-press → Pin / Unpin. */
    onPin: (() -> Unit)? = null,
    /** Long-press → Block number. */
    onBlock: (() -> Unit)? = null,
    /** True when this thread is pinned (shows a filled pin badge). */
    isPinned: Boolean = false,
    /** When true, the left swipe shows "Unarchive" instead of "Archive". */
    isArchived: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val displayName = remember(sms.sender) {
        ContactRepository(context).getCachedDisplayName(sms.sender)
    }
    var menuOpen by remember { mutableStateOf(false) }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onArchive?.invoke()
                    false   // keep the item in the list — ViewModel manages removal
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onDelete?.invoke()
                    false
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )

    // Determine which direction is active
    val isStartToEnd = dismissState.targetValue == SwipeToDismissBoxValue.StartToEnd
    val isEndToStart = dismissState.targetValue == SwipeToDismissBoxValue.EndToStart

    // Background colour for each direction
    val archiveColor = MaterialTheme.colorScheme.secondary
    val deleteColor = StatusError

    val bgColor by animateColorAsState(
        targetValue = when {
            isStartToEnd -> archiveColor
            isEndToStart -> deleteColor
            else -> Color.Transparent
        },
        label = "swipeBgColor"
    )

    // Icon scale pops up when the swipe passes the threshold
    val iconScale by animateFloatAsState(
        targetValue = when {
            isStartToEnd || isEndToStart -> 1.15f
            else -> 0.85f
        },
        animationSpec = spring(),
        label = "swipeIconScale"
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = onArchive != null,
        enableDismissFromEndToStart = onDelete != null,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgColor)
                    .padding(horizontal = 24.dp),
                contentAlignment = if (isStartToEnd) Alignment.CenterStart else Alignment.CenterEnd
            ) {
                when {
                    isStartToEnd -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.scale(iconScale)
                        ) {
                            Icon(
                                imageVector = if (isArchived) Icons.Default.Unarchive else Icons.Default.Archive,
                                contentDescription = if (isArchived) "Unarchive" else "Archive",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = if (isArchived) "Unarchive" else "Archive",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                        }
                    }
                    isEndToStart -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.scale(iconScale)
                        ) {
                            Text(
                                text = "Delete",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        },
        content = {
            Surface(
                modifier = modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = if (onPin != null || onBlock != null) {
                            { menuOpen = true }
                        } else null
                    ),
                color = if (sms.unread) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.background
                }
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(
                            name = displayName,
                            size = AvatarSize.Medium
                        )

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isPinned) {
                                    Icon(
                                        imageVector = Icons.Default.PushPin,
                                        contentDescription = "Pinned",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .size(14.dp)
                                            .padding(end = 4.dp)
                                    )
                                }
                                Text(
                                    text = displayName,
                                    fontWeight = if (sms.unread) FontWeight.ExtraBold else FontWeight.SemiBold,
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = sms.message,
                                fontSize = 14.sp,
                                fontWeight = if (sms.unread) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (sms.unread) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = formatConversationDate(sms.date),
                                fontSize = 12.sp,
                                fontWeight = if (sms.unread) FontWeight.Bold else FontWeight.Medium,
                                color = if (sms.unread) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                }
                            )

                            if (sms.unread) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(UnreadBadgeColor)
                                )
                            }
                        }
                    }

                    // Long-press conversation menu
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (onPin != null) {
                            DropdownMenuItem(
                                text = { Text(if (isPinned) "Unpin" else "Pin to top") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (isPinned) Icons.Outlined.PushPin else Icons.Default.PushPin,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    onPin()
                                }
                            )
                        }
                        if (!isArchived && onArchive != null) {
                            DropdownMenuItem(
                                text = { Text("Archive") },
                                leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onArchive()
                                }
                            )
                        }
                        if (onBlock != null) {
                            DropdownMenuItem(
                                text = { Text("Block number") },
                                leadingIcon = { Icon(Icons.Outlined.Block, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onBlock()
                                }
                            )
                        }
                        if (onDelete != null) {
                            DropdownMenuItem(
                                text = { Text("Delete", color = StatusError) },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = StatusError) },
                                onClick = {
                                    menuOpen = false
                                    onDelete()
                                }
                            )
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(start = 84.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                    )
                }
            }
        }
    )
}