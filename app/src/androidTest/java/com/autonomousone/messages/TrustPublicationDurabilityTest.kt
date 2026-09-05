package com.autonomousone.messages

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.TrustedDeviceEntity
import com.autonomousone.messages.data.TrustStatementOutboxEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrustPublicationDurabilityTest {
    @Test fun staleApprovalAckCannotReactivateRevokingDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, MessagesDatabase::class.java).build()
        try {
            val device = TrustedDeviceEntity("web", "default", "Browser", "WEB_PWA", "https://example.test",
                "signing", "encryption", "[]", "FROM_NOW_ON", "certificate", "signature", 2,
                TrustedDeviceEntity.STATUS_REVOKE_PENDING, 1, Long.MAX_VALUE, 2, 1, 2)
            db.trustedDeviceDao().upsert(device)
            db.trustedDeviceDao().setStatusForSequence("web", 1, TrustedDeviceEntity.STATUS_ACTIVE, 3)
            assertEquals(TrustedDeviceEntity.STATUS_REVOKE_PENDING, db.trustedDeviceDao().byId("web")?.status)
            db.trustedDeviceDao().setStatusForSequence("web", 2, TrustedDeviceEntity.STATUS_REVOKED, 4)
            assertEquals(TrustedDeviceEntity.STATUS_REVOKED, db.trustedDeviceDao().byId("web")?.status)
            try {
                db.withTransaction {
                    db.trustedDeviceDao().upsert(device.copy(deviceId = "rollback"))
                    db.trustStatementOutboxDao().enqueue(TrustStatementOutboxEntity("statement", 3,
                        "DEVICE_APPROVED", "rollback", "{}", "signature", "PENDING", 0, 1, null))
                    error("simulated persistence failure")
                }
            } catch (_: IllegalStateException) { }
            assertNull(db.trustedDeviceDao().byId("rollback"))
            assertEquals(0, db.trustStatementOutboxDao().pendingBatch().size)
        } finally { db.close() }
    }
}
