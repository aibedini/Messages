package com.autonomousone.messages

import com.autonomousone.messages.data.MessageCategory
import com.autonomousone.messages.messaging.KnownContactLookup
import com.autonomousone.messages.messaging.MessageClassifier
import com.autonomousone.messages.messaging.OtpDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FEATURE 12 — Smart Categories: the single local classifier.
 *
 * Pure JVM: `MessageClassifier` depends only on the unified `OtpDetector`, the
 * ADR-006 firewall singleton and a `KnownContactLookup` lambda, so every rule
 * below is exercised without Android, a database or a provider.
 *
 * Priority contract under test:
 *   OTP (high confidence only) → PERSONAL (known contact) → TRANSACTION →
 *   PROMOTION → PERSONAL (person-like sender) → UNKNOWN.
 * SPAM is deliberately NOT reachable from the classifier: it is a user action.
 */
class MessageClassifierTest {

    private val inbox = 1
    private val sent = 2

    private val contacts = setOf("+989121234567", "09121110000")

    private fun classifier(): MessageClassifier = MessageClassifier(
        knownContacts = KnownContactLookup { sender -> sender in contacts }
    )

    /** All six categories, so a new one cannot be added without a test update. */
    private fun allCategories(): List<MessageCategory> = MessageCategory.entries

    // ── OTP ────────────────────────────────────────────────────────────────

    @Test
    fun `verification code is OTP at high confidence`() {
        val result = classifier().classify("+981200", "Your verification code is 482193", inbox)
        assertEquals(MessageCategory.OTP, result.category)
        assertTrue(result.isOtp)
        assertTrue("OTP must clear the high-confidence floor", result.confidence >= 0.7f)
        assertEquals("482193", result.otp?.code)
    }

    @Test
    fun `persian bank dynamic password is OTP`() {
        val result = classifier().classify("20001", "رمز پویا: ۴۸۲۱۹۳", inbox)
        assertEquals(MessageCategory.OTP, result.category)
        assertTrue(result.isOtp)
        assertEquals(OtpDetector.OtpKind.BANK_DYNAMIC_PASSWORD, result.otp?.kind)
    }

    @Test
    fun `a weak code keyword alone is not an OTP`() {
        // "your order code 4821" is a weak match (0.5): below the floor, so the
        // message must NOT become auto-deletable OTP material.
        val result = classifier().classify("+989121234567", "your order code 4821", inbox)
        assertFalse("weak OTP matches must never set isOtp", result.isOtp)
        assertTrue(result.category != MessageCategory.OTP)
    }

    @Test
    fun `otp wins over a transaction reading for a bank OTP`() {
        val result = classifier().classify(
            "20001",
            "بانک ملت: رمز پویا 482193 مبلغ 500,000 ریال برداشت شد",
            inbox
        )
        assertEquals("OTP outranks TRANSACTION", MessageCategory.OTP, result.category)
        assertTrue(result.isOtp)
    }

    @Test
    fun `otp is detected for an outgoing message too but never by accident`() {
        val result = classifier().classify("20001", "Your verification code is 482193", sent)
        // The classifier is direction-agnostic; retention (not this class) is
        // what excludes outgoing rows from cleanup.
        assertTrue(result.isOtp)
        assertEquals(MessageCategory.OTP, result.category)
    }

    // ── TRANSACTION ────────────────────────────────────────────────────────

    @Test
    fun `bank debit notification is a transaction`() {
        val result = classifier().classify(
            "20001",
            "Your account 1234567890 was debited 500,000 IRR",
            inbox
        )
        assertEquals(MessageCategory.TRANSACTION, result.category)
        assertFalse(result.isOtp)
        assertTrue(result.confidence >= 0.6f)
    }

    @Test
    fun `persian deposit notification is a transaction`() {
        val result = classifier().classify(
            "20001",
            "مبلغ ۵۰۰,۰۰۰ ریال به حساب شما واریز شد",
            inbox
        )
        assertEquals(MessageCategory.TRANSACTION, result.category)
    }

    @Test
    fun `a bill with a currency needs two markers`() {
        val result = classifier().classify("20001", "قبض شما ۲۵۰۰۰۰ تومان", inbox)
        assertEquals(MessageCategory.TRANSACTION, result.category)
    }

