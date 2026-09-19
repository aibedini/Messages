package com.autonomousone.messages

import com.autonomousone.messages.repository.ConversationSearchHighlight
import com.autonomousone.messages.repository.ConversationSearchNavigation
import com.autonomousone.messages.repository.SearchDebounce
import com.autonomousone.messages.repository.SearchWindowPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.4.0 FEATURE 1 — the Android-free logic that decides WHEN a query runs,
 * HOW BIG a jump window may be, where ↑/↓ land, and WHICH characters are the
 * match.
 *
 * These are the parts that are easy to get subtly wrong and impossible to see
 * in a screenshot, so they are pinned here rather than only through the UI.
 */
class InConversationSearchLogicTest {

    // ── Debounce policy ─────────────────────────────────────────────────────

    @Test
    fun `one character never reaches the index`() {
        assertFalse(SearchDebounce.isExecutable("a"))
        assertFalse(SearchDebounce.isExecutable("7"))
        assertFalse(SearchDebounce.isExecutable("ک"))
        // Whitespace does not count toward the minimum.
        assertFalse(SearchDebounce.isExecutable("a "))
        assertFalse(SearchDebounce.isExecutable("  a  "))
    }

    @Test
    fun `two characters execute`() {
        assertTrue(SearchDebounce.isExecutable("ab"))
        assertTrue(SearchDebounce.isExecutable("ab "))
        assertTrue(SearchDebounce.isExecutable("  ab  "))
        assertTrue(SearchDebounce.isExecutable("کتاب"))
    }

    @Test
    fun `blank queries are skipped, not executed as empty`() {
        assertFalse(SearchDebounce.isExecutable(""))
        assertFalse(SearchDebounce.isExecutable("   "))
        assertFalse(SearchDebounce.isExecutable("\n\t"))
        assertEquals("blank", SearchDebounce.skipReason("  "))
        assertEquals("too-short(1)", SearchDebounce.skipReason("x"))
    }

    @Test
    fun `debounce window is within the specified 250-300 ms band`() {
        assertTrue(SearchDebounce.DEBOUNCE_MS in 250L..300L)
        assertEquals(2, SearchDebounce.MIN_QUERY_LENGTH)
    }

    // ── Window arithmetic ───────────────────────────────────────────────────

    @Test
    fun `window plan never exceeds the visible cap`() {
        val plan = SearchWindowPlan.around(
            before = SearchWindowPlan.MAX_VISIBLE_ROWS,
            after = SearchWindowPlan.MAX_VISIBLE_ROWS
        )
        assertEquals(SearchWindowPlan.MAX_VISIBLE_ROWS, plan.maxVisibleRows)
        // The probe row is requested on top of each half and is never painted.
        assertTrue(plan.maxVisibleRows <= SearchWindowPlan.MAX_VISIBLE_ROWS)
    }

    @Test
    fun `default window is a bounded page, not a conversation`() {
        val plan = SearchWindowPlan.around()
        assertTrue(plan.maxVisibleRows in 2..SearchWindowPlan.MAX_VISIBLE_ROWS)
        assertEquals(plan.visibleBefore + 1, plan.beforeQueryLimit)
        assertEquals(plan.visibleAfter + 1, plan.afterQueryLimit)
    }

    @Test
    fun `negative requests clamp to zero at a boundary`() {
        val plan = SearchWindowPlan.around(before = -5, after = 4)
        assertEquals(0, plan.visibleBefore)
        assertEquals(4, plan.visibleAfter)
        assertEquals(5, plan.maxVisibleRows)
    }

    @Test
    fun `the hard cap wins even for an absurd request`() {
        val plan = SearchWindowPlan.around(before = 10_000, after = 10_000, maxVisibleRows = 41)
        // maxVisibleRows is a CAP, not a default: asking for 41 still yields the hard
        // maximum of 40 painted rows (the anchor plus 39 context rows). The test used
        // to assert BOTH `maxVisibleRows == 40` and `visibleBefore == 40`, which cannot
        // both hold — 1 anchor + 40 + anything would be 42.
        assertEquals(SearchWindowPlan.MAX_VISIBLE_ROWS, plan.maxVisibleRows)
        assertEquals(0, plan.visibleAfter)
        assertEquals(SearchWindowPlan.MAX_VISIBLE_ROWS - 1, plan.visibleBefore)
    }

    // ── Hit-index navigation ────────────────────────────────────────────────

