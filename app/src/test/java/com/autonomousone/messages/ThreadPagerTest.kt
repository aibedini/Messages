package com.autonomousone.messages

import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.repository.ThreadMessageSource
import com.autonomousone.messages.repository.ThreadPager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.6.7 bidirectional pager: keyset paging against an in-memory fake of the
 * Telephony provider contract (selection + sortOrder + limit). Guards the
 * five properties the conversation-open regression depended on:
 *  - every public method returns canonical ASC (newest last);
 *  - loadLatest is a bounded newest window, never a full scan;
 *  - older/newer crawls use strictly-ordered keyset predicates (no OFFSET);
 *  - hasOlder/hasNewer exhaust correctly per source;
 *  - loadOldest arms the newer direction, and the two crawls meet in the
 *    middle without overlap or gaps.
 */
class ThreadPagerTest {

    private fun sms(id: Long, date: Long) =
        Sms(id = id, threadId = THREAD, sender = "+98912", message = "m$id", date = date, unread = false, type = 1)

    /** A fake that interprets the real selection strings we build. */
    private inner class FakeSource : ThreadMessageSource {
        var smsRows: List<Sms> = emptyList()
        var mmsRows: List<Sms> = emptyList()

        /** All selections the pager ever issued — for OFFSET/keyset assertions. */
        val smsSelections = mutableListOf<String>()

        var smsCalls = 0
        var mmsCalls = 0

        override fun querySms(
            selection: String,
            selectionArgs: Array<String>,
            sortOrder: String,
            limit: Int
        ): List<Sms> {
            smsSelections += selection
            smsCalls++
            val newestFirst = sortOrder.contains("DESC", ignoreCase = true)
            val filtered = applySmsKeyset(selection, selectionArgs)
            val sorted = if (newestFirst)
                filtered.sortedWith(compareByDescending<Sms> { it.date }.thenByDescending { it.id })
            else
                filtered.sortedWith(compareBy<Sms> { it.date }.thenBy { it.id })
            return sorted.take(limit)
        }

        override fun queryMms(
            selection: String,
            selectionArgs: Array<String>,
            sortOrder: String,
            limit: Int
        ): List<Sms> {
            mmsCalls++
            // Same keyset grammar as SMS; MMS dates in the fake stay in ms to
            // keep the assertions readable (the real mapper divides by 1000
            // only for provider args, which the pager already does).
            val newestFirst = sortOrder.contains("DESC", ignoreCase = true)
            val filtered = applyMmsKeyset(selection, selectionArgs)
            val sorted = if (newestFirst)
                filtered.sortedWith(compareByDescending<Sms> { it.date }.thenByDescending { kotlin.math.abs(it.id) })
            else
                filtered.sortedWith(compareBy<Sms> { it.date }.thenBy { kotlin.math.abs(it.id) })
            return sorted.take(limit)
        }

        private fun applySmsKeyset(selection: String, args: Array<String>): List<Sms> {
            var rows = smsRows
            // Telephony constants are lower-case column names ("date", "_id").
            when {
                selection.contains("date > ?") && selection.contains("_id > ?") -> {
                    // keyset: date > d OR (date = d AND id > i)
                    val d = args[args.size - 3].toLong()
                    val i = args[args.size - 1].toLong()
                    rows = rows.filter { it.date > d || (it.date == d && it.id > i) }
                }
                selection.contains("date > ?") -> {
                    val c = args.last().toLong()
                    rows = rows.filter { it.date > c }
                }
                selection.contains("date < ?") -> {
                    // "< date OR (= date AND id < id)"
                    val date = args[args.size - 3].toLong()
                    val id = args[args.size - 1].toLong()
                    rows = rows.filter { it.date < date || (it.date == date && it.id < id) }
                }
            }
            return rows
        }

        private fun applyMmsKeyset(selection: String, args: Array<String>): List<Sms> {
            // The pager converts MMS cursor dates to SECONDS for the provider;
            // fake rows carry ms, so convert the bound back here.
            var rows = mmsRows
            fun secondsToMs(s: String) = s.toLong() * 1000L
            when {
                selection.contains("date > ?") && selection.contains("_id > ?") -> {
                    val d = secondsToMs(args[args.size - 3])
                    val i = args[args.size - 1].toLong()
                    rows = rows.filter { it.date > d || (it.date == d && kotlin.math.abs(it.id) > i) }
                }
                selection.contains("date > ?") -> {
                    val c = secondsToMs(args.last())
                    rows = rows.filter { it.date > c }
                }
                selection.contains("date < ?") -> {
                    val date = secondsToMs(args[args.size - 3])
                    val id = args[args.size - 1].toLong()
                    rows = rows.filter { it.date < date || (it.date == date && kotlin.math.abs(it.id) < id) }
                }
            }
            return rows
        }
    }

    private val THREAD = 42L
    private val SOURCE_PAGE = 12

    @Test
    fun `loadLatest returns the newest bounded window in ASC order`() {
        val src = FakeSource().apply {
            // 30 messages: dates 1000..30000 step 1000, ids 1..30
            smsRows = (1..30).map { sms(it.toLong(), it * 1000L) }
        }
        val pager = ThreadPager.forTesting(src, THREAD)
        val page = pager.loadLatest()

        // Bounded: never more than the initial window (12 per source, no MMS).
        assertEquals(SOURCE_PAGE, page.size)
        // Canonical ASC: oldest of window first, newest of window last.
        assertEquals((19..30).map { it * 1000L }, page.map { it.date })
        assertTrue(pager.hasOlder)
        assertFalse("latest window must not arm the newer crawl", pager.hasNewer)
    }

