package com.autonomousone.messages.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import kotlin.coroutines.cancellation.CancellationException

/**
 * Exports every SMS conversation into one streamed JSON document and exposes it
 * as a shareable `content://` URI (via FileProvider) — no storage permission
 * required.
 *
 * Shape (unchanged from previous releases):
 * ```
 * {
 *   "app": "com.autonomousone.messages",
 *   "exportedAt": 1755900000000,
 *   "conversationCount": 12,
 *   "messageCount": 340,
 *   "conversations": [
 *     { "address": "+98912...", "threadId": 3, "messages": [
 *         { "type": "received", "sender": "...", "message": "...",
 *           "date": 0, "dateSent": 0, "status": -1 } ] }
 *   ]
 * }
 * ```
 *
 * ## Why this reads thread by thread
 *
 * The first streaming version paged the whole inbox with a multi-column keyset
 * (`thread_id, date, _id`). On a real device the SMS provider did not apply
 * that sort, the second page came back empty, and the archive reported
 * "500 messages in 1 conversation" as a success — one page and a false count.
 *
 * The current flow never depends on provider-side multi-column sorting:
 *
 * 1. count every SMS row (`_id` paging),
 * 2. scan every row's `thread_id` (`_id` paging),
 * 3. read each conversation with `thread_id = ? AND _id > ?`, `ORDER BY _id`.
 *
 * Only the primary key and plain columns are used, so paging works on every
 * provider. The archive is then verified: if fewer messages were read than the
 * provider reported, the file is deleted and the export fails instead of
 * handing the user a partial archive.
 *
 * Only one page of rows is in memory; conversations are written one at a time
 * to a private staging file.
 */
class ExportRepository(private val context: Context) {

    private companion object {
        const val TAG = "ExportRepository"
        const val COPY_BUFFER_BYTES = 256 * 1024
        const val STALE_EXPORT_MS = 24 * 60 * 60 * 1000L
        const val WORK_FILE_PREFIX = ".work-"
        const val PROGRESS_ROW_STEP = 200
        const val PROGRESS_TIME_STEP_MS = 250L
    }

    private val source = SmsProviderSource(context)

    data class ExportResult(
        val uri: Uri,
        val messageCount: Int,
        val conversationCount: Int,
        val bytes: Long
    )

