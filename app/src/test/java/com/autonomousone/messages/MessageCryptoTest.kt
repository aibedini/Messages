package com.autonomousone.messages

import com.autonomousone.messages.security.MessageCrypto
import com.autonomousone.messages.security.ConversationKeyRepository
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class MessageCryptoTest {
    @Test fun sharedBrowserVectorAlsoOpensWithTinkAndJca() {
        val v = JSONObject(java.io.File("../protocol/message-crypto-v1.json").readText())
        val grant = v.getJSONObject("grant")
        val m = v.getJSONObject("message")
        val fields = arrayOf(grant.getString("epochId"), grant.getString("conversationId"), grant.getString("deviceId"), "", "0")
        val kf = java.security.KeyFactory.getInstance("EC")
        val root = kf.generatePublic(java.security.spec.X509EncodedKeySpec(MessageCrypto.unb64(v.getString("rootPublicSpki"))))
        assertTrue(Signature.getInstance("SHA256withECDSA").run {
            initVerify(root); update(MessageCrypto.binding("GMweb-CKE-signature-v1", *fields, grant.getString("wrappedCke")))
            verify(MessageCrypto.unb64(grant.getString("rootSignature")))
        })
        val privateKey = kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(MessageCrypto.unb64(v.getString("recipientPrivatePkcs8")))) as java.security.interfaces.ECPrivateKey
        val scalar = privateKey.s.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }
        val params = com.google.crypto.tink.proto.HpkeParams.newBuilder()
            .setKem(com.google.crypto.tink.proto.HpkeKem.DHKEM_P256_HKDF_SHA256)
            .setKdf(com.google.crypto.tink.proto.HpkeKdf.HKDF_SHA256)
            .setAead(com.google.crypto.tink.proto.HpkeAead.AES_256_GCM).build()
        val pub = com.google.crypto.tink.proto.HpkePublicKey.newBuilder().setVersion(0).setParams(params)
            .setPublicKey(com.google.crypto.tink.shaded.protobuf.ByteString.copyFrom(MessageCrypto.unb64(v.getString("recipientPublicRaw")))).build()
        val key = com.google.crypto.tink.proto.HpkePrivateKey.newBuilder().setVersion(0).setPublicKey(pub)
            .setPrivateKey(com.google.crypto.tink.shaded.protobuf.ByteString.copyFrom(scalar)).build()
        com.google.crypto.tink.hybrid.HybridConfig.register()
        val decrypt = com.google.crypto.tink.Registry.getPrimitive("type.googleapis.com/google.crypto.tink.HpkePrivateKey", key.toByteString(), com.google.crypto.tink.HybridDecrypt::class.java)
        val cke = decrypt.decrypt(MessageCrypto.unb64(grant.getString("wrappedCke")), MessageCrypto.binding("GMweb-CKE-v1", *fields))
        val messageFields = arrayOf(fields[0], "event-test", "MESSAGE_CREATED", fields[1])
        val dek = MessageCrypto.open(cke, MessageCrypto.Sealed(MessageCrypto.unb64(m.getString("wrapIv")), MessageCrypto.unb64(m.getString("wrappedDek"))), MessageCrypto.binding("GMweb-DEK-v1", *messageFields))
        val payload = MessageCrypto.open(dek, MessageCrypto.Sealed(MessageCrypto.unb64(m.getString("iv")), MessageCrypto.unb64(m.getString("ciphertext"))), MessageCrypto.binding("GMweb-message-v1", *messageFields))
        assertEquals(v.getJSONObject("payload").getString("body"), JSONObject(String(payload, Charsets.UTF_8)).getString("body"))
    }
    @Test fun aeadBindsMessageAndRejectsTampering() {
        val key = MessageCrypto.randomKey()
        val aad = MessageCrypto.binding("test", "message")
        val sealed = MessageCrypto.seal(key, "hello".toByteArray(), aad)
        assertEquals("hello", String(MessageCrypto.open(key, sealed, aad)))
        try {
            MessageCrypto.open(key, sealed, MessageCrypto.binding("test", "other"))
            fail("AAD substitution accepted")
        } catch (_: javax.crypto.AEADBadTagException) { }
    }

    @Test fun v2AccountKeyringUsesCapabilityDomainsAndRoundTrips() {
        assertEquals("READ_MESSAGES", ConversationKeyRepository.domainFor(""))
        assertEquals("CONTACTS_READ", ConversationKeyRepository.domainFor("CONTACTS_READ"))
        assertEquals(7, ConversationKeyRepository.KEYRING_DOMAINS.size)

        val accountKey = MessageCrypto.randomKey()
        val payload = """{"messageId":"m1","body":"hello"}""".toByteArray()
        val envelope = JSONObject(String(MessageCrypto.encryptMessageV2(
            accountKey, "key-1", "READ_MESSAGES", "event-1", "MESSAGE_CREATED", "thread-1", payload
        )))
        val fields = arrayOf("key-1", "READ_MESSAGES", "event-1", "MESSAGE_CREATED", "thread-1")
        val dek = MessageCrypto.open(
            accountKey,
            MessageCrypto.Sealed(
                MessageCrypto.unb64(envelope.getString("wrapIv")),
                MessageCrypto.unb64(envelope.getString("wrappedDek"))
            ),
            MessageCrypto.binding("GMweb-DEK-v2", *fields)
        )
        val opened = MessageCrypto.open(
            dek,
            MessageCrypto.Sealed(
                MessageCrypto.unb64(envelope.getString("iv")),
                MessageCrypto.unb64(envelope.getString("ciphertext"))
            ),
            MessageCrypto.binding("GMweb-message-v2", *fields)
        )
        assertArrayEquals(payload, opened)
        dek.fill(0)
        accountKey.fill(0)
    }

    @Test fun v3FullHistoryAndFromNowOnWrapsOpenTheSameMessage() {
        val historyKey = MessageCrypto.randomKey()
        val liveKey = MessageCrypto.randomKey()
        val payload = """{"messageId":"m3","body":"full history"}""".toByteArray()
        val envelope = JSONObject(String(MessageCrypto.encryptMessageV3(
            historyKey, "history-1", liveKey, "live-1",
            "event-3", "MESSAGE_CREATED", "thread-3", payload
        )))
        val fields = arrayOf(
            "history-1", "live-1", "READ_MESSAGES", "event-3", "MESSAGE_CREATED", "thread-3"
        )
        fun open(wrappingKey: ByteArray, prefix: String, aad: String): ByteArray {
            val dek = MessageCrypto.open(
                wrappingKey,
                MessageCrypto.Sealed(
                    MessageCrypto.unb64(envelope.getString("${prefix}WrapIv")),
                    MessageCrypto.unb64(envelope.getString("${prefix}WrappedDek"))
                ),
                MessageCrypto.binding(aad, *fields)
            )
            return try {
                MessageCrypto.open(
                    dek,
                    MessageCrypto.Sealed(
                        MessageCrypto.unb64(envelope.getString("iv")),
                        MessageCrypto.unb64(envelope.getString("ciphertext"))
                    ),
                    MessageCrypto.binding("GMweb-message-v3", *fields)
                )
            } finally { dek.fill(0) }
        }
        assertArrayEquals(payload, open(historyKey, "history", "GMweb-history-DEK-v3"))
        assertArrayEquals(payload, open(liveKey, "live", "GMweb-live-DEK-v3"))
        historyKey.fill(0)
        liveKey.fill(0)
    }

    /** Generated artifact contains ONLY fresh throwaway test keys, never Android identities. */
    @Test fun exportTinkToBrowserInteroperabilityVector() {
        fun pair() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        fun coordinate(bytes: ByteArray): ByteArray = bytes.takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }
        val recipient = pair()
        val pub = recipient.public as ECPublicKey
        val rawPub = byteArrayOf(4) + coordinate(pub.w.affineX.toByteArray()) + coordinate(pub.w.affineY.toByteArray())
        val root = pair()
        val cke = MessageCrypto.randomKey()
        val fields = arrayOf("epoch-test", "conversation-test", "device-test", "", "0")
        val wrapped = MessageCrypto.b64(MessageCrypto.wrapForDevice(rawPub, cke, MessageCrypto.binding("GMweb-CKE-v1", *fields)))
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(root.private); update(MessageCrypto.binding("GMweb-CKE-signature-v1", *fields, wrapped))
        }.sign()
        val grant = JSONObject().put("v", 1).put("kind", "key-grant").put("epochId", fields[0])
            .put("conversationId", fields[1]).put("deviceId", fields[2]).put("category", "").put("historyFloor", 0)
            .put("wrappedCke", wrapped).put("rootSignature", MessageCrypto.b64(signature))
        val payload = """{"messageId":"message-test","body":"سلام encrypted history","address":"+123","dateMs":100,"direction":"in","status":0}"""
        val message = MessageCrypto.encryptMessage(cke, fields[0], "event-test", "MESSAGE_CREATED", fields[1], payload.toByteArray())
        val vector = JSONObject().put("testOnly", true).put("recipientPrivatePkcs8", MessageCrypto.b64(recipient.private.encoded))
            .put("recipientPublicRaw", MessageCrypto.b64(rawPub)).put("rootPublicSpki", MessageCrypto.b64(root.public.encoded))
            .put("grant", grant).put("message", JSONObject(String(message))).put("payload", JSONObject(payload))
        java.io.File("build/test-vectors/message-crypto-v1.json").apply { parentFile?.mkdirs(); writeText(vector.toString(2)) }
    }
}
