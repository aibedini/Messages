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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/**
 * LDV-004 PR-E slice 1 — pure producer core for `KEY_GRANT` payloads.
 *
 * The fake wrapper/signer are deliberately simple (XOR + SHA-256, no Tink) so the payload
 * and binding assertions exercise only this file. One separate, clearly named integration
 * test uses real HPKE (Tink) and real ECDSA (JCA) to prove the built `info` and signature
 * binding are what a device implementing `grant.go` would open/verify.
 */
class ConversationGrantPublisherTest {

    private val publicKey = ByteArray(ConversationGrantSchema.P256_PUBLIC_KEY_BYTES) { index ->
        if (index == 0) ConversationGrantSchema.P256_UNCOMPRESSED_PREFIX else index.toByte()
    }
    private val target = GrantTarget(deviceId = "device-1", encryptionPublicKey = publicKey)
    private val cke = ByteArray(ConversationGrantSchema.CKE_BYTES) { (it + 1).toByte() }
    private val issuedAt = 1_700_000_000_000L
    private val expiresAt = issuedAt + 24L * 60L * 60L * 1000L
    private val conversationId = "conversation-1"
    private val epochId = "epoch-1"

    private fun buildGrant(
        target: GrantTarget = this.target,
        conversationId: String = this.conversationId,
        epochId: String = this.epochId,
        key: ByteArray = this.cke,
        issuedAt: Long = this.issuedAt,
        expiresAt: Long = this.expiresAt,
        sequence: Long = 7L,
        wrapper: GrantKeyWrapper = FakeWrapper(),
        signer: GrantSigner = FakeSigner(),
    ): ConversationGrantPayload = ConversationGrantPublisher.buildGrant(
        target = target,
        conversationId = conversationId,
        epochId = epochId,
        key = key,
        issuedAt = issuedAt,
        expiresAt = expiresAt,
        sequence = sequence,
        wrapper = wrapper,
        signer = signer,
    )

    // ── Payload schema ─────────────────────────────────────────────────────

    @Test
    fun `grant payload uses the exact god-ai field names, order and values`() {
        val wrapper = FakeWrapper()
        val signer = FakeSigner()
        val payload = buildGrant(wrapper = wrapper, signer = signer)

        assertEquals(GrantKind.GRANT, payload.kind)
        assertEquals(
            listOf(
                "v",
                "kind",
                "grantId",
                "deviceId",
                "scopeMode",
                "conversationId",
                "keyDomain",
                "epochId",
                "wrappedKey",
                "wrappedKeyAlgorithm",
                "scopeSequence",
                "issuedAt",
                "expiresAt",
                "rootSignature",
            ),
            payload.toJsonMap().keys.toList(),
        )

        val map = payload.toJsonMap()
        assertEquals(1, map["v"])
        assertEquals("gmweb-conversation-key-grant", map["kind"])
        assertEquals(payload.grantId, map["grantId"])
        assertEquals("device-1", map["deviceId"])
        assertEquals("conversation", map["scopeMode"])
        assertEquals(conversationId, map["conversationId"])
        assertEquals("READ_MESSAGES", map["keyDomain"])
        assertEquals(epochId, map["epochId"])
        assertEquals("HPKE-P256-HKDF-SHA256-AES-256-GCM", map["wrappedKeyAlgorithm"])
        assertEquals(7L, map["scopeSequence"])
        assertEquals(issuedAt, map["issuedAt"])
        assertEquals(expiresAt, map["expiresAt"])
        assertEquals(payload.rootSignature, map["rootSignature"])
        assertFalse(map.containsKey("reason"))
        assertFalse(map.containsKey("revokesGrantIds"))

        // The wire field names must literally be the god-ai struct tags.
        val json = payload.toJson()
        val parsed = JSONObject(json)
        assertEquals(1, parsed.getInt("v"))
        assertEquals("gmweb-conversation-key-grant", parsed.getString("kind"))
        assertEquals(payload.grantId, parsed.getString("grantId"))
        assertEquals("device-1", parsed.getString("deviceId"))
        assertEquals("conversation", parsed.getString("scopeMode"))
        assertEquals(conversationId, parsed.getString("conversationId"))
        assertEquals("READ_MESSAGES", parsed.getString("keyDomain"))
        assertEquals(epochId, parsed.getString("epochId"))
        assertEquals(MessageCrypto.b64(wrapper.lastWrapped!!), parsed.getString("wrappedKey"))
        assertEquals(
            "HPKE-P256-HKDF-SHA256-AES-256-GCM",
            parsed.getString("wrappedKeyAlgorithm"),
        )
        assertEquals(7L, parsed.getLong("scopeSequence"))
        assertEquals(issuedAt, parsed.getLong("issuedAt"))
        assertEquals(expiresAt, parsed.getLong("expiresAt"))
        assertEquals(payload.rootSignature, parsed.getString("rootSignature"))
        assertFalse(parsed.has("reason"))
    }

