package com.autonomousone.messages.gateway.wake

import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.Certificate
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * STAGE-4 §2.1/§6: two configured pins both validate; a third certificate fails.
 *
 * A single pin plus a Let's Encrypt renewal is a guaranteed outage, so the rotation pin is not
 * optional. A pin mismatch is surfaced as MISMATCH and falls back; it never disables pinning.
 */
class CertificatePinRotationTest {

    private val current = HeldCertificate.Builder().commonName("current.example").build()
    private val next = HeldCertificate.Builder().commonName("next.example").build()
    private val third = HeldCertificate.Builder().commonName("third.example").build()

    @Test
    fun `both configured pins validate and a third certificate fails`() {
        val pins = WakeCertificatePins(
            current = WakeTestHarness.pinFor(current.certificate),
            next = WakeTestHarness.pinFor(next.certificate),
        )
        val pinner = pins.certificatePinner("current.example")!!

        pinner.check("current.example", listOf<Certificate>(current.certificate))
        pinner.check("current.example", listOf<Certificate>(next.certificate))

        val refused = runCatching {
            pinner.check("current.example", listOf<Certificate>(third.certificate))
        }
        assertTrue(refused.exceptionOrNull() is SSLPeerUnverifiedException)
    }

    @Test
    fun `pins accept the spellings a human may paste`() {
        val pin = WakeTestHarness.pinFor(current.certificate)
        val bare = pin.removePrefix("sha256/")
        assertEquals(pin, WakeCertificatePins.normalize(pin))
        assertEquals(pin, WakeCertificatePins(current = bare).normalized().single())
        assertEquals(pin, WakeCertificatePins.normalize(pin.replace("sha256/", "sha256:")))
        assertEquals(pin, WakeCertificatePins.normalize("  $bare  "))
    }

    @Test
    fun `an invalid pin fails closed instead of silently disabling pinning`() {
        val typo = WakeCertificatePins(current = "not-a-pin")
        assertTrue(typo.invalidInput)
        assertEquals(false, typo.configured)
        assertNull(typo.certificatePinner("gmweb.example"))
        assertFalse(WakeCertificatePins(current = "").invalidInput)
    }

    @Test
    fun `duplicate pins collapse but two distinct pins remain`() {
        val pin = WakeTestHarness.pinFor(current.certificate)
        assertEquals(1, WakeCertificatePins(pin, pin).normalized().size)
        assertEquals(
            2,
            WakeCertificatePins(pin, WakeTestHarness.pinFor(next.certificate)).normalized().size
        )
    }
}
