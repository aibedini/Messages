package com.autonomousone.messages

import com.autonomousone.messages.data.ConversationSearchHit
import com.autonomousone.messages.data.FtsQuery
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.repository.ConversationSearchDao
import com.autonomousone.messages.repository.InConversationSearchRepository
import com.autonomousone.messages.repository.MessageEntityView
import com.autonomousone.messages.repository.MessageIdentity
import com.autonomousone.messages.repository.SearchDebounce
import com.autonomousone.messages.repository.SearchJumpWindow
import com.autonomousone.messages.repository.SearchOutcome
import com.autonomousone.messages.repository.SearchWindowPlan
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.4.0 FEATURE 1 — the in-conversation search CONTRACT, asserted against a
 * fake DAO.
 *
 * The fake is the point: it records the exact call shape (thread id, MATCH
 * expression, LIMIT/OFFSET, window anchors) so the properties that keep a
 * 100K-message conversation usable are proven without Room or a device:
 *
 *  - search is pinned to ONE thread;
 *  - the MATCH expression is built by [FtsQuery] — never raw user input;
 *  - jump-to-message resolves ONE composite identity and reads TWO bounded
 *    keyset windows, never a sequential page walk and never a full list;
 *  - a trashed row (individually trashed, or hidden by a thread tombstone) is
 *    never surfaced;
 *  - keystrokes inside the debounce quiet period collapse into ONE query.
 */
class InConversationSearchRepositoryTest {

    // ── Fake DAO ────────────────────────────────────────────────────────────

    private data class SearchCall(
        val thread: Long,
        val match: String,
        val limit: Int,
        val offset: Int
    )

    private data class WindowCall(
        val thread: Long,
        val date: Long,
        val source: String,
        val providerId: Long,
        val limit: Int,
        val after: Boolean
    )

    /**
     * In-memory stand-in for the Room adapter. It mirrors the DAO predicates —
     * ACTIVE-UI filtering is applied here exactly as the SQL does, so "a trashed
     * hit is never surfaced" is a property of the pair under test rather than an
     * assumption.
     */
    private inner class FakeDao : ConversationSearchDao {

        val rows = mutableListOf<MessageEntityView>()
        val trashed = mutableSetOf<MessageIdentity.Key>()
        val tombstones = mutableMapOf<Long, Long>()

        val searchCalls = mutableListOf<SearchCall>()
        val countCalls = mutableListOf<Pair<Long, String>>()
        val findCalls = mutableListOf<Pair<String, Long>>()
        val windowCalls = mutableListOf<WindowCall>()

        override suspend fun searchThread(
            threadId: Long,
            match: String,
            limit: Int,
            offset: Int
        ): List<ConversationSearchHit> {
            searchCalls += SearchCall(threadId, match, limit, offset)
            return activeRows(threadId)
                .filter { matches(it.body, match) }
                .sortedWith(
                    compareByDescending<MessageEntityView> { it.date }
                        .thenByDescending { it.source }
                        .thenByDescending { it.providerId }
                )
                .drop(offset)
                .take(limit)
                .map {
                    ConversationSearchHit(
                        source = it.source,
                        providerId = it.providerId,
                        threadId = it.threadId,
                        body = it.body,
                        date = it.date
                    )
                }
        }

        override suspend fun countThreadMatches(threadId: Long, match: String): Int {
            countCalls += threadId to match
            return activeRows(threadId).count { matches(it.body, match) }
        }

        override suspend fun findActiveMessage(
            source: String,
            providerId: Long
        ): MessageEntityView? {
            findCalls += source to providerId
            return activeRows(0L).firstOrNull {
                it.source == source && it.providerId == providerId
            }
        }

        override suspend fun windowBefore(
            threadId: Long,
            date: Long,
            source: String,
            providerId: Long,
            limit: Int
        ): List<MessageEntityView> {
            windowCalls += WindowCall(threadId, date, source, providerId, limit, after = false)
            return activeRows(threadId)
                .filter { row ->
                    row.date < date ||
                        (row.date == date && row.source < source) ||
                        (row.date == date && row.source == source && row.providerId <= providerId)
                }
                .sortedWith(
                    compareByDescending<MessageEntityView> { it.date }
                        .thenByDescending { it.source }
                        .thenByDescending { it.providerId }
                )
                .take(limit)
        }

        override suspend fun windowAfter(
            threadId: Long,
            date: Long,
            source: String,
            providerId: Long,
            limit: Int
        ): List<MessageEntityView> {
            windowCalls += WindowCall(threadId, date, source, providerId, limit, after = true)
            return activeRows(threadId)
                .filter { row ->
                    row.date > date ||
                        (row.date == date && row.source > source) ||
                        (row.date == date && row.source == source && row.providerId > providerId)
                }
                .sortedWith(
                    compareBy<MessageEntityView> { it.date }
                        .thenBy { it.source }
                        .thenBy { it.providerId }
                )
                .take(limit)
        }

        /** ACTIVE UI: no individually-trashed row, no tombstone-hidden row. */
        private fun activeRows(threadId: Long): List<MessageEntityView> =
            rows.filter { row ->
                val key = MessageIdentity.Key(row.source, row.providerId)
                key !in trashed && !hiddenByTombstone(row)
            }.filter { threadId == 0L || it.threadId == threadId }

        private fun hiddenByTombstone(row: MessageEntityView): Boolean {
            val cutoff = tombstones[row.threadId] ?: return false
            return row.date < cutoff
        }

        /** Deliberately dumb token match; the MATCH SYNTAX is asserted separately. */
        private fun matches(body: String, match: String): Boolean {
            val terms = match.split('"').filter { it.isNotBlank() }
            if (terms.isEmpty()) return false
            return terms.all { body.contains(it, ignoreCase = true) }
        }
    }

