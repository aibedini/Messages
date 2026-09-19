package com.autonomousone.messages.repository

import android.content.Context
import com.autonomousone.messages.data.ConversationSearchHit
import com.autonomousone.messages.data.FtsQuery
import com.autonomousone.messages.data.MessageDao
import com.autonomousone.messages.data.MessageEntity
import com.autonomousone.messages.data.MessageFtsDao
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.utils.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * v3.4.0 FEATURE 1 — SEARCH INSIDE ONE CONVERSATION.
 *
 * ── Why a separate repository, and what it is NOT ───────────────────────────
 * The FTS index, the thread pin and the ACTIVE-UI predicates already exist
 * (`MessageFtsDao.searchThread` / `countThreadMatches`). This class adds ONLY
 * the policy the UI needs on top of them:
 *
 *  - a debounced query flow that never runs a 1-character query;
 *  - a bounded result page ([MAX_PAGE] hits) so a 100K-message conversation is
 *    never materialised — there is no `body.contains(query)` scan and no
 *    full-conversation load anywhere in this file;
 *  - jump-to-message that resolves ONE row by composite identity and then reads
 *    a bounded keyset window around it ([SearchWindowPlan.MAX_VISIBLE_ROWS]).
 *
 * ⚠ It does NOT own an FTS index, a message table, or a paging cursor that
 * survives the query — the repository is stateless except for the injected
 * debounce delay.
 *
 * ── Identity ────────────────────────────────────────────────────────────────
 * Hits are keyed by the composite `(source, providerId)` from the DAO. SMS 100
 * and MMS 100 are different messages, they can BOTH be in one thread's results,
 * and `Sms.id` keeps the app-wide convention (SMS positive, MMS negated) via
 * [MessageEntityView.modelId]. Nothing here ever identifies a message by body
 * or timestamp.
 *
 * ── Privacy ─────────────────────────────────────────────────────────────────
 * The query body and message content are NEVER logged. [log] receives a token
 * (`queryToken`) and counts only; [DiagnosticLog.event] additionally redacts
 * anything that looks like a phone number.
 */
