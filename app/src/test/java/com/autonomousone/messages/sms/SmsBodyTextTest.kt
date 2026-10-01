package com.autonomousone.messages.sms

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transmitted body is user content.
 *
 * These tests exist because two gateway routes used to read the message and
 * then send `json.optString(...).trim()` — silently deleting leading/trailing
 * line breaks and spaces from a message the sender had written, on a channel
 * where the recipient cannot tell it was edited.
 */
class SmsBodyTextTest {

    // ── multiline bodies survive byte-for-byte ───────────────────────────────

    @Test
    fun `two lines survive exactly`() {
        assertPreserved("line1\nline2")
    }

    @Test
    fun `persian multiline survives exactly`() {
        assertPreserved("سلام\nخوبی؟\nفردا ساعت ۸ می‌بینمت.")
    }

    @Test
    fun `mixed scripts survive exactly`() {
        assertPreserved("Hello\nسلام\n123")
    }

    @Test
    fun `an empty middle line is content and must survive`() {
        assertPreserved("line1\n\nline3")
        assertPreserved("اول\nدوم\n\nچهارم")
    }

    @Test
    fun `leading and trailing line breaks survive`() {
        assertPreserved("\nline1\nline2\n")
    }

    @Test
    fun `leading and trailing spaces survive`() {
        assertPreserved(" first line\nsecond line ")
        assertPreserved("   indented\n\ttabbed   ")
    }

    @Test
    fun `emoji survive with no corruption and no replacement character`() {
        val body = "سلام 👋\nخوبی؟ ❤️"
        assertPreserved(body)
        assertFalse("U+FFFD must never appear", body.contains('\uFFFD'))
    }

    @Test
    fun `a body shaped like gsm7 and one shaped like ucs2 both pass through untouched`() {
        // The normaliser is not an encoder: it must not touch length, structure or
        // any code point. SmsManager.divideMessage stays the segmentation authority.
        val gsm = "Hello world\nSecond line"
        val ucs2 = "سلام دنیا\nخط دوم"
        val mixed = "Hello\nسلام"
        val emoji = "Hello 👋"

        assertPreserved(gsm)
        assertPreserved(ucs2)
        assertPreserved(mixed)
        assertPreserved(emoji)
    }

    // ── line-ending representation only ──────────────────────────────────────

    @Test
    fun `crlf becomes lf`() {
        assertEquals("line1\nline2", SmsBodyText.normalizeLineEndings("line1\r\nline2"))
    }

    @Test
    fun `lone cr becomes lf`() {
        assertEquals("line1\nline2", SmsBodyText.normalizeLineEndings("line1\rline2"))
    }

    @Test
    fun `repeated newlines are never collapsed`() {
        assertEquals("\n\n", SmsBodyText.normalizeLineEndings("\n\n"))
        assertEquals("a\n\nb", SmsBodyText.normalizeLineEndings("a\r\n\r\nb"))
        assertEquals("a\n\nb", SmsBodyText.normalizeLineEndings("a\r\rb"))
        assertEquals("a\n\n\nb", SmsBodyText.normalizeLineEndings("a\n\n\nb"))
    }

    @Test
    fun `normalisation is idempotent`() {
        val once = SmsBodyText.normalizeLineEndings("a\r\nb\rc\nd")
        assertEquals(once, SmsBodyText.normalizeLineEndings(once))
    }

    // ── blank rejection without mutation ─────────────────────────────────────

    @Test
    fun `whitespace-only bodies are rejected as blank`() {
        for (blank in listOf("", " ", "   ", "\n", "\n\n", " \n ", "\r\n")) {
            assertFalse("[$blank] must be rejected", SmsBodyText.isSendable(blank))
        }
    }

    @Test
    fun `rejection never mutates the body`() {
        // Rejection is a verdict on the value, not an edit of it: the caller still
        // holds exactly what it was given. (Only the line-ending representation may
        // change, and only for \r\n / \r — never a character of content.)
        for (blank in listOf(" ", "\n", " \n ", "\n\n")) {
            assertEquals(blank, SmsBodyText.normalizeLineEndings(blank))
        }
        assertEquals("\n", SmsBodyText.normalizeLineEndings("\r\n"))
        assertEquals("\n", SmsBodyText.normalizeLineEndings("\r"))
    }

    @Test
    fun `any body with a visible character is sendable even when padded with newlines`() {
        assertTrue(SmsBodyText.isSendable("\n\nx\n\n"))
        assertTrue(SmsBodyText.isSendable("سلام"))
        assertTrue(SmsBodyText.isSendable("👋"))
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun assertPreserved(body: String) {
        val normalized = SmsBodyText.normalizeLineEndings(body)
        assertEquals("body must be preserved exactly", body, normalized)
        assertArrayEquals(
            "UTF-8 bytes must be identical (no encoding drift)",
            body.toByteArray(Charsets.UTF_8),
            normalized.toByteArray(Charsets.UTF_8)
        )
    }
}
