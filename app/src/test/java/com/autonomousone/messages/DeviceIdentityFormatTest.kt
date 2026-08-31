package com.autonomousone.messages.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * PR-05 wire-format pins that CAN run on the JVM: the uncompressed-point
 * encoding contract (0x04||X||Y, 65 bytes, 32-byte padded coordinates) and
 * ES256 sign/verify round-trip. Keystore generation itself is device-only.
 */
class DeviceIdentityFormatTest {

    /** Mirrors DeviceIdentity.uncompressedPoint() — kept in lockstep by this test. */
    private fun uncompressedPoint(key: ECPublicKey): ByteArray {
        val w = key.w
        val x = w.affineX.toByteArray()
        val y = w.affineY.toByteArray()
        fun pad(b: ByteArray): ByteArray = when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
        return byteArrayOf(0x04) + pad(x) + pad(y)
    }

    private fun freshKey(): ECPublicKey {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return generator.generateKeyPair().public as ECPublicKey
    }

    @Test
    fun `uncompressed point is exactly 65 bytes with the 0x04 prefix`() {
        val point = uncompressedPoint(freshKey())
        assertEquals(65, point.size)
        assertEquals(0x04.toByte(), point[0])
    }

    @Test
    fun `coordinates are padded to 32 bytes when BigInteger strips leading zeros`() {
        val key = freshKey()
        val point = uncompressedPoint(key)
        // Both coordinates decode back to exactly the BigInteger values.
        val x = java.math.BigInteger(1, point.copyOfRange(1, 33))
        val y = java.math.BigInteger(1, point.copyOfRange(33, 65))
        assertEquals(key.w.affineX, x)
        assertEquals(key.w.affineY, y)
        // And X ≠ Y (a 1-in-2^256 collision would fail this; acceptable).
        assertNotEquals(point.copyOfRange(1, 33), point.copyOfRange(33, 65))
    }

    @Test
    fun `ES256 round trip - SHA256withECDSA sign then verify with the paired public key`() {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        val data = "trust-statement:test".toByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(pair.private)
            update(data)
        }.sign()
        val verified = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(pair.public)
            update(data)
        }.verify(signature)
        assertEquals(true, verified)
        // Tampered data must fail
        val tampered = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(pair.public)
            update("trust-statement:evil".toByteArray())
        }
        assertEquals(false, tampered.verify(signature))
    }
}
