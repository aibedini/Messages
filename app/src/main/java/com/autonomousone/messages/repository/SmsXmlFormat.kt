package com.autonomousone.messages.repository

/**
 * Pure Kotlin writer for the "SMS Backup & Restore" XML shape:
 *
 * ```
 * <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
 * <smses count="123">
 * <sms address="..." body="..." date="..." date_sent="..." read="1" type="1" status="-1" thread_id="1" />
 * ...
 * </smses>
 * ```
 *
 * Why this is hand-written rather than `android.util.Xml`:
 *
 * 1. The count attribute must match the number of rows that ACTUALLY made it
 *    into the file. The old writer put `cursor.count` in the header before
 *    reading a single row, so any interruption produced a file whose header
 *    claimed more messages than it contained. Here the body is staged first and
 *    the header is emitted from the staged row count.
 * 2. `KXmlSerializer` silently emits characters that are illegal in XML 1.0
 *    (control bytes in SMS bodies), producing a file that some restorers
 *    reject. [sanitize] strips them deliberately.
 * 3. This file has no Android imports, so its escaping and accounting are
 *    covered by JVM unit tests.
 */
internal object SmsXmlFormat {

    const val HEADER: String = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"

    const val DOCUMENT_END: String = "</smses>\n"

    /** Opening tag carrying the EXACT number of `<sms>` rows that follow. */
    fun documentStart(rowCount: Int): String = "$HEADER\n<smses count=\"$rowCount\">\n"

    /** One self-closing `<sms ... />` element, newline-terminated. */
    fun row(row: SmsTransferRow): String = buildString(256) {
        append("<sms")
        attribute("address", row.address)
        attribute("body", row.body)
        append(" date=\"").append(row.date).append('"')
        append(" date_sent=\"").append(row.dateSent).append('"')
        append(" read=\"").append(row.read).append('"')
        append(" type=\"").append(row.type).append('"')
        append(" status=\"").append(row.status).append('"')
        append(" thread_id=\"").append(row.threadId).append('"')
        append(" />\n")
    }

    /**
     * Makes [value] safe for an XML 1.0 attribute and escapes the five markup
     * characters plus whitespace that XML would otherwise normalize.
     *
     * Unpaired surrogates are replaced with U+FFFD because they cannot be
     * encoded as UTF-8; well-formed surrogate pairs (emoji) are preserved.
     */
    fun escapeAttribute(value: String): String {
        val clean = sanitize(value)
        val out = StringBuilder(clean.length + 16)
        for (ch in clean) {
            when (ch) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&apos;")
                // Character references survive attribute-value normalization,
                // so tabs/newlines inside an SMS body round-trip exactly.
                '\t' -> out.append("&#9;")
                '\n' -> out.append("&#10;")
                '\r' -> out.append("&#13;")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    /**
     * Drops characters XML 1.0 forbids and repairs lone surrogates.
     *
     * Allowed: TAB, LF, CR, U+0020..U+D7FF, U+E000..U+FFFD, and supplementary
     * characters (surrogate pairs).
     */
    fun sanitize(value: String): String {
        var i = 0
        val out = StringBuilder(value.length)
        while (i < value.length) {
            val ch = value[i]
            when {
                ch == '\t' || ch == '\n' || ch == '\r' -> out.append(ch)
                ch.code < 0x20 -> Unit // forbidden control character
                ch.isHighSurrogate() -> {
                    val next = value.getOrNull(i + 1)
                    if (next != null && next.isLowSurrogate()) {
                        out.append(ch).append(next)
                        i++
                    } else {
                        out.append('\uFFFD')
                    }
                }
                ch.isLowSurrogate() -> out.append('\uFFFD')
                ch == '\uFFFE' || ch == '\uFFFF' -> Unit // non-characters
                ch.code in 0x7F..0x9F -> out.append(ch) // valid XML 1.0, keep
                else -> out.append(ch)
            }
            i++
        }
        return out.toString()
    }

    private fun StringBuilder.attribute(name: String, value: String) {
        append(' ').append(name).append("=\"").append(escapeAttribute(value)).append('"')
    }
}

/**
 * Streams `<sms>` rows to [out] and counts exactly what was written.
 *
 * The repository writes this into a private staging file, then emits
 * [SmsXmlFormat.documentStart] from [rowCount] — that is why the header can
 * never disagree with the body.
 */
internal class SmsXmlBodyWriter(private val out: Appendable) {

    var rowCount: Int = 0
        private set

    fun writeRow(row: SmsTransferRow) {
        out.append(SmsXmlFormat.row(row))
        rowCount++
    }
}
