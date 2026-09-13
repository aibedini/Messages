package com.autonomousone.messages

import com.autonomousone.messages.eve.EveSmsQueue
import com.autonomousone.messages.gateway.GatewayAckTracker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * DEFERRED correctness must NOT depend on GMweb redelivering the task.
 *
 * A task whose final pre-send validation could not be obtained is parked
 * DEFERRED and retried by EveSmsQueue's own backoff/sweep. When that local retry
 * produces a terminal outcome, the ACK is emitted by the local ledger — with no
 * second /gateway/pull of the same task and no second local send.
 *
 * "pulls" in this test counts logical deliveries from GMweb. It is incremented
 * exactly once (the initial pull) and every test asserts it stays at 1, so the
 * retry, the terminal state and the ACK are all proven to be locally driven.
 */
class GatewayDeferredAckTest {

    private class RoundTripStore : EveSmsQueue.Store {
        var records: List<EveSmsQueue.Record> = emptyList()
        var idem: Map<String, String> = emptyMap()
        override fun load(): Pair<List<EveSmsQueue.Record>, Map<String, String>> = records to idem
        override fun save(records: List<EveSmsQueue.Record>, idempotency: Map<String, String>) {
            this.records = records.toList()
            this.idem = idempotency.toMap()
        }
    }

    private data class Ack(val gatewayRequestId: String, val outcome: String, val reason: String?)

    private lateinit var store: RoundTripStore
    private lateinit var tracker: GatewayAckTracker
    private val sends = mutableListOf<String>()
    private val acks = mutableListOf<Ack>()
    private var nowMs = 1_700_000_000_000L
    private var pulls = 0

    @Before
    fun setUp() {
        store = RoundTripStore()
        sends.clear()
        acks.clear()
        pulls = 0
        nowMs = 1_700_000_000_000L
        EveSmsQueue.resetForTest(store)
        // Drain any persist queued by a previous test before this store is used.
        EveSmsQueue.awaitPersistence()
        EveSmsQueue.clock = { nowMs }
        tracker = newTracker()
    }

    @After
    fun tearDown() {
        EveSmsQueue.stop()
        EveSmsQueue.resetForTest(EveSmsQueue.MemoryStore())
    }

    private fun newTracker(): GatewayAckTracker = GatewayAckTracker(
        statusOf = { localRequestId -> EveSmsQueue.status(localRequestId) },
        sendAck = { rec, outcome, reason ->
            acks.add(Ack(rec.gatewayRequestId ?: "?", outcome, reason))
        }
    )

    private fun bootAndStop(
        validator: EveSmsQueue.FinalValidator,
        sender: (String, String) -> Boolean = { _, text -> sends.add(text); true }
    ) {
        EveSmsQueue.bootstrap(store, sender, validator)
        // Stop the worker so the test drives drainOne()/sweepDeferred() the way
        // the production worker loop does.
        EveSmsQueue.stop()
    }

    /** The one and only pull of the task, plus the poller's ledger registration. */
    private fun initialPull(gatewayRequestId: String): EveSmsQueue.Record {
        pulls++
        val rec = EveSmsQueue.enqueue(
            to = "+989120000001",
            text = "volume ended",
            priority = "critical",
            idempotencyKey = null,
            meta = EveSmsQueue.GatewayMeta(
                gatewayRequestId = gatewayRequestId,
                source = "eve",
                serviceKey = "eve:srv-1:client-1",
                notificationKind = "volume_ended",
                generation = 17,
                correlationId = "corr-" + gatewayRequestId,
                requiresValidation = true,
                pulledAt = nowMs
            )
        ).record
        tracker.track(gatewayRequestId, rec.requestId)
        return rec
    }

    private fun statusOf(rec: EveSmsQueue.Record) = EveSmsQueue.status(rec.requestId)!!

    // ── required scenario 1: timeout -> valid ────────────────────────────────