    // ── Fixture ─────────────────────────────────────────────────────────────

    private val thread = 7L
    private val otherThread = 99L

    private fun row(
        source: String,
        providerId: Long,
        body: String,
        date: Long,
        threadId: Long = 7L
    ) = MessageEntityView(
        source = source,
        providerId = providerId,
        threadId = threadId,
        body = body,
        date = date,
        rawAddress = "+989120000000",
        normalizedAddress = "+989120000000",
        type = 1,
        read = true,
        status = -1,
        dateSent = 0
    )

    /**
     * Thread 7 holds 500 older history rows plus a handful of `invoice` hits,
     * TWO of which share provider id 100 across sources, and one that is
     * individually trashed. Thread 99 also matches and must never leak in.
     */
    private fun daoWithFixture(): FakeDao {
        val dao = FakeDao()
        // History rows use provider ids in 1001..1500. The `(source, providerId)`
        // key is GLOBAL (the provider `_id` is unique per source across the whole
        // table), so seeding ids 1..500 would collide with the deliberate
        // SMS-100/MMS-100 pair below and silently overwrite one of them.
        (1..500L).forEach { i ->
            dao.rows += row(
                "sms",
                HISTORY_ID_BASE + i,
                "history $i",
                date = 1_000_000L + i * 1_000L
            )
        }
        dao.rows += row("sms", 501L, "the invoice is attached", date = 1_600_000L)
        dao.rows += row("sms", 502L, "another invoice copy", date = 1_700_000L)
        dao.rows += row("mms", 900L, "invoice photo", date = 1_800_000L)
        dao.rows += row("sms", 503L, "tail after the hits", date = 1_900_000L)
        // SMS 100 and MMS 100 are DIFFERENT messages inside the SAME thread.
        dao.rows += row("sms", 100L, "invoice sms hundred", date = 2_000_000L)
        dao.rows += row("mms", 100L, "invoice mms hundred", date = 2_100_000L)
        dao.rows += row("sms", 777L, "invoice but trashed", date = 2_200_000L)
        dao.trashed += MessageIdentity.Key("sms", 777L)
        // Another conversation that also matches.
        dao.rows += row(
            "sms",
            42L,
            "invoice in the other conversation",
            date = 3_000_000L,
            threadId = otherThread
        )
        return dao
    }

    private fun repository(dao: FakeDao) =
        InConversationSearchRepository(dao, log = { _, _ -> })

