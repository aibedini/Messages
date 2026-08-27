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
        /** Rows per page. */
        const val PAGE = 40
    }

    // Keyset cursor: last seen (date, id) from the previous page.
    private var lastDate: Long = Long.MAX_VALUE
    private var lastId: Long = Long.MAX_VALUE

    /** True when either source still has older rows to pull. */
    @Volatile
    var hasMore: Boolean = true
        private set

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
     */
    fun loadFirstPage(): List<Sms> {
        lastDate = Long.MAX_VALUE
        lastId = Long.MAX_VALUE
        val page = loadPage()
        hasMore = page.size >= (PAGE / 2)
        return page
    }

    /**
     * Loads the next OLDER page using keyset pagination. Returns empty when exhausted.
     */
    fun loadOlder(): List<Sms> {
        if (!hasMore) return emptyList()
        val page = loadPage()
        if (page.isEmpty()) {
            hasMore = false
            return emptyList()
        }
        hasMore = page.size >= (PAGE / 2)
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

    private fun loadPage(): List<Sms> {
        val repo = SmsRepository(context)

        // Keyset pagination for SMS: WHERE (thread) AND (date < cursor OR (date = cursor AND id < cursor))
        val keysetSelection = if (lastDate < Long.MAX_VALUE) {
            "(${Telephony.Sms.THREAD_ID} = ?) AND (" +
                "${Telephony.Sms.DATE} < ? OR " +
                "(${Telephony.Sms.DATE} = ? AND ${Telephony.Sms._ID} < ?))"
        } else {
            "${Telephony.Sms.THREAD_ID} = ?"
        }

        val keysetArgs = if (lastDate < Long.MAX_VALUE) {
            arrayOf(threadId.toString(), lastDate.toString(), lastDate.toString(), lastId.toString())
        } else {
            arrayOf(threadId.toString())
        }

        val sms = repo.querySmsRaw(
            selection = keysetSelection,
            selectionArgs = keysetArgs,
            sortOrder = "${Telephony.Sms.DATE} DESC, ${Telephony.Sms._ID} DESC",
            limit = PAGE
        )

        // Keyset pagination for MMS (thread-based only).
        val mms = if (threadId > 0L) {
            val mmsKeysetSelection = if (lastDate < Long.MAX_VALUE) {
                "(${Telephony.Mms.THREAD_ID} = ?) AND (" +
                    "${Telephony.Mms.DATE} < ? OR " +
                    "(${Telephony.Mms.DATE} = ? AND ${Telephony.Mms._ID} < ?))"
            } else {
                "${Telephony.Mms.THREAD_ID} = ?"
            }

            val mmsKeysetArgs = if (lastDate < Long.MAX_VALUE) {
                val lastDateSeconds = lastDate / 1000L
                arrayOf(threadId.toString(), lastDateSeconds.toString(), lastDateSeconds.toString(), lastId.toString())
            } else {
                arrayOf(threadId.toString())
            }

            repo.queryMmsRaw(
                selection = mmsKeysetSelection,
                selectionArgs = mmsKeysetArgs,
                sortOrder = "${Telephony.Mms.DATE} DESC, ${Telephony.Mms._ID} DESC",
                limit = PAGE
            )
        } else emptyList()

        // Update cursor to the oldest row we've seen.
        val allRows = sms + mms
        if (allRows.isNotEmpty()) {
            val oldest = allRows.minByOrNull { it.date }
            if (oldest != null) {
                lastDate = oldest.date
                lastId = oldest.id
            }
        }

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