    @Test
    fun deferredTaskIsRetriedAndAckedLocallyWithoutAnySecondPull() {
        var validationCalls = 0
        bootAndStop(
            EveSmsQueue.FinalValidator {
                validationCalls++
                if (validationCalls == 1) {
                    EveSmsQueue.ValidationDecision.Unavailable("timeout")
                } else {
                    EveSmsQueue.ValidationDecision.Valid
                }
            }
        )
        val rec = initialPull("A")

        // Final gate runs here: validation transport failed -> fail closed.
        EveSmsQueue.drainOne()
        assertEquals(EveSmsQueue.Status.DEFERRED, statusOf(rec).status)
        assertTrue(sends.isEmpty())
        assertEquals("nothing may be acked while DEFERRED", 0, tracker.drain())
        assertTrue(acks.isEmpty())
        assertEquals("no second /gateway/pull may have happened", 1, pulls)

        // The backoff expires. This is exactly what the queue worker's
        // sweepDeferred() does; no server involvement at all.
        nowMs = statusOf(rec).deferredUntil
        assertEquals(1, EveSmsQueue.sweepDeferred(nowMs))

        // The local worker retries the final validation for the SAME requestId.
        EveSmsQueue.drainOne()

        assertEquals("SmsManager invoked exactly once", 1, sends.size)
        assertEquals(EveSmsQueue.Status.SENT, statusOf(rec).status)
        assertEquals(2, validationCalls)
        assertTrue(statusOf(rec).nativeSubmitStartedAt > 0)

        // …and the ACK follows from that local terminal state, exactly once.
        assertEquals("ACK sent exactly once", 1, tracker.drain())
        assertEquals(listOf(Ack("A", "sent", null)), acks)
        assertEquals("never a second ACK", 0, tracker.drain())
        assertEquals(1, acks.size)
        assertEquals(1, pulls)
    }

    // ── required scenario 2: timeout -> superseded ───────────────────────────

    @Test
    fun deferredTaskSupersededOnLocalRetryIsNeverSentAndAckedSupersededOnce() {
        var validationCalls = 0
        bootAndStop(
            EveSmsQueue.FinalValidator {
                validationCalls++
                if (validationCalls == 1) {
                    EveSmsQueue.ValidationDecision.Unavailable("http_500")
                } else {
                    EveSmsQueue.ValidationDecision.Superseded("renewed")
                }
            }
        )
        val rec = initialPull("A")

        EveSmsQueue.drainOne()
        assertEquals(EveSmsQueue.Status.DEFERRED, statusOf(rec).status)
        assertEquals(0, tracker.drain())
        assertEquals(1, pulls)

        nowMs = statusOf(rec).deferredUntil
        assertEquals(1, EveSmsQueue.sweepDeferred(nowMs))
        EveSmsQueue.drainOne()

        assertEquals("SmsManager invoked zero times", 0, sends.size)
        assertEquals(EveSmsQueue.Status.SUPERSEDED, statusOf(rec).status)
        assertEquals(2, validationCalls)

        assertEquals("ACK superseded exactly once", 1, tracker.drain())
        assertEquals(listOf(Ack("A", "superseded", "renewed")), acks)
        assertEquals(0, tracker.drain())
        assertEquals(1, acks.size)
        assertEquals(1, pulls)
    }

    // ── the same guarantees across process death + reboot ────────────────────

    @Test
    fun deferredTaskSurvivesRestartAndIsStillRetriedAndAckedLocally() {
        bootAndStop(EveSmsQueue.FinalValidator {
            EveSmsQueue.ValidationDecision.Unavailable("offline")
        })
        val rec = initialPull("A")
        EveSmsQueue.drainOne()
        assertEquals(EveSmsQueue.Status.DEFERRED, statusOf(rec).status)
        EveSmsQueue.awaitPersistence()
        assertEquals(1, store.records.count { it.gatewayRequestId == "A" })
        assertEquals(EveSmsQueue.Status.DEFERRED, store.records.single().status)

        // Process death + reboot: the in-memory ledger is gone.
        EveSmsQueue.stop()
        tracker = newTracker()

        bootAndStop(EveSmsQueue.FinalValidator { EveSmsQueue.ValidationDecision.Valid })
        // The poller re-seeds its ledger from the durable queue on start.
        EveSmsQueue.outstandingGatewayRecords().forEach { r ->
            r.gatewayRequestId?.let { tracker.track(it, r.requestId) }
        }
        assertEquals(1, tracker.pendingCount)

        nowMs = statusOf(rec).deferredUntil
        assertEquals(1, EveSmsQueue.sweepDeferred(nowMs))
        EveSmsQueue.drainOne()

        assertEquals(1, sends.size)
        assertEquals(EveSmsQueue.Status.SENT, statusOf(rec).status)
        assertEquals(1, tracker.drain())
        assertEquals(listOf(Ack("A", "sent", null)), acks)
        assertEquals(1, pulls)
    }

