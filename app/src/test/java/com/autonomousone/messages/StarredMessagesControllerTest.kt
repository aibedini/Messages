package com.autonomousone.messages

import com.autonomousone.messages.data.MessageKey
import com.autonomousone.messages.data.MessageUserStateEntity
import com.autonomousone.messages.data.StarredMessageRow
import com.autonomousone.messages.repository.ContactNameResolver
import com.autonomousone.messages.repository.MessageIdentity
import com.autonomousone.messages.repository.StarredMessageItem
import com.autonomousone.messages.repository.StarredMessagePresenter
import com.autonomousone.messages.repository.StarredMessagesController
import com.autonomousone.messages.repository.StarredStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Starred messages paging + presentation (v3.4.0 FEATURE 7).
 *
 * The controller is Android-free, so the whole paging contract is pinned here:
 * bounded pages, newest-first order preserved, duplicate suppression (an OFFSET
 * page can repeat a row after a state change, and a repeated LazyColumn key would
 * crash the list), optimistic unstar with the COMPOSITE identity, and the rule
 * that a failed "load more" never blanks a list the user is reading.
 */
class StarredMessagesControllerTest {

    private val threadId = 7L

    private class FakeStore : StarredStore {
        val writes = mutableListOf<Triple<MessageKey, Long, Boolean>>()
        var failure: Throwable? = null

        override suspend fun isStarred(source: String, providerId: Long): Boolean = false

        override fun observeStarred(source: String, providerId: Long): Flow<MessageUserStateEntity?> =
            flowOf(null)

        override suspend fun setStarred(
            key: MessageKey,
            threadId: Long,
            starred: Boolean,
            now: Long
        ) {
            failure?.let { throw it }
            writes.add(Triple(key, threadId, starred))
        }

        override suspend fun starredPage(limit: Int, offset: Int): List<StarredMessageRow> =
            emptyList()

        override suspend fun starredPageInThread(
            threadId: Long,
            limit: Int,
            offset: Int
        ): List<StarredMessageRow> = emptyList()

        override fun observeStarredCountInThread(threadId: Long): Flow<Int> = flowOf(0)
    }

    private fun row(source: String, providerId: Long, date: Long, thread: Long = 7L) =
        StarredMessageRow(
            source = source,
            providerId = providerId,
            threadId = thread,
            body = "body $providerId",
            date = date,
            rawAddress = "+989121234567",
            normalizedAddress = "+989121234567",
            starredAt = date
        )

    private fun controller(
        scope: CoroutineScope,
        pageSize: Int = 2,
        page: (Int, Int) -> List<StarredMessageRow>
    ) = StarredMessagesController(
        contacts = { ContactNameResolver { raw, _ -> if (raw.isBlank()) "?" else "Ali" } },
        coroutineScope = scope,
        io = Dispatchers.Unconfined,
        pageSize = pageSize
    ).also { it.load(StarredMessagesController.PageSource.Global { limit, offset -> page(limit, offset) }) }

    private fun scope() = CoroutineScope(Dispatchers.Unconfined + Job())

    // ── Paging ──────────────────────────────────────────────────────────────

    @Test
    fun `first page is bounded and more pages are offered only while rows remain`() {
        val calls = mutableListOf<Pair<Int, Int>>()
        val rows = listOf(row("sms", 3, 300), row("sms", 2, 200), row("sms", 1, 100))
        val controller = controller(scope()) { limit, offset ->
            calls.add(limit to offset)
            rows.drop(offset).take(limit)
        }

        val first = controller.state.value
        assertEquals(2, first.items.size)
        assertFalse("a full page means there may be more", first.done)
        assertEquals(listOf(2 to 0), calls)

        controller.loadMore()
        val second = controller.state.value
        assertEquals(3, second.items.size)
        assertTrue("a short page is the end", second.done)
        assertEquals(listOf(2 to 0, 2 to 2), calls)
    }

    @Test
    fun `loadMore after the end is a no-op`() {
        val calls = mutableListOf<Pair<Int, Int>>()
        val controller = controller(scope(), pageSize = 5) { limit, offset ->
            calls.add(limit to offset)
            listOf(row("sms", 1, 100))
        }
        assertTrue(controller.state.value.done)
        controller.loadMore()
        controller.loadMore()
        assertEquals("no extra query once the end is known", 1, calls.size)
    }

    @Test
    fun `page order is preserved exactly as the DAO returned it`() {
        val controller = controller(scope(), pageSize = 10) { _, _ ->
            listOf(row("sms", 3, 300), row("mms", 3, 300), row("sms", 2, 200))
        }
        assertEquals(
            listOf("sms:3", "mms:3", "sms:2"),
            controller.state.value.items.map { it.listKey }
        )
    }

