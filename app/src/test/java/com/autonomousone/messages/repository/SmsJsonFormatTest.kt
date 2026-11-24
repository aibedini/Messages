package com.autonomousone.messages.repository

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks down the properties that made the old JSON export hang and lie:
 *
 * - writing is incremental (the body writer holds no row list),
 * - `conversationCount` and `messageCount` equal what the arrays contain,
 * - SMS text with quotes, backslashes, control characters and non-BMP
 *   characters survives a JSON round-trip.
 */
class SmsJsonFormatTest {

    private fun row(
        id: Long = 1L,
        threadId: Long = 3L,
        address: String = "+989120000000",
        body: String = "hello",
        date: Long = 1_700_000_000_000L,
        dateSent: Long = 1_700_000_000_001L,
        status: Int = -1,
        type: Int = 1
    ): SmsTransferRow = SmsTransferRow(
        id = id,
        threadId = threadId,
        address = address,
        body = body,
        date = date,
        dateSent = dateSent,
        read = 1,
        type = type,
        status = status
    )

    private fun document(block: SmsJsonBodyWriter.() -> Unit): Pair<JSONObject, SmsJsonBodyWriter> {
        val out = StringBuilder()
        val body = SmsJsonBodyWriter(out)
        body.block()
        val json = SmsJsonFormat.documentStart(
            app = "com.autonomousone.messages",
            exportedAt = 123L,
            conversationCount = body.conversationCount,
            messageCount = body.messageCount
        ) + out.toString() + SmsJsonFormat.DOCUMENT_END
        return JSONObject(json) to body
    }

    @Test
    fun `counts always equal what the arrays contain`() {
        val (root, body) = document {
            startConversation("+989120000001", 3L)
            writeMessage(row(id = 1, body = "one"))
            writeMessage(row(id = 2, body = "two"))
            endConversation()
            startConversation("+989120000002", 4L)
            writeMessage(row(id = 3, threadId = 4, body = "three"))
            endConversation()
        }

        assertEquals(2, body.conversationCount)
        assertEquals(3, body.messageCount)
        assertEquals(2, root.getInt("conversationCount"))
        assertEquals(3, root.getInt("messageCount"))

        val conversations = root.getJSONArray("conversations")
        assertEquals(2, conversations.length())
        val first = conversations.getJSONObject(0)
        assertEquals("+989120000001", first.getString("address"))
        assertEquals(3L, first.getLong("threadId"))
        val messages = first.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("one", messages.getJSONObject(0).getString("message"))
        assertEquals("received", messages.getJSONObject(0).getString("type"))
    }

    @Test
    fun `message text round-trips quotes backslashes controls and unicode`() {
        val tricky = "quote\" back\\ slash\nnewline\ttab\u0001ctrl 😀 سلام \u2028"
        val (root, _) = document {
            startConversation("+989120000000", 3L)
            writeMessage(row(body = tricky, type = 2))
            endConversation()
        }

        val message = root.getJSONArray("conversations")
            .getJSONObject(0)
            .getJSONArray("messages")
            .getJSONObject(0)

        assertEquals(tricky, message.getString("message"))
        assertEquals("sent", message.getString("type"))
    }

    @Test
    fun `lone surrogate is replaced so the archive stays valid utf8`() {
        val (root, _) = document {
            startConversation("+989120000000", 3L)
            writeMessage(row(body = "before\uD800after"))
            endConversation()
        }

        val text = root.getJSONArray("conversations")
            .getJSONObject(0)
            .getJSONArray("messages")
            .getJSONObject(0)
            .getString("message")

        assertEquals("before\uFFFDafter", text)
    }

    @Test
    fun `fifty thousand messages stream into a complete body`() {
        val out = StringBuilder()
        val body = SmsJsonBodyWriter(out)
        body.startConversation("+989120000000", 3L)
        repeat(50_000) { index ->
            body.writeMessage(row(id = index + 1L, body = "پیام شماره $index — ✅"))
        }
        body.endConversation()

        val json = SmsJsonFormat.documentStart(
            app = "com.autonomousone.messages",
            exportedAt = 123L,
            conversationCount = body.conversationCount,
            messageCount = body.messageCount
        ) + out.toString() + SmsJsonFormat.DOCUMENT_END

        assertEquals(50_000, body.messageCount)
        assertEquals(1, body.conversationCount)
        assertEquals(50_000, countOccurrences(json, "\"type\":"))
        assertEquals(50_000, countOccurrences(json, "\"sender\":"))
        assertTrue(json.startsWith("{\n  \"app\":"))
        assertTrue(json.endsWith("}\n"))
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
    fun `empty export is valid json with zero counts`() {
        val (root, body) = document { }

        assertEquals(0, body.messageCount)
        assertEquals(0, body.conversationCount)
        assertEquals(0, root.getInt("messageCount"))
        assertEquals(0, root.getJSONArray("conversations").length())
    }

    @Test
    fun `document can be built from one message per line without pretty-print blowup`() {
        val (_, body) = document {
            startConversation("+989120000000", 3L)
            writeMessage(row(body = "x".repeat(1_000)))
            endConversation()
        }

        assertEquals(1, body.messageCount)
    }

    @Test
    fun `quote escapes the characters that would break a json document`() {
        assertEquals(
            "\"a\\\"b\\\\c\\nd\\te\\u0001f\"",
            SmsJsonFormat.quote("a\"b\\c\nd\te\u0001f")
        )
        assertEquals("\"\\u2028\\u2029\"", SmsJsonFormat.quote("\u2028\u2029"))
    }

    @Test
    fun `senders are preserved as written`() {
        val (root, _) = document {
            startConversation("+98 & <office>", 9L)
            writeMessage(row(address = "+98 & <office>"))
            endConversation()
        }

        val conversation = root.getJSONArray("conversations").getJSONObject(0)
        assertEquals("+98 & <office>", conversation.getString("address"))
        assertEquals(
            "+98 & <office>",
            conversation.getJSONArray("messages").getJSONObject(0).getString("sender")
        )
    }
}