    @Test
    fun `grant id is deterministic from the documented LDV-004 tuple`() {
        val payload = buildGrant()
        val expected = UUID.nameUUIDFromBytes(
            "LDV-004|device-1|conversation|conversation-1|epoch-1|7".toByteArray(Charsets.UTF_8),
        ).toString()
        assertEquals(expected, payload.grantId)
        assertEquals(
            ConversationGrantPublisher.grantIdFor(
                deviceId = "device-1",
                scopeMode = GrantScopeMode.CONVERSATION,
                conversationId = conversationId,
                epochId = epochId,
                sequence = 7L,
            ),
            payload.grantId,
        )

        // Republication of the same input is byte-for-byte identical (idempotent event id).
        assertEquals(payload, buildGrant())
        assertEquals(payload.toJson(), buildGrant().toJson())
    }

    @Test
    fun `different device, conversation, epoch or sequence produce a different id`() {
        val ids = setOf(
            buildGrant().grantId,
            buildGrant(target = GrantTarget("device-2", publicKey)).grantId,
            buildGrant(conversationId = "conversation-2").grantId,
            buildGrant(epochId = "epoch-2").grantId,
            buildGrant(sequence = 8L).grantId,
        )
        assertEquals(5, ids.size)

        val accountId = ConversationGrantPublisher.buildAccountGrant(
            target = target,
            epochId = epochId,
            key = cke,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            sequence = 7L,
            wrapper = FakeWrapper(),
            signer = FakeSigner(),
        ).grantId
        assertNotEquals(buildGrant().grantId, accountId)
    }

    @Test
    fun `account grant binds the empty conversation id and omits it from the JSON`() {
        val wrapper = FakeWrapper()
        val payload = ConversationGrantPublisher.buildAccountGrant(
            target = target,
            epochId = epochId,
            key = cke,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            sequence = 9L,
            wrapper = wrapper,
            signer = FakeSigner(),
        )
        assertEquals(GrantScopeMode.ACCOUNT, payload.scopeMode)
        assertNull(payload.conversationId)
        assertFalse(payload.toJsonMap().containsKey("conversationId"))
        assertFalse(payload.toJson().contains("\"conversationId\""))
        assertEquals(
            MessageCrypto.binding(
                "GMweb-conversation-key-grant-v1",
                payload.grantId,
                "device-1",
                "account",
                "",
                epochId,
                "READ_MESSAGES",
            ).toList(),
            wrapper.lastInfo!!.toList(),
        )
    }

    // ── Bindings handed to the injected crypto ─────────────────────────────

    @Test
    fun `wrapper receives the exact six-field HPKE info and the target key`() {
        val wrapper = FakeWrapper()
        val payload = buildGrant(wrapper = wrapper)

        val expectedInfo = MessageCrypto.binding(
            "GMweb-conversation-key-grant-v1",
            payload.grantId,
            "device-1",
            "conversation",
            conversationId,
            epochId,
            "READ_MESSAGES",
        )
        assertArrayEquals(expectedInfo, wrapper.lastInfo!!)
        assertArrayEquals(publicKey, wrapper.lastRecipient!!)
        assertArrayEquals(cke, wrapper.lastKey!!)

        // Domain + one b64 line per bound field, newline-joined, no trailing newline.
        assertEquals(7, String(wrapper.lastInfo!!, Charsets.UTF_8).lines().size)
        assertEquals(
            "d\nYQ==\nYg==",
            String(MessageCrypto.binding("d", "a", "b"), Charsets.UTF_8),
        )
    }

