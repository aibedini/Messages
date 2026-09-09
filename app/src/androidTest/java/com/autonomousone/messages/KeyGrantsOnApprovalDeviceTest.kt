package com.autonomousone.messages

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.autonomousone.messages.data.MessageEntity
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.TrustedDeviceEntity
import com.autonomousone.messages.data.TelephonySyncCoordinator
import com.autonomousone.messages.security.ConversationKeyRepository
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3 regression: three pre-existing conversations still require exactly one
 * browser-bound history key after FULL_HISTORY approval.
 *
 * Run with a device/emulator: `gradlew :app:connectedDebugAndroidTest`.
 */
class KeyGrantsOnApprovalDeviceTest {

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
    fun grantsAreEmittedForEpochsThatPredateTheApproval() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, MessagesDatabase::class.java).build()
        try {
            val coordinator = TelephonySyncCoordinator(context, db)
            val now = System.currentTimeMillis()
            val deviceId = "web-pwa-fake-approved"

            // 1) Three conversations share one history master before approval.
            for (i in 1L..3L) {
                val message = MessageEntity(
                    "sms", 1_000L + i, 9_000L + i, "+15551230000", "+15551230000",
                    "hello backfill $i", 10_000L + i, 1, read = true
                )
                db.withTransaction {
                    db.messageDao().upsertAll(listOf(message))
                    coordinator.enqueueHistorical(message)
                }
            }
            val before = db.gatewayEventOutboxDao().claimable(Long.MAX_VALUE, 50)
            assertEquals(3, before.size)
            assertTrue("historical events must be encrypted v3", before.all { it.cryptoVersion == 3 })
            assertEquals("no grants before approval", 0, before.count { it.eventType == "HISTORY_KEY_GRANT" })

            // 2) The user approves a web device (FULL_HISTORY) — durable row.
            db.trustedDeviceDao().upsert(
                TrustedDeviceEntity(
                    deviceId = deviceId,
                    accountId = "default",
                    displayName = "Web · test",
                    deviceType = "WEB_PWA",
                    origin = "https://gmweb.example",
                    signingPublicKey = "",
                    encryptionPublicKey = rawUncompressedPointBase64(),
                    capabilitiesJson = "[\"READ_MESSAGES\"]",
                    historyGrant = "FULL_HISTORY",
                    certificateJson = org.json.JSONObject()
                        .put("webOrigin", "https://gmweb.example")
                        .put("pairingTranscriptHash", "grant-device-transcript")
                        .toString(),
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

            // 3) The approval drain emits one key, not one grant per thread.
            ConversationKeyRepository(db).drainHistoryGrants()
            val after = db.gatewayEventOutboxDao().claimable(Long.MAX_VALUE, 100)
            val grants = after.filter { it.eventType == "HISTORY_KEY_GRANT" }
            assertEquals("one history key unlocks every ordinary conversation", 1, grants.size)
            assertTrue(String(grants.single().ciphertext).contains(deviceId))
            assertTrue(String(grants.single().ciphertext).contains("wrappedKey"))
            assertFalse(after.any { it.eventType == "KEY_GRANT" })
        } finally {
            db.close()
        }
    }
}