class InConversationSearchRepository(
    private val dao: ConversationSearchDao,
    private val delayMillis: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val log: (String, String) -> Unit = { category, message ->
        DiagnosticLog.event(category, message)
    }
) {

    companion object {

        /** Log category for every in-conversation search transition. */
        const val LOG_CATEGORY = "CONV_SEARCH"

        /**
         * Hits per page. Bounded on purpose: the search UI shows one screen of
         * results and pages on scroll, so a query matching 20 000 messages
         * still reads [MAX_PAGE] rows to fill the first screen.
         */
        const val MAX_PAGE = 30

        /** `DiagnosticLog.phoneToken`-style digest of the query, never the query. */
        fun queryToken(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return "none"
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(trimmed.toByteArray(Charsets.UTF_8))
            return digest.take(4).joinToString("") { "%02x".format(it) }
        }

        fun create(context: Context): InConversationSearchRepository =
            InConversationSearchRepository(
                RoomConversationSearchDao(
                    MessagesDatabase.get(context).messageFtsDao(),
                    MessagesDatabase.get(context).messageDao()
                )
            )
    }

    /**
     * Debounced search outcomes for [threadId].
     *
     * Emission contract (consumed by ConversationViewModel):
     *  - a value that is superseded inside [debounceMillis] is DROPPED — it
     *    never reaches FTS;
     *  - a query shorter than [SearchDebounce.MIN_QUERY_LENGTH] characters
     *    (after trimming/quiet period) emits [SearchOutcome.Skipped] — the UI
     *    returns to its idle state, it does not show "no results";
     *  - otherwise exactly one [SearchOutcome.Completed] per settled query.
     *
     * [debounceMillis] is defaulted to the shipped policy and injectable so a
     * unit test can drive the debounce with `delayMillis = {}` and no wall-clock
     * waiting, and so a future settings change is one constant away.
     */
    fun outcomes(
        threadId: Long,
        queryFlow: Flow<String>,
        debounceMillis: Long = SearchDebounce.DEBOUNCE_MS
    ): Flow<SearchOutcome> = flow {
        var previous = ""
        queryFlow.collect { raw ->
            previous = raw
            delayMillis(debounceMillis)
            if (raw != previous) return@collect
            emit(search(threadId, raw))
        }
    }

    /**
     * One settled query. Never throws for an empty/too-short query: that is a
     * [SearchOutcome.Skipped], a normal UI state.
     */
    suspend fun search(threadId: Long, raw: String): SearchOutcome {
        if (!SearchDebounce.isExecutable(raw)) {
            log(
                LOG_CATEGORY,
                "search skip thread=$threadId reason=${SearchDebounce.skipReason(raw)}"
            )
            return SearchOutcome.Skipped(raw)
        }
        // FTS syntax is BUILT, never concatenated: every token is quoted and
        // embedded quotes doubled by FtsQuery, so a query of `OR` or `a*b` is
        // literal text. An expression that comes back empty is not executed.
        val match = FtsQuery.build(raw)
        if (match.isEmpty()) {
            log(LOG_CATEGORY, "search skip thread=$threadId reason=empty-match")
            return SearchOutcome.Skipped(raw)
        }

        val page = loadPage(threadId, match, offset = 0)
        log(
            LOG_CATEGORY,
            "search thread=$threadId query=${queryToken(raw)} total=${page.total} " +
                "returned=${page.hits.size} limit=$MAX_PAGE"
        )
        return SearchOutcome.Completed(raw, page)
    }

    /**
     * One additional bounded page for infinite scroll. [offset] is a multiple
     * of the page size held by the ViewModel, so the same page is never
     * re-fetched.
     */
    suspend fun loadMore(threadId: Long, raw: String, offset: Int): SearchOutcome {
        val match = FtsQuery.build(raw)
        if (match.isEmpty()) return SearchOutcome.Skipped(raw)
        val page = loadPage(threadId, match, offset = offset.coerceAtLeast(0))
        log(
            LOG_CATEGORY,
            "search page thread=$threadId query=${queryToken(raw)} offset=${page.offset} " +
                "returned=${page.hits.size} total=${page.total}"
        )
        return SearchOutcome.Completed(raw, page)
    }

    private suspend fun loadPage(threadId: Long, match: String, offset: Int) =
        ConversationSearchPage(
            hits = dao.searchThread(threadId, match, MAX_PAGE, offset),
            total = dao.countThreadMatches(threadId, match),
            offset = offset
        )

    /**
     * JUMP TO A SEARCH RESULT — O(1) row resolution + two bounded keyset reads.
     *
     * Deliberately NOT a sequential page walk: with a 100K-message thread,
     * paging until the target appears can be thousands of round trips. Instead:
     *
     *  1. `(source, providerId)` is resolved by the primary-key lookup
     *     (`MessageDao.findActiveByKey`), which also refuses a trashed row, so
     *     a stale result can never resurrect deleted history;
     *  2. its canonical anchor `(date, source, providerId)` seeds ONE
     *     `windowBefore` (inclusive of the anchor) and ONE `windowAfter`;
     *  3. the halves are assembled into a canonical ASC window whose size is
     *     hard-capped at [SearchWindowPlan.MAX_VISIBLE_ROWS].
     *
     * Thread resolution: the caller's [threadId] wins when it is known (the
     * conversation is open, so it is); otherwise the resolved row's own thread
     * is used — a search opened from a phone-only route still lands correctly.
     */
    suspend fun jumpToMessage(
        source: String,
        providerId: Long,
        threadId: Long = 0L
    ): SearchJumpWindow? = withContext(Dispatchers.IO) {
        val anchorEntity = dao.findActiveMessage(source, providerId) ?: run {
            // Two very different causes, one safe outcome: the hit was trashed
            // between the query and the tap, or the row is gone.
            log(
                LOG_CATEGORY,
                "jump miss source=$source providerId=$providerId thread=$threadId " +
                    "reason=no-active-row"
            )
            return@withContext null
        }

        val effectiveThread = if (threadId > 0L) threadId else anchorEntity.threadId
        if (effectiveThread <= 0L) {
            log(LOG_CATEGORY, "jump skip source=$source providerId=$providerId reason=no-thread")
            return@withContext null
        }

        val plan = SearchWindowPlan.around()
        val older = dao.windowBefore(
            threadId = effectiveThread,
            date = anchorEntity.date,
            source = anchorEntity.source,
            providerId = anchorEntity.providerId,
            limit = plan.beforeQueryLimit
        )
        val newer = dao.windowAfter(
            threadId = effectiveThread,
            date = anchorEntity.date,
            source = anchorEntity.source,
            providerId = anchorEntity.providerId,
            limit = plan.afterQueryLimit
        )

        val window = SearchWindowAssembler.assemble(anchorEntity, older, newer, plan)
        log(
            LOG_CATEGORY,
            "jump thread=$effectiveThread source=$source providerId=$providerId " +
                "rows=${window.rows.size} cap=${SearchWindowPlan.MAX_VISIBLE_ROWS} " +
                "hasOlder=${window.hasOlder} hasNewer=${window.hasNewer}"
        )
        window
    }

    /**
     * One more bounded slice of history on the OLDER side of an open jump
     * window. Keeps the keyset shape: the caller passes the first (oldest) row
     * it currently paints, and the result NEVER repeats it.
     *
     * The window is opened with a sentinel far-future anchor, which is the
     * cheapest correct way to ask "the [limit] newest active rows strictly
     * older than this row" without a second DAO query.
     */
    suspend fun crawlOlder(
        threadId: Long,
        oldestDate: Long,
        oldestSource: String,
        oldestProviderId: Long,
        limit: Int = SearchWindowPlan.DEFAULT_AROUND
    ): SearchJumpWindow? = withContext(Dispatchers.IO) {
        if (threadId <= 0L || limit <= 0) return@withContext null
        val rows = dao.windowBefore(
            threadId = threadId,
            // Sentinel: "newer than everything", combined with the strict
            // source/provider filter below it means strictly-older-than-anchor.
            date = Long.MAX_VALUE,
            source = "\uFFFF",
            providerId = Long.MAX_VALUE,
            limit = limit + 1
        ).filterNot {
            it.date > oldestDate ||
                (it.date == oldestDate && it.source == oldestSource &&
                    it.providerId >= oldestProviderId)
        }.take(limit)
        if (rows.isEmpty()) return@withContext null
        SearchJumpWindow(
            key = MessageIdentity.Key(oldestSource, oldestProviderId),
            rows = rows.map { it.toSms() }.asReversed(),
            hasOlder = rows.size >= limit,
            hasNewer = true
        )
    }
}

