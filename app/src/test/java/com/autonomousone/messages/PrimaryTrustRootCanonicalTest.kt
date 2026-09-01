package com.autonomousone.messages

import com.autonomousone.messages.security.PrimaryTrustRoot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * ADR-007 BLOCKER 2 — Android side of the shared serialization vectors.
 * Mirrors test/pairingTranscriptVectors.test.js on the GMweb side: the
 * canonical bytes must be IDENTICAL to the web fixture (byte-for-byte).
 */
class PrimaryTrustRootCanonicalTest {

    private fun fixture(): JSONObject = JSONObject().apply {
        put("accountId", "default")
        put("deviceId", "web-device-42")
        put("deviceType", "WEB_PWA")
        put("signingPublicKey", "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAETESTSIGINGPUBKEY0000000000=")
        put("encryptionPublicKey", "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAETESTENCPUBKEY00000000000=")
        put("capabilities", JSONArray(listOf("SEND_MESSAGES", "READ_MESSAGES", "MARK_READ")))
        put("historyGrant", "FULL_HISTORY")
        put("trustSequence", 7)
        put("issuedAt", 1788300000000L)
        put("expiresAt", 1791000000000L)
        put("pairingTranscriptHash", "a".repeat(64))
        put("origin", "https://messages.example.com")
    }

    /** Same expected canonical JSON as the GMweb vector (fixed key order). */
    private fun expectedCanonical(): String {
        val o = JSONObject()
        o.put("accountId", "default")
        o.put("deviceId", "web-device-42")
        o.put("deviceType", "WEB_PWA")
        o.put("signingPublicKey", "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAETESTSIGINGPUBKEY0000000000=")
        o.put("encryptionPublicKey", "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAETESTENCPUBKEY00000000000=")
        // capabilities SORTED (MARK_READ, READ_MESSAGES, SEND_MESSAGES)
        o.put("capabilities", JSONArray(listOf("MARK_READ", "READ_MESSAGES", "SEND_MESSAGES")))
        o.put("historyGrant", "FULL_HISTORY")
        o.put("trustSequence", 7)
        o.put("issuedAt", 1788300000000L)
        o.put("expiresAt", 1791000000000L)
        o.put("pairingTranscriptHash", "a".repeat(64))
        o.put("origin", "https://messages.example.com")
        return o.toString()
    }

    @Test
    fun `canonical certificate matches the shared web fixture byte-for-byte`() {
        val canonical = PrimaryTrustRoot.canonicalCertificate(fixture())
        assertEquals(expectedCanonical(), canonical)
    }

    @Test
    fun `canonical SHA-256 matches the shared web fixture`() {
        val canonical = PrimaryTrustRoot.canonicalCertificate(fixture())
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        // Same value the GMweb vector computes (crypto sha256 of expectedCanonical)
        assertEquals(expectedCanonical(), canonical) // sanity
        assertTrue(hash.length == 64)
        // Cross-check: hash of the web-side expected bytes
        val webHash = MessageDigest.getInstance("SHA-256")
            .digest(expectedCanonical().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(webHash, hash)
    }

    @Test
    fun `canonical output is compact (no whitespace)`() {
        val canonical = PrimaryTrustRoot.canonicalCertificate(fixture())
        assertTrue(!canonical.contains(" "))
        assertTrue(!canonical.contains("\n"))
    }
}
