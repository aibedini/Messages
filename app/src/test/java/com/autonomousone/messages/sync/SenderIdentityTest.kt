package com.autonomousone.messages.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SMS sender identity must survive to GMweb byte for byte.
 *
 * Proven root cause: `MessageEntity` carries `rawAddress` (the provider's exact string) and
 * `normalizedAddress` (a phone-matching helper), and cloud events were built from the HELPER. For a
 * branded sender the helper is empty, so GMweb received `address: ""` and could only show "Unknown
 * number / Unknown conversation" — for every short code, brand and alphanumeric id.
 *
 * NOTE: written and executed as part of this change (pure, no Android).
 */
class SenderIdentityTest {

    // ── identity preservation ────────────────────────────────────────────────

    @Test
    fun `branded and alphanumeric senders survive exactly`() {
        for (raw in listOf("PARSIANBANK", "ResalatBank", "Google", "Ssh3-652")) {
            assertEquals(raw, SenderIdentity.eventAddress(raw, ""))
            assertEquals(SenderKind.ALPHANUMERIC, SenderIdentity.classify(raw, ""))
        }
    }

    @Test
    fun `the raw value is never lowercased, stripped or digit-converted`() {
        val raw = "Ssh3-652"
        val emitted = SenderIdentity.eventAddress(raw, "")

        assertEquals("Ssh3-652", emitted)
        assertTrue("the hyphen is part of the identity", emitted.contains("-"))
        assertTrue("case is part of the identity", emitted.contains("Ssh"))
        assertFalse(emitted.all { it.isDigit() })
    }

    @Test
    fun `a short code stays a short code, not a phone number`() {
        assertEquals("3000", SenderIdentity.eventAddress("3000", "3000"))
        assertEquals(SenderKind.SHORT_CODE, SenderIdentity.classify("3000", "3000"))
        assertEquals("10005", SenderIdentity.eventAddress("10005", "10005"))
    }

    @Test
    fun `phone numbers are preserved as the raw display address in both spellings`() {
        assertEquals("09121234567", SenderIdentity.eventAddress("09121234567", "09121234567"))
        assertEquals("+989121234567", SenderIdentity.eventAddress("+989121234567", "989121234567"))
        assertEquals(SenderKind.PHONE, SenderIdentity.classify("+989121234567", "989121234567"))
    }

    @Test
    fun `Persian digit senders are preserved as the raw provider value`() {
        val raw = "۰۹۱۲۱۲۳۴۵۶۷"
        val emitted = SenderIdentity.eventAddress(raw, "09121234567")

        // The provider's own spelling leaves the device; the helper stays internal.
        assertEquals(raw, emitted)
        assertEquals(SenderKind.PHONE, SenderIdentity.classify(raw, null))
    }

    @Test
    fun `a raw value wins over the helper, always`() {
        assertEquals("PARSIANBANK", SenderIdentity.eventAddress("PARSIANBANK", "9999999"))
        assertEquals("09121234567", SenderIdentity.eventAddress("09121234567", "+989121234567"))
    }

    @Test
    fun `the helper is used only when the provider gave nothing`() {
        assertEquals("09121234567", SenderIdentity.eventAddress("", "09121234567"))
        assertEquals("09121234567", SenderIdentity.eventAddress(null, "09121234567"))
        assertEquals("09121234567", SenderIdentity.eventAddress("   ", "09121234567"))
    }

    // ── unknown is a last resort, never a fallback for a brand ───────────────

    @Test
    fun `only a genuinely absent address is unknown`() {
        assertEquals(SenderKind.UNKNOWN, SenderIdentity.classify("", ""))
        assertEquals(SenderKind.UNKNOWN, SenderIdentity.classify(null, null))
        assertEquals(SenderKind.UNKNOWN, SenderIdentity.classify("   ", "  "))
        assertEquals("", SenderIdentity.eventAddress(null, null))
    }

    @Test
    fun `a branded sender is NOT unknown`() {
        for (raw in listOf("PARSIANBANK", "ResalatBank", "Google", "Ssh3-652", "3000")) {
            assertFalse("$raw must not be classified UNKNOWN", SenderIdentity.classify(raw, "") == SenderKind.UNKNOWN)
        }
    }

    @Test
    fun `different alphanumeric senders never collapse into one empty identity`() {
        val a = SenderIdentity.eventAddress("PARSIANBANK", "")
        val b = SenderIdentity.eventAddress("ResalatBank", "")

        assertFalse(a == b)
        assertTrue(a.isNotBlank() && b.isNotBlank())
    }

    // ── contact lookup is for phone-shaped senders only ─────────────────────

    @Test
    fun `contacts are consulted only for real phone numbers`() {
        assertEquals("989121234567", SenderIdentity.contactLookupKey("+989121234567", "989121234567"))
        assertEquals("09121234567", SenderIdentity.contactLookupKey("09121234567", "09121234567"))
    }

    @Test
    fun `contacts are never consulted for brands, short codes or alphanumerics`() {
        assertNull(SenderIdentity.contactLookupKey("PARSIANBANK", ""))
        assertNull(SenderIdentity.contactLookupKey("Google", ""))
        assertNull(SenderIdentity.contactLookupKey("Ssh3-652", ""))
        assertNull(SenderIdentity.contactLookupKey("3000", "3000"))
        assertNull(SenderIdentity.contactLookupKey(null, null))
    }

    @Test
    fun `the lookup key falls back to the raw number when the helper is empty`() {
        // A phone-shaped raw value that was never normalised must still find its contact.
        assertEquals("09121234567", SenderIdentity.contactLookupKey("09121234567", ""))
    }

    @Test
    fun `replyability follows the same classification`() {
        assertTrue(SenderIdentity.isReplyable(SenderKind.PHONE))
        assertFalse(SenderIdentity.isReplyable(SenderKind.SHORT_CODE))
        assertFalse(SenderIdentity.isReplyable(SenderKind.ALPHANUMERIC))
        assertFalse(SenderIdentity.isReplyable(SenderKind.UNKNOWN))
    }
}
