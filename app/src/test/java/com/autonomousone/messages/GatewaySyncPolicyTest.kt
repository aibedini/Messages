package com.autonomousone.messages

import com.autonomousone.messages.data.GatewayEventOutboxEntity
import com.autonomousone.messages.repository.GatewaySyncRepository.Policy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Pins the LOCK 13 upload policy (ADR-003 session locks): batch limits,
 * full-jitter exponential backoff bounds, and the byte-cap rule
 * (a lone oversized event still ships alone — the queue must never wedge).
 */
class GatewaySyncPolicyTest {

    private fun event(bytes: Int, id: Long = 0) = GatewayEventOutboxEntity(
        id = id,
        eventUuid = "evt-$id",
        eventType = "MESSAGE_CREATED",
        aggregateId = "agg-$id",
        ciphertext = ByteArray(bytes),
        encoding = "json",
        schemaVersion = 1,
        createdAt = 0
    )

    @Test
    fun `batch is capped at 100 events`() {
        val candidates = List(150) { event(10, it.toLong()) }
        val batch = Policy.selectBatch(candidates)
        assertEquals(Policy.MAX_BATCH_EVENTS, batch.events.size)
        assertEquals(100, batch.events.size)
    }

    @Test
    fun `batch stops before exceeding the byte cap`() {
        // 40,960 B × 12 = 491,520 ≤ 524,288 cap; the 13th would overflow
        val candidates = List(20) { event(40 * 1024, it.toLong()) }
        val batch = Policy.selectBatch(candidates)
        assertTrue(batch.bytes <= Policy.MAX_BATCH_BYTES)
        assertEquals(12, batch.events.size) // 12 × 40,960 = 491,520 ≤ 524,288
    }

    @Test
    fun `a lone oversized event still ships alone - queue never wedges`() {
        val big = event(600 * 1024) // > 512 KiB cap by itself
        val batch = Policy.selectBatch(listOf(big, event(10, 1)))
        assertEquals(listOf(big), batch.events)
    }

    @Test
    fun `empty candidates produce an empty batch`() {
        assertTrue(Policy.selectBatch(emptyList()).events.isEmpty())
    }

    @Test
    fun `backoff stays within full-jitter bounds and respects the cap`() {
        // Statistical bound check per attempt level
        for (attempt in intArrayOf(0, 1, 3, 10, 30)) {
            repeat(200) {
                val d = Policy.backoffDelayMs(attempt, Random(it * 31 + attempt))
                val ceiling = minOf(
                    Policy.BACKOFF_CAP_MS,
                    Policy.BACKOFF_BASE_MS shl attempt.coerceIn(0, 20)
                )
                assertTrue("delay $d negative at attempt $attempt", d >= 0)
                assertTrue("delay $d over ceiling $ceiling at attempt $attempt", d < ceiling || ceiling <= 1)
            }
        }
        // attempt 0 ceiling = base
        repeat(50) {
            assertTrue(Policy.backoffDelayMs(0, Random(it)) < Policy.BACKOFF_BASE_MS)
        }
        // late attempts capped at 5 minutes
        repeat(50) {
            assertTrue(Policy.backoffDelayMs(30, Random(it)) < Policy.BACKOFF_CAP_MS)
        }
    }

    @Test
    fun `backoff ceiling grows monotonically until the cap`() {
        var prev = 0L
        for (attempt in 0..6) {
            val ceiling = minOf(Policy.BACKOFF_CAP_MS, Policy.BACKOFF_BASE_MS shl attempt)
            assertTrue(ceiling > prev || ceiling == Policy.BACKOFF_CAP_MS)
            prev = ceiling
        }
        assertEquals(Policy.BACKOFF_CAP_MS, minOf(Policy.BACKOFF_CAP_MS, Policy.BACKOFF_BASE_MS shl 12))
    }
}
