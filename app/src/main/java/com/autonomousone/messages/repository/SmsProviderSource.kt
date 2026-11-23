package com.autonomousone.messages.repository

import android.content.Context
import android.database.Cursor
import android.provider.Telephony
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.LinkedHashSet

/**
 * Paged reads over the SMS provider, shared by the XML backup and the JSON
 * export.
 *
 * ## Why every read pages by `_id` only
 *
 * A 40–50k-row inbox cannot be read with one cursor: it keeps refilling a
 * CursorWindow, cannot be cancelled between windows, and looks like a hang.
 * Paging is therefore mandatory — but the page KEY must work on every OEM
 * provider. Earlier export paging ordered by
 * `IFNULL(thread_id,0), IFNULL(date,0), _id`; on at least one device the
 * provider did not apply that complex sort, so the first 500-row page was
 * followed by an empty one and the export reported "500 messages in 1
 * conversation" as a success.
 *
 * Every query below now uses only:
 *
 * - equality / range predicates on `_id`, `thread_id`, and `thread_id IS NULL`,
 * - `ORDER BY _id ASC`,
 * - a hard row cap enforced by closing the cursor, never `LIMIT` in
 *   `sortOrder`.
 *
 * Those are the provider's own primary key and plain columns, which every SMS
 * provider supports. Conversation grouping no longer depends on a multi-column
 * sort: the exporter first scans the distinct thread ids, then reads each
 * thread's messages with `thread_id = ? AND _id > ?`.
 */
internal class SmsProviderSource(private val context: Context) {

    companion object {
        const val PAGE_SIZE = 500
        const val COUNT_PAGE_SIZE = 2_000
        const val THREAD_SCAN_PAGE_SIZE = 2_000

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
    }

    /** One page of rows plus the id of the last row (the next page's cursor). */
    data class RowPage(val rows: List<SmsTransferRow>, val lastId: Long)

    /**
     * Result of a full `_id` scan: every distinct thread id plus the number of
     * SMS rows the scan actually read (used to detect a provider that silently
     * returns fewer rows than it reports).
     */
    data class ThreadScan(val threadIds: List<Long>, val rowCount: Int)

