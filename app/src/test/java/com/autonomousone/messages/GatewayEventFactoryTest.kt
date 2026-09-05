package com.autonomousone.messages

import com.autonomousone.messages.data.GatewayEventFactory
import com.autonomousone.messages.data.GatewayEventOutboxEntity
import com.autonomousone.messages.repository.GatewaySyncRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * PR-02/PR-03 pins: deterministic §13 identity (event/message UUIDs), the
 * envelope round-trip contract (PR-01 crypto-friendly payload columns), and
 * the batch policy boundary values (LOCK 13).
 */
class GatewayEventFactoryTest {

    @Test
    fun `message identity is stable for the same provider row and never body-derived`() {
        val a = GatewayEventFactory.messageIdFor("sms", 18472, 1700000000000L)
        val b = GatewayEventFactory.messageIdFor("sms", 18472, 1700000000000L)
        assertEquals(a, b)
        // MMS id space is separate (source in the seed), and a different row
        // or date can never collide:
        assertNotEquals(a, GatewayEventFactory.messageIdFor("mms", 18472, 1700000000000L))
        assertNotEquals(a, GatewayEventFactory.messageIdFor("sms", 18473, 1700000000000L))
    }

    @Test
    fun `event uuid differs per kind so created status deleted can coexist`() {
        val created = GatewayEventFactory.eventUuidFor(
            GatewayEventFactory.Types.MESSAGE_CREATED, "sms", 7, 1000L
        )
        val status = GatewayEventFactory.eventUuidFor(
            GatewayEventFactory.Types.MESSAGE_STATUS_CHANGED, "sms", 7, 1000L
        )
        assertNotEquals(created, status)
    }

    @Test
    fun `envelope round-trip preserves the payload and declares cryptoVersion zero`() {
        val row = GatewayEventFactory.messageCreated(
            source = "sms",
            providerId = 42,
            conversationId = "conv-uuid",
            direction = "in",
            body = "سلام این یک پیام آزمایشی است",
            dateMs = 1700000000000L,
            status = -1,
            address = "+989121234567"
        )
        assertEquals("envelope.v1", row.encoding)
        assertEquals(0, row.cryptoVersion)
        assertEquals("conv-uuid", row.aggregateId)
        val decoded = GatewayEventFactory.decodePayloadEnvelope(row.ciphertext)
        assertTrue(decoded.contains("\"body\":\"سلام این یک پیام آزمایشی است\""))
        assertTrue(decoded.contains("\"direction\":\"in\""))
        assertTrue(decoded.contains("\"address\":\"+989121234567\""))
    }

    @Test
    fun `envelope rejects unknown crypto versions - the Phase 7 extension point is explicit`() {
        val e = GatewayEventOutboxEntity(
            eventUuid = "x", eventType = "t", aggregateId = "c",
            ciphertext = """{"ciphertextB64":"aGk=","encoding":"application/json","schemaVersion":1,"cryptoVersion":1}"""
                .toByteArray(),
            encoding = "envelope.v1", schemaVersion = 1, createdAt = 0
        )
        try {
            GatewayEventFactory.decodePayloadEnvelope(e.ciphertext)
            org.junit.Assert.fail("cryptoVersion=1 must be rejected until Phase 7 lands")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("cryptoVersion=1"))
        }
    }

    // ── LOCK 13 boundary values (Policy) ────────────────────────────────────

    private fun event(bytes: Int, id: Long = 0) = GatewayEventOutboxEntity(
        id = id, eventUuid = "e$id", eventType = "t", aggregateId = "a",
        ciphertext = ByteArray(bytes), encoding = "e", schemaVersion = 1, createdAt = 0
    )

    @Test
    fun `batch caps - exactly 100 events and exactly 512 KiB accepted byte size`() {
        assertEquals(100, GatewaySyncRepository.Policy.MAX_BATCH_EVENTS)
        assertEquals(512 * 1024, GatewaySyncRepository.Policy.MAX_BATCH_BYTES)
        // 13 × 40,960 = 532,480 > 524,288 → 12 accepted (each ≤ cap alone)
        val batch = GatewaySyncRepository.Policy.selectBatch(List(20) { event(40 * 1024, it.toLong()) })
        assertEquals(12, batch.events.size)
        // A single oversized event still ships (never wedges the queue)
        val lone = GatewaySyncRepository.Policy.selectBatch(listOf(event(600 * 1024, 99)))
        assertEquals(1, lone.events.size)
    }

    @Test
    fun `backoff is full-jitter - never negative and capped at five minutes`() {
        repeat(500) {
            val d = GatewaySyncRepository.Policy.backoffDelayMs(it % 25, Random(it))
            assertTrue(d >= 0)
            assertTrue(d < GatewaySyncRepository.Policy.BACKOFF_CAP_MS || it % 25 == 0)
        }
    }
}
