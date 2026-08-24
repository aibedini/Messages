package com.autonomousone.messages

import com.autonomousone.messages.utils.DigitNormalizer
import com.autonomousone.messages.utils.SmsSegmentCounter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsCounterAndDigitsTest {

    // ── SmsSegmentCounter ────────────────────────────────────────────────────

    @Test
    fun `empty message has zero segments`() {
        val info = SmsSegmentCounter.count("")
        assertEquals(0, info.segments)
    }

    @Test
    fun `short ascii is one gsm7 segment`() {
        val info = SmsSegmentCounter.count("Hello, this is a plain English SMS!")
        assertEquals(1, info.segments)
        assertEquals(SmsSegmentCounter.Encoding.GSM7, info.encoding)
        assertEquals(160, info.charsPerSegment)
    }

    @Test
    fun `long ascii splits at 153 chars per part`() {
        val text = "a".repeat(320) // ceil(320/153) = 3
        val info = SmsSegmentCounter.count(text)
        assertEquals(3, info.segments)
        assertEquals(SmsSegmentCounter.Encoding.GSM7, info.encoding)
        assertEquals(153, info.charsPerSegment)
        assertEquals(139, info.charsRemainingInLast) // 459 - 320
    }

    @Test
    fun `persian text uses ucs2 with 70 char limit`() {
        val persian = "سلام، این یک پیام آزمایشی فارسی است"
        val info = SmsSegmentCounter.count(persian)
        assertEquals(1, info.segments)
        assertEquals(SmsSegmentCounter.Encoding.UCS2, info.encoding)
        assertEquals(70, info.charsPerSegment)
        assertTrue(info.charsRemainingInLast > 0)
    }

    @Test
    fun `long persian splits at 67 per part`() {
        val text = "خ".repeat(150) // ceil(150/67) = 3
        val info = SmsSegmentCounter.count(text)
        assertEquals(3, info.segments)
        assertEquals(SmsSegmentCounter.Encoding.UCS2, info.encoding)
        assertEquals(67, info.charsPerSegment)
    }

    @Test
    fun `one emoji flips to ucs2`() {
        val info = SmsSegmentCounter.count("hi 😀")
        assertEquals(SmsSegmentCounter.Encoding.UCS2, info.encoding)
    }

    @Test
    fun `extended gsm chars count double`() {
        // '[', ']' are extended-table → 2 units each. "[][]" = 8 units.
        val info = SmsSegmentCounter.count("[][]")
        assertEquals(1, info.segments)
        assertEquals(152, info.charsRemainingInLast)
    }

    // ── DigitNormalizer ──────────────────────────────────────────────────────

    @Test
    fun `persian digits convert to ascii`() {
        assertEquals("09124887338", DigitNormalizer.toAsciiDigits("۰۹۱۲۴۸۸۷۳۳۸"))
    }

    @Test
    fun `arabic-indic digits convert to ascii`() {
        assertEquals("09124887338", DigitNormalizer.toAsciiDigits("٠٩١٢٤٨٨٧٣٣٨"))
    }

    @Test
    fun `mixed persian and ascii works`() {
        assertEquals("0+98-912 345", DigitNormalizer.toAsciiDigits("۰+۹۸-۹۱۲ ۳۴۵"))
    }

    @Test
    fun `plain ascii passes through unchanged`() {
        val s = "+989121234567"
        assertEquals(s, DigitNormalizer.toAsciiDigits(s))
    }

    @Test
    fun `detection finds non-ascii digits`() {
        assertTrue(DigitNormalizer.hasNonAsciiDigits("خط ۰۹۱۲"))
        assertFalse(DigitNormalizer.hasNonAsciiDigits("خط 0912"))
    }
}