    private fun canonical() = compareBy<Sms> { it.date }
        .thenBy { MessageIdentity.sourceOf(it.id) }
        .thenBy { MessageIdentity.providerIdOf(it.id) }

    private fun SearchJumpWindow.anchorId(): Long =
        rows.first { MessageIdentity.keyOf(it.id) == key }.id

    // ── Query execution ─────────────────────────────────────────────────────

    @Test
    fun `a query shorter than two characters never touches the database`() = runTest {
        val dao = daoWithFixture()
        val repo = repository(dao)

        val outcome = repo.search(thread, "i")

        assertTrue(outcome is SearchOutcome.Skipped)
        assertTrue(dao.searchCalls.isEmpty())
        assertTrue(dao.countCalls.isEmpty())
    }

    @Test
    fun `a blank query never touches the database`() = runTest {
        val dao = daoWithFixture()
        val repo = repository(dao)

        assertTrue(repo.search(thread, "   ") is SearchOutcome.Skipped)
        assertTrue(dao.searchCalls.isEmpty())
    }

    @Test
    fun `the match expression is built by FtsQuery and never concatenated from input`() = runTest {
        val dao = daoWithFixture()
        val repo = repository(dao)
        val raw = "\" OR * : NEAR("

        repo.search(thread, raw)

        val call = dao.searchCalls.single()
        assertEquals(FtsQuery.build(raw), call.match)
        // Every token is quoted, so the operator soup is literal TEXT.
        assertTrue(call.match.startsWith("\""))
        assertFalse(call.match.contains(" NEAR("))
        // The counter uses the exact same expression as the page.
        assertEquals(call.match, dao.countCalls.single().second)
    }

    @Test
    fun `search is pinned to one thread and never surfaces another conversation`() = runTest {
        val dao = daoWithFixture()
        val repo = repository(dao)

        val outcome = repo.search(thread, "invoice") as SearchOutcome.Completed

        assertEquals(listOf(thread), dao.searchCalls.map { it.thread })
        assertEquals(thread, dao.countCalls.single().first)
        assertTrue(outcome.page.hits.isNotEmpty())
        assertTrue(outcome.page.hits.all { it.threadId == thread })
        assertTrue(outcome.page.hits.none { it.body.contains("other conversation") })
    }

    @Test
    fun `an empty result set is a Completed page with zero hits`() = runTest {
        val dao = daoWithFixture()
        val repo = repository(dao)

        val outcome = repo.search(thread, "zzzzz-no-such-token") as SearchOutcome.Completed

        assertEquals(0, outcome.page.total)
        assertTrue(outcome.page.hits.isEmpty())
    }

    @Test
    fun `the result page is bounded regardless of how many messages match`() = runTest {
        val dao = FakeDao()
        (1..5_000L).forEach { i -> dao.rows += row("sms", i, "invoice $i", date = i) }
        val repo = repository(dao)

        val outcome = repo.search(thread, "invoice") as SearchOutcome.Completed

        assertEquals(InConversationSearchRepository.MAX_PAGE, dao.searchCalls.single().limit)
        assertEquals(0, dao.searchCalls.single().offset)
        assertEquals(InConversationSearchRepository.MAX_PAGE, outcome.page.hits.size)
        // The counter still reports the true total.
        assertEquals(5_000, outcome.page.total)
    }

    @Test
    fun `a trashed hit is never surfaced`() = runTest {
        val dao = daoWithFixture()
        val repo = repository(dao)

        val outcome = repo.search(thread, "trashed") as SearchOutcome.Completed

        assertEquals(0, outcome.page.total)
        assertTrue(outcome.page.hits.isEmpty())
    }

    @Test
    fun `a conversation tombstone hides its snapshot from search`() = runTest {
        val dao = daoWithFixture()
        dao.tombstones[thread] = 1_600_000L
        val repo = repository(dao)

        val outcome = repo.search(thread, "invoice") as SearchOutcome.Completed

        assertTrue(outcome.page.total > 0)
        assertTrue(outcome.page.hits.all { it.date > 1_600_000L })
    }

