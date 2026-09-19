package com.autonomousone.messages

import com.autonomousone.messages.messaging.OtpDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unified OTP detection (v3.4.0 FEATURE 13).
 *
 * The cases the release spec names explicitly. Threshold rule under test:
 * a STRONG context is the only path above 0.7 confidence, which is what the
 * bubble "Copy code" action and the global auto-delete require.
 */
class OtpDetectorTest {

    private val HIGH_CONFIDENCE = 0.7f

    // ── Detection: ASCII ────────────────────────────────────────────────────

    @Test
    fun `ascii code with strong context is detected at high confidence`() {
        val detection = OtpDetector.detect("+981200", "Your verification code is 482193")
        assertNotNull(detection)
        assertEquals("482193", detection!!.code)
        assertTrue(detection.confidence >= HIGH_CONFIDENCE)
    }

    @Test
    fun `grouped code is joined`() {
        assertEquals("482193", OtpDetector.detect("", "Your code is 482-193")!!.code)
    }

    @Test
    fun `six digit code is kind OTP`() {
        assertEquals(
            OtpDetector.OtpKind.OTP,
            OtpDetector.detect("", "Your code is 123456")!!.kind
        )
    }

    @Test
    fun `four digit code is kind VERIFICATION`() {
        assertEquals(
            OtpDetector.OtpKind.VERIFICATION,
            OtpDetector.detect("", "Verification code: 4821")!!.kind
        )
    }

    @Test
    fun `persian dynamic password maps to bank kind`() {
        val detection = OtpDetector.detect("+981200", "رمز پویا: 482193")
        assertNotNull(detection)
        assertEquals("482193", detection!!.code)
        assertEquals(OtpDetector.OtpKind.BANK_DYNAMIC_PASSWORD, detection.kind)
        assertTrue(detection.confidence >= HIGH_CONFIDENCE)
    }

    @Test
    fun `password reset maps to reset kind`() {
        assertEquals(
            OtpDetector.OtpKind.PASSWORD_RESET,
            OtpDetector.detect("", "Your password reset code is 991122")!!.kind
        )
    }

    // ── Detection: Persian and Arabic-Indic digits ──────────────────────────

    @Test
    fun `persian digits are folded to ascii`() {
        val detection = OtpDetector.detect("", "کد تایید شما ۱۲۳۴۵۶")
        assertNotNull(detection)
        assertEquals("123456", detection!!.code)
        assertTrue(detection.confidence >= HIGH_CONFIDENCE)
    }

    @Test
    fun `arabic-indic digits are folded to ascii`() {
        val detection = OtpDetector.detect("", "کد تایید شما ١٢٣٤٥٦")
        assertNotNull(detection)
        assertEquals("123456", detection!!.code)
    }

    @Test
    fun `mixed script digits are folded`() {
        assertEquals("123456", OtpDetector.normalizeDigits("۱۲۳۴٥۶"))
    }

    // ── Rejection: look-alikes that must never be treated as an OTP ─────────

    @Test
    fun `phone number is rejected`() {
        assertNull(OtpDetector.detect("", "call me at 09120000000"))
    }

    @Test
    fun `date is rejected`() {
        assertNull(OtpDetector.detect("", "meeting moved to 2026-05-01"))
    }

    @Test
    fun `price is rejected`() {
        assertNull(OtpDetector.detect("", "total is 1.234.567 tomans"))
    }

    @Test
    fun `long account number is rejected even with a code keyword`() {
        assertNull(OtpDetector.detect("", "your account code 1234567890"))
    }

    @Test
    fun `no keyword means no detection`() {
        assertNull(OtpDetector.detect("", "lunch at 1234 tomorrow?"))
    }

    @Test
    fun `blank body is rejected`() {
        assertNull(OtpDetector.detect("", "   "))
    }

    // ── Confidence discipline ───────────────────────────────────────────────

    @Test
    fun `weak keyword alone stays below the auto-delete threshold`() {
        val detection = OtpDetector.detect("", "your order code 4821")
        assertNotNull(detection)
        assertTrue(
            "weak context must NOT be auto-deletable",
            detection!!.confidence < HIGH_CONFIDENCE
        )
    }

    @Test
    fun `plain number with no keyword never reaches the threshold`() {
        assertNull(OtpDetector.detect("", "reference 482193"))
    }

    // ── Plausibility helper ─────────────────────────────────────────────────

    @Test
    fun `plausible code accepts four to eight digits`() {
        assertTrue(OtpDetector.isPlausibleCode("1234"))
        assertTrue(OtpDetector.isPlausibleCode("12345678"))
        assertFalse(OtpDetector.isPlausibleCode("123"))
        assertFalse(OtpDetector.isPlausibleCode("123456789"))
        assertFalse(OtpDetector.isPlausibleCode("12a4"))
    }

    @Test
    fun `year-like run is not a code`() {
        assertFalse(OtpDetector.isPlausibleCode("2026"))
    }
}