    @Test
    fun `signer receives the exact ten-field grant signature binding`() {
        val signer = FakeSigner()
        val payload = buildGrant(signer = signer)

        val expectedBinding = MessageCrypto.binding(
            "GMweb-conversation-key-grant-signature-v1",
            payload.grantId,
            "device-1",
            "conversation",
            conversationId,
            epochId,
            "READ_MESSAGES",
            "7",
            issuedAt.toString(),
            expiresAt.toString(),
            payload.wrappedKey!!,
        )
        assertArrayEquals(expectedBinding, signer.lastBinding!!)
        assertEquals(11, String(signer.lastBinding!!, Charsets.UTF_8).lines().size)
        // Every bound field is itself base64-encoded UTF-8 (MessageCrypto.binding, and
        // god-ai devcrypto.Binding): the already-base64 wrappedKey is encoded once more.
        val bindingLines = String(signer.lastBinding!!, Charsets.UTF_8).lines()
        assertEquals(
            MessageCrypto.b64(payload.wrappedKey!!.toByteArray(Charsets.UTF_8)),
            bindingLines.last(),
        )
    }

    // ── Tombstones ─────────────────────────────────────────────────────────

    @Test
    fun `tombstone reuses the grant id, carries no key material and signs the revoke binding`() {
        val grantSigner = FakeSigner()
        val grant = buildGrant(signer = grantSigner)
        val tombstoneSigner = FakeSigner()
        val tombstone = ConversationGrantPublisher.buildTombstone(
            target = target,
            grantedGrant = grant,
            issuedAt = issuedAt + 1_000L,
            sequence = 8L,
            reason = GrantRevokeReason.SUPERSEDED,
            signer = tombstoneSigner,
        )

        assertEquals(GrantKind.REVOKE, tombstone.kind)
        assertEquals("gmweb-conversation-key-revoke", tombstone.kind.wire)
        assertEquals(grant.grantId, tombstone.grantId)
        assertTrue(tombstone.isTombstone)
        assertNull(tombstone.wrappedKey)
        assertNull(tombstone.keyDomain)
        assertNull(tombstone.epochId)
        assertNull(tombstone.expiresAt)
        assertNull(tombstone.scopeMode)
        assertEquals(GrantRevokeReason.SUPERSEDED, tombstone.reason)

        assertEquals(
            listOf(
                "v",
                "kind",
                "grantId",
                "deviceId",
                "conversationId",
                "scopeSequence",
                "issuedAt",
                "rootSignature",
                "reason",
            ),
            tombstone.toJsonMap().keys.toList(),
        )

        val expectedBinding = MessageCrypto.binding(
            "GMweb-conversation-key-revoke-signature-v1",
            grant.grantId,
            "device-1",
            conversationId,
            "8",
            (issuedAt + 1_000L).toString(),
            "superseded",
        )
        assertArrayEquals(expectedBinding, tombstoneSigner.lastBinding!!)
        assertEquals(7, String(tombstoneSigner.lastBinding!!, Charsets.UTF_8).lines().size)

        // No key material, no ciphertext, no grant fields in the tombstone JSON.
        val json = tombstone.toJson()
        assertFalse(json.contains(grant.wrappedKey!!))
        assertFalse(json.contains("wrappedKey"))
        assertFalse(json.contains("keyDomain"))
        assertFalse(json.contains("epochId"))
        assertFalse(json.contains("expiresAt"))
        assertFalse(json.contains("scopeMode"))
        assertFalse(json.contains("revokesGrantIds"))
        assertEquals(8L, JSONObject(json).getLong("scopeSequence"))
        assertEquals("superseded", JSONObject(json).getString("reason"))
    }

