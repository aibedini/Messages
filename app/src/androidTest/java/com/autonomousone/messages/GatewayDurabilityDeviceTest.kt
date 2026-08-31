package com.autonomousone.messages.gateway

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.autonomousone.messages.data.GatewayEventFactory
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.RemoteCommandEntity
import com.autonomousone.messages.repository.GatewaySyncRepository
import com.autonomousone.messages.sms.GatewayOutgoingPipeline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PR-01..04 process-death contract tests (ADR-003 matrix, device-only).
 *
 * These exercise the DURABILITY layer against a real Room database on a real
 * device — the parts a JVM test cannot prove: actual SQLite locking, actual
 * Keystore presence, actual ActivityLifecycle kills are covered by the
 * manual ADR-003 matrix; what matters here is that the same repo operations
 * that survive process death behave identically on-device.
 *
 * Run: ./gradlew :app:connectedDebugAndroidTest (device/emulator attached).
 */
@RunWith(AndroidJUnit4::class)
class GatewayDurabilityDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun freshRepo(): GatewaySyncRepository {
        val db = MessagesDatabase.get(context)
        return GatewaySyncRepository(db)
    }

    @Test
    fun keystoreIdentity_enrollsOnRealDevice() {
        val identity = DeviceIdentity.ensureEnrolled()
        assertEquals(65, identity.trustRootPublicPoint.size)
        assertEquals(65, identity.signingPublicPoint.size)
        assertEquals(65, identity.encryptionPublicPoint.size)
        assertTrue(DeviceIdentity.isEnrolled())
    }

    @Test
    fun commandRedelivery_isIgnoredByUniqueIndex() = runBlocking {
        val repo = freshRepo()
        val cmd = RemoteCommandEntity(
            commandId = java.util.UUID.randomUUID().toString(),
            type = "SEND_SMS",
            ciphertext = """{"phone":"+989120000000","body":"device-test"}""".toByteArray(),
            encoding = "application/json",
            schemaVersion = 1,
            receivedAt = System.currentTimeMillis(),
            issuedAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 24L * 3600_000,
            idempotencyKey = "device-test-${System.nanoTime()}",
        )
        assertTrue("first ingest must insert", repo.ingestCommand(cmd))
        val dup = cmd.copy(commandId = java.util.UUID.randomUUID().toString())
        assertFalse("same idempotencyKey must be ignored", repo.ingestCommand(dup))
    }

    @Test
    fun guardedTransitions_rejectIllegalJumpsOnDevice() = runBlocking {
        val repo = freshRepo()
        val cmdId = java.util.UUID.randomUUID().toString()
        val inserted = repo.ingestCommand(
            RemoteCommandEntity(
                commandId = cmdId, type = "SEND_SMS", ciphertext = ByteArray(0),
                encoding = "application/json", schemaVersion = 1,
                receivedAt = System.currentTimeMillis(), issuedAt = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + 3_600_000,
                idempotencyKey = "guard-${System.nanoTime()}",
            )
        )
        assertTrue(inserted)
        // RECEIVED → COMPLETED must NOT pass (must go through ACCEPTED/EXECUTING)
        assertEquals(false, repo.markCommandState(cmdId, RemoteCommandEntity.STATE_COMPLETED, listOf(RemoteCommandEntity.STATE_EXECUTING)))
        assertEquals(true, repo.markCommandAcceptedIfReceived(cmdId))
        assertEquals(true, repo.markCommandState(cmdId, RemoteCommandEntity.STATE_EXECUTING, listOf(RemoteCommandEntity.STATE_ACCEPTED)))
        assertEquals(true, repo.markCommandState(cmdId, RemoteCommandEntity.STATE_COMPLETED, listOf(RemoteCommandEntity.STATE_EXECUTING)))
    }

    @Test
    fun outboxRecoverSending_requeuesAfterSimulatedCrash() = runBlocking {
        val repo = freshRepo()
        val before = repo.pendingDepth()
        // simulate the crash-window state by claiming then "dying" (no ACK):
        val factory = GatewayEventFactory.threadRead("conv-${System.nanoTime()}")
        // enqueue happens inside TelephonySyncCoordinator normally; insert directly here:
        MessagesDatabase.get(context).gatewayEventOutboxDao().insertOrIgnore(factory)
        val claimed = repo.claimBatch(System.currentTimeMillis())
        assertTrue(claimed.isNotEmpty())
        // recovery must move SENDING rows back to PENDING (recoverSending is
        // the first act of EventUploader.start())
        val recovered = repo.recoverSending()
        assertTrue(recovered >= claimed.size)
        assertTrue(repo.pendingDepth() >= before)
    }
}
