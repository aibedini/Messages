package com.autonomousone.messages.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.StringWriter
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Locks down the properties that made the old backup wrong:
 *
 * - the `<smses count>` value must equal the number of `<sms>` rows actually
 *   written (the old writer took it from the cursor before reading anything),
 * - every body character that XML allows must round-trip (Persian, emoji,
 *   quotes, newlines, tabs),
 * - characters XML forbids must be removed instead of producing a file that
 *   restorers reject.
 */
class SmsXmlFormatTest {

    private fun row(
        id: Long = 1L,
        threadId: Long = 42L,
        address: String = "+989120000000",
        body: String = "hello",
        date: Long = 1_700_000_000_000L,
        dateSent: Long = 1_700_000_000_001L,
        read: Int = 1,
        type: Int = 1,
        status: Int = -1
    ): SmsTransferRow = SmsTransferRow(
        id = id,
        threadId = threadId,
        address = address,
        body = body,
        date = date,
        dateSent = dateSent,
        read = read,
        type = type,
        status = status
    )

    private fun parse(xml: String): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        val document = factory.newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        return document.documentElement
    }

    private fun document(rows: List<SmsTransferRow>): String {
        val out = StringWriter()
        val body = SmsXmlBodyWriter(out)
        rows.forEach(body::writeRow)
        return SmsXmlFormat.documentStart(body.rowCount) + out.toString() + SmsXmlFormat.DOCUMENT_END
    }

    @Test
    fun `count attribute always equals the rows actually written`() {
        val rows = (1..1_000).map { index ->
            row(id = index.toLong(), body = "message $index")
        }
        val root = parse(document(rows))

        assertEquals("1000", root.getAttribute("count"))
        assertEquals(1_000, root.getElementsByTagName("sms").length)
        assertEquals(1_000, SmsXmlBodyWriter(StringWriter()).also { writer ->
            rows.forEach(writer::writeRow)
        }.rowCount)
    }

    @Test
    fun `attributes round-trip exactly including markup whitespace and persian`() {
        val bodyText = "a & b < c > d \"q\" 'x'\nsecond\tline — سلام دنیا 😀"
        val root = parse(
            document(
                listOf(
                    row(
                        address = "+98 & <office>",
                        body = bodyText,
                        date = 123L,
                        dateSent = 456L,
                        read = 0,
                        type = 2,
                        status = 64,
                        threadId = 7L
                    )
                )
            )
        )
        val sms = root.getElementsByTagName("sms").item(0) as Element

        assertEquals("+98 & <office>", sms.getAttribute("address"))
        assertEquals(bodyText, sms.getAttribute("body"))
        assertEquals("123", sms.getAttribute("date"))
        assertEquals("456", sms.getAttribute("date_sent"))
        assertEquals("0", sms.getAttribute("read"))
        assertEquals("2", sms.getAttribute("type"))
        assertEquals("64", sms.getAttribute("status"))
        assertEquals("7", sms.getAttribute("thread_id"))
    }

    @Test
    fun `illegal xml characters are stripped and do not truncate the file`() {
        val dirty = "a\u0000b\u0001c\u001Fd\uFFFEe\uFFFFf"
        val root = parse(document(listOf(row(body = dirty), row(id = 2L, body = "after"))))

        val entries = root.getElementsByTagName("sms")
        assertEquals(2, entries.length)
        assertEquals("abcdef", (entries.item(0) as Element).getAttribute("body"))
        assertEquals("after", (entries.item(1) as Element).getAttribute("body"))
    }

    @Test
    fun `unpaired surrogates become replacement characters instead of invalid utf8`() {
        val root = parse(document(listOf(row(body = "before\uD800after"))))

        val body = (root.getElementsByTagName("sms").item(0) as Element).getAttribute("body")
        assertEquals("before\uFFFDafter", body)
    }

    @Test
    fun `attribute escaping uses character references for whitespace`() {
        assertEquals(
            "&amp;&lt;&gt;&quot;&apos;&#9;&#10;&#13;",
            SmsXmlFormat.escapeAttribute("&<>\"'\t\n\r")
        )
    }

    @Test
    fun `empty document is still valid xml with count zero`() {
        val root = parse(document(emptyList()))

        assertEquals("0", root.getAttribute("count"))
        assertEquals(0, root.getElementsByTagName("sms").length)
    }

    @Test
    fun `fifty thousand staged rows assemble into a complete archive`() {
        val workDir = Files.createTempDirectory("sms-xml-archive").toFile()
        val archive = SmsXmlArchive(workDir)
        val stage = archive.openStage("body.xml")
        try {
            repeat(50_000) { index ->
                stage.body.writeRow(
                    row(
                        id = index + 1L,
                        threadId = (index % 500).toLong(),
                        body = "پیام شماره $index — deliverable ✅"
                    )
                )
            }

            val out = ByteArrayOutputStream()
            val totalBytes = archive.assemble(stage, out, checkActive = {}, onCopy = { _, _ -> })
            val xml = String(out.toByteArray(), Charsets.UTF_8)

            assertEquals(50_000, stage.rowCount)
            assertEquals(totalBytes, out.size().toLong())
            assertEquals(
                "<smses count=\"50000\">",
                xml.lineSequence().first { it.startsWith("<smses") }
            )
            assertEquals(50_000, countOccurrences(xml, "<sms "))
            assertTrue(xml.endsWith("</smses>\n"))
        } finally {
            archive.discard(stage)
            workDir.deleteRecursively()
        }
    }

    private fun countOccurrences(text: String, needle: String): Int {
        var count = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            count++
            index = text.indexOf(needle, index + needle.length)
        }
        return count
    }

    @Test
    fun `assembly fails loudly when the destination rejects bytes`() {
        val workDir = Files.createTempDirectory("sms-xml-fail").toFile()
        val archive = SmsXmlArchive(workDir)
        val stage = archive.openStage("body.xml")
        try {
            stage.body.writeRow(row(body = "hello"))

            val failingDestination = object : OutputStream() {
                private var written = 0

                override fun write(b: Int) {
                    if (written >= 16) throw IOException("disk full")
                    written++
                }
            }

            var failed = false
            try {
                archive.assemble(stage, failingDestination, checkActive = {}, onCopy = { _, _ -> })
            } catch (expected: IOException) {
                failed = true
            }
            assertTrue("A short write must not be reported as a successful backup", failed)
        } finally {
            archive.discard(stage)
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `header matches sms backup and restore contract`() {
        val xml = document(emptyList())

        assertTrue(
            "Header must stay compatible with SMS Backup & Restore",
            xml.startsWith(
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n" +
                    "<smses count=\"0\">"
            )
        )
        assertTrue(xml.endsWith("</smses>\n"))
    }
}