    @Test
    fun `paging asks for the next bounded page`() = runTest {
        val dao = FakeDao()
        (1..100L).forEach { i -> dao.rows += row("sms", i, "invoice $i", date = i) }
        val repo = repository(dao)

        repo.loadMore(thread, "invoice", offset = InConversationSearchRepository.MAX_PAGE)

        val call = dao.searchCalls.single()
        assertEquals(InConversationSearchRepository.MAX_PAGE, call.limit)
        assertEquals(InConversationSearchRepository.MAX_PAGE, call.offset)
    }

    // ── Composite identity + jump ───────────────────────────────────────────

    @Test
    fun `SMS 100 and MMS 100 resolve to different messages`() = runTest {
        val dao = daoWithFixture()
        val repo = repository(dao)

        val smsWindow = repo.jumpToMessage("sms", 100L, thread)
        val mmsWindow = repo.jumpToMessage("mms", 100L, thread)

        assertNotNull(smsWindow)
        assertNotNull(mmsWindow)
        assertEquals(MessageIdentity.Key("sms", 100L), smsWindow!!.key)
        assertEquals(MessageIdentity.Key("mms", 100L), mmsWindow!!.key)
        // Model ids keep the app-wide convention: SMS positive, MMS negated —
        // so navigating to one can never land on the other.
        assertEquals(100L, smsWindow.anchorId())
        assertEquals(-100L, mmsWindow.anchorId())
        assertEquals(
            "invoice sms hundred",
            smsWindow.rows.single { it.id == 100L }.message
        )
        assertEquals(
            "invoice mms hundred",
            mmsWindow.rows.single { it.id == -100L }.message
        )
        // The two windows were read with the two different sources.
        assertEquals(
            setOf("sms", "mms"),
            dao.windowCalls.map { it.source }.toSet()
        )
    }

    @Test
    fun `jump resolves the exact row then reads two bounded keyset windows`() = runTest {
        val dao = daoWithFixture()
        val repo = repository(dao)
        val plan = SearchWindowPlan.around()

        val window = repo.jumpToMessage("sms", 501L, thread)!!

        // ONE primary-key resolution, by composite identity.
        assertEquals(listOf("sms" to 501L), dao.findCalls)
        // Exactly two window reads: one older (inclusive of the anchor), one
        // newer (strictly after). Never a sequential page walk.
        assertEquals(2, dao.windowCalls.size)
        val older = dao.windowCalls.single { !it.after }
        val newer = dao.windowCalls.single { it.after }
        assertEquals(1_600_000L, older.date)
        assertEquals("sms", older.source)
        assertEquals(501L, older.providerId)
        assertEquals(1_600_000L, newer.date)
        assertEquals(thread, older.thread)
        assertEquals(thread, newer.thread)
        // The probe row is requested on top of the painted half.
        assertEquals(plan.beforeQueryLimit, older.limit)
        assertEquals(plan.afterQueryLimit, newer.limit)
        // The assembled window is the anchor plus both painted halves.
        assertEquals(1 + plan.visibleBefore + plan.visibleAfter, window.rows.size)
        // The anchor is inside it exactly once.
        assertEquals(1, window.rows.count { it.id == 501L })
    }

    @Test
    fun `jump-to-result window is capped at 40 rows even in a huge thread`() = runTest {
        val dao = FakeDao()
        // 100,000 ACTIVE messages with the target exactly in the middle.
        (1..100_000L).forEach { i -> dao.rows += row("sms", i, "m$i", date = i) }
        val repo = repository(dao)

        val window = repo.jumpToMessage("sms", 50_000L, thread)!!

        assertTrue(
            "window must be <= ${SearchWindowPlan.MAX_VISIBLE_ROWS} rows, was ${window.rows.size}",
            window.rows.size <= SearchWindowPlan.MAX_VISIBLE_ROWS
        )
        // One resolution + two bounded window reads. NO full-list call, NO
        // OFFSET walk, NO per-message query.
        assertEquals(1, dao.findCalls.size)
        assertEquals(2, dao.windowCalls.size)
        assertTrue(dao.windowCalls.all { it.limit <= SearchWindowPlan.MAX_VISIBLE_ROWS })
        assertTrue(dao.searchCalls.isEmpty())
        // The anchor is present exactly once, and the rows are canonical ASC.
        assertEquals(1, window.rows.count { it.id == 50_000L })
        assertEquals(window.rows.sortedWith(canonical()), window.rows)
    }