    @Test
    fun `standalone tombstone has a deterministic id and account tombstones omit the conversation`() {
        val signer = FakeSigner()
        val tombstone = ConversationGrantPublisher.buildTombstone(
            target = target,
            conversationId = conversationId,
            issuedAt = issuedAt,
            sequence = 8L,
            reason = GrantRevokeReason.REVOKED,
            signer = signer,
        )
        assertEquals(
            UUID.nameUUIDFromBytes(
                "LDV-004|device-1|conversation|conversation-1||8".toByteArray(Charsets.UTF_8),
            ).toString(),
            tombstone.grantId,
        )
        assertEquals(conversationId, tombstone.conversationId)

        val accountSigner = FakeSigner()
        val accountWide = ConversationGrantPublisher.buildTombstone(
            target = target,
            conversationId = null,
            issuedAt = issuedAt,
            sequence = 9L,
            reason = GrantRevokeReason.UNLINKED,
            signer = accountSigner,
        )
        assertNull(accountWide.conversationId)
        assertFalse(accountWide.toJsonMap().containsKey("conversationId"))
        assertFalse(accountWide.toJson().contains("\"conversationId\""))
        assertArrayEquals(
            MessageCrypto.binding(
                "GMweb-conversation-key-revoke-signature-v1",
                accountWide.grantId,
                "device-1",
                "",
                "9",
                issuedAt.toString(),
                "unlinked",
            ),
            accountSigner.lastBinding,
        )
    }

    // ── Rejection of bad input ─────────────────────────────────────────────

    private fun assertRejected(block: () -> Unit) {
        assertThrows(IllegalArgumentException::class.java) { block() }
    }

    @Test
    fun `bad grant input is rejected before the injected crypto runs`() {
        val wrapper = FakeWrapper()
        val signer = FakeSigner()

        assertRejected { buildGrant(conversationId = " ", wrapper = wrapper, signer = signer) }
        assertRejected {
            buildGrant(
                conversationId = "x".repeat(ConversationGrantSchema.MAX_CONVERSATION_ID_LENGTH + 1),
                wrapper = wrapper,
                signer = signer,
            )
        }
        assertRejected {
            buildGrant(target = GrantTarget(" ", publicKey), wrapper = wrapper, signer = signer)
        }
        assertRejected {
            buildGrant(
                target = GrantTarget("device-1", ByteArray(64)),
                wrapper = wrapper,
                signer = signer,
            )
        }
        assertRejected {
            buildGrant(
                target = GrantTarget("device-1", ByteArray(65) { 5 }),
                wrapper = wrapper,
                signer = signer,
            )
        }
        assertRejected { buildGrant(epochId = " ", wrapper = wrapper, signer = signer) }
        assertRejected {
            buildGrant(
                epochId = "e".repeat(ConversationGrantSchema.MAX_EPOCH_ID_LENGTH + 1),
                wrapper = wrapper,
                signer = signer,
            )
        }
        assertRejected { buildGrant(key = ByteArray(31), wrapper = wrapper, signer = signer) }
        assertRejected { buildGrant(key = ByteArray(0), wrapper = wrapper, signer = signer) }
        assertRejected { buildGrant(sequence = 0L, wrapper = wrapper, signer = signer) }
        assertRejected { buildGrant(issuedAt = 0L, wrapper = wrapper, signer = signer) }
        assertRejected {
            buildGrant(expiresAt = issuedAt, wrapper = wrapper, signer = signer)
        }

        assertEquals("no wrapper call for rejected input", 0, wrapper.calls)
        assertEquals("no signer call for rejected input", 0, signer.calls)
    }

    @Test
    fun `a wrapper or signer output outside the schema is rejected`() {
        assertRejected {
            buildGrant(wrapper = FakeWrapper { _, _, _ -> ByteArray(0) })
        }
        assertRejected {
            buildGrant(
                wrapper = FakeWrapper { _, _, _ ->
                    ByteArray(ConversationGrantSchema.MAX_WRAPPED_KEY_BYTES + 1)
                },
            )
        }
        assertRejected { buildGrant(signer = FakeSigner { ByteArray(0) }) }
        assertRejected {
            buildGrant(signer = FakeSigner { ByteArray(ConversationGrantSchema.MAX_SIGNATURE_BYTES + 1) })
        }
    }

