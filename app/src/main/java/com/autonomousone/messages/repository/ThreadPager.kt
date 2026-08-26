package com.autonomousone.messages.repository

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.repository.ContactRepository
import kotlin.math.max

/**
 * Paged loader for a single conversation thread.
 *
 * Instead of reading the WHOLE thread from the provider on open (which is why
 * huge threads showed "Reading messages… N/N" spinners), we read only the most
 * recent [PAGE] rows and page backwards as the user scrolls up — the same
 * windowed approach Google Messages/WhatsApp use.
 *
 * SMS and MMS are merged per-page: we fetch the newest `limit` of each source
 * (offset by how many already shown) and interleave by date, so the visible
 * history stays chronologically seamless across page boundaries.
 */
class ThreadPager(
    private val context: Context,
    private val threadId: Long,
    private val phone: String = ""
) {
    companion object {
        /** Rows per page (SMS + MMS each), i.e. up to 2×PAGE items rendered. */
        const val PAGE = 40
    }

    // How many NEWEST rows are already handed to the UI (per source).
    private var smsConsumed = 0
    private var mmsConsumed = 0

    /**
     * Phone-only route (threadId == 0): query by ADDRESS suffix instead of a
     * bogus THREAD_ID = 0 selection, which always returned an empty page.
     * last-7-digits matching mirrors how the rest of the app groups threads.
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

    /** True when either source still has older rows to pull. */
    @Volatile
    var hasMore: Boolean = true
        private set

    /**
     * Loads the FIRST page (newest messages). Marks nothing read; caller decides.
     */
    fun loadFirstPage(): List<Sms> {
        smsConsumed = 0
        mmsConsumed = 0
        val page = loadPage()
        hasMore = page.size >= (PAGE / 2) // conservative: keep paging until proven exhausted
        return page
    }

    /**
     * Loads the next OLDER page. Returns empty when exhausted.
     */
    fun loadOlder(): List<Sms> {
        if (!hasMore) return emptyList()
        val page = loadPage(skipSms = smsConsumed, skipMms = mmsConsumed)
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

    // ── internals ────────────────────────────────────────────────────────────

    private fun loadPage(): List<Sms> = loadPage(0, 0)

    private fun loadPage(skipSms: Int, skipMms: Int): List<Sms> {
        val repo = SmsRepository(context)
        val sms = repo.querySmsRaw(
            selection = smsSelection,
            selectionArgs = smsArgs,
            sortOrder = "${Telephony.Sms.DATE} DESC",
            limit = PAGE,
            offset = skipSms
        )
        // MMS keeps the thread-based path; phone-only threads rarely carry MMS
        // history and the addr-table join is expensive. ponytail: acceptable
        // ceiling — upgrade to an addr-based MMS query if a real thread needs it.
        val mms = if (threadId > 0L) repo.queryMmsRaw(
            selection = "${Telephony.Mms.THREAD_ID} = ?",
            selectionArgs = arrayOf(threadId.toString()),
            sortOrder = "${Telephony.Mms.DATE} DESC",
            limit = PAGE,
            offset = skipMms
        ) else emptyList()
        smsConsumed += sms.size
        mmsConsumed += mms.size
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
