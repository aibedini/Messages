package com.autonomousone.messages.repository

import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.Telephony
import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Full SMS backup / restore.
 *
 * Format: Android-compatible XML (same shape as the classic "SMS Backup &
 * Restore" app), so backups stay portable between apps and are human-readable:
 *
 * ```
 * <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
 * <smses count="2">
 *   <sms address="+98..." body="hi" date="1755..." date_sent="..." read="1" type="1" status="-1" thread_id="7" />
 * </smses>
 * ```
 *
 * ## Why the implementation is staged
 *
 * The old version streamed straight into the user-picked SAF document and wrote
 * `count="<cursor.count>"` before reading a single row. Any interruption —
 * screen off, app killed, provider error, a body the XML serializer choked on —
 * left a TRUNCATED file that still claimed the full message count. That is the
 * "22 MB backup for 50k messages" failure: the file looked plausible but was
 * cut off, with no way to tell.
 *
 * Now the rows are streamed into a private staging file first, then the header
 * is written from the staged row count and the bytes are copied to the chosen
 * document. If anything fails or the user cancels, the destination document is
 * deleted/truncated instead of being left half-written.
 *
 * Reading is keyset-paged (see [SmsProviderSource]), so a 50k-row inbox cannot
 * pin one huge CursorWindow or turn into an uninterruptible loop.
 */
class BackupRepository(private val context: Context) {

    private companion object {
        const val TAG = "BackupRepository"
        const val RESTORE_BATCH_SIZE = 200
        const val STALE_WORK_FILE_MS = 60 * 60 * 1000L
        const val COPY_BUFFER_BYTES = 256 * 1024
    }

    private val source = SmsProviderSource(context)

    data class BackupResult(val messageCount: Int, val bytes: Long)

    data class RestoreResult(val insertedCount: Int, val skippedCount: Int)

    /**
     * Streams every stored SMS into [destination] as Android-style XML.
     *
     * Emits [TransferProgress] for counting, writing and saving so the UI can
     * show a real progress bar. Suspends cooperatively between rows, so
     * cancellation from the Settings screen interrupts the operation promptly.
     *
     * @throws DataTransferException when the archive could not be produced
     *         completely; the destination is discarded in that case.
     */
    suspend fun backupTo(
        destination: Uri,
        onProgress: (TransferProgress) -> Unit
    ): BackupResult {
        onProgress(TransferProgress(DataTransferPhase.COUNTING))
        var workDir: File? = null
        var archive: SmsXmlArchive? = null
        var stage: SmsXmlArchive.Stage? = null

        try {
            val providerTotal = source.count { count ->
                onProgress(TransferProgress(DataTransferPhase.COUNTING, count, 0))
            }
            onProgress(TransferProgress(DataTransferPhase.COUNTING, providerTotal, providerTotal))
            if (providerTotal == 0) {
                throw DataTransferException("No SMS messages were found on this phone; nothing was written.")
            }

            val dir = File(context.cacheDir, "backup-work").apply { mkdirs() }
            workDir = dir
            cleanStaleWorkFiles(dir)
            val xmlArchive = SmsXmlArchive(dir)
            archive = xmlArchive
            val xmlStage = xmlArchive.openStage("sms-body-${System.currentTimeMillis()}.xml")
            stage = xmlStage
            val job = currentCoroutineContext()[Job]

            var afterId = -1L
            while (true) {
                currentCoroutineContext().ensureActive()
                val page = source.readByIdPage(afterId)
                if (page.rows.isEmpty()) break
                for (row in page.rows) xmlStage.body.writeRow(row)
                if (page.lastId <= afterId) {
                    throw DataTransferException(
                        "The SMS provider returned rows out of order; the backup was aborted to avoid a corrupt file."
                    )
                }
                afterId = page.lastId
                onProgress(
                    TransferProgress(DataTransferPhase.WRITING, xmlStage.rowCount, providerTotal)
                )
                if (page.rows.size < SmsProviderSource.PAGE_SIZE) break
            }

            val writtenRows = xmlStage.rowCount
            if (writtenRows == 0) {
                throw DataTransferException("The SMS provider returned no rows during the backup.")
            }
            if (writtenRows < providerTotal) {
                Log.w(
                    TAG,
                    "Provider reported $providerTotal messages but $writtenRows were written; " +
                        "messages may have been deleted while the backup was running"
                )
            }
            xmlStage.flush()

            onProgress(
                TransferProgress(
                    phase = DataTransferPhase.SAVING,
                    done = writtenRows,
                    total = providerTotal,
                    bytesDone = 0L,
                    bytesTotal = xmlStage.file.length()
                )
            )

            val output = try {
                context.contentResolver.openOutputStream(destination, "wt")
            } catch (e: Exception) {
                throw DataTransferException(
                    "The selected file could not be opened for writing: ${e.describe()}",
                    e
                )
            } ?: throw DataTransferException("The selected file could not be opened for writing.")

            val totalBytes = BufferedOutputStream(output, COPY_BUFFER_BYTES).use { out ->
                xmlArchive.assemble(
                    stage = xmlStage,
                    destination = out,
                    checkActive = { job?.ensureActive() }
                ) { copiedBytes, bodyBytes ->
                    onProgress(
                        TransferProgress(
                            phase = DataTransferPhase.SAVING,
                            done = writtenRows,
                            total = providerTotal,
                            bytesDone = copiedBytes,
                            bytesTotal = bodyBytes
                        )
                    )
                }
            }

            onProgress(
                TransferProgress(
                    phase = DataTransferPhase.SAVING,
                    done = writtenRows,
                    total = providerTotal,
                    bytesDone = xmlStage.file.length(),
                    bytesTotal = xmlStage.file.length()
                )
            )
            return BackupResult(messageCount = writtenRows, bytes = totalBytes)
        } catch (e: CancellationException) {
            discardDestination(destination)
            throw e
        } catch (e: DataTransferException) {
            discardDestination(destination)
            throw e
        } catch (e: Exception) {
            discardDestination(destination)
            throw DataTransferException(e.describe(), e)
        } finally {
            if (archive != null && stage != null) archive.discard(stage)
            workDir?.delete()
        }
    }

    /**
     * Reads XML from [source] and inserts rows into Telephony.Sms. Returns the
     * inserted/skipped counts.
     *
     * Restore is additive and never replaces existing rows. Rows are inserted in
     * batches inside one provider transaction (with a per-row fallback), because
     * one `insert()` per message made a 50k-row restore look like a hang.
     *
     * @throws DataTransferException when the file is not a usable backup; the
     *         message states how many rows were already restored.
     */
    suspend fun restoreFrom(
        source: Uri,
        onProgress: (TransferProgress) -> Unit
    ): RestoreResult {
        val stream = try {
            context.contentResolver.openInputStream(source)
        } catch (e: Exception) {
            throw DataTransferException("The selected backup file could not be opened: ${e.describe()}", e)
        } ?: throw DataTransferException("The selected backup file could not be opened.")

        var declaredTotal = 0
        var rowsRead = 0
        var inserted = 0
        var skipped = 0
        var sawRoot = false
        var batch = ArrayList<ContentValues>(RESTORE_BATCH_SIZE)

        try {
            BufferedInputStream(stream, 64 * 1024).use { input ->
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(input, null)

                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) {
                        when (parser.name) {
                            "smses" -> {
                                sawRoot = true
                                declaredTotal = parser.getAttributeValue(null, "count")
                                    ?.toIntOrNull()
                                    ?.coerceAtLeast(0)
                                    ?: 0
                                onProgress(
                                    TransferProgress(DataTransferPhase.RESTORING, 0, declaredTotal)
                                )
                            }

                            "sms" -> {
                                rowsRead++
                                val values = readSmsValues(parser)
                                if (values == null) {
                                    skipped++
                                } else {
                                    batch.add(values)
                                }
                                if (batch.size >= RESTORE_BATCH_SIZE) {
                                    currentCoroutineContext().ensureActive()
                                    val outcome = flushBatch(batch)
                                    inserted += outcome.first
                                    skipped += outcome.second
                                    batch = ArrayList(RESTORE_BATCH_SIZE)
                                    onProgress(
                                        TransferProgress(
                                            DataTransferPhase.RESTORING,
                                            rowsRead,
                                            restoreProgressTotal(declaredTotal, rowsRead)
                                        )
                                    )
                                }
                            }
                        }
                    }
                    event = parser.next()
                }
            }

            if (batch.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val outcome = flushBatch(batch)
                inserted += outcome.first
                skipped += outcome.second
            }

            onProgress(
                TransferProgress(
                    DataTransferPhase.RESTORING,
                    rowsRead,
                    restoreProgressTotal(declaredTotal, rowsRead)
                )
            )

            if (!sawRoot) {
                throw DataTransferException("The selected file is not an SMS XML backup.")
            }
            if (rowsRead == 0) {
                throw DataTransferException("The backup file contains no <sms> entries.")
            }
            if (inserted == 0) {
                throw DataTransferException("None of the $rowsRead messages in the backup could be restored.")
            }
            return RestoreResult(insertedCount = inserted, skippedCount = skipped)
        } catch (e: CancellationException) {
            throw e
        } catch (e: XmlPullParserException) {
            throw DataTransferException(
                "The backup file is damaged or truncated (line ${e.lineNumber}); " +
                    "$inserted messages were restored before the error.",
                e
            )
        } catch (e: IOException) {
            throw DataTransferException(
                "The backup file could not be read: ${e.describe()}; " +
                    "$inserted messages were restored before the error.",
                e
            )
        } catch (e: DataTransferException) {
            throw e
        } catch (e: Exception) {
            throw DataTransferException(
                "${e.describe()}; $inserted messages were restored before the error.",
                e
            )
        } finally {
            // Rows already inserted must become visible even when the parse
            // failed halfway through the file.
            try {
                SmsRepository(context).notifyExternalChange()
            } catch (_: Exception) {
                // The observer may simply not be registered yet.
            }
        }
    }

    // ------------------------------------------------------------------ backup

    private fun cleanStaleWorkFiles(dir: File) {
        val cutoff = System.currentTimeMillis() - STALE_WORK_FILE_MS
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }

    /**
     * Removes the destination document a failed/cancelled backup created, so it
     * cannot later be mistaken for a complete archive. Falls back to truncation
     * when the provider does not allow deletion.
     */
    private fun discardDestination(uri: Uri) {
        try {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not delete the partial backup document; truncating instead", e)
        }
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { }
        } catch (e: Exception) {
            Log.w(TAG, "Could not truncate the partial backup document", e)
        }
    }

    // ----------------------------------------------------------------- restore

    /**
     * Progress total for restore. The `<smses count>` attribute is the only
     * source; when a file omits it the bar stays indeterminate instead of
     * claiming "100%" after the first batch.
     */
    private fun restoreProgressTotal(declaredTotal: Int, rowsRead: Int): Int =
        if (declaredTotal > 0) maxOf(declaredTotal, rowsRead) else 0

    /**
     * Maps one `<sms>` element to provider values, or null when it has no usable
     * address (restoring such a row would create an orphan thread).
     */
    private fun readSmsValues(parser: XmlPullParser): ContentValues? {
        var address: String? = null
        val values = ContentValues()
        for (i in 0 until parser.attributeCount) {
            val name = parser.getAttributeName(i)
            val value = parser.getAttributeValue(i) ?: continue
            when (name) {
                "address" -> address = value
                "body" -> values.put(Telephony.Sms.BODY, value)
                "date" -> value.toLongOrNull()?.let { values.put(Telephony.Sms.DATE, it) }
                "date_sent", "dateSent" ->
                    value.toLongOrNull()?.let { values.put(Telephony.Sms.DATE_SENT, it) }
                "read" -> value.toIntOrNull()?.let { values.put(Telephony.Sms.READ, it) }
                "type" -> value.toIntOrNull()?.let { values.put(Telephony.Sms.TYPE, it) }
                "status" -> value.toIntOrNull()?.let { values.put(Telephony.Sms.STATUS, it) }
                // THREAD_ID is device-specific — the provider recomputes it.
            }
        }
        if (address.isNullOrBlank()) return null
        values.put(Telephony.Sms.ADDRESS, address)
        if (!values.containsKey(Telephony.Sms.BODY)) values.put(Telephony.Sms.BODY, "")
        if (!values.containsKey(Telephony.Sms.DATE)) values.put(Telephony.Sms.DATE, 0L)
        if (!values.containsKey(Telephony.Sms.DATE_SENT)) values.put(Telephony.Sms.DATE_SENT, 0L)
        if (!values.containsKey(Telephony.Sms.READ)) values.put(Telephony.Sms.READ, 1)
        if (!values.containsKey(Telephony.Sms.STATUS)) values.put(Telephony.Sms.STATUS, -1)
        if (!values.containsKey(Telephony.Sms.TYPE)) values.put(Telephony.Sms.TYPE, 1)
        return values
    }

    /**
     * Inserts one batch inside a single provider transaction.
     *
     * @return inserted to skipped counts.
     */
    private fun flushBatch(batch: List<ContentValues>): Pair<Int, Int> {
        val operations = ArrayList<ContentProviderOperation>(batch.size)
        for (values in batch) {
            operations.add(
                ContentProviderOperation.newInsert(Telephony.Sms.CONTENT_URI)
                    .withValues(values)
                    .build()
            )
        }
        val authority = Telephony.Sms.CONTENT_URI.authority ?: "sms"
        return try {
            val results = context.contentResolver.applyBatch(authority, operations)
            val inserted = results.count { it.uri != null }
            inserted to (batch.size - inserted)
        } catch (e: Exception) {
            Log.w(TAG, "Batched restore failed; falling back to row-by-row inserts", e)
            var inserted = 0
            for (values in batch) {
                try {
                    if (context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values) != null) {
                        inserted++
                    }
                } catch (rowError: Exception) {
                    Log.w(TAG, "A restored row was rejected by the SMS provider", rowError)
                }
            }
            inserted to (batch.size - inserted)
        }
    }
}

/** Short, user-showable description of a failure (never a stack trace). */
private fun Throwable.describe(): String {
    val text = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
    return text.replace('\n', ' ').take(300)
}
