package com.autonomousone.messages

import androidx.test.platform.app.InstrumentationRegistry
import com.autonomousone.messages.security.PairingProtocol
import org.json.JSONObject
import org.junit.Assert.assertTrue
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

/** Exercises the actual Android JSONObject implementation on a device. */
class PairingProtocolRuntimeTest {
    @Test fun platformJsonMatchesSharedFixture() {
        val raw = InstrumentationRegistry.getInstrumentation().context.assets
            .open("pairing-protocol-v1.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val fixture = JSONObject(raw)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(fixture.getString("trustRootPublicKey"))))
        val vectors = fixture.getJSONArray("vectors")
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val input = v.getJSONObject("input")
            val canonical = when (v.getString("kind")) {
                "certificate" -> PairingProtocol.certificate(input)
                "transcript" -> PairingProtocol.transcript(input)
                "challenge" -> PairingProtocol.challenge(input)
                else -> PairingProtocol.enrollment(input)
            }
            val bytes = canonical.toByteArray(Charsets.UTF_8)
            assertEquals(v.getString("canonicalBase64"), Base64.getEncoder().encodeToString(bytes))
            assertEquals(v.getString("sha256"), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            // The android_linked_browser_default vector is intentionally unsigned
            // (root signatures require the enrolled phone's keystore key).
            if (v.has("signature")) {
                assertTrue(Signature.getInstance("SHA256withECDSA").apply { initVerify(key); update(bytes) }
                    .verify(Base64.getDecoder().decode(v.getString("signature"))))
            }
        }
    }
}