    @Test
    fun `older crawl pages strictly below the cursor and never re-reads rows`() {
        val src = FakeSource().apply {
            smsRows = (1..30).map { sms(it.toLong(), it * 1000L) }
        }
        val pager = ThreadPager.forTesting(src, THREAD)
        val first = pager.loadLatest()               // ids 19..30
        val second = pager.loadOlder()               // PAGE_PER_SOURCE=40 → everything older
        assertEquals((1..18).map { it.toLong() }, second.map { it.id })

        val combined = (first + second).map { it.id }
        assertEquals("no duplicates across pages", combined.size, combined.distinct().size)
        assertEquals("full thread covered", (1..30).map { it.toLong() }.sorted(), combined.sorted())
        assertFalse("exhausted older direction reports hasOlder=false", pager.hasOlder)
    }

    @Test
    fun `keyset uses date and id predicates, never OFFSET`() {
        val src = FakeSource().apply {
            smsRows = (1..60).map { sms(it.toLong(), it * 1000L) }
        }
        val pager = ThreadPager.forTesting(src, THREAD)
        pager.loadLatest()
        pager.loadOlder()
        assertTrue(
            "pager must use date-only full scans on first page, keyset afterwards",
            src.smsSelections.any { it.contains("date < ?") && it.contains("_id < ?") }
        )
        assertTrue(
            "OFFSET is banned (v2.6.7 goal #7)",
            src.smsSelections.none { it.contains("OFFSET", ignoreCase = true) }
        )
    }

    @Test
    fun `loadOldest is ASC head window and arms the newer direction`() {
        val src = FakeSource().apply {
            smsRows = (1..30).map { sms(it.toLong(), it * 1000L) }
        }
        val pager = ThreadPager.forTesting(src, THREAD)
        val head = pager.loadOldest()

        assertEquals((1..12).map { it * 1000L }, head.map { it.date })
        assertTrue(pager.hasNewer)
        assertFalse("oldest window disables the older crawl", pager.hasOlder)
    }

    @Test
    fun `newer crawl walks back to the newest row without gaps`() {
        val src = FakeSource().apply {
            smsRows = (1..30).map { sms(it.toLong(), it * 1000L) }
        }
        val pager = ThreadPager.forTesting(src, THREAD)
        val head = pager.loadOldest()                // ids 1..12
        val next = pager.loadNewer()                 // PAGE_PER_SOURCE=40 → ids 13..30
        assertEquals((13..30).map { it.toLong() }, next.map { it.id })

        val walk = (head + next).map { it.id }
        assertEquals(walk.size, walk.distinct().size)
        assertFalse(pager.hasNewer)
        // Every public call returns ASC.
        assertTrue((head + next).zipWithNext().all { (a, b) -> a.date <= b.date })
    }

    @Test
    fun `sms and mms cursors are independent and merge ascending`() {
        val src = FakeSource().apply {
            smsRows = listOf(sms(1, 1000), sms(2, 3000), sms(3, 5000))
            // MMS model ids are negative in the app's model.
            mmsRows = listOf(
                Sms(-10, THREAD, "+98912", "pic", 2000, false, 1),
                Sms(-11, THREAD, "+98912", "pic", 4000, false, 1)
            )
        }
        val pager = ThreadPager.forTesting(src, THREAD)
        val page = pager.loadLatest()
        assertEquals(listOf(1000L, 2000L, 3000L, 4000L, 5000L), page.map { it.date })

        // Both sources are shorter than the window → direction is exhausted…
        assertFalse(pager.hasOlder)
        // …and a tiny thread that has no more rows returns empty, not stale rows.
        assertTrue(pager.loadOlder().isEmpty())
    }

    @Test
    fun `exhausted mms source does not block sms pagination`() {
        val src = FakeSource().apply {
            smsRows = (1..30).map { sms(it.toLong(), it * 1000L) }
            mmsRows = listOf(Sms(-1, THREAD, "+98912", "one", 1500, false, 1))
        }
        val pager = ThreadPager.forTesting(src, THREAD)
        pager.loadLatest()
        val older = pager.loadOlder()
        // SMS kept paging (18 more) even though MMS was exhausted after page 1.
        assertTrue(older.any { it.id > 0 })
    }

    @Test
    fun `loadNewerSince returns strictly newer rows in ASC order`() {
        val src = FakeSource().apply {
            smsRows = (1..30).map { sms(it.toLong(), it * 1000L) }
        }
        val pager = ThreadPager.forTesting(src, THREAD)
        val tail = pager.loadNewerSince(28000L)
        assertEquals(listOf(29000L, 30000L), tail.map { it.date })
    }

    @Test
    fun `threadId of zero never queries the mms table`() {
        val src = FakeSource().apply {
            smsRows = listOf(sms(1, 1000))
            mmsRows = List(50) { Sms(-it.toLong(), 0L, "+98912", "m", it * 100L, false, 1) }
        }
        val pager = ThreadPager.forTesting(src, 0L, phone = "+989120000000")
        val page = pager.loadLatest()
        assertEquals(1, page.size)
        assertEquals("phone-only threads must skip MMS entirely", 0, src.mmsCalls)
        assertTrue(src.smsCalls > 0)
        // threadId<=0: MMS can never mark the direction exhausted on its own —
        // a small SMS-only thread exhausts immediately and correctly.
        assertFalse(pager.hasOlder)
    }
}
