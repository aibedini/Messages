package com.autonomousone.messages.repository

import android.content.Context
import android.provider.Telephony
import android.util.Log
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.repository.ContactRepository

/**
 * Paged loader for a single conversation thread.
 *
 * V2: Keyset (cursor) pagination instead of OFFSET.
 *
 * Instead of "skip the first N rows" (O(N) on every page), we remember the
 * last seen (date, id) and query WHERE date < :lastDate. This is O(1)
 * regardless of how deep the user has scrolled — even at offset 250,000.
 *
 * SMS and MMS are merged per-page: we fetch the newest `limit` of each source
 * and interleave by date, so the visible history stays chronologically
 * seamless across page boundaries.
 */
class ThreadPager(
    private val context: Context,
    private val threadId: Long,
    private val phone: String = ""
) {
    companion object {
        /**
         * Rows fetched PER SOURCE on the very first page (the one that gates
         * the paint on open). SMS and MMS are queried independently, so the
         * worst-case read for opening a conversation is 2 × INITIAL_PER_SOURCE
         * rows — not 80. A ten-year-old thread must cost the same to open as
         * a two-message one; anything deeper than this window is nobody's
         * business until the user scrolls up.
         */
        const val INITIAL_PER_SOURCE = 12

        /** Rows per source on every OLDER page (user-initiated scroll-up). */
        const val OLDER_PAGE = 40
    }

    // Keyset cursors: PER-SOURCE last seen (date, id). One shared cursor for
    // the merged SMS+MMS crawl skipped rows (see loadPage).
    private var lastSmsDate: Long = Long.MAX_VALUE
    private var lastSmsId: Long = Long.MAX_VALUE
    private var lastMmsDate: Long = Long.MAX_VALUE
    private var lastMmsId: Long = Long.MAX_VALUE

    /** True when either source still has older rows to pull. */
    @Volatile
    var hasMore: Boolean = true
        private set

    // Exhaustion is decided PER SOURCE: a 24-row merged page proves nothing if
    // SMS filled its quota while MMS returned 2 rows. These track whether the
    // last crawl left each source's quota unspent.
    private var smsExhausted = false
    private var mmsExhausted = false

    /**
     * Phone-only route (threadId == 0): query by ADDRESS suffix instead of a
     * bogus THREAD_ID = 0 selection, which always returned an empty page.
     */
    private val smsSelection: String =
        if (threadId > 0L || phone.isBlank())
            "${Telephony.Sms.THREAD_ID} = ?"
        else {
            val digits = ContactRepository.normalizePhone(phone)
                .takeLast(if (ContactRepository.normalizePhone(phone).length >= 7) 7 else 0)
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

    /**
     * Loads the FIRST page (newest messages). Resets the cursor.
     * Small window: this read is on the critical path of opening a
     * conversation, so it pulls INITIAL_PER_SOURCE from each source.
     */
    fun loadFirstPage(): List<Sms> {
        lastSmsDate = Long.MAX_VALUE
        lastSmsId = Long.MAX_VALUE
        lastMmsDate = Long.MAX_VALUE
        lastMmsId = Long.MAX_VALUE
        smsExhausted = false
        mmsExhausted = false
        val page = loadPage(INITIAL_PER_SOURCE)
        hasMore = !(smsExhausted && (mmsExhausted || threadId <= 0L))
        return page
    }

    /**
     * Loads the next OLDER page using keyset pagination. Returns empty when exhausted.
     */
    fun loadOlder(): List<Sms> {
        if (!hasMore) return emptyList()
        val page = loadPage(OLDER_PAGE)
        if (page.isEmpty()) {
            hasMore = false
            return emptyList()
        }
        hasMore = !(smsExhausted && (mmsExhausted || threadId <= 0L))
        return page
    }

    /**
     * Refreshes the TAIL (for new incoming/outgoing while chat is open).
     * Cheap query limited to rows newer than what we already hold.
     */
    fun loadNewerSince(newestDate: Long): List<Sms> {
        val repo = SmsRepository(context)
        val sms = repo.querySmsRaw(
            selection = "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.DATE} > ?",
            selectionArgs = arrayOf(threadId.toString(), newestDate.toString()),
            sortOrder = "${Telephony.Sms.DATE} ASC",
            limit = 100
        )
        val mms = repo.queryMmsRaw(
            selection = "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.DATE} > ?",
            selectionArgs = arrayOf(threadId.toString(), (newestDate / 1000L).toString()),
            sortOrder = "${Telephony.Mms.DATE} ASC",
            limit = 100
        )
        return merge(sms, mms)
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
        return SmsRepository(context).querySmsRaw(
            selection = "${Telephony.Sms._ID} IN ($placeholders)",
            selectionArgs = unique.map(Long::toString).toTypedArray(),
            sortOrder = "${Telephony.Sms.DATE} ASC",
            limit = unique.size
        )
    }

    // ── internals ──────────────────────────────────────────────────────────

    private fun loadPage(limit: Int): List<Sms> {
        val repo = SmsRepository(context)

        // Keyset pagination for SMS. Uses the CLASS-level smsSelection/smsArgs:
        // loadPage used to hardcode "THREAD_ID = ?" with a possibly-0 threadId
        // (the phone-only route) — every page then returned empty and the
        // shared lastDate/lastId cursor of a mixed SMS/MMS crawl skipped rows
        // whenever one source's oldest row was newer than the other's.
        // Per-source cursors fix the skip; the address selection fixes the
        // phone route.
        val keysetSelection = if (lastSmsDate < Long.MAX_VALUE) {
            "($smsSelection) AND (" +
                "${Telephony.Sms.DATE} < ? OR " +
                "(${Telephony.Sms.DATE} = ? AND ${Telephony.Sms._ID} < ?))"
        } else {
            smsSelection
        }

        val keysetArgs = if (lastSmsDate < Long.MAX_VALUE) {
            smsArgs + arrayOf(lastSmsDate.toString(), lastSmsDate.toString(), lastSmsId.toString())
        } else {
            smsArgs
        }

        val sms = repo.querySmsRaw(
            selection = keysetSelection,
            selectionArgs = keysetArgs,
            sortOrder = "${Telephony.Sms.DATE} DESC, ${Telephony.Sms._ID} DESC",
            limit = limit
        )
        smsExhausted = sms.size < limit

        // Keyset pagination for MMS (thread-based only).
        val mms = if (threadId > 0L) {
            val mmsKeysetSelection = if (lastMmsDate < Long.MAX_VALUE) {
                "(${Telephony.Mms.THREAD_ID} = ?) AND (" +
                    "${Telephony.Mms.DATE} < ? OR " +
                    "(${Telephony.Mms.DATE} = ? AND ${Telephony.Mms._ID} < ?))"
            } else {
                "${Telephony.Mms.THREAD_ID} = ?"
            }

            val mmsKeysetArgs = if (lastMmsDate < Long.MAX_VALUE) {
                val lastDateSeconds = lastMmsDate / 1000L
                arrayOf(threadId.toString(), lastDateSeconds.toString(), lastDateSeconds.toString(), lastMmsId.toString())
            } else {
                arrayOf(threadId.toString())
            }

            repo.queryMmsRaw(
                selection = mmsKeysetSelection,
                selectionArgs = mmsKeysetArgs,
                sortOrder = "${Telephony.Mms.DATE} DESC, ${Telephony.Mms._ID} DESC",
                limit = limit
            )
        } else emptyList()
        if (threadId > 0L) mmsExhausted = mms.size < limit

        // Advance EACH source's cursor to the oldest row IT returned. A shared
        // cursor let the source with the newer tail drag the other one back:
        // SMS rows between the merged-oldest and the SMS-oldest were skipped
        // forever (and MMS negative ids poisoned the SMS _ID < ? predicate).
        sms.lastOrNull()?.let { lastSmsDate = it.date; lastSmsId = it.id }
        mms.lastOrNull()?.let { lastMmsDate = it.date; lastMmsId = kotlin.math.abs(it.id) }

        return merge(sms, mms).asReversed() // provider gave DESC → display ASC
    }

    /** Interleave two date-DESC lists into one date-DESC list. */
    private fun merge(a: List<Sms>, b: List<Sms>): List<Sms> {
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
}