    /**
     * Builds the JSON archive and returns a shareable URI.
     *
     * @throws DataTransferException when no complete archive could be produced.
     *         Partial staging/final files are removed, so a truncated JSON file
     *         is never offered to the share sheet.
     */
    suspend fun exportAllChats(
        onProgress: (TransferProgress) -> Unit
    ): ExportResult {
        onProgress(TransferProgress(DataTransferPhase.COUNTING))
        val countedMessages = source.count { count ->
            onProgress(TransferProgress(DataTransferPhase.COUNTING, count, 0))
        }
        onProgress(
            TransferProgress(DataTransferPhase.COUNTING, countedMessages, countedMessages)
        )
        if (countedMessages == 0) {
            throw DataTransferException("No SMS messages were found on this phone; nothing was exported.")
        }

        val scan = source.scanThreads { scannedRows ->
            onProgress(TransferProgress(DataTransferPhase.COUNTING, scannedRows, countedMessages))
        }
        if (scan.rowCount < countedMessages) {
            throw DataTransferException(
                "The SMS provider counted $countedMessages messages but the conversation scan " +
                    "returned only ${scan.rowCount}; the export was aborted instead of saving a partial archive."
            )
        }
        val expectedTotal = scan.rowCount
        onProgress(TransferProgress(DataTransferPhase.COUNTING, expectedTotal, expectedTotal))

        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        cleanStaleFiles(dir)
        val stamp = System.currentTimeMillis()
        val bodyFile = File(dir, "$WORK_FILE_PREFIX$stamp.body")
        val finalFile = File(dir, "sms_export_$stamp.json")
        var finalCreated = false

        try {
            var messagesWritten = 0
            var conversationsWritten = 0

            BufferedWriter(
                OutputStreamWriter(bodyFile.outputStream(), Charsets.UTF_8),
                256 * 1024
            ).use { writer ->
                val body = SmsJsonBodyWriter(writer)
                var lastReportedCount = 0
                var lastReportedAt = 0L

                fun reportProgress(force: Boolean) {
                    val now = System.currentTimeMillis()
                    if (force ||
                        messagesWritten - lastReportedCount >= PROGRESS_ROW_STEP ||
                        now - lastReportedAt >= PROGRESS_TIME_STEP_MS
                    ) {
                        lastReportedCount = messagesWritten
                        lastReportedAt = now
                        onProgress(
                            TransferProgress(
                                DataTransferPhase.WRITING,
                                messagesWritten,
                                expectedTotal
                            )
                        )
                    }
                }

                // 1) One conversation at a time, paged by _id only.
                for (threadId in scan.threadIds) {
                    if (threadId <= 0L) continue // handled by the unthreaded pass below
                    currentCoroutineContext().ensureActive()

                    var afterId = -1L
                    var conversationOpen = false
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val page = source.readThreadPage(threadId, afterId)
                        if (page.rows.isEmpty()) break

                        if (!conversationOpen) {
                            body.startConversation(
                                address = page.rows.first().address.ifBlank { UNKNOWN_ADDRESS },
                                threadId = threadId
                            )
                            conversationOpen = true
                        }
                        for (row in page.rows) body.writeMessage(row)
                        messagesWritten = body.messageCount

                        if (page.lastId <= afterId) {
                            throw DataTransferException(
                                "The SMS provider returned rows out of order while reading " +
                                    "conversation $threadId; the export was aborted."
                            )
                        }
                        afterId = page.lastId
                        reportProgress(force = false)
                        if (page.rows.size < SmsProviderSource.PAGE_SIZE) break
                    }
                    if (conversationOpen) body.endConversation()
                }

                // 2) Rows without a thread: group by address, exactly like the
                //    previous exporter did. Usually empty; loaded only then.
                val unthreaded = source.readUnthreadedRows()
                if (unthreaded.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    val grouped = LinkedHashMap<String, MutableList<SmsTransferRow>>()
                    for (row in unthreaded) {
                        val address = row.address.ifBlank { UNKNOWN_ADDRESS }
                        grouped.getOrPut(address) { ArrayList() }.add(row)
                    }
                    for ((address, rows) in grouped) {
                        currentCoroutineContext().ensureActive()
                        body.startConversation(address = address, threadId = 0L)
                        for (row in rows) body.writeMessage(row)
                        body.endConversation()
                    }
                    messagesWritten = body.messageCount
                }
                reportProgress(force = true)

                conversationsWritten = body.conversationCount
            }

            if (messagesWritten == 0) {
                throw DataTransferException("The SMS provider returned no rows during the export.")
            }
            requireCompleteArchive(messagesWritten, expectedTotal, "export")
            Log.i(
                TAG,
                "SMS export: scanned ${scan.rowCount} rows / ${scan.threadIds.size} threads, " +
                    "wrote $messagesWritten messages in $conversationsWritten conversations"
            )

            val prefix = SmsJsonFormat.documentStart(
                app = context.packageName,
                exportedAt = stamp,
                conversationCount = conversationsWritten,
                messageCount = messagesWritten
            ).toByteArray(Charsets.UTF_8)
            val suffix = SmsJsonFormat.DOCUMENT_END.toByteArray(Charsets.UTF_8)
            val bodyBytes = bodyFile.length()
            onProgress(
                TransferProgress(
                    phase = DataTransferPhase.SAVING,
                    done = messagesWritten,
                    total = expectedTotal,
                    bytesDone = 0L,
                    bytesTotal = bodyBytes
                )
            )

            var copiedBytes = 0L
            BufferedOutputStream(finalFile.outputStream(), COPY_BUFFER_BYTES).use { out ->
                finalCreated = true
                out.write(prefix)
                bodyFile.inputStream().use { input ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        copiedBytes += read
                        onProgress(
                            TransferProgress(
                                phase = DataTransferPhase.SAVING,
                                done = messagesWritten,
                                total = expectedTotal,
                                bytesDone = copiedBytes,
                                bytesTotal = bodyBytes
                            )
                        )
                    }
                }
                out.write(suffix)
                out.flush()
            }

            if (copiedBytes != bodyBytes) {
                throw DataTransferException(
                    "Only $copiedBytes of $bodyBytes export bytes reached the file; it would have been incomplete."
                )
            }
            val expectedBytes = prefix.size.toLong() + bodyBytes + suffix.size
            if (finalFile.length() != expectedBytes) {
                throw DataTransferException(
                    "The exported file is ${finalFile.length()} bytes but $expectedBytes were expected."
                )
            }

            onProgress(
                TransferProgress(
                    phase = DataTransferPhase.SAVING,
                    done = messagesWritten,
                    total = expectedTotal,
                    bytesDone = bodyBytes,
                    bytesTotal = bodyBytes
                )
            )

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                finalFile
            )
            return ExportResult(
                uri = uri,
                messageCount = messagesWritten,
                conversationCount = conversationsWritten,
                bytes = expectedBytes
            )
        } catch (e: CancellationException) {
            bodyFile.delete()
            if (finalCreated) finalFile.delete()
            throw e
        } catch (e: DataTransferException) {
            bodyFile.delete()
            if (finalCreated) finalFile.delete()
            throw e
        } catch (e: Exception) {
            bodyFile.delete()
            if (finalCreated) finalFile.delete()
            throw DataTransferException(e.describe(), e)
        } finally {
            bodyFile.delete()
        }
    }

    private fun cleanStaleFiles(dir: File) {
        val cutoff = System.currentTimeMillis() - STALE_EXPORT_MS
        dir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val isWorkFile = file.name.startsWith(WORK_FILE_PREFIX)
            if (isWorkFile || file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }
}

/** Short, user-showable description of a failure (never a stack trace). */
private fun Throwable.describe(): String {
    val text = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
    return text.replace('\n', ' ').take(300)
}