/** Result of one debounced query, as consumed by the ViewModel. */
sealed interface SearchOutcome {

    /** Query was blank or shorter than [SearchDebounce.MIN_QUERY_LENGTH]. */
    data class Skipped(val query: String) : SearchOutcome

    /** A bounded page (possibly zero hits) for [query]. */
    data class Completed(
        val query: String,
        val page: ConversationSearchPage
    ) : SearchOutcome
}

/**
 * Production adapter: the EXISTING `messages_fts` index for matching, the
 * EXISTING `messages` table for identity and windows. No second index is
 * created, and every read is bounded by the caller's `limit`.
 */
class RoomConversationSearchDao(
    private val ftsDao: MessageFtsDao,
    private val messageDao: MessageDao
) : ConversationSearchDao {

    override suspend fun searchThread(
        threadId: Long,
        match: String,
        limit: Int,
        offset: Int
    ): List<ConversationSearchHit> = ftsDao.searchThread(threadId, match, limit, offset)

    override suspend fun countThreadMatches(threadId: Long, match: String): Int =
        ftsDao.countThreadMatches(threadId, match)

    override suspend fun findActiveMessage(source: String, providerId: Long): MessageEntityView? =
        messageDao.findActiveByKey(source, providerId)?.toView()

    override suspend fun windowBefore(
        threadId: Long,
        date: Long,
        source: String,
        providerId: Long,
        limit: Int
    ): List<MessageEntityView> =
        messageDao.windowBefore(threadId, date, source, providerId, limit).map { it.toView() }

    override suspend fun windowAfter(
        threadId: Long,
        date: Long,
        source: String,
        providerId: Long,
        limit: Int
    ): List<MessageEntityView> =
        messageDao.windowAfter(threadId, date, source, providerId, limit).map { it.toView() }

    private fun MessageEntity.toView() = MessageEntityView(
        source = source,
        providerId = providerId,
        threadId = threadId,
        body = body,
        date = date,
        rawAddress = rawAddress,
        normalizedAddress = normalizedAddress,
        type = type,
        read = read,
        status = status,
        dateSent = dateSent
    )
}
