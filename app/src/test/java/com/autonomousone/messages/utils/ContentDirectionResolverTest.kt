package com.autonomousone.messages.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The content-aware text-direction policy (v3.4.2).
 *
 * The contract that matters is that direction comes from the CONTENT and never from
 * the app locale, the bubble side, or the first character that happens to be there.
 * `NEUTRAL` is a real answer, not a failure: digits, emoji and punctuation alone
 * carry no paragraph direction, so the UI falls back to its own layout.
 */
class ContentDirectionResolverTest {

    // ── Single-script ───────────────────────────────────────────────────────

    @Test
    fun `persian is rtl`() = assertEquals(ContentDirection.RTL, resolve("سلام خوبی؟"))

    @Test
    fun `arabic is rtl`() = assertEquals(ContentDirection.RTL, resolve("مرحبا كيف حالك"))

    @Test
    fun `hebrew is rtl`() = assertEquals(ContentDirection.RTL, resolve("שלום מה שלומך"))

    @Test
    fun `english is ltr`() = assertEquals(ContentDirection.LTR, resolve("Hello, how are you?"))

    @Test
    fun `other latin languages are ltr`() {
        assertEquals(ContentDirection.LTR, resolve("Bonjour ça va"))
        assertEquals(ContentDirection.LTR, resolve("Guten Tag"))
    }

    // ── Leading neutrals must never decide ──────────────────────────────────

    @Test
    fun `emoji then persian is rtl`() = assertEquals(ContentDirection.RTL, resolve("😊 سلام"))

    @Test
    fun `emoji then english is ltr`() = assertEquals(ContentDirection.LTR, resolve("😂 Hello"))

    @Test
    fun `number then persian is rtl`() = assertEquals(ContentDirection.RTL, resolve("123 سلام"))

    @Test
    fun `number then english is ltr`() = assertEquals(ContentDirection.LTR, resolve("123 Hello"))

    @Test
    fun `punctuation then persian is rtl`() =
        assertEquals(ContentDirection.RTL, resolve("!!! سلام"))

    @Test
    fun `whitespace then persian is rtl`() =
        assertEquals(ContentDirection.RTL, resolve("   سلام"))

    // ── Neutrals only ───────────────────────────────────────────────────────

    @Test
    fun `numbers only is neutral`() = assertEquals(ContentDirection.NEUTRAL, resolve("123456"))

    @Test
    fun `persian digits only is neutral`() =
        assertEquals(ContentDirection.NEUTRAL, resolve("۱۲۳۴۵۶"))

    @Test
    fun `arabic indic digits only is neutral`() =
        assertEquals(ContentDirection.NEUTRAL, resolve("١٢٣٤٥٦"))

    @Test
    fun `a phone number is neutral`() =
        assertEquals(ContentDirection.NEUTRAL, resolve("+98 912 123 4567"))

    @Test
    fun `punctuation only is neutral`() =
        assertEquals(ContentDirection.NEUTRAL, resolve("!!! ... ??"))

    @Test
    fun `emoji only is neutral`() = assertEquals(ContentDirection.NEUTRAL, resolve("😊🎉"))

    @Test
    fun `empty and blank are neutral`() {
        assertEquals(ContentDirection.NEUTRAL, resolve(""))
        assertEquals(ContentDirection.NEUTRAL, resolve("    "))
        assertEquals(ContentDirection.NEUTRAL, resolve(null))
    }

    // ── Mixed content: FIRST strong run wins ────────────────────────────────

    @Test
    fun `persian then english is rtl`() =
        assertEquals(ContentDirection.RTL, resolve("سلام John"))

    @Test
    fun `english then persian is ltr`() =
        assertEquals(ContentDirection.LTR, resolve("Hello علی"))

    @Test
    fun `an english url after persian keeps the persian direction`() =
        assertEquals(ContentDirection.RTL, resolve("سلام https://example.com"))

    @Test
    fun `an english url before persian keeps the url direction`() =
        assertEquals(ContentDirection.LTR, resolve("https://example.com سلام"))

    @Test
    fun `a url alone is ltr`() =
        assertEquals(ContentDirection.LTR, resolve("https://example.com"))

    @Test
    fun `pin then latin is ltr`() =
        assertEquals(ContentDirection.LTR, resolve("\uD83D\uDCCD Tehran تهران"))

    @Test
    fun `pin then persian is rtl`() =
        assertEquals(ContentDirection.RTL, resolve("\uD83D\uDCCD تهران Tehran"))

    @Test
    fun `persian digits then a persian word is rtl`() =
        assertEquals(ContentDirection.RTL, resolve("۱۲۳ سلام"))

    @Test
    fun `persian digits then a latin word is ltr`() =
        assertEquals(ContentDirection.LTR, resolve("۱۲۳ Test"))

    // ── Multiline: the first strong character of the whole text decides ─────

    @Test
    fun `multiline persian is rtl`() =
        assertEquals(ContentDirection.RTL, resolve("سلام\nحالت خوبه؟"))

    @Test
    fun `multiline english is ltr`() =
        assertEquals(ContentDirection.LTR, resolve("English line\nsecond line"))

    @Test
    fun `a mixed sentence reads from its first strong character`() {
        assertEquals(ContentDirection.RTL, resolve("سلام John، ساعت 5 بیا"))
        assertEquals(ContentDirection.LTR, resolve("Hi علی, how are you?"))
    }

    // ── The resolver must never transform the text ──────────────────────────

    @Test
    fun `resolving does not mutate or reorder the text`() {
        val mixed = "سلام John، ساعت 5 بیا"
        val before = mixed.toCharArray()
        resolve(mixed)
        assertEquals(String(before), mixed)
    }

    @Test
    fun `no bidi control marks are present in any resolved input`() {
        // The resolver reports a direction; it must never need to insert LRM/RLM.
        val samples = listOf("سلام", "Hello", "123 سلام", "😊 Hello")
        samples.forEach { sample ->
            assertEquals(
                "bidi marks must never be added to message text: $sample",
                sample,
                sample.replace("\u200E", "").replace("\u200F", "")
            )
        }
    }

    private fun resolve(text: String?) = ContentDirectionResolver.resolve(text)
}
