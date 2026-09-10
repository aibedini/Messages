package com.autonomousone.messages.ui.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autonomousone.messages.R
import com.autonomousone.messages.ui.components.ChatBubble
import com.autonomousone.messages.ui.design.MessagesMotion
import com.autonomousone.messages.ui.design.MessagesSpacing

/**
 * Message timeline for a conversation (PR-04).
 *
 * Encapsulates the reverse-layout LazyColumn, date separators, the loading
 * spinners at both crawl edges, and the floating Jump-to-latest button with its
 * pending badge. Stateless: every data source and callback is passed in by the
 * screen, and the screen owns all scroll state / animation flags (RFP §8, §15,
 * §16). ThreadPager semantics are NOT touched — this only renders the mapped
 * [ChatListItem] list with stable keys/content types.
 */
@Composable
fun MessageList(
    listState: LazyListState,
    chatItems: List<ChatListItem>,
    isLoadingNewer: Boolean,
    isLoadingOlder: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    showJumpFab: Boolean,
    pendingNewMessagesCount: Int,
    onJumpToLatest: () -> Unit,
    shouldAnimateEntry: (Long) -> Boolean,
    onEntryAnimationFinished: (Long) -> Unit,
    onForward: (String) -> Unit,
    onPhoneClick: (String) -> Unit,
    onResend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        // ── Pull-to-refresh: drag down at the top of the thread to silently
        // re-check the provider. The newest row sits at data index 0 (visual
        // bottom in reverse layout), so a merged page never slides the row
        // being read.
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(
                    horizontal = MessagesSpacing.Md + 2.dp,
                    vertical = MessagesSpacing.Sm
                ),
                verticalArrangement = Arrangement.spacedBy(MessagesSpacing.Xs)
            ) {
                // reverseLayout: data head = visual BOTTOM (newest edge).
                if (isLoadingNewer) {
                    item(key = "newer_messages_sync") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.conv_syncing_newer),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                items(
                    items = chatItems,
                    key = ::chatItemKey,
                    contentType = { if (it is ChatListItem.DateSeparator) "date" else "message" }
                ) { item ->
                    when (item) {
                        is ChatListItem.DateSeparator -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                                    tonalElevation = 1.dp
                                ) {
                                    Text(
                                        text = item.dateText,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                        is ChatListItem.MessageItem -> {
                            val sms = item.sms
                            MessageEntrance(
                                messageId = sms.id,
                                animate = shouldAnimateEntry(sms.id),
                                outgoing = sms.type == 2,
                                modifier = Modifier.animateItem(
                                    fadeInSpec = null,
                                    fadeOutSpec = null,
                                    placementSpec = spring(
                                        dampingRatio = 1f,
                                        stiffness = 550f
                                    )
                                ),
                                onAnimationFinished = { onEntryAnimationFinished(sms.id) }
                            ) {
                                ChatBubble(
                                    sms = sms,
                                    onForward = onForward,
                                    onPhoneClick = onPhoneClick,
                                    onResend = onResend
                                )
                            }
                        }
                    }
                }
                // OLDER crawl spinner at the data tail = visual TOP edge.
                if (isLoadingOlder) {
                    item(key = "older_messages_sync") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.conv_syncing_older),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // ── Floating Jump-to-latest ──────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 14.dp, bottom = 110.dp),
            contentAlignment = Alignment.BottomEnd
        ) {
            Column {
                AnimatedVisibility(
                    visible = showJumpFab && chatItems.isNotEmpty(),
                    enter = fadeIn(MessagesMotion.Fast) + scaleIn(
                        initialScale = 0.82f,
                        animationSpec = spring(stiffness = 500f)
                    ),
                    exit = fadeOut(MessagesMotion.Fast) + scaleOut(targetScale = 0.88f)
                ) {
                    Box {
                        SmallFloatingActionButton(
                            onClick = onJumpToLatest,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowDownward,
                                contentDescription = stringResource(R.string.jump_to_latest)
                            )
                        }
                        if (pendingNewMessagesCount > 0) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 4.dp, y = (-4).dp)
                            ) {
                                Text(
                                    text = if (pendingNewMessagesCount > 99) "99+" else pendingNewMessagesCount.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
