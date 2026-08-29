package com.autonomousone.messages.repository

import android.content.Context
import android.provider.Telephony
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.repository.ContactRepository
import kotlin.math.abs

/**
 * Reads one provider table for the pager. Production talks to the Telephony
 * provider; unit tests substitute an in-memory fake so keyset semantics can
 * be verified without an Android ContentResolver (PART AZ).
 */
internal interface ThreadMessageSource {
    fun querySms(selection: String, selectionArgs: Array<String>, sortOrder: String, limit: Int): List<Sms>
    fun queryMms(selection: String, selectionArgs: Array<String>, sortOrder: String, limit: Int): List<Sms>
}

/** Real source: SmsRepository's raw bounded queries. */
internal class ProviderThreadMessageSource(context: Context) : ThreadMessageSource {
    private val repo = SmsRepository(context)

    override fun querySms(selection: String, selectionArgs: Array<String>, sortOrder: String, limit: Int) =
        repo.querySmsRaw(selection, selectionArgs, sortOrder, limit)

    override fun queryMms(selection: String, selectionArgs: Array<String>, sortOrder: String, limit: Int) =
        repo.queryMmsRaw(selection, selectionArgs, sortOrder, limit)
}

/**
 * Paged loader for a single conversation thread.
 *
 * V3: BIDIRECTIONAL keyset pagination.
 *
 * The window can be anchored at either boundary and crawl toward the other:
 *
 *   loadLatest()  → newest page, then loadOlder() crawls back in time
 *   loadOldest()  → true first messages (provider ASC, never Room — the
 *                   shadow may still be backfilling), then loadNewer()
 *                   crawls forward
 *
 * Every keyset step is O(page size) — "skip the first N rows" (OFFSET) is
 * never used, so a ten-year-old thread costs the same to open, to jump
 * inside, and to walk as a two-message one.
 *
 * Canonical invariant: every public method RETURNS oldest→newest (ASC). The
 * ViewModel and UI never see a DESC list; descending order exists only
 * inside a provider query and is flipped before returning.
 *
 * SMS and MMS keep INDEPENDENT cursors and exhaustion flags: one merged
 * cursor let the source with the newer tail skip the other's rows forever.
 */