    @Test
    fun `bad tombstone input is rejected`() {
        val grant = buildGrant()
        assertRejected {
            ConversationGrantPublisher.buildTombstone(
                target = target,
                conversationId = conversationId,
                issuedAt = 0L,
                sequence = 8L,
                reason = GrantRevokeReason.REVOKED,
                signer = FakeSigner(),
            )
        }
        assertRejected {
            ConversationGrantPublisher.buildTombstone(
                target = target,
                conversationId = conversationId,
                issuedAt = issuedAt,
                sequence = 0L,
                reason = GrantRevokeReason.REVOKED,
                signer = FakeSigner(),
            )
        }
        assertRejected {
            ConversationGrantPublisher.buildTombstone(
                target = target,
                conversationId = "x".repeat(ConversationGrantSchema.MAX_CONVERSATION_ID_LENGTH + 1),
                issuedAt = issuedAt,
                sequence = 8L,
                reason = GrantRevokeReason.REVOKED,
                signer = FakeSigner(),
            )
        }
        assertRejected {
            ConversationGrantPublisher.buildTombstone(
                target = target,
                grantedGrant = grant,
                issuedAt = issuedAt,
                sequence = grant.scopeSequence,
                reason = GrantRevokeReason.REVOKED,
                signer = FakeSigner(),
            )
        }
        assertRejected {
            ConversationGrantPublisher.buildTombstone(
                target = target,
                grantedGrant = grant,
                issuedAt = issuedAt,
                sequence = grant.scopeSequence - 1,
                reason = GrantRevokeReason.REVOKED,
                signer = FakeSigner(),
            )
        }
        assertRejected {
            ConversationGrantPublisher.buildTombstone(
                target = GrantTarget("device-2", publicKey),
                grantedGrant = grant,
                issuedAt = issuedAt,
                sequence = 8L,
                reason = GrantRevokeReason.REVOKED,
                signer = FakeSigner(),
            )
        }
        val tombstone = ConversationGrantPublisher.buildTombstone(
            target = target,
            grantedGrant = grant,
            issuedAt = issuedAt,
            sequence = 8L,
            reason = GrantRevokeReason.REVOKED,
            signer = FakeSigner(),
        )
        assertRejected {
            ConversationGrantPublisher.buildTombstone(
                target = target,
                grantedGrant = tombstone,
                issuedAt = issuedAt,
                sequence = 9L,
                reason = GrantRevokeReason.REVOKED,
                signer = FakeSigner(),
            )
        }
    }

    @Test
    fun `grantId helpers reject contradictory scope arguments`() {
        assertRejected {
            ConversationGrantPublisher.grantIdFor(
                "device-1",
                GrantScopeMode.ACCOUNT,
                conversationId,
                epochId,
                1L,
            )
        }
        assertRejected {
            ConversationGrantPublisher.grantIdFor(
                "device-1",
                GrantScopeMode.CONVERSATION,
                null,
                epochId,
                1L,
            )
        }
        assertRejected {
            ConversationGrantPublisher.tombstoneGrantIdFor(
                "device-1",
                GrantScopeMode.CONVERSATION,
                null,
                1L,
            )
        }
    }

    // ── No key material in logs ────────────────────────────────────────────

    @Test
    fun `payload toString redacts the ciphertext and signature and never carries the CKE`() {
        val payload = buildGrant()
        val rendered = payload.toString()
        assertTrue(rendered.contains("<redacted"))
        assertFalse(rendered.contains(MessageCrypto.b64(cke)))
        assertFalse(rendered.contains(payload.wrappedKey!!))
        assertFalse(rendered.contains(payload.rootSignature))
        // The target's toString shows only the key length.
        assertFalse(target.toString().contains(MessageCrypto.b64(publicKey)))
        assertTrue(target.toString().contains("65 bytes"))
        // The wire JSON is the only place the ciphertext belongs, and it never has the CKE.
        assertTrue(payload.toJson().contains(payload.wrappedKey!!))
        assertFalse(payload.toJson().contains(MessageCrypto.b64(cke)))
    }

