package com.autonomousone.messages.repository

import android.content.Context
import android.database.Cursor
import android.provider.Telephony
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Keyset-paged reads over the SMS provider, shared by the XML backup and the
 * JSON export.
 *
 * Why keyset paging instead of one cursor over the whole table:
 *
 * - A single `content://sms` cursor over 40–50k rows keeps refilling a
 *   CursorWindow for minutes and cannot be interrupted between windows. The
 *   UI sees "Working…" with no numbers and users reasonably call it a hang.
 * - Each page here is a bounded query (`_id > ?` or a
 *   `(thread_id, date, _id) > (?, ?, ?)` keyset) with a hard row cap, so the
 *   operation can stop between pages, report progress and cancel cleanly.
 * - `LIMIT` is deliberately NOT used: some OEM providers reject clauses in
 *   `sortOrder`. The cap is enforced by closing the cursor after reading
 *   [limit] rows, which is portable and keeps the provider scan indexed.
 */
internal class SmsProviderSource(private val context: Context) {

    companion object {
        const val PAGE_SIZE = 500
        const val COUNT_PAGE_SIZE = 2_000

        val PROJECTION = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.DATE_SENT,
            Telephony.Sms.READ,
            Telephony.Sms.TYPE,
            Telephony.Sms.STATUS
        )

