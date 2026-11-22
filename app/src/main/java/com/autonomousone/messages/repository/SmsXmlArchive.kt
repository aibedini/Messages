package com.autonomousone.messages.repository

import java.io.BufferedWriter
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter

/**
 * Two-phase XML archive writer: stage every `<sms>` row in a private file, then
 * assemble `header + staged body + footer` into the user's destination.
 *
 * This is the fix for the truncated-backup bug. The header carries the number of
 * rows that were ACTUALLY staged, so it cannot claim messages that never made it
 * into the file. The destination is only opened after the staged body is
 * complete, and the number of copied bytes is verified before success is
 * reported.
 *
 * No Android imports: the whole staging/assembly contract is unit-tested on the
 * JVM, including a 50k-row archive.
 */
internal class SmsXmlArchive(private val workDir: File) {

    private companion object {
        const val WRITE_BUFFER_BYTES = 256 * 1024
        const val COPY_BUFFER_BYTES = 256 * 1024
    }

    /**
     * A body being staged in [file]. Close it through [SmsXmlArchive.assemble]
     * (or [SmsXmlArchive.discard] on failure), never directly.
     */
    class Stage internal constructor(
        val file: File,
        internal val writer: BufferedWriter,
        val body: SmsXmlBodyWriter
    ) {
        val rowCount: Int get() = body.rowCount

        /** Pushes buffered rows to disk so [File.length] is accurate. */
        fun flush() {
            writer.flush()
        }
    }

    /** Opens a staged body file inside [workDir]. */
    fun openStage(fileName: String): Stage {
        val file = File(workDir, fileName)
        val writer = BufferedWriter(
            OutputStreamWriter(file.outputStream(), Charsets.UTF_8),
            WRITE_BUFFER_BYTES
        )
        return Stage(file = file, writer = writer, body = SmsXmlBodyWriter(writer))
    }

    /**
     * Flushes the staged body, writes the complete document to [destination],
     * and verifies that every staged byte was copied.
     *
     * @param checkActive invoked between copy chunks so cancellation stops the
     *        copy instead of leaving a half-written destination.
     * @param onCopy receives `(copiedBodyBytes, bodyTotalBytes)` per chunk.
     * @return total document size in bytes.
     */
    fun assemble(
        stage: Stage,
        destination: OutputStream,
        checkActive: () -> Unit,
        onCopy: (copiedBytes: Long, bodyBytes: Long) -> Unit
    ): Long {
        stage.writer.close()

        val header = SmsXmlFormat.documentStart(stage.rowCount).toByteArray(Charsets.UTF_8)
        val footer = SmsXmlFormat.DOCUMENT_END.toByteArray(Charsets.UTF_8)
        val bodyBytes = stage.file.length()

        destination.write(header)
        var copiedBytes = 0L
        stage.file.inputStream().use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                checkActive()
                val read = input.read(buffer)
                if (read < 0) break
                destination.write(buffer, 0, read)
                copiedBytes += read
                onCopy(copiedBytes, bodyBytes)
            }
        }
        destination.write(footer)
        destination.flush()

        if (copiedBytes != bodyBytes) {
            throw DataTransferException(
                "Only $copiedBytes of $bodyBytes message bytes reached the file; it would have been incomplete."
            )
        }
        return header.size.toLong() + bodyBytes + footer.size
    }

    /**
     * Closes (if still open) and deletes the staged body. Safe to call after a
     * successful [assemble]; the stage file is always removed.
     */
    fun discard(stage: Stage) {
        try {
            stage.writer.close()
        } catch (_: Exception) {
            // Already closed or the disk failed; the file is deleted below anyway.
        }
        stage.file.delete()
    }
}
