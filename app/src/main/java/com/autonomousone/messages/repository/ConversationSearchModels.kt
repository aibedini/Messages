package com.autonomousone.messages.repository

import com.autonomousone.messages.data.ConversationSearchHit
import com.autonomousone.messages.model.Sms
import kotlin.math.max
import kotlin.math.min

/**
 * v3.4.0 FEATURE 1 — in-conversation SEARCH RESULT paging shape.
 *
 * One page of `messages_fts` matches pinned to a single thread. `limit` is
 * always bounded by the caller ([InConversationSearchRepository.MAX_PAGE]); the
 * FTS index answers the MATCH before the join, so a page costs
 * O(matches paged through), never O(thread size).
 */
data class ConversationSearchPage(
    val hits: List<ConversationSearchHit>,
    val total: Int,
    val offset: Int
)

/**
 * The bounded window loaded around ONE search result for JUMP-TO-MESSAGE.
 *
 * [rows] is canonical ASC (`date, source, providerId`) — the exact order the
 * conversation LazyColumn paints — and never exceeds
 * [SearchWindowPlan.MAX_VISIBLE_ROWS] rows. `hasOlder`/`hasNewer` are "the
 * probe row came back", i.e. there is at least one more active message on that
 * side, so the screen can offer a crawl without a second query.
 */
data class SearchJumpWindow(
    val key: MessageIdentity.Key,
    val rows: List<Sms>,
    val hasOlder: Boolean,
    val hasNewer: Boolean
)

/**
 * Pure arithmetic for "load a small window around message X".
 *
 * The invariant that matters: `1 + before + after <= MAX_VISIBLE_ROWS`, always.
 * A jump must paint a page, not a conversation — a 100K-message thread may not
 * be materialised because the user tapped a search hit.
 */
data class SearchWindowPlan(
    val before: Int,
    val after: Int,
    /** Requested counts; each is one MORE than we paint, as the probe row. */
    val beforeQueryLimit: Int,
    val afterQueryLimit: Int
) {
    /** Rows painted for the older half, probe row excluded. */
    val visibleBefore: Int get() = before

    /** Rows painted for the newer half, probe row excluded. */
    val visibleAfter: Int get() = after

    /** Visible rows in the worst case: the anchor plus both halves. */
    val maxVisibleRows: Int get() = 1 + before + after

    companion object {

        /** Hard cap on the painted window (briefing: visible window ≤ 40). */
        const val MAX_VISIBLE_ROWS = 40

        /** Default half-width used by the ViewModel. */
        const val DEFAULT_AROUND = 18

        /**
         * Splits the budget around the anchor. A negative request is clamped to
         * zero (a boundary jump genuinely has no rows on that side); an
         * over-wide request is trimmed from the NEWER side first, because the
         * row the user searched for reads better with its preceding context.
         */
        fun around(
            before: Int = DEFAULT_AROUND,
            after: Int = DEFAULT_AROUND,
            maxVisibleRows: Int = MAX_VISIBLE_ROWS
        ): SearchWindowPlan {
            // The cap is a CAP, not a default: a caller asking for a wider window must
            // still get the hard maximum, because the whole point of the bound is that
            // no code path can materialise a 100K-message conversation.
            val bounded = maxVisibleRows.coerceAtMost(MAX_VISIBLE_ROWS)
            val budget = max(0, bounded - 1)
            var older = max(0, before)
            var newer = max(0, after)
            if (older + newer > budget) {
                val overflow = older + newer - budget
                val fromNewer = min(newer, overflow)
                newer -= fromNewer
                older -= (overflow - fromNewer)
            }
            return SearchWindowPlan(
                before = older,
                after = newer,
                // +1 row on each side is a PROBE: if it comes back there is
                // more history on that side, so the UI can offer a crawl
                // without a second COUNT.
                beforeQueryLimit = older + 1,
                afterQueryLimit = newer + 1
            )
        }
    }
}

/**
 * DB-access seam for in-conversation search.
 *
 * The production implementation ([RoomConversationSearchDao]) is a thin adapter
 * over the EXISTING `MessageFtsDao` + `MessageDao` — there is no second FTS
 * index and no second message table. Tests substitute an in-memory fake, which
 * is what lets the repository contract (bounded limit, per-thread pin, no
 * full-list call, exact composite identity) be asserted on the JVM without
 * Robolectric.
 *
 * Every method that can reach a trashed row applies the ACTIVE-UI predicates;
 * the fake in the tests mirrors that so "a trashed hit is never surfaced" is
 * pinned by a test rather than by convention.
 */