    @Test
    fun `a repeated row across pages is deduplicated`() {
        // An OFFSET page can repeat a row when state changed between requests; a
        // repeated LazyColumn key would crash the list.
        val controller = controller(scope()) { _, offset ->
            if (offset == 0) listOf(row("sms", 3, 300), row("sms", 2, 200))
            else listOf(row("sms", 2, 200), row("sms", 1, 100))
        }
        controller.loadMore()
        assertEquals(
            listOf("sms:3", "sms:2", "sms:1"),
            controller.state.value.items.map { it.listKey }
        )
    }

    @Test
    fun `empty list is empty state rather than an endless spinner`() {
        val controller = controller(scope()) { _, _ -> emptyList() }
        val state = controller.state.value
        assertTrue(state.isEmpty)
        assertFalse(state.isInitialLoading)
        assertTrue(state.done)
        assertNull(state.error)
    }

    @Test
    fun `a failed load reports an error`() {
        val controller = controller(scope()) { _, _ -> throw IllegalStateException("db closed") }
        val state = controller.state.value
        assertEquals("db closed", state.error)
        assertFalse(state.loading)
        assertTrue(state.items.isEmpty())
    }

    @Test
    fun `a failed load more keeps the rows already shown`() {
        var fail = false
        val controller = controller(scope()) { _, offset ->
            if (fail && offset > 0) throw IllegalStateException("db closed")
            listOf(row("sms", 3, 300), row("sms", 2, 200))
        }
        assertEquals(2, controller.state.value.items.size)

        fail = true
        controller.loadMore()
        val state = controller.state.value
        assertEquals("the user keeps reading what was already loaded", 2, state.items.size)
        assertEquals("db closed", state.error)

        controller.clearError()
        assertNull(controller.state.value.error)
    }

    // ── Thread scoping ──────────────────────────────────────────────────────

    @Test
    fun `thread scope pages the in-conversation list`() {
        val seen = mutableListOf<Long>()
        val controller = StarredMessagesController(
            contacts = { ContactNameResolver { raw, _ -> raw } },
            coroutineScope = scope(),
            io = Dispatchers.Unconfined,
            pageSize = 10
        )
        controller.load(
            StarredMessagesController.PageSource.Thread(threadId) { limit, offset ->
                seen.add(threadId)
                listOf(row("sms", 1, 100))
            }
        )
        assertEquals(listOf(threadId), seen)
        assertEquals(1, controller.state.value.items.size)
    }

    // ── Unstar ──────────────────────────────────────────────────────────────

    @Test
    fun `unstar writes the composite key and drops the row optimistically`() {
        val store = FakeStore()
        val controller = controller(scope(), pageSize = 10) { _, _ ->
            listOf(row("sms", 100, 300), row("mms", 100, 200))
        }
        val target = controller.state.value.items.first()

        controller.unstar(store, target, now = 55L)

        assertEquals(1, store.writes.size)
        assertEquals(MessageKey("sms", 100L), store.writes[0].first)
        assertEquals(threadId, store.writes[0].second)
        assertFalse(store.writes[0].third)
        assertEquals(
            "the unstarred row is gone; MMS 100 stays",
            listOf("mms:100"),
            controller.state.value.items.map { it.listKey }
        )
    }

    @Test
    fun `a failed unstar keeps the row and reports the error`() {
        val store = FakeStore().apply { failure = IllegalStateException("read only") }
        val controller = controller(scope(), pageSize = 10) { _, _ -> listOf(row("sms", 100, 300)) }
        val target = controller.state.value.items.first()

        controller.unstar(store, target)

        assertEquals(1, controller.state.value.items.size)
        assertEquals("unstar: read only", controller.state.value.error)
    }

    // ── Presentation ────────────────────────────────────────────────────────

    @Test
    fun `presentation keeps the composite identity and falls back to the address`() {
        val item = StarredMessagePresenter.presentation(
            row("mms", 100, 1_700_000_000_000L),
            ContactNameResolver { _, _ -> "" }
        )
        assertEquals(MessageIdentity.Key("mms", 100L), item.key)
        assertEquals("sms:100" == item.listKey, false)
        assertEquals("mms:100", item.listKey)
        assertEquals("+989121234567", item.displayName)
        assertEquals(threadId, item.threadId)
    }

    @Test
    fun `snippets collapse whitespace and stay bounded`() {
        assertEquals("a b c", StarredMessagePresenter.snippet("a\n\n b\tc "))
        assertEquals(StarredMessagePresenter.NO_TEXT, StarredMessagePresenter.snippet("   "))
        val long = "x".repeat(StarredMessagePresenter.SNIPPET_MAX + 50)
        val clipped = StarredMessagePresenter.snippet(long)
        assertEquals(StarredMessagePresenter.SNIPPET_MAX + 1, clipped.length)
        assertTrue(clipped.endsWith("…"))
    }

    @Test
    fun `an item built by hand keeps a stable list key`() {
        val item = StarredMessageItem(
            key = MessageIdentity.Key("sms", 7L),
            threadId = 3L,
            body = "x",
            timestamp = 1L,
            formattedDate = "",
            displayName = "Ali",
            starredAt = 1L
        )
        assertEquals("sms:7", item.listKey)
    }
}
