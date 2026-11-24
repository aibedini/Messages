package com.autonomousone.messages.data

import com.autonomousone.messages.security.ConversationGrantPublisher
import com.autonomousone.messages.security.ConversationGrantSchema
import com.autonomousone.messages.security.EventCryptoPolicy
import com.autonomousone.messages.security.GrantKeyWrapper
import com.autonomousone.messages.security.GrantRevokeReason
import com.autonomousone.messages.security.GrantSigner
import com.autonomousone.messages.security.GrantTarget
import com.autonomousone.messages.security.ConversationGrantPayload
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * LDV-004 PR-E slice 2b — the `KEY_GRANT` outbox row the existing uploader drains.
 *
 * Plain JVM test: the factory is pure and `GatewayEventOutboxEntity` is a data class, so the
 * assertions run without Room. The important claims are:
 *
 *  - the row is the shape `EventCryptoPolicy` (the uploader's own allowlist) accepts, so it is
 *    claimable and uploadable instead of dead-lettering on the first attempt;
 *  - the column holds the raw JSON GMweb must be able to `json_extract('$.deviceId')` from,
 *    and the WIRE form is its base64 (what `EventUploader` sends);
 *  - the event id is deterministic and equal to the payload `grantId`;
 *  - no plaintext key appears anywhere.
 */
class ConversationGrantOutboxTest {

    private val publicKey = ByteArray(ConversationGrantSchema.P256_PUBLIC_KEY_BYTES) { index ->
        if (index == 0) ConversationGrantSchema.P256_UNCOMPRESSED_PREFIX else index.toByte()
    }
    private val target = GrantTarget(deviceId = "ai-device-1", encryptionPublicKey = publicKey)
    private val rawCke = ByteArray(ConversationGrantSchema.CKE_BYTES) { (it + 1).toByte() }
    private val issuedAt = 1_700_000_000_000L
    private val expiresAt = issuedAt + 60L * 60L * 1000L
    private val epochId = "epoch-7"

    private fun grant(
        conversationId: String = "c-a",
        sequence: Long = 3L,
    ): ConversationGrantPayload = ConversationGrantPublisher.buildGrant(
        target = target,
        conversationId = conversationId,
        epochId = epochId,
        key = rawCke,
        issuedAt = issuedAt,
        expiresAt = expiresAt,
        sequence = sequence,
        wrapper = FakeWrapper,
        signer = FakeSigner,
    )

    private fun accountGrant(sequence: Long = 1L): ConversationGrantPayload =
        ConversationGrantPublisher.buildAccountGrant(
            target = target,
            epochId = epochId,
            key = rawCke,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            sequence = sequence,
            wrapper = FakeWrapper,
            signer = FakeSigner,
        )

    private fun tombstone(sequence: Long = 4L): ConversationGrantPayload =
        ConversationGrantPublisher.buildTombstone(
            target = target,
            conversationId = "c-a",
            issuedAt = issuedAt,
            sequence = sequence,
            reason = GrantRevokeReason.REVOKED,
            signer = FakeSigner,
        )

    private fun row(payload: ConversationGrantPayload): GatewayEventOutboxEntity =
        ConversationGrantOutbox.create(
            eventId = payload.grantId,
            deviceId = target.deviceId,
            payloadJson = payload.toJson().toByteArray(Charsets.UTF_8),
            createdAt = issuedAt,
        )

    // ── Row shape ───────────────────────────────────────────────────────────

    @Test
    fun `builds the KEY_GRANT row the uploader understands`() {
        val payload = grant()
        val entity = row(payload)

        assertEquals(ConversationGrantOutbox.EVENT_TYPE, entity.eventType)
        assertEquals("KEY_GRANT", entity.eventType)
        assertEquals("envelope.v1", entity.encoding)
        assertEquals(1, entity.cryptoVersion)
        assertEquals(1, entity.schemaVersion)
        assertEquals("the event id is the payload grantId", payload.grantId, entity.eventUuid)
        assertEquals("c-a", entity.aggregateId)
        assertEquals(issuedAt, entity.createdAt)
        assertEquals(GatewayEventOutboxEntity.STATE_PENDING, entity.state)
        assertEquals(GatewayEventOutboxEntity.PRIORITY_REALTIME, entity.priority)
        assertEquals(0L, entity.nextAttemptAt)
        assertEquals(0, entity.attemptCount)
        assertArrayEquals(payload.toJson().toByteArray(Charsets.UTF_8), entity.ciphertext)
    }

    @Test
    fun `account grants aggregate under the account keyring`() {
        val entity = row(accountGrant())

        assertEquals(ConversationGrantOutbox.ACCOUNT_AGGREGATE_ID, entity.aggregateId)
        assertEquals("__account_keyring__", entity.aggregateId)
        assertFalse(
            "an account grant names no conversation",
            JSONObject(String(entity.ciphertext, Charsets.UTF_8)).has("conversationId"),
        )
    }

    @Test
    fun `a tombstone is the same event type with the revoke kind`() {
        val entity = row(tombstone())
        val json = JSONObject(String(entity.ciphertext, Charsets.UTF_8))

        assertEquals("KEY_GRANT", entity.eventType)
        assertEquals("gmweb-conversation-key-revoke", json.getString("kind"))
        assertEquals("c-a", entity.aggregateId)
        assertEquals("c-a", json.getString("conversationId"))
    }

    // ── Uploader / GMweb contract ───────────────────────────────────────────

    @Test
    fun `every variant passes the uploader's own allowlist`() {
        // EventCryptoPolicy is exactly what EventUploader calls before putting a row on the
        // wire; a row that fails it is dead-lettered instead of published.
        EventCryptoPolicy.validateForUpload(row(grant()))
        EventCryptoPolicy.validateForUpload(row(accountGrant()))
        EventCryptoPolicy.validateForUpload(row(tombstone()))
    }

    @Test
    fun `the wire payload is base64 of the JSON and carries the routing deviceId`() {
        val payload = grant()
        val entity = row(payload)

        // EventUploader sends android.util.Base64.NO_WRAP(ciphertext), which is the standard
        // alphabet without line breaks — the same output as the JDK encoder used here.
        val wire = Base64.getEncoder().encodeToString(entity.ciphertext)
        val decoded = JSONObject(String(Base64.getDecoder().decode(wire), Charsets.UTF_8))

        assertEquals(
            "GMweb routes by json_extract(ciphertext, '$.deviceId')",
            target.deviceId,
            decoded.getString("deviceId"),
        )
        assertEquals(1, decoded.getInt("v"))
        assertEquals("gmweb-conversation-key-grant", decoded.getString("kind"))
        assertEquals(payload.grantId, decoded.getString("grantId"))
        assertEquals("conversation", decoded.getString("scopeMode"))
        assertEquals("c-a", decoded.getString("conversationId"))
        assertEquals("READ_MESSAGES", decoded.getString("keyDomain"))
        assertEquals(3L, decoded.getLong("scopeSequence"))
        assertEquals(issuedAt, decoded.getLong("issuedAt"))
        assertEquals(expiresAt, decoded.getLong("expiresAt"))
        assertTrue(decoded.has("rootSignature"))
        assertTrue(decoded.has("wrappedKey"))
    }

    // ── Targeting and determinism ───────────────────────────────────────────

    @Test
    fun `a payload addressed to another device is refused`() {
        val bytes = grant().toJson().toByteArray(Charsets.UTF_8)

        val error = assertThrows(IllegalArgumentException::class.java) {
            ConversationGrantOutbox.create(
                eventId = "event-1",
                deviceId = "some-other-device",
                payloadJson = bytes,
                createdAt = issuedAt,
            )
        }
        assertTrue(error.message.orEmpty().contains("different device"))
    }

    @Test
    fun `the event id is deterministic and a re-enqueue is the same row`() {
        val first = row(grant(sequence = 3L))
        val second = row(grant(sequence = 3L))
        val later = row(grant(sequence = 4L))

        assertEquals(first.eventUuid, second.eventUuid)
        assertEquals(first.eventUuid, ConversationGrantPublisher.grantIdFor(
            deviceId = target.deviceId,
            scopeMode = com.autonomousone.messages.security.GrantScopeMode.CONVERSATION,
            conversationId = "c-a",
            epochId = epochId,
            sequence = 3L,
        ))
        assertFalse(
            "a new decision has a new id, so it is a new row",
            first.eventUuid == later.eventUuid,
        )
    }

    // ── Key material ────────────────────────────────────────────────────────

    @Test
    fun `the row carries no plaintext key`() {
        val entity = row(grant())
        val text = String(entity.ciphertext, Charsets.UTF_8)

        assertFalse(
            "the base64 CKE must never be serialised",
            text.contains(Base64.getEncoder().encodeToString(rawCke)),
        )
        assertFalse(
            "nor may the raw CKE bytes appear",
            String(rawCke, Charsets.ISO_8859_1) in text,
        )
        // The wrapped key (HPKE ciphertext) is the only key material present.
        assertTrue(text.contains(JSONObject(text).getString("wrappedKey")))
    }

    // ── Fail-closed parsing ─────────────────────────────────────────────────

    @Test
    fun `unparseable payload metadata is treated as absent`() {
        val garbage = "not json".toByteArray(Charsets.UTF_8)

        assertNull(ConversationGrantOutbox.conversationIdOf(garbage))
        assertNull(ConversationGrantOutbox.deviceIdOf(garbage))
        assertEquals(ConversationGrantOutbox.ACCOUNT_AGGREGATE_ID, ConversationGrantOutbox.aggregateIdOf(garbage))
        assertThrows(IllegalArgumentException::class.java) {
            ConversationGrantOutbox.create("event-1", target.deviceId, garbage, issuedAt)
        }
    }

    // ── Fakes ───────────────────────────────────────────────────────────────

    /** Deterministic HPKE-shaped wrap (96 bytes). No crypto, no key material retained. */
    private object FakeWrapper : GrantKeyWrapper {
        override fun wrap(
            recipientPublicKey: ByteArray,
            key: ByteArray,
            info: ByteArray,
        ): ByteArray = ByteArray(96) { (it + 1).toByte() }
    }

    /** Deterministic DER-shaped signature (64 bytes). No key involved. */
    private object FakeSigner : GrantSigner {
        override fun sign(binding: ByteArray): ByteArray = ByteArray(64) { (it + 1).toByte() }
    }
}
