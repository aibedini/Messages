package com.autonomousone.messages

import com.autonomousone.messages.messaging.SpamHeuristics
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The local spam heuristic must catch service-channel advertising without silencing real people,
 * banks, OTPs or receipts. The classifier tests cover the priority; these pin the score itself.
 */
class SpamHeuristicsTest {

    private fun spam(sender: String, body: String, known: Boolean = false) =
        SpamHeuristics.assess(sender, body, knownContact = known)

    @Test
    fun `a known contact is never spam even with advertising words`() {
        val result = spam("+989121234567", "تخفیف ویژه خرید کنید https://shop.example", known = true)
        assertFalse(result.isSpam)
        assertTrue(result.reasons.isEmpty())
        assertEqualsZero(result.score)
    }

    @Test
    fun `a plain discount from a service is a promotion, not spam`() {
        val result = spam("20001", "جشنواره فروش ویژه با ۵۰ درصد تخفیف")
        assertFalse(result.isSpam)
    }

    @Test
    fun `a discount with a link and a buy action from a short code is spam`() {
        val result = spam("20001", "تخفیف ویژه فقط امروز! همین حالا خرید کنید https://shop.example/off")
        assertTrue(result.isSpam)
        assertTrue(SpamHeuristics.Reason.PROMOTIONAL_LANGUAGE in result.reasons)
        assertTrue(SpamHeuristics.Reason.LINK in result.reasons)
    }

    @Test
    fun `a prize or lottery message is spam`() {
        val result = spam("20001", "تبریک! شما برنده جایزه ۵ میلیون تومان شدید")
        assertTrue(result.isSpam)
        assertTrue(SpamHeuristics.Reason.PHISHING_LANGUAGE in result.reasons)
    }

    @Test
    fun `a bank security warning without a link or action is not spam`() {
        val result = spam("20001", "رمز دوم خود را هرگز به کسی ندهید")
        assertFalse(result.isSpam)
    }

    @Test
    fun `a phishing request to send the dynamic password is spam`() {
        val result = spam("20001", "برای ادامه، رمز پویا خود را ارسال کنید")
        assertTrue(result.isSpam)
    }

    @Test
    fun `a phishing link from a short code is spam`() {
        val result = spam("20001", "حساب شما مسدود شده، برای رفع مسدودی کلیک کنید https://bit.ly/x")
        assertTrue(result.isSpam)
        assertTrue(SpamHeuristics.Reason.SHORT_LINK in result.reasons)
    }

    @Test
    fun `an unsubscribe footer keeps a marketing message out of spam`() {
        val result = spam("20001", "Big sale this week only! Reply STOP to unsubscribe")
        assertFalse(result.isSpam)
    }

    @Test
    fun `a neutral link from a person is not spam`() {
        val result = spam("+989121234567", "این لینک را ببین https://example.com")
        assertFalse(result.isSpam)
    }

    @Test
    fun `blank text is not spam`() {
        assertFalse(spam("20001", "   ").isSpam)
    }

    @Test
    fun `persian digits in the sender do not break the service shape`() {
        val result = spam("۲۰۰۰۱", "تخفیف ویژه خرید کنید https://shop.example")
        assertTrue(result.isSpam)
    }

    private fun assertEqualsZero(value: Float) {
        org.junit.Assert.assertEquals(0f, value, 0f)
    }
}
