package com.autonomousone.messages

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.autonomousone.messages.data.CloudHistoryCheckpointEntity
import com.autonomousone.messages.data.GatewayEventOutboxEntity
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.repository.GatewaySyncRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryAckCheckpointDeviceTest {
    @Test
    fun ackWatermarkAdvancesOnlyAcrossContiguousRowsAndDeadLetterBlocksCompletion() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, MessagesDatabase::class.java).build()
        try {
            val dao = db.gatewayEventOutboxDao()
            val repository = GatewaySyncRepository(db)
            (1L..4L).forEach { ordinal ->
                dao.insertOrIgnore(GatewayEventOutboxEntity(
                    eventUuid = "history-$ordinal",
                    eventType = "MESSAGE_CREATED",
                    aggregateId = "thread",
                    ciphertext = byteArrayOf(1),
                    encoding = "envelope.v3", schemaVersion = 1, cryptoVersion = 3, createdAt = 1,
                    historySource = "sms",
                    historyGeneration = 4,
                    historyOrdinal = ordinal,
                    historyDate = 1000 - ordinal,
                    historyProviderId = ordinal,
                ))
            }
            db.cloudHistoryCheckpointDao().upsert(CloudHistoryCheckpointEntity(
                source = "sms", generation = 4,
                producerCursorDate = 996, producerCursorProviderId = 4,
                nextOrdinal = 5, ackedContiguousOrdinal = 0,
                ackedCursorDate = Long.MAX_VALUE, ackedCursorProviderId = Long.MAX_VALUE,
                sourceExhausted = true, updatedAt = 1,
            ))

            repository.onAcked("history-1", 1, 10)
            repository.onAcked("history-2", 2, 10)
            repository.onAcked("history-4", 4, 10)
            assertEquals(2, db.cloudHistoryCheckpointDao().get("sms")?.ackedContiguousOrdinal)
            assertFalse(repository.isHistoryDeliveryComplete("sms"))

            repository.onAcked("history-3", 3, 10)
            assertEquals(4, db.cloudHistoryCheckpointDao().get("sms")?.ackedContiguousOrdinal)
            assertTrue(repository.isHistoryDeliveryComplete("sms"))

            dao.insertOrIgnore(GatewayEventOutboxEntity(
                eventUuid = "history-5", eventType = "MESSAGE_CREATED", aggregateId = "thread",
                ciphertext = byteArrayOf(1), historySource = "sms", historyGeneration = 4,
                historyOrdinal = 5, historyDate = 995, historyProviderId = 5,
                encoding = "envelope.v3", schemaVersion = 1, cryptoVersion = 3, createdAt = 1,
            ))
            dao.markDead("history-5")
            val checkpoint = db.cloudHistoryCheckpointDao().get("sms")!!
            db.cloudHistoryCheckpointDao().upsert(checkpoint.copy(nextOrdinal = 6))
            assertFalse(repository.isHistoryDeliveryComplete("sms"))
        } finally {
            db.close()
        }
    }
}