    // ── redelivery is treated as a duplicate ─────────────────────────────────

    @Test
    fun redeliveredTerminalTaskCreatesNoSecondSendAndNoSecondAck() {
        var validationCalls = 0
        bootAndStop(EveSmsQueue.FinalValidator {
            validationCalls++
            EveSmsQueue.ValidationDecision.Valid
        })
        val rec = initialPull("A")
        EveSmsQueue.drainOne()
        assertEquals(1, sends.size)
        assertEquals(1, tracker.drain())
        assertEquals(1, acks.size)

        // GMweb never saw the ACK and redelivers the very same requestId.
        pulls++
        val again = EveSmsQueue.enqueue(
            to = "+989120000001",
            text = "volume ended",
            priority = "critical",
            idempotencyKey = null,
            meta = EveSmsQueue.GatewayMeta(gatewayRequestId = "A", requiresValidation = true)
        )
        assertFalse("a redelivery must not create a second local record", again.created)
        assertEquals(rec.requestId, again.record.requestId)
        tracker.track("A", again.record.requestId) // no-op: already acknowledged

        assertEquals(0, tracker.drain())
        assertEquals(1, sends.size)
        assertEquals(1, acks.size)
        assertEquals(1, validationCalls)
    }

    // ── canonical outcome coverage on the local ack path ─────────────────────

    @Test
    fun providerFailureIsAckedOnceAsCanonicalFailed() {
        bootAndStop(
            EveSmsQueue.FinalValidator { EveSmsQueue.ValidationDecision.Valid },
            sender = { _, _ -> false }
        )
        val rec = initialPull("A")
        EveSmsQueue.drainOne()

        assertEquals(EveSmsQueue.Status.FAILED, statusOf(rec).status)
        assertEquals(1, tracker.drain())
        assertEquals(Ack("A", "failed", "provider_error"), acks.single())
        assertEquals(0, tracker.drain())
    }

    @Test
    fun localCancellationIsAckedOnceAsFailedWithACancelledReason() {
        bootAndStop(EveSmsQueue.FinalValidator { EveSmsQueue.ValidationDecision.Valid })
        val rec = initialPull("A")

        assertTrue(EveSmsQueue.cancel(rec.requestId)!!.ok)
        assertEquals(0, sends.size)

        assertEquals(1, tracker.drain())
        assertEquals(Ack("A", "failed", EveSmsQueue.REASON_CANCELLED_LOCALLY), acks.single())
        assertEquals(0, tracker.drain())
        assertEquals(1, acks.size)
    }

    @Test
    fun trackerNeverAcksANonTerminalTaskEarly() {
        bootAndStop(EveSmsQueue.FinalValidator {
            EveSmsQueue.ValidationDecision.Unavailable("timeout")
        })
        val rec = initialPull("A")
        EveSmsQueue.drainOne()

        assertEquals(EveSmsQueue.Status.DEFERRED, statusOf(rec).status)
        assertTrue(tracker.isTracked("A"))
        assertEquals(0, tracker.drain())
        assertTrue(acks.isEmpty())
        assertEquals(1, tracker.pendingCount)
    }

    @Test
    fun outstandingGatewayRecordsExcludesTerminalTasks() {
        bootAndStop(EveSmsQueue.FinalValidator { EveSmsQueue.ValidationDecision.Valid })
        val rec = initialPull("A")
        assertEquals(1, EveSmsQueue.outstandingGatewayRecords().size)

        EveSmsQueue.drainOne()
        assertEquals(EveSmsQueue.Status.SENT, statusOf(rec).status)
        assertEquals(0, EveSmsQueue.outstandingGatewayRecords().size)
    }
}