class ThreadPager private constructor(
    private val source: ThreadMessageSource,
    val threadId: Long,
    private val phone: String = ""
) {
    constructor(context: Context, threadId: Long, phone: String = "") :
        this(ProviderThreadMessageSource(context), threadId, phone)

    companion object {
        /**
         * Unit-test seam (PART AZ): drive the pager from an in-memory fake
         * source. PRIVATE primary constructor + this factory keeps exactly
         * ONE public constructor candidate — `getApplication()` at real call
         * sites resolves generically without overload ambiguity.
         */
        internal fun forTesting(source: ThreadMessageSource, threadId: Long, phone: String = "") =
            ThreadPager(source, threadId, phone)

        /**
         * Rows fetched PER SOURCE on a boundary page (the one that gates the
         * paint on open, and the Go-to-first-message page). SMS and MMS are
         * queried independently, so the worst-case read is 2 × 12 rows.
         */
        const val INITIAL_PER_SOURCE = 12

        /** Rows per source on every interior page (user-initiated scroll). */
        const val PAGE_PER_SOURCE = 40

        /** Old name kept for callers/tests written against v2.6.5. */
        const val OLDER_PAGE = PAGE_PER_SOURCE
    }

    // ── Older-direction cursors (keyset: date DESC, _id DESC) ─────────────
    private var olderSmsDate = Long.MAX_VALUE
    private var olderSmsId = Long.MAX_VALUE
    private var olderMmsDate = Long.MAX_VALUE
    private var olderMmsId = Long.MAX_VALUE
    private var olderSmsExhausted = false
    private var olderMmsExhausted = false

    // ── Newer-direction cursors (keyset: date ASC, _id ASC) ───────────────
    private var newerSmsDate = Long.MIN_VALUE
    private var newerSmsId = Long.MIN_VALUE
    private var newerMmsDate = Long.MIN_VALUE
    private var newerMmsId = Long.MIN_VALUE
    private var newerSmsExhausted = false
    private var newerMmsExhausted = false

    /** True when the older direction still has rows to pull. */
    @Volatile
    var hasOlder: Boolean = true
        private set

    /** True when the newer direction still has rows (only after loadOldest). */
    @Volatile
    var hasNewer: Boolean = false
        private set

    /** Compatibility alias used by existing call sites of v2.6.5. */
    val hasMore: Boolean get() = hasOlder

    // ── Phone-only selection (threadId == 0) ──────────────────────────────
    // Query by ADDRESS suffix instead of a bogus THREAD_ID = 0 selection,
    // which always returned an empty page.
    private val smsSelection: String =
        if (threadId > 0L || phone.isBlank())
            "${Telephony.Sms.THREAD_ID} = ?"
        else {
            val norm = ContactRepository.normalizePhone(phone)
            val digits = norm.takeLast(if (norm.length >= 7) 7 else norm.length)
            "(substr(${Telephony.Sms.ADDRESS}, -${digits.length}) = ? OR ${Telephony.Sms.ADDRESS} = ?)"
        }

    private val smsArgs: Array<String> =
        when {
            threadId > 0L -> arrayOf(threadId.toString())
            phone.isBlank() -> arrayOf("0")
            else -> {
                val norm = ContactRepository.normalizePhone(phone)
                val digits = norm.takeLast(if (norm.length >= 7) 7 else norm.length)
                arrayOf(digits, phone)
            }
        }

    // ── Public boundary + crawl API (all @Synchronized; all return ASC) ───

    /**
     * Newest window: `ORDER BY date DESC LIMIT 12` per source, flipped to
     * ASC. Resets the older crawl and clears the newer direction — a fresh
     * open is always a fresh LATEST window.
     */
    @Synchronized
    fun loadLatest(): List<Sms> {
        resetOlderCursor()
        hasNewer = false
        val page = loadOlderPage(INITIAL_PER_SOURCE)
        hasOlder = !(olderSmsExhausted && (olderMmsExhausted || threadId <= 0L))
        return page
    }

    /** Next OLDER page from the current keyset position. ASC output. */
    @Synchronized
    fun loadOlder(): List<Sms> {
        if (!hasOlder) return emptyList()
        val page = loadOlderPage(PAGE_PER_SOURCE)
        if (page.isEmpty()) {
            hasOlder = false
            return emptyList()
        }
        hasOlder = !(olderSmsExhausted && (olderMmsExhausted || threadId <= 0L))
        return page
    }

    /**
     * The TRUE first messages: provider `ORDER BY date ASC LIMIT 12` per
     * source. Room is deliberately NOT consulted — the shadow may still be
     * backfilling, and "Go to first message" must be correct even at
     * Historical backfill 184,500 / 360,000. ASC output; arms the newer
     * direction for loadNewer().
     */
    @Synchronized
    fun loadOldest(): List<Sms> {
        resetNewerCursor()
        hasOlder = false

        val sms = source.querySms(
            selection = smsSelection,
            selectionArgs = smsArgs,
            sortOrder = "${Telephony.Sms.DATE} ASC, ${Telephony.Sms._ID} ASC",
            limit = INITIAL_PER_SOURCE
        )
        newerSmsExhausted = sms.size < INITIAL_PER_SOURCE

        // MMS without a real thread id: same rule as the older direction.
        val mms = if (threadId > 0L) {
            source.queryMms(
                selection = "${Telephony.Mms.THREAD_ID} = ?",
                selectionArgs = arrayOf(threadId.toString()),
                sortOrder = "${Telephony.Mms.DATE} ASC, ${Telephony.Mms._ID} ASC",
                limit = INITIAL_PER_SOURCE
            )
        } else emptyList()
        if (threadId > 0L) newerMmsExhausted = mms.size < INITIAL_PER_SOURCE

        // Newer cursors land on the NEWEST row of this oldest page (last in
        // each ASC list). MMS rows carry a negative model id (provider id
        // negated by the cursor mapper) — abs() restores the real _id. MMS
        // DATE is modelled in ms by queryMmsRaw, so cursors stay in ms.
        sms.lastOrNull()?.let { newerSmsDate = it.date; newerSmsId = it.id }
        mms.lastOrNull()?.let { newerMmsDate = it.date; newerMmsId = abs(it.id) }

        hasNewer = !(newerSmsExhausted && (newerMmsExhausted || threadId <= 0L))
        return mergeAscending(sms, mms)
    }

    /** Next NEWER page (only meaningful after loadOldest). ASC output. */
    @Synchronized
    fun loadNewer(): List<Sms> {
        if (!hasNewer) return emptyList()
        val page = loadNewerPage(PAGE_PER_SOURCE)
        if (page.isEmpty()) {
            hasNewer = false
            return emptyList()
        }
        hasNewer = !(newerSmsExhausted && (newerMmsExhausted || threadId <= 0L))
        return page
    }

    /**
     * Refreshes the TAIL (for new incoming/outgoing while chat is open).
     * Cheap bounded query for rows strictly newer than what we already hold.
     * ASC output. Does not touch the crawl cursors.
     */
    @Synchronized
    fun loadNewerSince(newestDate: Long): List<Sms> {
        val sms = source.querySms(
            selection = "($smsSelection) AND ${Telephony.Sms.DATE} > ?",
            selectionArgs = smsArgs + newestDate.toString(),
            sortOrder = "${Telephony.Sms.DATE} ASC, ${Telephony.Sms._ID} ASC",
            limit = 100
        )
        val mms = if (threadId > 0L) {
            source.queryMms(
                selection = "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.DATE} > ?",
                selectionArgs = arrayOf(threadId.toString(), (newestDate / 1000L).toString()),
                sortOrder = "${Telephony.Mms.DATE} ASC, ${Telephony.Mms._ID} ASC",
                limit = 100
            )
        } else emptyList()
        return mergeAscending(sms, mms)
    }

    /**
     * Re-reads visible SMS rows by provider id. A status callback changes the
     * row in place (its DATE is unchanged), so a strictly-newer tail query
     * cannot observe PENDING -> SENT/DELIVERED/FAILED transitions.
     */
    fun loadSmsRowsById(ids: Collection<Long>): List<Sms> {
        val unique = ids.filter { it > 0L }.distinct()
        if (unique.isEmpty()) return emptyList()
        val placeholders = unique.joinToString(",") { "?" }
        return source.querySms(
            selection = "${Telephony.Sms._ID} IN ($placeholders)",
            selectionArgs = unique.map(Long::toString).toTypedArray(),
            sortOrder = "${Telephony.Sms.DATE} ASC",
            limit = unique.size
        )
    }

    @Deprecated("Use loadLatest()", ReplaceWith("loadLatest()"))
    fun loadFirstPage(): List<Sms> = loadLatest()

    // ── internals ──────────────────────────────────────────────────────────

    private fun resetOlderCursor() {
        olderSmsDate = Long.MAX_VALUE; olderSmsId = Long.MAX_VALUE
        olderMmsDate = Long.MAX_VALUE; olderMmsId = Long.MAX_VALUE
        olderSmsExhausted = false; olderMmsExhausted = false
    }

    private fun resetNewerCursor() {
        newerSmsDate = Long.MIN_VALUE; newerSmsId = Long.MIN_VALUE
        newerMmsDate = Long.MIN_VALUE; newerMmsId = Long.MIN_VALUE
        newerSmsExhausted = false; newerMmsExhausted = false
    }

    /** One OLDER keyset step. Provider answers DESC; we return ASC. */
    private fun loadOlderPage(limit: Int): List<Sms> {
        val keysetSelection = if (olderSmsDate < Long.MAX_VALUE) {
            "($smsSelection) AND (" +
                "${Telephony.Sms.DATE} < ? OR " +
                "(${Telephony.Sms.DATE} = ? AND ${Telephony.Sms._ID} < ?))"
        } else {
            smsSelection
        }
        val keysetArgs = if (olderSmsDate < Long.MAX_VALUE) {
            smsArgs + arrayOf(olderSmsDate.toString(), olderSmsDate.toString(), olderSmsId.toString())
        } else {
            smsArgs
        }
        val sms = source.querySms(
            selection = keysetSelection,
            selectionArgs = keysetArgs,
            sortOrder = "${Telephony.Sms.DATE} DESC, ${Telephony.Sms._ID} DESC",
            limit = limit
        )
        olderSmsExhausted = sms.size < limit

        val mms = if (threadId > 0L) {
            val mmsKeysetSelection = if (olderMmsDate < Long.MAX_VALUE) {
                "(${Telephony.Mms.THREAD_ID} = ?) AND (" +
                    "${Telephony.Mms.DATE} < ? OR " +
                    "(${Telephony.Mms.DATE} = ? AND ${Telephony.Mms._ID} < ?))"
            } else {
                "${Telephony.Mms.THREAD_ID} = ?"
            }
            val mmsKeysetArgs = if (olderMmsDate < Long.MAX_VALUE) {
                val lastDateSeconds = olderMmsDate / 1000L
                arrayOf(threadId.toString(), lastDateSeconds.toString(), lastDateSeconds.toString(), olderMmsId.toString())
            } else {
                arrayOf(threadId.toString())
            }
            source.queryMms(
                selection = mmsKeysetSelection,
                selectionArgs = mmsKeysetArgs,
                sortOrder = "${Telephony.Mms.DATE} DESC, ${Telephony.Mms._ID} DESC",
                limit = limit
            )
        } else emptyList()
        if (threadId > 0L) olderMmsExhausted = mms.size < limit

        // Advance EACH source's cursor to the oldest row IT returned.
        sms.lastOrNull()?.let { olderSmsDate = it.date; olderSmsId = it.id }
        mms.lastOrNull()?.let { olderMmsDate = it.date; olderMmsId = abs(it.id) }

        return mergeDescending(sms, mms).asReversed() // provider DESC → canonical ASC
    }

    /** One NEWER keyset step. Provider answers ASC; we return ASC. */
    private fun loadNewerPage(limit: Int): List<Sms> {
        val keysetSelection = if (newerSmsDate > Long.MIN_VALUE) {
            "($smsSelection) AND (" +
                "${Telephony.Sms.DATE} > ? OR " +
                "(${Telephony.Sms.DATE} = ? AND ${Telephony.Sms._ID} > ?))"
        } else {
            smsSelection
        }
        val keysetArgs = if (newerSmsDate > Long.MIN_VALUE) {
            smsArgs + arrayOf(newerSmsDate.toString(), newerSmsDate.toString(), newerSmsId.toString())
        } else {
            smsArgs
        }
        val sms = source.querySms(
            selection = keysetSelection,
            selectionArgs = keysetArgs,
            sortOrder = "${Telephony.Sms.DATE} ASC, ${Telephony.Sms._ID} ASC",
            limit = limit
        )
        newerSmsExhausted = sms.size < limit

        val mms = if (threadId > 0L) {
            val mmsKeysetSelection = if (newerMmsDate > Long.MIN_VALUE) {
                "(${Telephony.Mms.THREAD_ID} = ?) AND (" +
                    "${Telephony.Mms.DATE} > ? OR " +
                    "(${Telephony.Mms.DATE} = ? AND ${Telephony.Mms._ID} > ?))"
            } else {
                "${Telephony.Mms.THREAD_ID} = ?"
            }
            val mmsKeysetArgs = if (newerMmsDate > Long.MIN_VALUE) {
                val lastDateSeconds = newerMmsDate / 1000L
                arrayOf(threadId.toString(), lastDateSeconds.toString(), lastDateSeconds.toString(), newerMmsId.toString())
            } else {
                arrayOf(threadId.toString())
            }
            source.queryMms(
                selection = mmsKeysetSelection,
                selectionArgs = mmsKeysetArgs,
                sortOrder = "${Telephony.Mms.DATE} ASC, ${Telephony.Mms._ID} ASC",
                limit = limit
            )
        } else emptyList()
        if (threadId > 0L) newerMmsExhausted = mms.size < limit

        sms.lastOrNull()?.let { newerSmsDate = it.date; newerSmsId = it.id }
        mms.lastOrNull()?.let { newerMmsDate = it.date; newerMmsId = abs(it.id) }

        return mergeAscending(sms, mms)
    }

    /** Interleave two date-DESC lists into one date-DESC list. */
    private fun mergeDescending(a: List<Sms>, b: List<Sms>): List<Sms> {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val out = ArrayList<Sms>(a.size + b.size)
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            val takeA = when {
                i >= a.size -> false
                j >= b.size -> true
                else -> a[i].date >= b[j].date
            }
            if (takeA) out.add(a[i++]) else out.add(b[j++])
        }
        return out
    }

    /** Interleave two date-ASC lists into one date-ASC list. */
    private fun mergeAscending(a: List<Sms>, b: List<Sms>): List<Sms> {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val out = ArrayList<Sms>(a.size + b.size)
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            val takeA = when {
                i >= a.size -> false
                j >= b.size -> true
                else -> a[i].date <= b[j].date
            }
            if (takeA) out.add(a[i++]) else out.add(b[j++])
        }
        return out
    }
}
