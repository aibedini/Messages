package com.autonomousone.messages.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autonomousone.messages.R
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.ui.components.SmsItem

/**
 * A single lightweight, stable-identity row item consumed by [ConversationList].
 *
 * Home renders a row directly from the domain [Sms] plus the handful of
 * per-row flags Home already holds; no provider/contact I/O happens inside
 * the list item (RFP §8/§9). `draftKey` is resolved once by the caller, never
 * re-derived per recomposition.
 */
data class HomeRow(
    val sms: Sms,
    val displayName: String,
    val draftKey: String,
    val draftText: String,
    val isPinned: Boolean,
    val isArchived: Boolean,
    val showYouMarker: Boolean,
)

/**
 * Conversation list + search scaffolding for Home. Owns the LazyColumn, the
 * pull-to-refresh, the "direct send to number" shortcut row and the global
 * (all-messages) search result section. Stateless: every action and data
 * source is passed in by the screen (RFP §8).
 *
 * Keys/contentType are stable and namespaced (RFP §12):
 *  - conversations: `c${id}`, contentType "conversation"
 *  - search scaffolding: `direct_send`, `result_count`, `global_header`
 *  - global hits: `global_${id}`
 */
@Composable
fun ConversationList(
    listState: LazyListState,
    rows: List<HomeRow>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    search: String,
    resultCount: Int,
    showDirectSend: Boolean,
    directNumber: String,
    onDirectSend: () -> Unit,
    globalHeaderText: String,
    globalHits: List<HomeRow>,
    onGlobalHitClick: (HomeRow) -> Unit,
    onRowClick: (HomeRow) -> Unit,
    onRowPin: (HomeRow) -> Unit,
    onRowBlock: (HomeRow) -> Unit,
    onRowArchive: (HomeRow) -> Unit,
    onRowDelete: (HomeRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)
        ) {
            if (showDirectSend) {
                item(key = "direct_send") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clickable(onClick = onDirectSend),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = stringResource(R.string.home_search_send_to, directNumber),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            if (search.isNotBlank()) {
                item(key = "result_count") {
                    Text(
                        text = "$resultCount conversation${if (resultCount == 1) "" else "s"} found",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                    )
                }
            }

            if (globalHits.isNotEmpty()) {
                item(key = "global_header") {
                    Text(
                        text = globalHeaderText,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                    )
                }
                items(
                    items = globalHits,
                    key = { "global_${it.sms.id}" }
                ) { hit ->
                    SmsItem(
                        sms = hit.sms.copy(
                            message = "🔎 ${hit.sms.message.take(80)}"
                        ),
                        displayName = hit.displayName,
                        onClick = { onGlobalHitClick(hit) }
                    )
                }
            }

            items(
                items = rows,
                key = { "c${it.sms.id}" },
                contentType = { "conversation" }
            ) { row ->
                SmsItem(
                    sms = row.sms,
                    displayName = row.displayName,
                    modifier = Modifier.animateItem(
                        fadeInSpec = androidx.compose.animation.core.tween(durationMillis = 220),
                        placementSpec = androidx.compose.animation.core.spring(
                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                            stiffness = androidx.compose.animation.core.Spring.StiffnessLow
                        ),
                        fadeOutSpec = androidx.compose.animation.core.tween(durationMillis = 160)
                    ),
                    isPinned = row.isPinned,
                    isArchived = row.isArchived,
                    draftText = row.draftText,
                    showYouMarker = row.showYouMarker,
                    onClick = { onRowClick(row) },
                    onPin = { onRowPin(row) },
                    onBlock = { onRowBlock(row) },
                    onArchive = { onRowArchive(row) },
                    onDelete = { onRowDelete(row) },
                )
            }
        }
    }
}
