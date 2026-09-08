package com.autonomousone.messages

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.autonomousone.messages.data.MessageEntity
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.GatewayEventFactory
import com.autonomousone.messages.data.TrustedDeviceEntity
import com.autonomousone.messages.data.TelephonySyncCoordinator
import com.autonomousone.messages.security.ConversationKeyRepository
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FIX 5 — on-device end-to-end smoke of the sync flow (no network):
 *
 *   1. An inbound message is mirrored → an encrypted MESSAGE_CREATED row is
 *      committed to the durable outbox (body never leaves as plaintext).
 *   2. A browser is approved (trusted device, FULL_HISTORY).
 *   3. The post-approve key-grant drain emits a KEY_GRANT for that device.
 *
 * The network hops (batch upload + PWA decrypt) require a real gateway and a
 * linked browser; this test pins every device-side precondition instead.
 * Run: `gradlew :app:connectedDebugAndroidTest`.
 */
class MessageSyncE2EDeviceTest {

    private fun rawUncompressedPointBase64(): String {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val pub = kpg.generateKeyPair().public as ECPublicKey
        val size = (pub.params.curve.field.fieldSize + 7) / 8
        fun coord(b: java.math.BigInteger): ByteArray {
            val raw = b.toByteArray()
            val out = ByteArray(size)
            val copy = if (raw.size > size) raw.copyOfRange(raw.size - size, raw.size) else raw
            System.arraycopy(copy, 0, out, size - copy.size, copy.size)
            return out
        }
        val out = ByteArray(1 + 2 * size)
        out[0] = 4
        System.arraycopy(coord(pub.w.affineX), 0, out, 1, size)
        System.arraycopy(coord(pub.w.affineY), 0, out, 1 + size, size)
        return Base64.getEncoder().encodeToString(out)
    }

    @Test
    fun inboundMirrorApprovalAndGrantProduceUploadableE2eeRows() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, MessagesDatabase::class.java).build()
        try {
            val coordinator = TelephonySyncCoordinator(context, db)
            val now = System.currentTimeMillis()

            // 1) Inbound message arrives → durable encrypted event in outbox.
            val inbound = MessageEntity(
                "sms", 50_001L, 80_001L, "+15551230000", "+15551230000",
                "hello e2e", now, 1, read = false
            )
            db.withTransaction {
                db.messageDao().upsertAll(listOf(inbound))
                coordinator.enqueueHistorical(inbound)
            }
            val outboxBeforeApprove = db.gatewayEventOutboxDao().claimable(Long.MAX_VALUE, 10)
            assertEquals(1, outboxBeforeApprove.size)
            assertEquals("MESSAGE_CREATED", outboxBeforeApprove.single().eventType)
            assertEquals(1, outboxBeforeApprove.single().cryptoVersion)
            assertFalse(
                "ciphertext must not contain the plaintext body",
                String(outboxBeforeApprove.single().ciphertext).contains("hello e2e")
            )

            val outgoing = MessageEntity(
                "sms", 50_002L, 80_001L, "+15551230000", "+15551230000",
                "reply e2e", now + 1, 2, read = true
            )
            db.withTransaction {
                db.messageDao().upsertAll(listOf(outgoing))
                coordinator.enqueueHistorical(outgoing)
            }

            // 2) Approve a FULL_HISTORY browser device with contacts access.
            db.trustedDeviceDao().upsert(
                TrustedDeviceEntity(
                    deviceId = "web-e2e-device",
                    accountId = "default",
                    displayName = "Web · e2e",
                    deviceType = "WEB_PWA",
                    origin = "https://gmweb.example",
                    signingPublicKey = "",
                    encryptionPublicKey = rawUncompressedPointBase64(),
                    capabilitiesJson = "[\"READ_MESSAGES\",\"CONTACTS_READ\"]",
                    historyGrant = "FULL_HISTORY",
                    certificateJson = "{}",
                    certificateSignature = "",
                    trustSequence = 1,
                    status = TrustedDeviceEntity.STATUS_ACTIVE,
                    approvedAt = now,
                    expiresAt = now + 90L * 24 * 3600 * 1000,
                    revokedAt = null,
                    createdAt = now,
                    updatedAt = now
                )
            )

            // 3) A contact snapshot is encrypted and grants its separate key.
            db.withTransaction {
                val plain = GatewayEventFactory.outboxRow(
                    eventUuid = UUID.randomUUID().toString(),
                    eventType = "CONTACTS_SNAPSHOT",
                    conversationId = "contacts",
                    payloadJson = JSONObject()
                        .put("snapshotId", "device-test")
                        .put("chunkIndex", 0)
                        .put("chunkCount", 1)
                        .put("replaceAll", true)
                        .put("contacts", org.json.JSONArray())
                        .put("deleted", org.json.JSONArray())
                        .toString(),
                )
                val encrypted = ConversationKeyRepository(db).encrypt(plain, now, "CONTACTS_READ")
                db.gatewayEventOutboxDao().insertOrIgnore(encrypted)
            }

            // 4) Post-approve drain emits message-history grants too.
            ConversationKeyRepository(db).drainHistoryGrants()
            val allRows = db.gatewayEventOutboxDao().claimable(Long.MAX_VALUE, 50)
            assertEquals(2, allRows.count { it.eventType == "MESSAGE_CREATED" })
            assertTrue(allRows.any { it.eventType == "CONTACTS_SNAPSHOT" && it.cryptoVersion == 1 })
            assertTrue(allRows.any { it.eventType == "CONTACTS_KEY_GRANT" })
            val grants = allRows
                .filter { it.eventType == "KEY_GRANT" }
            assertTrue("KEY_GRANT must exist for the newly approved device", grants.isNotEmpty())
            assertTrue(grants.all { String(it.ciphertext).contains("web-e2e-device") })
        } finally {
            db.close()
        }
    }
}