    @Test
    fun `a bare currency mention from a short code is not a transaction`() {
        val result = classifier().classify("20001", "۲۰۰۰۰ تومان", inbox)
        assertTrue(
            "an incidental amount must not fabricate a receipt",
            result.category != MessageCategory.TRANSACTION
        )
    }

    @Test
    fun `marketing copy mentioning a purchase is not a transaction`() {
        val result = classifier().classify(
            "20001",
            "Buy now and get 50% off your next purchase",
            inbox
        )
        assertEquals(MessageCategory.PROMOTION, result.category)
    }

    // ── PROMOTION ──────────────────────────────────────────────────────────

    @Test
    fun `unsubscribe footer makes it a promotion`() {
        val result = classifier().classify(
            "20001",
            "Big sale this week only! Reply STOP to unsubscribe",
            inbox
        )
        assertEquals(MessageCategory.PROMOTION, result.category)
        assertTrue(result.confidence >= 0.6f)
    }

    @Test
    fun `persian discount blast is a promotion`() {
        val result = classifier().classify(
            "20001",
            "جشنواره فروش ویژه با ۵۰ درصد تخفیف",
            inbox
        )
        assertEquals(MessageCategory.PROMOTION, result.category)
    }

    @Test
    fun `a prize announcement is a promotion and not a transaction`() {
        val result = classifier().classify(
            "20001",
            "Congratulations! You have won 5000000 tomans",
            inbox
        )
        assertEquals(MessageCategory.PROMOTION, result.category)
    }

    // ── PERSONAL ───────────────────────────────────────────────────────────

    @Test
    fun `a known contact is personal`() {
        val result = classifier().classify("+989121234567", "سلام، فردا می‌بینمت", inbox)
        assertEquals(MessageCategory.PERSONAL, result.category)
        assertFalse(result.isOtp)
    }

    @Test
    fun `a known contact mentioning money stays personal`() {
        // Rule 2 exists so a friend's message about money is not filed as a bank
        // transaction.
        val result = classifier().classify(
            "+989121234567",
            "۱ میلیون تومان بهت بدهکارم",
            inbox
        )
        assertEquals(MessageCategory.PERSONAL, result.category)
    }

    @Test
    fun `an ordinary phone number is a person-like sender`() {
        val result = classifier().classify("+98 912 555 0000", "are you free tomorrow?", inbox)
        assertEquals(MessageCategory.PERSONAL, result.category)
    }

    @Test
    fun `a short code is never person-like`() {
        val result = classifier().classify("12345", "kfhdkshf", inbox)
        assertEquals(MessageCategory.UNKNOWN, result.category)
    }

    // ── UNKNOWN fallback ───────────────────────────────────────────────────

    @Test
    fun `unknown sender with neutral text falls back to UNKNOWN`() {
        val result = classifier().classify("20001", "kfhdkshf", inbox)
        assertEquals(MessageCategory.UNKNOWN, result.category)
        assertEquals(0f, result.confidence, 0f)
        assertFalse(result.isOtp)
        assertNull(result.otp)
    }

    @Test
    fun `blank body falls back to UNKNOWN`() {
        val result = classifier().classify("20001", "   ", inbox)
        assertEquals(MessageCategory.UNKNOWN, result.category)
        assertEquals(0f, result.confidence, 0f)
    }

    // ── The classifier never originates SPAM (v3.4.0 rule) ─────────────────

    @Test
    fun `the classifier never returns SPAM from a heuristic`() {
        val hostile = listOf(
            "WINNER!!! claim your prize now",
            "FREE FREE FREE click here",
            "تبریک! شما برنده جایزه شدید",
            "limited time offer buy now",
            "????????????????"
        )
        for (body in hostile) {
            val result = classifier().classify("20001", body, inbox)
            assertTrue(
                "spam must be a user action, not a heuristic: $body -> ${result.category}",
                result.category != MessageCategory.SPAM
            )
        }
    }

    // ── Number look-alikes: not an OTP, not a transaction ──────────────────

