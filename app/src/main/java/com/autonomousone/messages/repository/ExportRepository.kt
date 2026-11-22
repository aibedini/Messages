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
 * ## Why this streams
 *
 * The old implementation loaded every `Sms` object, grouped the whole inbox,
 * then built a `JSONObject` tree for all of it. At 40–50k messages that is
 * hundreds of MB of short-lived objects on a phone: garbage-collection thrash
 * at best, an OOM kill at worst — the user-visible "Export all chats goes to
 * Working and never returns".
 *
 * Here the conversations are written one at a time, in provider
 * `(thread_id, date, _id)` order, into a private staging file. Only one page of
 * rows is in memory. The header counts are taken from the writer's accounting,
 * so the document can never claim more messages than it contains.
 */
class ExportRepository(private val context: Context) {

    private companion object {
        const val TAG = "ExportRepository"
        const val COPY_BUFFER_BYTES = 256 * 1024
        const val STALE_EXPORT_MS = 24 * 60 * 60 * 1000L
        const val WORK_FILE_PREFIX = ".work-"
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
        val providerTotal = source.count { count ->
            onProgress(TransferProgress(DataTransferPhase.COUNTING, count, 0))
        }
        onProgress(TransferProgress(DataTransferPhase.COUNTING, providerTotal, providerTotal))
        if (providerTotal == 0) {
            throw DataTransferException("No SMS messages were found on this phone; nothing was exported.")
        }

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

                // 1) Threaded rows, streamed conversation by conversation.
                var cursor: SmsProviderSource.ThreadKeys? = null
                var openThread: Long? = null
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val page = source.readByThreadPage(cursor)
                    if (page.rows.isEmpty()) break

                    for (row in page.rows) {
                        val thread = row.threadId
                        if (openThread == null || thread != openThread) {
                            if (openThread != null) body.endConversation()
                            body.startConversation(
                                address = row.address.ifBlank { UNKNOWN_ADDRESS },
                                threadId = thread
                            )
                            openThread = thread
                        }
                        body.writeMessage(row)
                    }

                    messagesWritten = body.messageCount
                    val next = page.next
                    if (next == null || next == cursor) {
                        throw DataTransferException(
                            "The SMS provider returned rows out of order; the export was aborted to avoid a corrupt file."
                        )
                    }
                    cursor = next
                    onProgress(TransferProgress(DataTransferPhase.WRITING, messagesWritten, providerTotal))
                    if (page.rows.size < SmsProviderSource.PAGE_SIZE) break
                }
                if (openThread != null) body.endConversation()

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
                        body.startConversation(address = address, threadId = 0L)
                        for (row in rows) body.writeMessage(row)
                        body.endConversation()
                    }
                    messagesWritten = body.messageCount
                    onProgress(TransferProgress(DataTransferPhase.WRITING, messagesWritten, providerTotal))
                }

                conversationsWritten = body.conversationCount
            }

            if (messagesWritten == 0) {
                throw DataTransferException("The SMS provider returned no rows during the export.")
            }
            if (messagesWritten < providerTotal) {
                Log.w(
                    TAG,
                    "Provider reported $providerTotal messages but $messagesWritten were exported; " +
                        "messages may have been deleted while the export was running"
                )
            }

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
                    total = providerTotal,
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
                                total = providerTotal,
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
                    total = providerTotal,
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
