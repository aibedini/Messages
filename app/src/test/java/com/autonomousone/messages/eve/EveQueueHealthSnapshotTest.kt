package com.autonomousone.messages.eve

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The EVE queue's health projection (v3.4.x P0).
 *
 * The user's question is always the same: "EVE sent a request — where did it stop?". The
 * chain is `GMweb queued → Android pulled → Validation passed → Local queue → SIM submitted
 * → GMweb ACK`, and the card can only name the stage that stopped if the queue reports its
 * own counts and timestamps honestly.
 *
 * These tests also pin the two things that make it safe to display: no message body and no
 * full number leaves here, and a gateway request is reported as a SHORT TOKEN, never as its
 * id.
 */
class EveQueueHealthSnapshotTest {

    private lateinit var store: EveSmsQueue.MemoryStore
    private var clock = 1_000_000L

    @Before
    fun setUp() {
        store = EveSmsQueue.MemoryStore()
        EveSmsQueue.resetForTest(store)
        EveSmsQueue.clock = { clock }
        EveSmsQueue.bootstrap(store, sender = { _, _ -> true })
        // Stop the worker so the test drives the queue deterministically.
        EveSmsQueue.stop()
    }

    @After
    fun tearDown() {
        EveSmsQueue.resetForTest(EveSmsQueue.MemoryStore())
    }

    private fun enqueue(
        priority: String = "announcement",
        idempotencyKey: String? = null,
        gatewayRequestId: String? = null
    ) = EveSmsQueue.enqueue(
        to = "+989121234567",
        text = "secret body that must never be reported",
        priority = priority,
        idempotencyKey = idempotencyKey,
        meta = gatewayRequestId?.let {
            EveSmsQueue.GatewayMeta(gatewayRequestId = it, pulledAt = clock)
        }
    ).record

    @Test
    fun `anEmptyQueueIsIdle`() {
        val health = EveSmsQueue.healthSnapshot(clock)

        assertTrue(health.idle)
        assertEquals(0, health.queued)
        assertEquals(0, health.active)
        assertEquals(0, health.deferred)
        assertNull(health.lastPulledRequestToken)
        assertNull(health.lastLocalTransitionAt)
        assertNull(health.lastNativeSubmitAt)
    }

    @Test
    fun `aQueuedTaskIsCountedAsQueued`() {
        enqueue()

        val health = EveSmsQueue.healthSnapshot(clock)

        assertEquals(1, health.queued)
        assertEquals(0, health.active)
        assertFalse(health.idle)
        assertEquals(clock, health.lastLocalTransitionAt)
    }

    @Test
    fun `aPulledTaskContributesItsShortTokenButNeverItsIdOrBody`() {
        enqueue(gatewayRequestId = "gw-abcdefghijklmnop")

        val health = EveSmsQueue.healthSnapshot(clock)
        val token = health.lastPulledRequestToken!!

        assertEquals("a short handle, never the id itself", 8, token.length)
        assertFalse(token.contains("gw-abcdefghijklmnop"))
        // Nothing that identifies the user's message may reach the card or a report.
        val rendered = health.toString()
        assertFalse(rendered.contains("+989121234567"))
        assertFalse(rendered.contains("secret body"))
    }

    @Test
    fun `onlyGatewayTasksCountTowardsThePulledToken`() {
        enqueue(idempotencyKey = "local-1")

        val health = EveSmsQueue.healthSnapshot(clock)

        assertEquals(1, health.queued)
        assertNull(
            "a locally composed send has no gateway identity to report",
            health.lastPulledRequestToken
        )
    }

    @Test
    fun `priorityIsNotFlattenedIntoTheCounts`() {
        enqueue(priority = "critical", idempotencyKey = "a")
        enqueue(priority = "announcement", idempotencyKey = "b")

        val health = EveSmsQueue.healthSnapshot(clock)

        assertEquals("the card shows a total; priority stays available separately", 2, health.queued)
        assertEquals(
            mapOf("critical" to 1, "expired" to 0, "expiring" to 0, "announcement" to 1),
            EveSmsQueue.pendingByPriority()
        )
    }

    @Test
    fun `aCancelledTaskIsCountedApartFromAQueuedOne`() {
        enqueue(idempotencyKey = "queued", gatewayRequestId = "gw-q")
        val cancelled = enqueue(idempotencyKey = "cancelled", gatewayRequestId = "gw-c")
        EveSmsQueue.cancel(cancelled.requestId)

        val health = EveSmsQueue.healthSnapshot(clock)

        assertEquals(1, health.queued)
        assertEquals(1, health.cancelledRecent)
        assertEquals(0, health.active)
        assertEquals(0, health.failedRecent)
    }

    @Test
    fun `aDeliveredTaskLeavesTheQueueAndStampsTheNativeSubmit`() {
        enqueue(gatewayRequestId = "gw-1")
        clock = 1_000_500L

        EveSmsQueue.drainOne()

        val health = EveSmsQueue.healthSnapshot(clock)
        assertEquals("nothing is left waiting", 0, health.queued)
        assertEquals(1, health.sentRecent)
        assertNotNull("the SIM submission is the stage after the local queue", health.lastNativeSubmitAt)
    }

    @Test
    fun `theSnapshotIsConsistentWithTheQueuesOwnPendingTotal`() {
        enqueue(idempotencyKey = "a", gatewayRequestId = "gw-1")
        enqueue(idempotencyKey = "b", gatewayRequestId = "gw-2")

        val health = EveSmsQueue.healthSnapshot(clock)

        assertEquals(
            "the projection must agree with the queue's own pending total",
            EveSmsQueue.totalPending(),
            health.queued + health.active + health.deferred
        )
    }

    @Test
    fun `theClockIsInjectedSoTheSnapshotHasNoWallClockDependency`() {
        clock = 5_000_000L
        enqueue(gatewayRequestId = "gw-1")

        val health = EveSmsQueue.healthSnapshot(clock)

        assertEquals(5_000_000L, health.lastLocalTransitionAt)
    }

    @Test
    fun `theGatewayAckStampIsNeverSetByTheQueueItself`() {
        enqueue(gatewayRequestId = "gw-1")
        EveSmsQueue.drainOne()

        val health = EveSmsQueue.healthSnapshot(clock)

        assertNull(
            "the ACK leg is owned by whoever performed it, so the two can never disagree",
            health.lastGatewayAckAt
        )
    }
}
