package com.autonomousone.messages.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsItem(
    sms: Sms,
    onClick: () -> Unit,
    onArchive: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val displayName = remember(sms.sender) {
        ContactRepository(context).getCachedDisplayName(sms.sender)
    }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onArchive?.invoke()
                    false
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onDelete?.invoke()
                    false
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = onArchive != null,
        enableDismissFromEndToStart = onDelete != null,
        backgroundContent = {
            val color by animateColorAsState(
                targetValue = when (dismissState.targetValue) {
                    SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.secondary
                    SwipeToDismissBoxValue.EndToStart -> StatusError
                    else -> Color.Transparent
                },
                label = "swipeBgColor"
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color)
                    .padding(horizontal = 24.dp),
                contentAlignment = if (dismissState.targetValue == SwipeToDismissBoxValue.StartToEnd) {
                    Alignment.CenterStart
                } else Alignment.CenterEnd
            ) {
                if (dismissState.targetValue == SwipeToDismissBoxValue.StartToEnd) {
                    Icon(
                        imageVector = Icons.Default.Archive,
                        contentDescription = "Archive",
                        tint = Color.White
                    )
                } else if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = Color.White
                    )
                }
            }
        },
        content = {
            Surface(
                modifier = modifier
                    .fillMaxWidth()
                    .clickable { onClick() },
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