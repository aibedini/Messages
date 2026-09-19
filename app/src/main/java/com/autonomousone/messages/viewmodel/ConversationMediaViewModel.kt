package com.autonomousone.messages.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autonomousone.messages.data.MessageAssetKind
import com.autonomousone.messages.media.AssetPaging
import com.autonomousone.messages.media.LinkExtractor
import com.autonomousone.messages.media.MessageAssetBackfillWorker
import com.autonomousone.messages.repository.MessageAssetRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The three tabs of the Media / Links / Files browser. */
enum class MediaTab(val kind: MessageAssetKind) {
    MEDIA(MessageAssetKind.MEDIA),
    LINKS(MessageAssetKind.LINK),
    FILES(MessageAssetKind.FILE)
}

/**
 * One renderable row. Deliberately NOT the Room entity: the LINKS tab also shows
 * a snippet derived from the source message body, and the UI should never be able
 * to reach a raw provider value it did not ask for.
 */
data class MediaItem(
    val assetKey: String,
    val kind: MessageAssetKind,
    val value: String,
    val mimeType: String,
    val displayName: String,
    val date: Long,
    val snippet: String = ""
)

/**
 * Backing state for [com.autonomousone.messages.ui.screens.ConversationMediaScreen].
 *
 * Paging rules: newest first, [AssetPaging.PAGE_SIZE] rows per page, offsets
 * tracked PER TAB so switching tabs never re-fetches what is already loaded, and
 * an explicit end-of-list flag so scrolling cannot issue unbounded queries.
 *
 * Counts are observed live from `countByKind`, so the tab badges stay correct
 * while the screen is open even though the loaded page is a snapshot.
 */
class ConversationMediaViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MessageAssetRepository(application)

    val items = mutableStateListOf<MediaItem>()

    val counts = mutableStateMapOf<MediaTab, Int>().apply {
        MediaTab.entries.forEach { put(it, 0) }
    }

    var selectedTab by mutableStateOf(MediaTab.MEDIA)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var isLoadingMore by mutableStateOf(false)
        private set
    var endReached by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    private var threadId = 0L
    private var opened = false
    private var countJob: Job? = null
    private var loadJob: Job? = null

    private val cache = mutableMapOf<MediaTab, List<MediaItem>>()
    private val offsets = mutableMapOf<MediaTab, Int>()
    private val ends = mutableMapOf<MediaTab, Boolean>()

    /** True only for the "loading the first page of an empty tab" state. */
    val showLoading: Boolean get() = isLoading && items.isEmpty()

    /** True only when the tab genuinely has nothing to show. */
    val showEmpty: Boolean
        get() = !isLoading && errorMessage == null && items.isEmpty()

    fun open(threadId: Long) {
        if (threadId <= 0L) return
        if (opened && this.threadId == threadId) return
        this.threadId = threadId
        opened = true
        observeCounts(threadId)
        // Pre-existing history is covered by the checkpointed, bounded sweep —
        // never synchronously, and never as a side effect of scrolling.
        MessageAssetBackfillWorker.scheduleOnce(getApplication())
        loadFirstPage(selectedTab)
    }

    fun selectTab(tab: MediaTab) {
        if (selectedTab == tab && items.isNotEmpty()) return
        selectedTab = tab
        val cached = cache[tab]
        if (cached != null) {
            items.clear()
            items.addAll(cached)
            endReached = ends[tab] ?: false
            errorMessage = null
            isLoading = false
            return
        }
        loadFirstPage(tab)
    }

    /** Explicit refresh of the visible tab (top-bar action). */
    fun refresh() {
        cache.remove(selectedTab)
        offsets.remove(selectedTab)
        ends.remove(selectedTab)
        loadFirstPage(selectedTab)
    }

    fun retry() {
        errorMessage = null
        loadFirstPage(selectedTab)
    }

    fun consumeError() {
        errorMessage = null
    }

    fun loadMore() {
        val tab = selectedTab
        if (isLoading || isLoadingMore || (ends[tab] ?: true)) return
        if (items.isEmpty()) return
        isLoadingMore = true
        viewModelScope.launch {
            try {
                val offset = offsets[tab] ?: items.size
                val page = fetch(tab, offset, AssetPaging.PAGE_SIZE)
                if (selectedTab == tab) {
                    items.addAll(page)
                    offsets[tab] = offset + page.size
                    ends[tab] = AssetPaging.reachedEnd(page.size, AssetPaging.PAGE_SIZE)
                    endReached = ends[tab] ?: false
                    cache[tab] = items.toList()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                errorMessage = t.message
            } finally {
                isLoadingMore = false
            }
        }
    }

    private fun observeCounts(threadId: Long) {
        countJob?.cancel()
        countJob = viewModelScope.launch {
            MediaTab.entries.forEach { tab ->
                launch {
                    repository.observeCount(threadId, tab.kind).collect { value ->
                        counts[tab] = value
                    }
                }
            }
        }
    }

    private fun loadFirstPage(tab: MediaTab) {
        loadJob?.cancel()
        errorMessage = null
        isLoading = true
        loadJob = viewModelScope.launch {
            try {
                val page = fetch(tab, offset = 0, limit = AssetPaging.PAGE_SIZE)
                cache[tab] = page
                offsets[tab] = page.size
                ends[tab] = AssetPaging.reachedEnd(page.size, AssetPaging.PAGE_SIZE)
                if (selectedTab == tab) {
                    items.clear()
                    items.addAll(page)
                    endReached = ends[tab] ?: false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (selectedTab == tab) {
                    items.clear()
                    errorMessage = t.message
                }
            } finally {
                if (selectedTab == tab) isLoading = false
            }
        }
    }

    private suspend fun fetch(tab: MediaTab, offset: Int, limit: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            when (tab) {
                MediaTab.LINKS -> repository.pageLinks(threadId, offset, limit).map { row ->
                    MediaItem(
                        assetKey = row.assetKey,
                        kind = MessageAssetKind.from(row.kind),
                        value = row.value,
                        mimeType = row.mimeType,
                        displayName = row.displayName,
                        date = row.date,
                        // Local derivation only: no page is ever fetched.
                        snippet = LinkExtractor.snippetFor(row.body.orEmpty())
                    )
                }

                else -> repository.page(threadId, tab.kind, offset, limit).map { entity ->
                    MediaItem(
                        assetKey = entity.assetKey,
                        kind = MessageAssetKind.from(entity.kind),
                        value = entity.value,
                        mimeType = entity.mimeType,
                        displayName = entity.displayName,
                        date = entity.date
                    )
                }
            }
        }
}