        private const val THREAD_KEY = "IFNULL(${Telephony.Sms.THREAD_ID}, 0)"
        private const val DATE_KEY = "IFNULL(${Telephony.Sms.DATE}, 0)"
    }

    /** Position of a row in the `(thread_id, date, _id)` ordering. */
    data class ThreadKeys(val threadId: Long, val date: Long, val id: Long)

    /** One page of rows plus the keyset cursor for the next page. */
    data class RowPage(val rows: List<SmsTransferRow>, val next: ThreadKeys?) {
        val lastId: Long get() = rows.lastOrNull()?.id ?: -1L
    }

    /**
     * Counts every SMS row, emitting the running total after each page so the
     * UI can show "reading messages…" instead of an indefinite spinner.
     */
    suspend fun count(onProgress: (Int) -> Unit): Int {
        var count = 0
        var afterId = -1L
        while (true) {
            currentCoroutineContext().ensureActive()
            val ids = readIdPage(afterId, COUNT_PAGE_SIZE)
            if (ids.isEmpty()) break
            count += ids.size
            val lastId = ids.last()
            if (lastId <= afterId) {
                throw DataTransferException(
                    "The SMS provider returned ids out of order while counting messages."
                )
            }
            afterId = lastId
            onProgress(count)
            if (ids.size < COUNT_PAGE_SIZE) break
        }
        return count
    }

    /**
     * Reads one page in provider-id order. Used by the backup, where archive
     * order only needs to be stable (restore assigns fresh ids either way).
     */
    fun readByIdPage(afterId: Long, limit: Int = PAGE_SIZE): RowPage {
        return readRows(
            selection = "${Telephony.Sms._ID} > ?",
            selectionArgs = arrayOf(afterId.toString()),
            sortOrder = "${Telephony.Sms._ID} ASC",
            limit = limit
        ) { cursor, index ->
            ThreadKeys(threadId = index.threadId(cursor), date = index.date(cursor), id = index.id(cursor))
        }
    }

    /**
     * Reads one page ordered by `(thread_id, date, _id)` so every conversation
     * is a contiguous run and the JSON export can stream it without ever
     * materializing more than one page.
     */
    fun readByThreadPage(after: ThreadKeys?, limit: Int = PAGE_SIZE): RowPage {
        val selection = StringBuilder("$THREAD_KEY > 0")
        val args = ArrayList<String>(6)
        if (after != null) {
            selection.append(" AND ($THREAD_KEY > ?")
                .append(" OR ($THREAD_KEY = ?")
                .append(" AND ($DATE_KEY > ?")
                .append(" OR ($DATE_KEY = ? AND ${Telephony.Sms._ID} > ?))))")
            args.add(after.threadId.toString())
            args.add(after.threadId.toString())
            args.add(after.date.toString())
            args.add(after.date.toString())
            args.add(after.id.toString())
        }
        return readRows(
            selection = selection.toString(),
            selectionArgs = args.toTypedArray(),
            sortOrder = "$THREAD_KEY ASC, $DATE_KEY ASC, ${Telephony.Sms._ID} ASC",
            limit = limit
        ) { cursor, index ->
            ThreadKeys(threadId = index.threadId(cursor), date = index.date(cursor), id = index.id(cursor))
        }
    }

    /**
     * Rows the provider did not assign to a thread (thread_id null/0).
     *
     * These are an anomaly, but the previous exporter grouped them by address,
     * so they are read separately and grouped the same way rather than being
     * silently attached to the "thread 0" conversation.
     */
    fun readUnthreadedRows(): List<SmsTransferRow> {
        val rows = ArrayList<SmsTransferRow>()
        val cursor = query(
            selection = "$THREAD_KEY = 0",
            selectionArgs = null,
            sortOrder = "IFNULL(${Telephony.Sms.ADDRESS}, '') ASC, $DATE_KEY ASC, ${Telephony.Sms._ID} ASC"
        )
        cursor.use { cursor ->
            val index = RowIndex(cursor)
            while (cursor.moveToNext()) {
                rows.add(index.row(cursor))
            }
        }
        return rows
    }

    private fun readIdPage(afterId: Long, limit: Int): List<Long> {
        val ids = ArrayList<Long>(limit)
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID),
            "${Telephony.Sms._ID} > ?",
            arrayOf(afterId.toString()),
            "${Telephony.Sms._ID} ASC"
        ) ?: throw DataTransferException("The SMS provider is not available.")
        cursor.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            while (ids.size < limit && cursor.moveToNext()) {
                ids.add(cursor.getLong(idIndex))
            }
        }
        return ids
    }

    private fun readRows(
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String,
        limit: Int,
        nextKeys: (Cursor, RowIndex) -> ThreadKeys
    ): RowPage {
        val rows = ArrayList<SmsTransferRow>(limit)
        var next: ThreadKeys? = null
        val cursor = query(selection, selectionArgs, sortOrder)
        cursor.use { cursor ->
            val index = RowIndex(cursor)
            while (rows.size < limit && cursor.moveToNext()) {
                rows.add(index.row(cursor))
                next = nextKeys(cursor, index)
            }
        }
        return RowPage(rows = rows, next = next)
    }

    private fun query(
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String
    ): Cursor {
        return context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            PROJECTION,
            selection,
            selectionArgs,
            sortOrder
        ) ?: throw DataTransferException("The SMS provider is not available.")
    }

    private class RowIndex(cursor: Cursor) {
        private val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
        private val threadIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
        private val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
        private val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
        private val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
        private val dateSentIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE_SENT)
        private val readIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
        private val typeIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
        private val statusIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.STATUS)

        fun id(cursor: Cursor): Long = cursor.getLong(idIndex)

        fun threadId(cursor: Cursor): Long =
            if (cursor.isNull(threadIndex)) 0L else cursor.getLong(threadIndex)

        fun date(cursor: Cursor): Long =
            if (cursor.isNull(dateIndex)) 0L else cursor.getLong(dateIndex)

        fun row(cursor: Cursor): SmsTransferRow = SmsTransferRow(
            id = cursor.getLong(idIndex),
            threadId = threadId(cursor),
            address = cursor.getString(addressIndex).orEmpty(),
            body = cursor.getString(bodyIndex).orEmpty(),
            date = date(cursor),
            dateSent = if (cursor.isNull(dateSentIndex)) 0L else cursor.getLong(dateSentIndex),
            read = if (cursor.isNull(readIndex)) 1 else cursor.getInt(readIndex),
            type = if (cursor.isNull(typeIndex)) 1 else cursor.getInt(typeIndex),
            status = if (cursor.isNull(statusIndex)) -1 else cursor.getInt(statusIndex)
        )
    }
}
