package com.autonomousone.messages

import com.autonomousone.messages.security.PairingProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class PrimaryTrustRootCanonicalTest {
    @Test fun sharedFixedBytesHashesAndSignatures() {
        val raw = checkNotNull(javaClass.classLoader?.getResourceAsStream("pairing-protocol-v1.json"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val fixture = JSONObject(raw)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(
            Base64.getDecoder().decode(fixture.getString("trustRootPublicKey"))))
        val vectors = fixture.getJSONArray("vectors")
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val input = v.getJSONObject("input")
            val canonical = when (v.getString("kind")) {
                "certificate" -> PairingProtocol.certificate(input)
                "transcript" -> PairingProtocol.transcript(input)
                "challenge" -> PairingProtocol.challenge(input)
                else -> PairingProtocol.enrollment(input)
            }.toByteArray(Charsets.UTF_8)
            assertEquals(v.getString("canonicalBase64"), Base64.getEncoder().encodeToString(canonical))
            assertEquals(v.getString("sha256"), MessageDigest.getInstance("SHA-256").digest(canonical).joinToString("") { "%02x".format(it) })
            assertTrue(Signature.getInstance("SHA256withECDSA").apply { initVerify(key); update(canonical) }
                .verify(Base64.getDecoder().decode(v.getString("signature"))))
        }
    }
}