    @Test
    fun `next and previous WRAP at both ends`() {
        assertEquals(1, ConversationSearchNavigation.next(0, 3))
        assertEquals(2, ConversationSearchNavigation.next(1, 3))
        // ↓ from the last hit returns to the first.
        assertEquals(0, ConversationSearchNavigation.next(2, 3))
        // ↑ from the first hit goes to the last.
        assertEquals(2, ConversationSearchNavigation.previous(0, 3))
        assertEquals(0, ConversationSearchNavigation.previous(1, 3))
    }

    @Test
    fun `a single hit has nowhere to go and stays put`() {
        assertEquals(0, ConversationSearchNavigation.next(0, 1))
        assertEquals(0, ConversationSearchNavigation.previous(0, 1))
    }

    @Test
    fun `no hits means no position`() {
        assertEquals(ConversationSearchNavigation.NO_INDEX, ConversationSearchNavigation.next(-1, 0))
        assertEquals(ConversationSearchNavigation.NO_INDEX, ConversationSearchNavigation.previous(0, 0))
        assertEquals(ConversationSearchNavigation.NO_INDEX, ConversationSearchNavigation.initial(0))
        assertEquals(0, ConversationSearchNavigation.displayOrdinal(-1, 0))
    }

    @Test
    fun `an out-of-range index is recovered instead of crashing`() {
        assertEquals(0, ConversationSearchNavigation.next(9, 3))
        assertEquals(0, ConversationSearchNavigation.previous(9, 3))
    }

    @Test
    fun `counter ordinal is one-based and matches the counter format`() {
        // "3 of 14" → index 2 with 14 total.
        assertEquals(3, ConversationSearchNavigation.displayOrdinal(2, 14))
        assertEquals(1, ConversationSearchNavigation.displayOrdinal(0, 14))
        assertEquals(14, ConversationSearchNavigation.displayOrdinal(13, 14))
    }

    // ── Highlight ranges ────────────────────────────────────────────────────

    @Test
    fun `highlight finds every literal occurrence case-insensitively`() {
        val ranges = ConversationSearchHighlight.ranges("Invoice total: invoice paid", "invoice")
        assertEquals(2, ranges.size)
        assertEquals(0, ranges[0].start)
        assertEquals(7, ranges[0].end)
        assertEquals(15, ranges[1].start)
        assertEquals(22, ranges[1].end)
    }

    @Test
    fun `highlight treats FTS operators as literal text`() {
        // An FTS operator is DATA to the highlighter, never a pattern: the query is
        // matched literally, and case-insensitively.
        val body = "either this or that"
        assertTrue(ConversationSearchHighlight.hasMatch(body, "OR"))
        val ranges = ConversationSearchHighlight.ranges(body, "OR")
        assertTrue("a literal query must highlight something", ranges.isNotEmpty())
        // Every returned range must land on real text and stay inside the body.
        ranges.forEach { range ->
            assertTrue(range.start >= 0)
            assertTrue(range.end <= body.length)
            assertTrue(range.start < range.end)
            assertEquals("or", body.substring(range.start, range.end).lowercase())
        }
    }

    @Test
    fun `highlight is empty when the body does not contain the query`() {
        assertTrue(ConversationSearchHighlight.ranges("hello", "zzz").isEmpty())
        assertTrue(ConversationSearchHighlight.ranges("hello", "  ").isEmpty())
        assertTrue(ConversationSearchHighlight.ranges("", "hello").isEmpty())
    }

    @Test
    fun `persian ZWNJ is folded so a match is still highlighted`() {
        // "می‌روم" (with ZWNJ) must be highlighted when the user typed "میروم".
        // "من " = 3 chars, then the 5-letter token plus its ZWNJ = 6 chars.
        val body = "من می\u200Cروم خانه"
        val ranges = ConversationSearchHighlight.ranges(body, "میروم")
        assertEquals(1, ranges.size)
        assertEquals(3, ranges[0].start)
        assertEquals(9, ranges[0].end)
    }

    @Test
    fun `a longer token is not split into overlapping sub-matches`() {
        // The query has ONE token, "aba". Matching it against "abab" must produce the
        // single real occurrence [0,3) — not a cascade of overlapping sub-matches that
        // would paint more of the bubble than the user searched for.
        val ranges = ConversationSearchHighlight.ranges("abab", "aba ba")
        assertEquals(1, ranges.size)
        assertEquals(0, ranges[0].start)
        assertEquals(3, ranges[0].end)
    }

    @Test
    fun `ranges are ascending and non-overlapping`() {
        val ranges = ConversationSearchHighlight.ranges("a b a b a", "a b")
        for (i in 1 until ranges.size) {
            assertTrue(ranges[i].start >= ranges[i - 1].end)
        }
    }
}