    // ── Optional real-crypto integration (Tink HPKE + JCA ECDSA) ───────────

    @Test
    fun `real HPKE wrapper and ECDSA signer round-trip with the built bindings`() {
        val device = newP256KeyPair()
        val rawPublicKey = rawP256PublicKey(device)
        val root = newP256KeyPair()
        val signer = GrantSigner { binding ->
            Signature.getInstance("SHA256withECDSA").run {
                initSign(root.private)
                update(binding)
                sign()
            }
        }
        val wrapper = GrantKeyWrapper { _, keyBytes, info ->
            MessageCrypto.wrapForDevice(rawPublicKey, keyBytes, info)
        }
        val key = MessageCrypto.randomKey()
        try {
            val grant = ConversationGrantPublisher.buildGrant(
                target = GrantTarget("device-hpke", rawPublicKey),
                conversationId = "conversation-hpke",
                epochId = "epoch-hpke",
                key = key,
                issuedAt = issuedAt,
                expiresAt = expiresAt,
                sequence = 3L,
                wrapper = wrapper,
                signer = signer,
            )

            // A device rebuilds `info` from the payload exactly as grant.go does.
            val info = MessageCrypto.binding(
                "GMweb-conversation-key-grant-v1",
                grant.grantId,
                grant.deviceId,
                grant.scopeMode!!.wire,
                grant.conversationId!!,
                grant.epochId!!,
                grant.keyDomain!!,
            )
            val opened = hpkeOpen(device, rawPublicKey, grant.wrappedKey!!, info)
            assertArrayEquals(key, opened)
            opened.fill(0)

            // Wrong scope => the device cannot open the ciphertext.
            val wrongInfo = MessageCrypto.binding(
                "GMweb-conversation-key-grant-v1",
                grant.grantId,
                grant.deviceId,
                grant.scopeMode!!.wire,
                "conversation-other",
                grant.epochId!!,
                grant.keyDomain!!,
            )
            assertThrows(Exception::class.java) {
                hpkeOpen(device, rawPublicKey, grant.wrappedKey!!, wrongInfo)
            }

            // The signature verifies over the exact binding and fails on tampering.
            val binding = ConversationGrantPublisher.grantSignatureBinding(
                grantId = grant.grantId,
                deviceId = grant.deviceId,
                scopeMode = grant.scopeMode!!,
                conversationId = grant.conversationId,
                epochId = grant.epochId!!,
                scopeSequence = grant.scopeSequence,
                issuedAt = grant.issuedAt,
                expiresAt = grant.expiresAt!!,
                wrappedKey = grant.wrappedKey!!,
            )
            assertTrue(ecdsaVerify(root, binding, grant.rootSignature))
            assertFalse(ecdsaVerify(root, binding + byteArrayOf(0), grant.rootSignature))

            val tombstone = ConversationGrantPublisher.buildTombstone(
                target = GrantTarget("device-hpke", rawPublicKey),
                grantedGrant = grant,
                issuedAt = issuedAt + 1L,
                sequence = 4L,
                reason = GrantRevokeReason.REVOKED,
                signer = signer,
            )
            val revokeBinding = ConversationGrantPublisher.revokeSignatureBinding(
                grantId = tombstone.grantId,
                deviceId = tombstone.deviceId,
                conversationId = tombstone.conversationId,
                scopeSequence = tombstone.scopeSequence,
                issuedAt = tombstone.issuedAt,
                reason = tombstone.reason!!,
            )
            assertTrue(ecdsaVerify(root, revokeBinding, tombstone.rootSignature))
        } finally {
            key.fill(0)
        }
    }

    // ── Fakes ──────────────────────────────────────────────────────────────

