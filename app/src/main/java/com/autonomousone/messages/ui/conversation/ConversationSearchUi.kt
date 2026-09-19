package com.autonomousone.messages.ui.conversation

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonomousone.messages.R
import com.autonomousone.messages.data.ConversationSearchHit
import com.autonomousone.messages.repository.ConversationSearchHighlight
import com.autonomousone.messages.repository.ConversationSearchNavigation
import com.autonomousone.messages.repository.MessageIdentity
import com.autonomousone.messages.utils.formatDateHeader
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * v3.4.0 FEATURE 1 — the conversation header while search mode is on.
 *
 * `[←] [ Search messages… ] [n of m] [↑] [↓] [X]`
 *
 * The back arrow and the X both leave search mode (nothing is destroyed — the
 * conversation window is untouched); the counter is hidden while there is no
 * position to show, and the arrows are DISABLED (never a clickable no-op) when
 * the query has no matches.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationSearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onClose: () -> Unit,
    index: Int,
    total: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    TopAppBar(
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.conv_search_close)
                )
            }
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.conv_search_hint),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 16.sp
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = ImeAction.Search
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                    )
                }
            }
        },
        actions = {
            if (total > 0) {
                Text(
                    text = stringResource(
                        R.string.conv_search_counter_fmt,
                        ConversationSearchNavigation.displayOrdinal(index, total),
                        total
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = onPrevious, enabled = total > 1) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.conv_search_previous)
                    )
                }
                IconButton(onClick = onNext, enabled = total > 1) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.conv_search_next)
                    )
                }
            }
            // Shown only when there is something to clear: a permanently
            // visible X that did nothing would be a clickable no-op. The left
            // arrow is the way OUT of search mode.
            if (query.isNotEmpty()) {
                IconButton(onClick = onClearQuery) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.conv_search_clear)
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

/**
 * The search RESULT panel: Loading / Content / Empty / Error, never blank.
 *
 * @param hits newest-first, exactly the DAO order.
 * @param selectedKey the hit the ↑/↓ selection currently points at; the row is
 *   tinted so the counter and the list always agree.
 */
@Composable
fun ConversationSearchResults(
    query: String,
    isLoading: Boolean,
    hits: List<ConversationSearchHit>,
    errorMessage: String?,
    selectedKey: MessageIdentity.Key?,
    onHitClick: (ConversationSearchHit) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    // A query long enough to execute but whose page is not here yet: show the
    // progress state. A too-short query stays idle (the parent hides the panel).
    val isIdle = query.length < 2

    when {
        isIdle -> SearchIdleHint(modifier)
        errorMessage != null -> SearchErrorState(
            message = errorMessage,
            onRetry = onRetry,
            modifier = modifier
        )
        isLoading && hits.isEmpty() -> SearchLoadingState(modifier)
        hits.isEmpty() -> SearchEmptyState(query, modifier)
        else -> SearchHitList(
            query = query,
            hits = hits,
            selectedKey = selectedKey,
            onHitClick = onHitClick,
            onLoadMore = onLoadMore,
            modifier = modifier
        )
    }
}

@Composable
private fun SearchIdleHint(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.conv_search_idle_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(32.dp)
        )
    }
}

@Composable
private fun SearchLoadingState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.conv_search_searching),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SearchEmptyState(query: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = stringResource(R.string.conv_search_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.conv_search_empty_body, query),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SearchErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}

@Composable
private fun SearchHitList(
    query: String,
    hits: List<ConversationSearchHit>,
    selectedKey: MessageIdentity.Key?,
    onHitClick: (ConversationSearchHit) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Bounded paging: ask for the next page only when the user reaches the end
    // of what is loaded, so the panel never grows past what is on screen.
    LaunchedEffect(listState, hits.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (lastVisible >= hits.size - 3) onLoadMore()
            }
    }
    // Selecting a hit from the arrows scrolls the panel to it as well.
    LaunchedEffect(selectedKey) {
        val key = selectedKey ?: return@LaunchedEffect
        val index = hits.indexOfFirst { MessageIdentity.Key(it.source, it.providerId) == key }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(
            items = hits,
            key = { hit -> "hit_${hit.source}_${hit.providerId}" }
        ) { hit ->
            SearchHitRow(
                hit = hit,
                query = query,
                isSelected = selectedKey == MessageIdentity.Key(hit.source, hit.providerId),
                onClick = { onHitClick(hit) }
            )
        }
    }
}

@Composable
private fun SearchHitRow(
    hit: ConversationSearchHit,
    query: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    // Pure-logic range computation, memoised per (body, query).
    val highlightColor = MaterialTheme.colorScheme.primary
    val annotated = remember(hit.body, query, highlightColor) {
        buildHighlightedBody(hit.body, query, highlightColor)
    }

    Surface(
        onClick = onClick,
        color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
        else Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = formatDateHeader(hit.date),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = annotated,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Highlights every literal occurrence of each query token in the SURFACE
 * colour. A hit with no literal occurrence (an FTS token match across ZWNJ,
 * say) renders unhighlighted rather than wrong.
 */
internal fun buildHighlightedBody(
    body: String,
    query: String,
    highlight: Color
): AnnotatedString {
    val ranges = ConversationSearchHighlight.ranges(body, query)
    if (ranges.isEmpty()) return AnnotatedString(body)
    return buildAnnotatedString {
        var cursor = 0
        ranges.forEach { range ->
            if (range.start > cursor) append(body.substring(cursor, range.start))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = highlight)) {
                append(body.substring(range.start, range.end))
            }
            cursor = range.end
        }
        if (cursor < body.length) append(body.substring(cursor))
    }
}
