package com.autonomousone.messages.ui.home

import com.autonomousone.messages.model.Sms
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSearchTest {

    private fun sms(
        sender: String = "+989120000001",
        message: String = "سلام، این یک پیام تست است",
        threadId: Long = 1L,
    ) = Sms(
        id = threadId,
        threadId = threadId,
        sender = sender,
        message = message,
        date = 0L,
        unread = false,
        type = 1,
    )

    @Test
    fun blankQuery_matchesEverything() {
        val s = sms()
        assertTrue(HomeSearch.matches(s, "", emptyMap()))
        assertTrue(HomeSearch.matches(s, "   ", emptyMap()))
    }

    @Test
    fun matchesDisplayName_viaContactMap() {
        // HomeSearch resolves names through ContactRepository.normalizePhone,
        // which preserves a leading "+" (it only strips spaces/-/()/extra plus).
        val contacts = mapOf("+989120000001" to "آرش محمدی")
        val s = sms(sender = "+989120000001")
        assertTrue(HomeSearch.matches(s, "آرش", contacts))
        assertTrue(HomeSearch.matches(s, "محمدی", contacts))
        // Case-insensitive ASCII name match.
        assertTrue(HomeSearch.matches(s, "ARSH", mapOf("+989120000001" to "Arsh M.")))
    }

    @Test
    fun matchesRawSenderOrSnippet() {
        val s = sms(sender = "+989123456789", message = "قرار ساعت ۸ فردا")
        assertTrue(HomeSearch.matches(s, "+989123456789", emptyMap()))
        assertTrue(HomeSearch.matches(s, "فردا", emptyMap()))
    }

    @Test
    fun matchesNormalizedDigits() {
        // "0912" must match "+98 0912 ..." once digits are normalized
        // (spaces/+/dashes stripped). Choose a number whose raw digit stream
        // actually contains "0912".
        val s = sms(sender = "+989 09 12 34 56", message = "x")
        assertTrue(HomeSearch.matches(s, "0912", emptyMap()))
    }

    @Test
    fun nonMatchingQuery_false() {
        val s = sms(sender = "+989120000001", message = "hello world")
        assertFalse(HomeSearch.matches(s, "nonexistent", emptyMap()))
        assertFalse(HomeSearch.matches(s, "999999", emptyMap()))
    }

    @Test
    fun looksLikeNumber_heuristic() {
        assertTrue(HomeSearch.looksLikeNumber("0912"))
        assertTrue(HomeSearch.looksLikeNumber("+989120000001"))
        assertFalse(HomeSearch.looksLikeNumber("ab"))
        assertFalse(HomeSearch.looksLikeNumber(""))
        assertFalse(HomeSearch.looksLikeNumber("12"))  // too few digits
        // Letters mixed with digits shouldn't be treated as a direct-send number.
        assertFalse(HomeSearch.looksLikeNumber("foo91"))
    }
}