    /** Deterministic, reversible XOR-with-SHA-256(info) wrapper. No Tink. */
    private class FakeWrapper(
        private val delegate: (ByteArray, ByteArray, ByteArray) -> ByteArray = ::xorWrap,
    ) : GrantKeyWrapper {
        var calls = 0
        var lastRecipient: ByteArray? = null
        var lastKey: ByteArray? = null
        var lastInfo: ByteArray? = null
        var lastWrapped: ByteArray? = null

        override fun wrap(recipientPublicKey: ByteArray, key: ByteArray, info: ByteArray): ByteArray {
            calls++
            lastRecipient = recipientPublicKey.copyOf()
            lastKey = key.copyOf()
            lastInfo = info.copyOf()
            return delegate(recipientPublicKey, key, info).also { lastWrapped = it.copyOf() }
        }
    }

    /** Deterministic fake DER-shaped signature over the binding. No real key involved. */
    private class FakeSigner(
        private val delegate: (ByteArray) -> ByteArray = ::fakeSignature,
    ) : GrantSigner {
        var calls = 0
        var lastBinding: ByteArray? = null

        override fun sign(binding: ByteArray): ByteArray {
            calls++
            lastBinding = binding.copyOf()
            return delegate(binding)
        }
    }

    private companion object {
        fun newP256KeyPair(): KeyPair =
            KeyPairGenerator.getInstance("EC")
                .apply { initialize(ECGenParameterSpec("secp256r1")) }
                .generateKeyPair()

        fun coordinate(bytes: ByteArray): ByteArray =
            bytes.takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }

        fun rawP256PublicKey(keyPair: KeyPair): ByteArray {
            val publicKey = keyPair.public as ECPublicKey
            return byteArrayOf(0x04) +
                coordinate(publicKey.w.affineX.toByteArray()) +
                coordinate(publicKey.w.affineY.toByteArray())
        }

        fun ecdsaVerify(root: KeyPair, binding: ByteArray, signatureBase64: String): Boolean =
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(root.public)
                update(binding)
                verify(MessageCrypto.unb64(signatureBase64))
            }

        /** Tink HPKE open with the same suite/`info` a god-ai device would use. */
        fun hpkeOpen(
            device: KeyPair,
            rawPublicKey: ByteArray,
            wrappedKeyBase64: String,
            info: ByteArray,
        ): ByteArray {
            val privateKey = device.private as ECPrivateKey
            val scalar = coordinate(privateKey.s.toByteArray())
            val params = HpkeParams.newBuilder()
                .setKem(HpkeKem.DHKEM_P256_HKDF_SHA256)
                .setKdf(HpkeKdf.HKDF_SHA256)
                .setAead(HpkeAead.AES_256_GCM)
                .build()
            val publicProto = HpkePublicKey.newBuilder()
                .setVersion(0)
                .setParams(params)
                .setPublicKey(ByteString.copyFrom(rawPublicKey))
                .build()
            val keyProto = HpkePrivateKey.newBuilder()
                .setVersion(0)
                .setPublicKey(publicProto)
                .setPrivateKey(ByteString.copyFrom(scalar))
                .build()
            HybridConfig.register()
            val decrypt = Registry.getPrimitive(
                "type.googleapis.com/google.crypto.tink.HpkePrivateKey",
                keyProto.toByteString(),
                HybridDecrypt::class.java,
            )
            return decrypt.decrypt(MessageCrypto.unb64(wrappedKeyBase64), info)
        }
    }
}

/** Deterministic, reversible XOR-with-SHA-256(info) wrapper body. No Tink. */
private fun xorWrap(recipientPublicKey: ByteArray, key: ByteArray, info: ByteArray): ByteArray {
    val mask = MessageDigest.getInstance("SHA-256").digest(info)
    return ByteArray(key.size) { index ->
        (key[index].toInt() xor mask[index % mask.size].toInt()).toByte()
    }
}

/** Deterministic fake DER-shaped signature over the binding. No real key involved. */
private fun fakeSignature(binding: ByteArray): ByteArray =
    byteArrayOf(0x30, 0x44) + MessageDigest.getInstance("SHA-256").digest(binding)
