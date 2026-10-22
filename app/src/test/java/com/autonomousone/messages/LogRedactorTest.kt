package com.autonomousone.messages

import com.autonomousone.messages.utils.LogLevel
import com.autonomousone.messages.utils.LogLineFormatter
import com.autonomousone.messages.utils.LogRedactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactorTest {

    @Test
    fun `jwt is removed entirely`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
        val out = LogRedactor.redact("ws url token=$jwt end")
        assertFalse(out.contains("eyJ"))
        assertFalse(out.contains("dozjg"))
    }

    @Test
    fun `bearer header and secret pairs are masked`() {
        val out = LogRedactor.redact("""Authorization: Bearer abc.def-123 {"password":"hunter2","refresh_token":"r1"}""")
        assertFalse(out.contains("abc.def-123"))
        assertFalse(out.contains("hunter2"))
        assertFalse(out.contains("\"r1\""))
        assertTrue(out.contains("<redacted>"))
    }

    @Test
    fun `email keeps only first letter and domain`() {
        assertEquals("login a***@example.com ok", LogRedactor.redact("login agent.one@example.com ok"))
    }

    @Test
    fun `phone numbers become stable tokens`() {
        val out = LogRedactor.redact("from +989121234567")
        assertFalse(out.contains("9121234567"))
        assertTrue(out.startsWith("from phone#"))
    }

    @Test
    fun `formatter writes one structured line and redacts field values`() {
        val line = LogLineFormatter.format(
            isoTime = "2026-10-22T10:00:00Z",
            level = LogLevel.WARN,
            tag = "EVECHAT",
            thread = "main",
            message = "send failed\nretrying",
            fields = mapOf("c" to "outbox", "kind" to "NETWORK", "who" to "x@y.io", "skip" to null, "note" to "two words")
        )
        assertEquals(
            "2026-10-22T10:00:00Z W/EVECHAT [main] send failed retrying c=outbox kind=NETWORK who=x***@y.io note=\"two words\"\n",
            line
        )
    }
}
