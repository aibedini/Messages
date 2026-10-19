package com.autonomousone.messages.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The display contract for a SIM number: three carrier spellings converge on one grouped form, and
 * a manual value wins over the platform value without being rewritten when it cannot be parsed.
 */
class SimNumberFormatTest {

    @Test
    fun `all three Iranian spellings format identically`() {
        assertEquals("0933 442 2769", SimNumberFormat.format("+989334422769"))
        assertEquals("0933 442 2769", SimNumberFormat.format("989334422769"))
        assertEquals("0933 442 2769", SimNumberFormat.format("09334422769"))
    }

    @Test
    fun `spaces, dashes and country prefix zeroes are tolerated`() {
        assertEquals("0933 442 2769", SimNumberFormat.format("+98 933 442 2769"))
        assertEquals("0933 442 2769", SimNumberFormat.format("0933-442-2769"))
        assertEquals("0933 442 2769", SimNumberFormat.format("00989334422769"))
    }

    @Test
    fun `Persian and Arabic digits are converted before grouping`() {
        assertEquals("0933 442 2769", SimNumberFormat.format("۰۹۳۳۴۴۲۲۷۶۹"))
        assertEquals("0933 442 2769", SimNumberFormat.format("۰۹۳۳ ۴۴۲ ۲۷۶۹"))
        assertEquals("0933 442 2769", SimNumberFormat.format("٠٩٣٣٤٤٢٢٧٦٩"))
    }

    @Test
    fun `blank, short and non-Iranian inputs return null`() {
        assertNull(SimNumberFormat.format(null))
        assertNull(SimNumberFormat.format(""))
        assertNull(SimNumberFormat.format("   "))
        assertNull(SimNumberFormat.format("abc"))
        assertNull(SimNumberFormat.format("0933442"))
        assertNull(SimNumberFormat.format("02188888888")) // landline, not a mobile line
        assertNull(SimNumberFormat.format("+1234567890"))
        assertNull(SimNumberFormat.format("093344227690"))
    }

    @Test
    fun `last four and LRI isolation are stable`() {
        assertEquals("2769", SimNumberFormat.lastFour("+989334422769"))
        assertNull(SimNumberFormat.lastFour(""))
        assertEquals("\u20660933 442 2769\u2069", SimNumberFormat.isolate("0933 442 2769"))
    }

    @Test
    fun `manual number wins over the system number`() {
        assertEquals(
            "0912 345 6789",
            SimNumberDisplay.resolve("09123456789", "+989334422769")
        )
    }

    @Test
    fun `system number is the fallback when no manual value exists`() {
        assertEquals("0933 442 2769", SimNumberDisplay.resolve(null, "+989334422769"))
        assertEquals("0933 442 2769", SimNumberDisplay.resolve("  ", "+989334422769"))
    }

    @Test
    fun `neither value present means no number to show`() {
        assertNull(SimNumberDisplay.resolve(null, ""))
        assertNull(SimNumberDisplay.resolve(null, null))
        assertNull(SimNumberDisplay.resolve("", ""))
    }

    @Test
    fun `an unparseable manual value is shown as typed rather than replaced by the system value`() {
        assertEquals("my work line", SimNumberDisplay.resolve("my work line", "+989334422769"))
    }
}
