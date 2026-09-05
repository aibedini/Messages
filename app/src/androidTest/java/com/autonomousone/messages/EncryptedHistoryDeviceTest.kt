package com.autonomousone.messages

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.autonomousone.messages.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EncryptedHistoryDeviceTest {
    @Test fun eligibleHistoryIsEncryptedDurableAndIdempotentWhileOtpStaysLocal() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, MessagesDatabase::class.java).build()
        try {
            val coordinator = TelephonySyncCoordinator(context, db)
            val message = MessageEntity("sms", 123, 456, "+15551230000", "+15551230000", "Dinner at six", 1000, 1, read = true)
            suspend fun persist(row: MessageEntity) = db.withTransaction {
                db.messageDao().upsertAll(listOf(row))
                coordinator.enqueueHistorical(row)
            }
            persist(message)
            val first = db.gatewayEventOutboxDao().claimable(Long.MAX_VALUE, 10).single()
            assertEquals(1, first.cryptoVersion)
            assertFalse(String(first.ciphertext).contains(message.body))
            assertEquals(GatewayEventFactory.eventUuidFor("MESSAGE_CREATED", "sms", 123, 1000), first.eventUuid)
            persist(message)
            assertEquals(1, db.gatewayEventOutboxDao().pendingDepth())
            assertArrayEquals(first.ciphertext, db.gatewayEventOutboxDao().claimable(Long.MAX_VALUE, 10).single().ciphertext)
            persist(message.copy(providerId = 124, body = "Your verification code is 123456"))
            assertEquals(1, db.gatewayEventOutboxDao().pendingDepth())
            coordinator.syncAllowed = false
            persist(message.copy(providerId = 125))
            assertEquals(1, db.gatewayEventOutboxDao().pendingDepth())
        } finally { db.close() }
    }
}
