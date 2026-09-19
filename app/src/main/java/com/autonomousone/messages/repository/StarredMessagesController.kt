package com.autonomousone.messages.repository

import com.autonomousone.messages.data.MessageKey
import com.autonomousone.messages.data.StarredMessageRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Starred messages (v3.4.0 FEATURE 7) — paged list state for BOTH the global
 * Starred browser and the in-conversation starred list.
 *
 * One class serves both because the only difference is the query: the global one
 * spans every conversation ([StarredStore.starredPage]) and the in-conversation
 * one is pinned to a thread ([StarredStore.starredPageInThread]). Both are newest
 * first and both are ACTIVE-UI filtered in SQL, so a starred message inside a
 * trashed conversation is NOT in Starred.
 *
 * Paging is bounded on purpose: a page is [PAGE_SIZE] rows and the next page is
 * requested only when the user reaches the end, so a user with 5,000 starred
 * messages never materialises 5,000 rows to open the screen.
 *
 * Android-free: the caller owns the dispatcher ([io]) and the contact-name source
 * is injected, so the whole paging contract is unit-tested on the JVM.
 */
class StarredMessagesController(
    private val contacts: suspend () -> ContactNameResolver,
    private val coroutineScope: CoroutineScope,
    private val io: CoroutineContext = Dispatchers.IO,
    private val pageSize: Int = PAGE_SIZE
) {

    /** One paged query: newest first, ACTIVE-UI filtered. */
    fun interface Pages {
        suspend fun page(limit: Int, offset: Int): List<StarredMessageRow>
    }

    /**
     * Which list this controller drives.
     *
     * A distinct [Thread] case (rather than one optional threadId) means the
     * in-conversation screen cannot accidentally page the global list, and the
     * global screen cannot forget to pin a thread.
     */
    sealed interface PageSource {
        /** Every conversation, newest first. */
        data class Global(val pages: Pages) : PageSource

        /** One conversation, newest first. */
        data class Thread(val threadId: Long, val pages: Pages) : PageSource
    }

    /** The list state. [done] means "the last page came back short / empty". */
    data class UiState(
        val loading: Boolean = false,
        val loadingMore: Boolean = false,
        val items: List<StarredMessageItem> = emptyList(),
        val done: Boolean = false,
        val error: String? = null
    ) {
        /** Nothing starred: the screen shows its Empty state, not a spinner. */
        val isEmpty: Boolean get() = !loading && error == null && items.isEmpty()

        /** True while the first page is on its way and nothing is shown yet. */
        val isInitialLoading: Boolean get() = loading && items.isEmpty()
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var resolver: ContactNameResolver = ContactMapNameResolver.EMPTY
    private var offset: Int = 0
    private var source: PageSource? = null

    /**
     * Loads page 0 for [source].
     *
     * The contact map is read ONCE per page (not per row), so a 50-row page costs
     * one contact lookup and one database query.
     */
    fun load(source: PageSource) {
        this.source = source
        offset = 0
        _state.value = UiState(loading = true)
        launchPage(reset = true)
    }

    /** Next page. A no-op while a load is in flight or the end was reached. */
    fun loadMore() {
        val current = _state.value
        if (source == null) return
        if (current.loading || current.loadingMore || current.done) return
        launchPage(reset = false)
    }

    /** Re-reads page 0 (e.g. after the user unstars a row elsewhere). */
    fun refresh() {
        source?.let { load(it) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /**
     * Unstars [item] and removes it from the list immediately.
     *
     * The write goes through [StarredStore] with the COMPOSITE [MessageKey], never
     * a raw id. The row is dropped optimistically because paging is OFFSET-based:
     * leaving a now-unstarred row in the list would shift every later page by one
     * and silently skip a real starred message.
     */
    fun unstar(
        store: StarredStore,
        item: StarredMessageItem,
        now: Long = System.currentTimeMillis()
    ) {
        coroutineScope.launch {
            try {
                withContext(io) {
                    // `StarredMessageItem.key` is a MessageIdentity.Key (the UI
                    // identity vocabulary); the user-state writer speaks
                    // MessageKey. Both are the composite (source, providerId), and
                    // the conversion is explicit so the two types can never be
                    // silently confused.
                    store.setStarred(
                        key = MessageKey(item.key.source, item.key.providerId),
                        threadId = item.threadId,
                        starred = false,
                        now = now
                    )
                }
                _state.update { current ->
                    current.copy(
                        items = current.items.filterNot { it.listKey == item.listKey },
                        error = null
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _state.update { it.copy(error = "unstar: ${messageFor(error)}") }
            }
        }
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private fun launchPage(reset: Boolean) {
        val requestedOffset = if (reset) 0 else offset
        val requestedSource = source ?: return
        // Resolve the paged query OUTSIDE the coroutine: a sealed type smart-cast
        // is not stable across a suspension point, so capturing the concrete
        // `pages` object keeps the dispatch unambiguous.
        val pages = when (requestedSource) {
            is PageSource.Global -> requestedSource.pages
            is PageSource.Thread -> requestedSource.pages
        }
        coroutineScope.launch {
            _state.update {
                if (reset) it.copy(loading = true, error = null, done = false)
                else it.copy(loadingMore = true, error = null)
            }
            try {
                resolver = withContext(io) { contacts() }
                val rows = withContext(io) { pages.page(pageSize, requestedOffset) }
                val page = StarredMessagePresenter.presentationAll(rows, resolver)
                offset = requestedOffset + rows.size
                _state.update { current ->
                    val merged = if (reset) page else current.items + page
                    // Deduplicate by composite identity: an OFFSET page can repeat a
                    // row when state changed between two requests, and a duplicated
                    // LazyColumn key would crash the list.
                    val deduped = LinkedHashMap<String, StarredMessageItem>(merged.size)
                    merged.forEach { deduped[it.listKey] = it }
                    current.copy(
                        loading = false,
                        loadingMore = false,
                        done = rows.size < pageSize,
                        items = deduped.values.toList(),
                        error = null
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // The already-loaded page is PRESERVED: a failed "load more" must
                // not blank a list the user is reading.
                _state.update {
                    it.copy(loading = false, loadingMore = false, error = messageFor(error))
                }
            }
        }
    }

    private fun messageFor(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName

    companion object {
        /** Rows per page: cheap enough to fetch, large enough to fill a screen. */
        const val PAGE_SIZE = 50
    }
}
