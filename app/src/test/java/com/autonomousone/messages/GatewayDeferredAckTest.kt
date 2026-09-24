package com.autonomousone.messages

import com.autonomousone.messages.eve.EveSmsQueue
import com.autonomousone.messages.gateway.GatewayAckTracker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    /** What the fake ACK transport answers. A refused report must not be recorded as delivered. */
    private var ackAccepted = true

    @Before
    fun setUp() {
        store = RoundTripStore()
        sends.clear()
        acks.clear()
        pulls = 0
        ackAccepted = true
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
        // Explicit, because the lambda's last expression used to BE `acks.add(...)` — which returns
        // Boolean true. That accidental truthy return is exactly what would have hidden the defect this
        // suite now covers: a tracker that treats every report as accepted.
        sendAck = { rec, outcome, reason ->
            acks.add(Ack(rec.gatewayRequestId ?: "?", outcome, reason))
            ackAccepted
        }
    )

    private fun bootAndStop(
        validator: EveSmsQueue.FinalValidator,
        sender: (EveSmsQueue.Record) -> Boolean = { record -> sends.add(record.text); true }
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
            sender = { false }
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

    // ── A report GMweb did not accept is not a delivered report ──────────────
    //
    // The defect this covers: the tracker marked a task acknowledged BEFORE attempting the
    // transmission, so a refused or lost report looked delivered and was never sent again. The report
    // is the bridge's product — losing it left GMweb showing a delivered message as unresolved.

    @Test
    fun aRefusedReportStaysQueuedAndIsRetried() {
        bootAndStop(EveSmsQueue.FinalValidator { EveSmsQueue.ValidationDecision.Valid })
        val rec = initialPull("A")
        EveSmsQueue.drainOne()
        assertEquals(EveSmsQueue.Status.SENT, statusOf(rec).status)

        ackAccepted = false
        assertEquals("a refused report reports nothing as acked", 0, tracker.drain())
        assertEquals("but it was attempted", 1, acks.size)
        assertTrue("and it is still tracked", tracker.isTracked("A"))
        assertFalse("and not recorded as acknowledged", tracker.wasAcked("A"))

        // The next cycle tries again, and this time GMweb accepts it.
        ackAccepted = true
        assertEquals(1, tracker.drain())
        assertEquals(2, acks.size)
        assertEquals("both attempts report the same outcome", acks[0].outcome, acks[1].outcome)
        assertTrue(tracker.wasAcked("A"))

        // And once accepted, it is never sent a third time.
        assertEquals(0, tracker.drain())
        assertEquals(2, acks.size)
    }

    @Test
    fun anAcceptedReportIsNeverSentTwice() {
        bootAndStop(EveSmsQueue.FinalValidator { EveSmsQueue.ValidationDecision.Valid })
        val rec = initialPull("A")
        EveSmsQueue.drainOne()

        assertEquals("the first drain reports it", 1, tracker.drain())
        assertEquals("and then there is nothing left to report", 0, tracker.drain())
        assertEquals(0, tracker.drain())
        assertEquals("exactly one ACK on the wire", 1, acks.size)
    }

    @Test
    fun aFailedReportIsStillPendingAfterAProcessDeath() {
        // The in-memory ledger does not survive a restart, so the DURABLE marker is what keeps the
        // report alive. A terminal record with no accepted report must be re-seeded.
        bootAndStop(EveSmsQueue.FinalValidator { EveSmsQueue.ValidationDecision.Valid })
        val rec = initialPull("A")
        EveSmsQueue.drainOne()
        assertEquals(EveSmsQueue.Status.SENT, statusOf(rec).status)

        ackAccepted = false
        tracker.drain()
        EveSmsQueue.awaitPersistence()

        assertEquals("the record is terminal and reported by nobody", 1, EveSmsQueue.unreportedGatewayRecords().size)
        assertEquals("and it is no longer outstanding", 0, EveSmsQueue.outstandingGatewayRecords().size)

        // Reboot: a fresh tracker, seeded from the durable record.
        tracker = newTracker()
        EveSmsQueue.unreportedGatewayRecords().forEach { r ->
            r.gatewayRequestId?.let { tracker.track(it, r.requestId) }
        }
        ackAccepted = true
        assertEquals(1, tracker.drain())
        assertTrue(EveSmsQueue.markGatewayReported(rec.requestId, nowMs))
        assertEquals("once reported it leaves the unreported list", 0, EveSmsQueue.unreportedGatewayRecords().size)
    }

    @Test
    fun markingAReportedTaskAcknowledgesItOnTheNextStart() {
        bootAndStop(EveSmsQueue.FinalValidator { EveSmsQueue.ValidationDecision.Valid })
        val rec = initialPull("A")
        EveSmsQueue.drainOne()

        assertTrue(EveSmsQueue.markGatewayReported(rec.requestId, nowMs))
        // The queue keeps the record (it is the audit trail), but its report is done.
        assertNotNull(statusOf(rec))
        assertTrue(EveSmsQueue.unreportedGatewayRecords().isEmpty())
    }

    @Test
    fun markingAnUnknownRecordReportsFailureRatherThanInventingOne() {
        assertFalse(EveSmsQueue.markGatewayReported("does-not-exist", nowMs))
    }

    @Test
    fun anUnloadedQueueReportsNoMeasurementRatherThanZero() {
        // Before the durable state is read, the in-memory map is empty because nothing has been loaded
        // — not because everything is reported. A support report must not turn "we have not looked" into
        // a clean bill of health, which is the mistake the reconciliation and verification sections
        // already had to be corrected for.
        EveSmsQueue.resetForTest(EveSmsQueue.MemoryStore())
        assertNull("nothing has been read yet", EveSmsQueue.unreportedGatewayBacklog())

        bootAndStop(EveSmsQueue.FinalValidator { EveSmsQueue.ValidationDecision.Valid })
        assertEquals("and once read, an empty queue is a real zero", 0, EveSmsQueue.unreportedGatewayBacklog())
    }

    @Test
    fun theBacklogCountsOnlyTerminalUnreportedGatewayTasks() {
        bootAndStop(EveSmsQueue.FinalValidator { EveSmsQueue.ValidationDecision.Valid })
        val rec = initialPull("A")
        assertEquals("still outstanding, not a finished-but-unreported task", 0, EveSmsQueue.unreportedGatewayBacklog())

        EveSmsQueue.drainOne()
        assertEquals("finished, and nobody has reported it", 1, EveSmsQueue.unreportedGatewayBacklog())

        assertTrue(EveSmsQueue.markGatewayReported(rec.requestId, nowMs))
        assertEquals("reported, so it leaves the backlog", 0, EveSmsQueue.unreportedGatewayBacklog())
    }
}