    @Test
    fun `jump reports which sides still have history to crawl`() = runTest {
        val dao = FakeDao()
        (1..100L).forEach { i -> dao.rows += row("sms", i, "m$i", date = i) }
        val repo = repository(dao)

        // Middle: both directions have more than the probe row.
        val middle = repo.jumpToMessage("sms", 50L, thread)!!
        assertTrue(middle.hasOlder)
        assertTrue(middle.hasNewer)

        // Newest row in the thread: nothing newer exists.
        val newest = repo.jumpToMessage("sms", 100L, thread)!!
        assertFalse(newest.hasNewer)
        assertTrue(newest.hasOlder)
    }

    @Test
    fun `jumping to a row that does not exist returns null`() = runTest {
        val dao = daoWithFixture()
        val repo = repository(dao)

        assertNull(repo.jumpToMessage("sms", 4_000L, thread))
    }

    @Test
    fun `jumping to a trashed hit returns null instead of resurrecting it`() = runTest {
        val dao = daoWithFixture()
        val repo = repository(dao)

        val window = repo.jumpToMessage("sms", 777L, thread)

        assertNull(window)
        // No window read is attempted for a row the user deleted.
        assertTrue(dao.windowCalls.isEmpty())
    }

    @Test
    fun `a thread id of zero falls back to the resolved row's own thread`() = runTest {
        val dao = daoWithFixture()
        val repo = repository(dao)

        val window = repo.jumpToMessage("sms", 501L, threadId = 0L)

        assertNotNull(window)
        assertEquals(listOf(thread, thread), dao.windowCalls.map { it.thread })
    }

    // ── Debounce ────────────────────────────────────────────────────────────

    @Test
    fun `keystrokes inside the quiet period collapse into one query`() = runTest {
        val dao = daoWithFixture()
        val queries = Channel<String>(Channel.UNLIMITED)
        val repo = repository(dao)
        val collected = mutableListOf<SearchOutcome>()

        val job = launch { repo.outcomes(thread, queries.receiveAsFlow()).toList(collected) }
        // The collector must be SUBSCRIBED before the first keystroke, or the
        // channel has no receiver yet.
        runCurrent()

        // All three keystrokes land inside ONE quiet period: "in" joins the
        // collector, then "inv" and "invo" arrive at +100 ms and +200 ms — before
        // the 280 ms deadline. Only the LAST value may reach FTS.
        queries.trySend("in")
        advanceTimeBy(100)
        queries.trySend("inv")
        advanceTimeBy(100)
        queries.trySend("invo")
        // Move past the quiet period with NO further keystrokes.
        advanceTimeBy(SearchDebounce.DEBOUNCE_MS + 50)
        runCurrent()

        queries.close()
        job.join()

        assertEquals("only the settled keystroke may execute", 1, collected.size)
        assertEquals("invo", (collected.single() as SearchOutcome.Completed).query)
        assertEquals(1, dao.searchCalls.size)
        assertEquals(FtsQuery.build("invo"), dao.searchCalls.single().match)
    }

    @Test
    fun `a query that settles past the quiet period executes exactly once`() = runTest {
        val dao = daoWithFixture()
        val repo = repository(dao)

        val outcomes = repo.outcomes(thread, flowOf("invoice")).toList()

        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single() is SearchOutcome.Completed)
        assertEquals(1, dao.searchCalls.size)
    }

    @Test
    fun `the shipped debounce policy is pinned inside the 250-300 ms band`() {
        assertTrue(SearchDebounce.DEBOUNCE_MS in 250L..300L)
        assertEquals(2, SearchDebounce.MIN_QUERY_LENGTH)
    }

    private companion object {
        /**
         * Provider ids for the history rows, far from the deliberate ids used by the
         * named fixture rows (100, 501-503, 777, 900). The identity is the GLOBAL
         * `(source, providerId)` pair, so any overlap silently replaced a row.
         */
        const val HISTORY_ID_BASE = 1_000L
    }
}
