package com.autonomousone.messages

import com.autonomousone.messages.utils.IncomingShareParser
import org.junit.Assert.assertEquals
import org.junit.Test

/** External share-intent parsing (ACTION_SEND / ACTION_SENDTO). */
class IncomingShareParserTest {

    @Test
    fun `sendto with encoded plus number and body`() {
        val r = IncomingShareParser.fromSendTo("sms:%2B989121234567?body=Salam", null, null)
        assertEquals("+989121234567", r.phone)
        assertEquals("Salam", r.text)
    }

    @Test
    fun `sendto literal plus survives decoding`() {
        // '+' must NOT become a space (classic URLDecoder pitfall)
        val r = IncomingShareParser.fromSendTo("smsto:+989121234567", null, null)
        assertEquals("+989121234567", r.phone)
        assertEquals("", r.text)
    }

    @Test
    fun `sendto sms_body extra wins over query`() {
        val r = IncomingShareParser.fromSendTo(
            "sms:09120000000?body=query", "extra", null
        )
        assertEquals("extra", r.text)
    }

    @Test
    fun `sendto falls back to EXTRA_TEXT when no body anywhere`() {
        val r = IncomingShareParser.fromSendTo("smsto:09120000000", null, "shared text")
        assertEquals("shared text", r.text)
    }

    @Test
    fun `plain send carries only text`() {
        val r = IncomingShareParser.fromSend("look at this")
        assertEquals("", r.phone)
        assertEquals("look at this", r.text)
    }

    @Test
    fun `null uri yields text-only result`() {
        val r = IncomingShareParser.fromSendTo(null, null, "just text")
        assertEquals("", r.phone)
        assertEquals("just text", r.text)
    }

    @Test
    fun `dialer mmsto uri preserves recipient and encoded draft`() {
        val r = IncomingShareParser.fromSendTo(
            "mmsto:%2B989121234567?body=hello%20there",
            null,
            null
        )
        assertEquals("+989121234567", r.phone)
        assertEquals("hello there", r.text)
    }

    @Test
    fun `mms uri accepts sms_body query parameter`() {
        val r = IncomingShareParser.fromSendTo(
            "mms:09121234567?sms_body=draft%20message",
            null,
            null
        )
        assertEquals("09121234567", r.phone)
        assertEquals("draft message", r.text)
    }
}