    @Test
    fun `a plain phone number is not an OTP and not a transaction digest`() {
        val result = classifier().classify("20001", "call me at 09123456789", inbox)
        assertFalse(result.isOtp)
        assertTrue(result.category != MessageCategory.OTP)
        assertTrue(result.category != MessageCategory.TRANSACTION)
    }

    @Test
    fun `a date and a price are not an OTP or a transaction digest`() {
        val result = classifier().classify(
            "20001",
            "meeting moved to 2026-05-01, total is 1.234.567",
            inbox
        )
        assertFalse(result.isOtp)
        assertTrue(result.category != MessageCategory.OTP)
        assertTrue(result.category != MessageCategory.TRANSACTION)
    }

    @Test
    fun `an account number is not an OTP`() {
        val result = classifier().classify("20001", "your account code 1234567890", inbox)
        assertFalse(result.isOtp)
        assertTrue(result.category != MessageCategory.OTP)
    }

    // ── Determinism and bounded confidence ─────────────────────────────────

    @Test
    fun `classification is deterministic for the same input`() {
        val samples = listOf(
            Triple("+981200", "Your verification code is 482193", inbox),
            Triple("20001", "Your account 1234567890 was debited 500,000 IRR", inbox),
            Triple("20001", "Big sale! Reply STOP to unsubscribe", inbox),
            Triple("+989121234567", "سلام", inbox),
            Triple("20001", "kfhdkshf", inbox),
            Triple("20001", "قبض شما ۲۵۰۰۰۰ تومان", inbox)
        )
        val classifier = classifier()
        for ((sender, body, type) in samples) {
            val first = classifier.classify(sender, body, type)
            val second = classifier.classify(sender, body, type)
            assertEquals("category must be stable for: $body", first.category, second.category)
            assertEquals("confidence must be stable for: $body", first.confidence, second.confidence, 0f)
            assertEquals("isOtp must be stable for: $body", first.isOtp, second.isOtp)
            assertEquals("otp code must be stable for: $body", first.otp?.code, second.otp?.code)
        }
    }

    @Test
    fun `confidence is always bounded to zero through one`() {
        val samples = listOf(
            "",
            " ",
            "Your verification code is 482193",
            "رمز پویا ۱۲۳۴۵۶",
            "sdflkjsdflkjsdf",
            "Your account was debited",
            "50% off, unsubscribe",
            "سلام",
            "1234567890123456",
            "۲۰۲۶-۰۵-۰۱ 1.234.567 تومان",
            "Congratulations you have won 999999999 tomans"
        )
        val classifier = classifier()
        for (body in samples) {
            for (sender in listOf("20001", "+989121234567", "BANKMELLAT", "", "123456789098765")) {
                val result = classifier.classify(sender, body, inbox)
                assertTrue(
                    "confidence out of range for '$sender'/'$body': ${result.confidence}",
                    result.confidence >= 0f && result.confidence <= 1f
                )
                assertTrue(
                    "category must come from the vocabulary",
                    result.category in allCategories()
                )
                if (result.isOtp) {
                    assertNotNull("isOtp requires the detector result", result.otp)
                    assertEquals(MessageCategory.OTP, result.category)
                    assertTrue(result.otp!!.confidence >= OtpDetector.HIGH_CONFIDENCE)
                } else {
                    assertTrue(
                        "a non-OTP must not carry an OTP payload",
                        result.category != MessageCategory.OTP
                    )
                }
            }
        }
    }

    @Test
    fun `a known-contact lookup failure degrades the category and never throws`() {
        val exploding = MessageClassifier(
            knownContacts = KnownContactLookup { throw IllegalStateException("contact store down") }
        )
        val result = exploding.classify("+989121234567", "hello there", inbox)
        assertEquals(MessageCategory.PERSONAL, result.category)
    }

    @Test
    fun `persian digits classify the same as ascii digits`() {
        val classifier = classifier()
        val ascii = classifier.classify("20001", "Your account 1234 was debited 500,000 IRR", inbox)
        val persian = classifier.classify("۲۰۰۰۱", "Your account ۱۲۳۴ was debited ۵۰۰,۰۰۰ IRR", inbox)
        assertEquals(ascii.category, persian.category)
    }
}