interface ConversationSearchDao {

    /** One bounded page of matches inside [threadId]. */
    suspend fun searchThread(
        threadId: Long,
        match: String,
        limit: Int,
        offset: Int
    ): List<ConversationSearchHit>

    /** Total active matches inside [threadId], for the "3 of 14" counter. */
    suspend fun countThreadMatches(threadId: Long, match: String): Int

    /** Exact ACTIVE-UI row by composite identity; null when trashed/missing. */
    suspend fun findActiveMessage(source: String, providerId: Long): MessageEntityView?

    /** ≤ [limit] rows older than (inclusive of) the anchor, newest-first. */
    suspend fun windowBefore(
        threadId: Long,
        date: Long,
        source: String,
        providerId: Long,
        limit: Int
    ): List<MessageEntityView>

    /** ≤ [limit] rows strictly newer than the anchor, oldest-first. */
    suspend fun windowAfter(
        threadId: Long,
        date: Long,
        source: String,
        providerId: Long,
        limit: Int
    ): List<MessageEntityView>
}

/**
 * Android/Room-free projection of the row fields the search window needs.
 *
 * The DAO returns Room entities; the adapter maps them here so the repository
 * (and its tests) never touch Room types and the exact composite id convention
 * (`Sms.id`: SMS positive, MMS negated) is applied in ONE place.
 */
data class MessageEntityView(
    val source: String,
    val providerId: Long,
    val threadId: Long,
    val body: String,
    val date: Long,
    val rawAddress: String,
    val normalizedAddress: String,
    val type: Int,
    val read: Boolean,
    val status: Int,
    val dateSent: Long
) {
    /** UI/model identity: mirrors MessageEntity.toSms() exactly. */
    val modelId: Long get() = if (source == MessageEntityView.SOURCE_MMS) -providerId else providerId

    val key: MessageIdentity.Key get() = MessageIdentity.Key(source, providerId)

    fun toSms(): Sms = Sms(
        id = modelId,
        threadId = threadId,
        sender = rawAddress.ifBlank { normalizedAddress },
        message = body,
        date = date,
        unread = !read,
        type = type,
        status = status,
        dateSent = dateSent
    )

    companion object {
        const val SOURCE_SMS = "sms"
        const val SOURCE_MMS = "mms"
    }
}

/**
 * Pure window assembly: canonical ASC, anchor exactly once, hard cap enforced
 * even if a misbehaving source over-delivers.
 */
object SearchWindowAssembler {

    /**
     * @param before newest-first rows OLDER than the anchor, INCLUDING the
     *   anchor itself (the DAO query is inclusive so one read carries it).
     * @param after oldest-first rows strictly NEWER than the anchor.
     */
    fun assemble(
        anchor: MessageEntityView,
        before: List<MessageEntityView>,
        after: List<MessageEntityView>,
        plan: SearchWindowPlan = SearchWindowPlan.around()
    ): SearchJumpWindow {
        val older = ArrayDeque<MessageEntityView>()
        var overflowOlder = false
        before.forEach { row ->
            if (row.key == anchor.key) return@forEach
            if (older.size < plan.visibleBefore) older.addLast(row) else overflowOlder = true
        }
        val newer = ArrayList<MessageEntityView>(plan.visibleAfter)
        var overflowNewer = false
        after.forEach { row ->
            if (row.key == anchor.key) return@forEach
            if (newer.size < plan.visibleAfter) newer.add(row) else overflowNewer = true
        }

        val rows = ArrayList<Sms>(older.size + 1 + newer.size)
        // `before` arrives newest-first; `older` was filled in that order, so
        // walking it in reverse yields the canonical ASCENDING prefix.
        older.toList().asReversed().forEach { rows.add(it.toSms()) }
        rows.add(anchor.toSms())
        newer.forEach { rows.add(it.toSms()) }

        return SearchJumpWindow(
            key = anchor.key,
            rows = rows.take(SearchWindowPlan.MAX_VISIBLE_ROWS),
            hasOlder = overflowOlder,
            hasNewer = overflowNewer
        )
    }
}
