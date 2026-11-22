package com.autonomousone.messages.repository

/**
 * Pure Kotlin writer for the "Export all chats" JSON archive.
 *
 * The previous implementation built the WHOLE archive as nested
 * `JSONArray`/`JSONObject` trees in memory. With 40–50k messages that is tens of
 * thousands of objects plus every SMS body held twice, which is exactly the
 * shape that made the export appear to hang (GC thrash / OOM) on a real inbox.
 *
 * This writer streams: one conversation and one message at a time, so peak
 * memory is one row, not one inbox. The counts in the header are taken from the
 * writer's own accounting, so `messageCount`/`conversationCount` always match
 * the `messages` arrays that follow.
 *
 * No Android imports: escaping and counting are unit-tested on the JVM.
 */
internal object SmsJsonFormat {

    /** Opening of the document, including the exact counts of what follows. */
    fun documentStart(
        app: String,
        exportedAt: Long,
        conversationCount: Int,
        messageCount: Int
    ): String = buildString(192) {
        append("{\n")
        append("  \"app\": ").append(quote(app)).append(",\n")
        append("  \"exportedAt\": ").append(exportedAt).append(",\n")
        append("  \"conversationCount\": ").append(conversationCount).append(",\n")
        append("  \"messageCount\": ").append(messageCount).append(",\n")
        append("  \"conversations\": [\n")
    }

    const val DOCUMENT_END: String = "\n  ]\n}\n"

    fun conversationStart(address: String, threadId: Long): String = buildString(128) {
        append("    {\n")
        append("      \"address\": ").append(quote(address)).append(",\n")
        append("      \"threadId\": ").append(threadId).append(",\n")
        append("      \"messages\": [\n")
    }

    const val CONVERSATION_END: String = "\n      ]\n    }"

    /** One message object, indented to sit inside a conversation's array. */
    fun message(row: SmsTransferRow): String = buildString(192) {
        append("{\"type\":")
        append(quote(if (row.type == 1) "received" else "sent"))
        append(",\"sender\":").append(quote(row.address.ifBlank { UNKNOWN_ADDRESS }))
        append(",\"message\":").append(quote(row.body))
        append(",\"date\":").append(row.date)
        append(",\"dateSent\":").append(row.dateSent)
        append(",\"status\":").append(row.status)
        append('}')
    }

    /**
     * Quotes [value] as a JSON string.
     *
     * Control characters are escaped rather than dropped (JSON permits them as
     * `\uXXXX`), unlike the XML writer where they are illegal outright. Lone
     * surrogates become U+FFFD so the resulting file is valid UTF-8.
     */
    fun quote(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            when {
                ch == '\\' -> out.append("\\\\")
                ch == '"' -> out.append("\\\"")
                ch == '\b' -> out.append("\\b")
                ch == '\u000C' -> out.append("\\f")
                ch == '\n' -> out.append("\\n")
                ch == '\r' -> out.append("\\r")
                ch == '\t' -> out.append("\\t")
                ch.code < 0x20 -> out.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                // Valid JSON, but they break several JS consumers; escaping is legal.
                ch == '\u2028' -> out.append("\\u2028")
                ch == '\u2029' -> out.append("\\u2029")
                ch.isHighSurrogate() -> {
                    val next = value.getOrNull(i + 1)
                    if (next != null && next.isLowSurrogate()) {
                        out.append(ch).append(next)
                        i++ // consume the low surrogate
                    } else {
                        out.append('\uFFFD')
                    }
                }
                ch.isLowSurrogate() -> out.append('\uFFFD')
                else -> out.append(ch)
            }
            i++
        }
        out.append('"')
        return out.toString()
    }
}

/**
 * Streams the `conversations` array to [out], tracking the exact number of
 * conversations and messages written. The repository uses those two numbers
 * for [SmsJsonFormat.documentStart].
 */
internal class SmsJsonBodyWriter(private val out: Appendable) {

    var messageCount: Int = 0
        private set

    var conversationCount: Int = 0
        private set

    private var conversationOpen = false
    private var firstMessageInConversation = true

    fun startConversation(address: String, threadId: Long) {
        check(!conversationOpen) { "A conversation is already open" }
        if (conversationCount > 0) out.append(",\n")
        out.append(SmsJsonFormat.conversationStart(address, threadId))
        conversationOpen = true
        conversationCount++
        firstMessageInConversation = true
    }

    fun writeMessage(row: SmsTransferRow) {
        check(conversationOpen) { "writeMessage() called with no open conversation" }
        if (!firstMessageInConversation) out.append(",\n")
        out.append("        ").append(SmsJsonFormat.message(row))
        firstMessageInConversation = false
        messageCount++
    }

    fun endConversation() {
        check(conversationOpen) { "endConversation() called with no open conversation" }
        out.append(SmsJsonFormat.CONVERSATION_END)
        conversationOpen = false
    }
}
