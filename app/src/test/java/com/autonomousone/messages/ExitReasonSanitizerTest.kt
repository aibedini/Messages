package com.autonomousone.messages

import com.autonomousone.messages.diagnostics.ExitReasonSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Phase 14 privacy gate. Pure JVM: no Android, no emulator, no disk.
 */
class ExitReasonSanitizerTest {

    @Test
    fun `phone numbers are redacted`() {
        val sanitized = ExitReasonSanitizer.sanitize("caller +989121234567 alt 09123456789")
        assertFalse(sanitized.contains("989121234567"))
        assertFalse(sanitized.contains("09123456789"))
        assertTrue(sanitized.contains("phone#"))
    }

    @Test
    fun `sms body assignments are redacted`() {
        val sanitized = ExitReasonSanitizer.sanitize("body=سلام this is a private sms")
        assertFalse(sanitized.contains("private sms"))
        assertFalse(sanitized.contains("سلام"))
        assertTrue(sanitized.contains("body=[redacted-body]"))
    }

    @Test
    fun `token like assignments are redacted`() {
        val sanitized = ExitReasonSanitizer.sanitize("token=abcdef0123456789abcdef0123456789")
        assertFalse(sanitized.contains("abcdef0123456789abcdef0123456789"))
        assertTrue(sanitized.contains("redacted"))
    }

    @Test
    fun `bearer jwt credentials are redacted`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0." +
            "dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
        val sanitized = ExitReasonSanitizer.sanitize("Authorization: Bearer " + jwt)
        assertFalse(sanitized.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertFalse(sanitized.contains(jwt))
        assertTrue(sanitized.contains("redacted"))
    }

    @Test
    fun `null and empty input is safe`() {
        assertEquals("", ExitReasonSanitizer.sanitize(null))
        assertEquals("", ExitReasonSanitizer.sanitize(""))
    }

    @Test
    fun `bounded excerpt is truncated at the byte cap`() {
        val cap = 1024
        val source = CountingInputStream(ByteArray(64 * 1024) { 'a'.code.toByte() })
        val excerpt = ExitReasonSanitizer.boundedExcerpt(source, cap)
        assertEquals(cap, excerpt.length)
        assertTrue(excerpt.length <= cap)
        assertEquals(cap, source.bytesRead)
    }

    @Test
    fun `null and empty traces are safe`() {
        assertEquals("", ExitReasonSanitizer.boundedExcerpt(null, 1024))
        assertEquals("", ExitReasonSanitizer.boundedExcerpt(ByteArrayInputStream(ByteArray(0)), 1024))
        assertEquals("", ExitReasonSanitizer.boundedExcerpt(ByteArrayInputStream(ByteArray(8)), 0))
        assertEquals("", ExitReasonSanitizer.sanitizeExcerpt(null))
    }

    @Test
    fun `sanitized excerpt redacts and respects the persisted cap`() {
        val payload = "phone +989121234567\n" + "x".repeat(40_000)
        val excerpt = ExitReasonSanitizer.sanitizeExcerpt(
            ByteArrayInputStream(payload.toByteArray(Charsets.UTF_8)),
            8 * 1024,
        )
        assertFalse(excerpt.contains("989121234567"))
        assertTrue(excerpt.length <= ExitReasonSanitizer.MAX_PERSISTED_EXCERPT_CHARS)
    }

    @Test
    fun `a failing trace stream still returns what was read`() {
        val failing = object : InputStream() {
            private var served = 0
            override fun read(): Int {
                if (served >= 10) throw IOException("boom")
                served++
                return 'b'.code
            }
        }
        assertEquals("bbbbbbbbbb", ExitReasonSanitizer.boundedExcerpt(failing, 128))
    }

    private class CountingInputStream(private val delegate: ByteArray) : InputStream() {
        var bytesRead = 0
            private set
        private var index = 0

        override fun read(): Int {
            if (index >= delegate.size) return -1
            bytesRead++
            return delegate[index++].toInt() and 0xFF
        }

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (index >= delegate.size) return -1
            val count = minOf(length, delegate.size - index)
            System.arraycopy(delegate, index, target, offset, count)
            index += count
            bytesRead += count
            return count
        }
    }
}
