package com.autonomousone.messages.security

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.Registry
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.proto.HpkeAead
import com.google.crypto.tink.proto.HpkeKdf
import com.google.crypto.tink.proto.HpkeKem
import com.google.crypto.tink.proto.HpkeParams
import com.google.crypto.tink.proto.HpkePrivateKey
import com.google.crypto.tink.proto.HpkePublicKey
import com.google.crypto.tink.shaded.protobuf.ByteString
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Consumer/contract half of the LDV-004 cross-implementation HPKE vector.
 *
 * The committed resource `hpke-p256-grant-v1.json` was produced once by the
 * JVM/Tink producer path (see god-ai's `docs/contracts/vectors/` copy) and is
 * also opened by god-ai's `internal/platform/devcrypto/hpke_vector_test.go`.
 * Both sides pin the same bytes: this test proves Messages/Tink can decrypt the
 * recorded ciphertext, while the Go test proves `crypto/hpke` can. Regenerating
 * the vector requires updating both copies.
 *
 * The recipient key in the vector is synthetic throwaway test material. It is
 * not a device identity and not a secret; never use it for a real device.
 */
class HpkeGrantVectorTest {
    @Test
    fun committedVectorDecryptsWithTink() {
        val vector = loadVector()
        assertTrue("vector must be labeled test_only", vector.getBoolean("test_only"))
        assertTrue("vector must carry its synthetic-material note", vector.getString("note").isNotBlank())

        assertEquals("1.18.0", vector.getString("tink_version"))
        assertEquals("P-256", vector.getString("curve"))
        assertEquals("DHKEM_P256_HKDF_SHA256", vector.getString("kem"))
        assertEquals("HKDF_SHA256", vector.getString("kdf"))
        assertEquals("AES_256_GCM", vector.getString("aead"))
        assertEquals("GMweb-conversation-key-grant-v1", vector.getString("info_domain"))

        val domain = vector.getString("info_domain")
        val info = unb64(vector.getString("info_b64"))
        val rebuiltInfo = MessageCrypto.binding(
            domain,
            vector.getString("grant_id"),
            vector.getString("device_id"),
            vector.getString("scope_mode"),
            vector.getString("conversation_id"),
            vector.getString("epoch_id"),
            vector.getString("key_domain"),
        )
        assertArrayEquals("info_b64 must be binding() over the six grant fields", info, rebuiltInfo)

        val plaintext = unb64(vector.getString("plaintext_b64"))
        assertArrayEquals(
            "vector plaintext must stay the fixed synthetic CKE",
            ByteArray(32) { index -> index.toByte() },
            plaintext,
        )

        val publicKey = unb64(vector.getString("recipient_public_key_uncompressed_b64"))
        assertEquals(65, publicKey.size)
        assertEquals(0x04, publicKey[0].toInt() and 0xFF)

        val ciphertext = unb64(vector.getString("ciphertext_b64"))
        // Tink 1.18.0 through the legacy Registry path uses OutputPrefixType.RAW:
        // encrypt() returns `enc || ct`, a 65-byte uncompressed point plus the
        // AES-256-GCM ciphertext and tag. A prefix or another layout would fail.
        assertEquals("enc || ct", 65 + plaintext.size + 16, ciphertext.size)
        assertEquals(0x04, ciphertext[0].toInt() and 0xFF)

        val pkcs8 = unb64(vector.getString("recipient_private_key_pkcs8_b64"))
        val opened = tinkDecrypt(
            pkcs8 = pkcs8,
            rawPublicKey = publicKey,
            ciphertext = ciphertext,
            info = info,
        )
        assertArrayEquals("Tink HybridDecrypt must recover the vector plaintext", plaintext, opened)

        // Authentication is not weakened for the vector: a changed tag and a
        // changed bound field must both fail closed.
        val tampered = ciphertext.copyOf().also { it[it.lastIndex] = (it[it.lastIndex].toInt() xor 0xFF).toByte() }
        assertDecryptFails(pkcs8, publicKey, tampered, info, "tampered ciphertext")
        val wrongInfo = MessageCrypto.binding(
            domain,
            vector.getString("grant_id"),
            vector.getString("device_id"),
            vector.getString("scope_mode"),
            vector.getString("conversation_id") + "-other",
            vector.getString("epoch_id"),
            vector.getString("key_domain"),
        )
        assertDecryptFails(pkcs8, publicKey, ciphertext, wrongInfo, "wrong info")
    }

    private fun assertDecryptFails(
        pkcs8: ByteArray,
        publicKey: ByteArray,
        ciphertext: ByteArray,
        info: ByteArray,
        what: String,
    ) {
        try {
            tinkDecrypt(
                pkcs8 = pkcs8,
                rawPublicKey = publicKey,
                ciphertext = ciphertext,
                info = info,
            )
            fail("$what decrypted; the HPKE authentication was not enforced")
        } catch (_: GeneralSecurityException) {
            // Expected: wrong key material, tag or info fails authentication.
        }
    }

    private fun loadVector(): JSONObject {
        val stream = javaClass.getResourceAsStream("/hpke-p256-grant-v1.json")
            ?: error("missing test resource hpke-p256-grant-v1.json")
        return stream.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
    }

    /**
     * Tink HPKE P-256 private keys carry the raw 32-byte scalar, not PKCS#8;
     * the committed vector stores PKCS#8 so the key is portable and auditable.
     * Java 17's ECPrivateKey.s still carries the sign byte, so it is truncated
     * or left-padded to the field size.
     */
    private fun scalarFromPkcs8(pkcs8: ByteArray): ByteArray {
        val privateKey = KeyFactory.getInstance("EC")
            .generatePrivate(PKCS8EncodedKeySpec(pkcs8)) as ECPrivateKey
        return privateKey.s.toByteArray().takeLast(32).toByteArray()
            .let { ByteArray(32 - it.size) + it }
    }

    private fun tinkDecrypt(
        pkcs8: ByteArray,
        rawPublicKey: ByteArray,
        ciphertext: ByteArray,
        info: ByteArray,
    ): ByteArray {
        val params = HpkeParams.newBuilder()
            .setKem(HpkeKem.DHKEM_P256_HKDF_SHA256)
            .setKdf(HpkeKdf.HKDF_SHA256)
            .setAead(HpkeAead.AES_256_GCM)
            .build()
        val publicKey = HpkePublicKey.newBuilder()
            .setVersion(0)
            .setParams(params)
            .setPublicKey(ByteString.copyFrom(rawPublicKey))
            .build()
        val privateKey = HpkePrivateKey.newBuilder()
            .setVersion(0)
            .setPublicKey(publicKey)
            .setPrivateKey(ByteString.copyFrom(scalarFromPkcs8(pkcs8)))
            .build()
        HybridConfig.register()
        val primitive = Registry.getPrimitive(
            "type.googleapis.com/google.crypto.tink.HpkePrivateKey",
            privateKey.toByteString(),
            HybridDecrypt::class.java,
        )
        return primitive.decrypt(ciphertext, info)
    }

    private companion object {
        fun unb64(value: String): ByteArray = Base64.getDecoder().decode(value)
    }
}