    /**
     * Counts every SMS row, emitting the running total after each page.
     *
     * Uses `_id > ?` paging with a plain `_id` sort, so the count either
     * completes or fails loudly (the id must advance); it cannot loop forever.
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
     * Scans every SMS row once for its `thread_id`, paging by `_id`.
     *
     * Returns the distinct thread ids (0 for null/0) and the exact number of
     * rows read, so the caller can refuse to produce an archive when the scan
     * sees fewer rows than the count pass.
     */
    suspend fun scanThreads(onProgress: (scannedRows: Int) -> Unit): ThreadScan {
        val threadIds = LinkedHashSet<Long>()
        var scannedRows = 0
        var afterId = -1L
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = readThreadIdPage(afterId, THREAD_SCAN_PAGE_SIZE)
            if (page.isEmpty()) break
            for (entry in page) threadIds.add(entry.threadId)
            scannedRows += page.size
            val lastId = page.last().id
            if (lastId <= afterId) {
                throw DataTransferException(
                    "The SMS provider returned ids out of order while scanning conversations."
                )
            }
            afterId = lastId
            onProgress(scannedRows)
            if (page.size < THREAD_SCAN_PAGE_SIZE) break
        }
        return ThreadScan(threadIds = threadIds.toList(), rowCount = scannedRows)
    }

    /** Reads one page in provider-id order (backup). */
    fun readByIdPage(afterId: Long, limit: Int = PAGE_SIZE): RowPage =
        readRows(
            selection = "${Telephony.Sms._ID} > ?",
            selectionArgs = arrayOf(afterId.toString()),
            sortOrder = "${Telephony.Sms._ID} ASC",
            limit = limit
        )

    /**
     * Reads one page of a single conversation, paging by `_id`.
     *
     * For [threadId] 0 the selection also covers rows whose `thread_id` is
     * NULL; the exporter groups those by address afterwards.
     */
    fun readThreadPage(threadId: Long, afterId: Long, limit: Int = PAGE_SIZE): RowPage {
        val selection: String
        val selectionArgs: Array<String>
        if (threadId > 0L) {
            selection = "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms._ID} > ?"
            selectionArgs = arrayOf(threadId.toString(), afterId.toString())
        } else {
            selection = "(${Telephony.Sms.THREAD_ID} = 0 OR ${Telephony.Sms.THREAD_ID} IS NULL)" +
                " AND ${Telephony.Sms._ID} > ?"
            selectionArgs = arrayOf(afterId.toString())
        }
        return readRows(
            selection = selection,
            selectionArgs = selectionArgs,
            sortOrder = "${Telephony.Sms._ID} ASC",
            limit = limit
        )
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
            projection = PROJECTION,
            selection = "(${Telephony.Sms.THREAD_ID} = 0 OR ${Telephony.Sms.THREAD_ID} IS NULL)",
            selectionArgs = null,
            sortOrder = "${Telephony.Sms._ID} ASC"
        )
        cursor.use { cursor ->
            val index = RowIndex(cursor)
            while (cursor.moveToNext()) {
                rows.add(index.row(cursor))
            }
        }
        return rows
    }

    private class ThreadIdRow(val id: Long, val threadId: Long)

    private fun readThreadIdPage(afterId: Long, limit: Int): List<ThreadIdRow> {
        val rows = ArrayList<ThreadIdRow>(limit)
        val cursor = query(
            projection = arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID),
            selection = "${Telephony.Sms._ID} > ?",
            selectionArgs = arrayOf(afterId.toString()),
            sortOrder = "${Telephony.Sms._ID} ASC"
        )
        cursor.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val threadIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            while (rows.size < limit && cursor.moveToNext()) {
                val threadId = if (cursor.isNull(threadIndex)) 0L else cursor.getLong(threadIndex)
                rows.add(ThreadIdRow(id = cursor.getLong(idIndex), threadId = threadId))
            }
        }
        return rows
    }

    private fun readIdPage(afterId: Long, limit: Int): List<Long> {
        val ids = ArrayList<Long>(limit)
        val cursor = query(
            projection = arrayOf(Telephony.Sms._ID),
            selection = "${Telephony.Sms._ID} > ?",
            selectionArgs = arrayOf(afterId.toString()),
            sortOrder = "${Telephony.Sms._ID} ASC"
        )
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
        limit: Int
    ): RowPage {
        val rows = ArrayList<SmsTransferRow>(limit)
        val cursor = query(PROJECTION, selection, selectionArgs, sortOrder)
        cursor.use { cursor ->
            val index = RowIndex(cursor)
            while (rows.size < limit && cursor.moveToNext()) {
                rows.add(index.row(cursor))
            }
        }
        return RowPage(rows = rows, lastId = rows.lastOrNull()?.id ?: -1L)
    }

    private fun query(
        projection: Array<String>,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String
    ): Cursor {
        return context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
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

        fun row(cursor: Cursor): SmsTransferRow = SmsTransferRow(
            id = cursor.getLong(idIndex),
            threadId = if (cursor.isNull(threadIndex)) 0L else cursor.getLong(threadIndex),
            address = cursor.getString(addressIndex).orEmpty(),
            body = cursor.getString(bodyIndex).orEmpty(),
            date = if (cursor.isNull(dateIndex)) 0L else cursor.getLong(dateIndex),
            dateSent = if (cursor.isNull(dateSentIndex)) 0L else cursor.getLong(dateSentIndex),
            read = if (cursor.isNull(readIndex)) 1 else cursor.getInt(readIndex),
            type = if (cursor.isNull(typeIndex)) 1 else cursor.getInt(typeIndex),
            status = if (cursor.isNull(statusIndex)) -1 else cursor.getInt(statusIndex)
        )
    }
}
