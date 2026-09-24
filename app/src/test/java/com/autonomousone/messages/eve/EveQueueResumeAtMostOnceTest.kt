package com.autonomousone.messages.eve

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * At-most-once for a remotely requested SMS (mission §47/§78).
 *
 * The invariant is absolute: *"No remotely requested SEND_SMS command may cause the same SMS to be
 * sent twice because of retries."* A duplicate SMS is an irreversible real-world action, so where
 * the evidence is ambiguous the queue must decline to send.
 *
 * The defect this pins: `drainOne` persists `submittedOnce = true` BEFORE invoking the native
 * submit, and `bootstrap` re-queued every interrupted `ACTIVE` record regardless — so a process
 * death in that window sent the message a second time
 * (`docs/gateway-replication-audit.md`, Blocker 14).
 *
 * The assertions read the record state `bootstrap` computes synchronously, before the worker thread
 * is started, so they are deterministic rather than racing the queue.
 */
class EveQueueResumeAtMostOnceTest {

    /** In-memory store: `bootstrap` takes a `Store`, so no Android is needed. */
    private class FakeStore(var records: List<EveSmsQueue.Record>) : EveSmsQueue.Store {
        var saves = 0
        override fun load(): Pair<List<EveSmsQueue.Record>, Map<String, String>> =
            records to emptyMap()

        override fun save(
            records: List<EveSmsQueue.Record>,
            idempotency: Map<String, String>
        ) {
            this.records = records
            saves++
        }
    }

    private fun record(
        requestId: String,
        status: EveSmsQueue.Status,
        submittedOnce: Boolean = false,
        nativeSubmitStartedAt: Long = 0L,
        createdAt: Long = 1_000L
    ) = EveSmsQueue.Record(
        requestId = requestId,
        jobId = "job-$requestId",
        to = "+989120000000",
        text = "hello",
        priority = "normal",
        priorityLevel = 5,
        status = status,
        createdAt = createdAt,
        submittedOnce = submittedOnce,
        nativeSubmitStartedAt = nativeSubmitStartedAt
    )

    @After
    fun tearDown() {
        EveSmsQueue.resetForTest(EveSmsQueue.MemoryStore())
    }

    private fun bootstrapWith(records: List<EveSmsQueue.Record>): FakeStore {
        val store = FakeStore(records)
        EveSmsQueue.resetForTest(store)
        EveSmsQueue.bootstrap(store, sender = { true })
        // Drive the queue manually, exactly as the existing queue tests do.
        EveSmsQueue.stop()
        return store
    }

    // ── The invariant ────────────────────────────────────────────────────────

    @Test
    fun `anInterruptedSubmitIsNeverReQueued`() {
        // The crash window: ACTIVE with submittedOnce already durable.
        val store = bootstrapWith(
            listOf(
                record(
                    requestId = "req-1",
                    status = EveSmsQueue.Status.ACTIVE,
                    submittedOnce = true,
                    nativeSubmitStartedAt = 5_000L
                )
            )
        )

        val recovered = requireNotNull(EveSmsQueue.status("req-1"))
        assertFalse(
            "the message must NOT be queued again — that is a second SMS",
            recovered.status == EveSmsQueue.Status.QUEUED
        )
        assertEquals(EveSmsQueue.Status.FAILED, recovered.status)
        assertEquals(EveSmsQueue.REASON_INTERRUPTED_AFTER_SUBMIT, recovered.failedReason)
        assertEquals("manual_review_required", recovered.verificationStatus)
        assertTrue("and it must be terminal, so nothing can pick it up", recovered.terminal)
    }

    @Test
    fun `anInterruptedSubmitIsNotOfferedToTheWorker`() {
        // Terminal records are never offered, so nothing downstream can send it either.
        bootstrapWith(
            listOf(
                record(
                    requestId = "req-1",
                    status = EveSmsQueue.Status.ACTIVE,
                    submittedOnce = true
                )
            )
        )

        assertFalse("the worker must find nothing to send", EveSmsQueue.drainOne())
    }

    @Test
    fun `anInterruptBeforeAnySubmitIsSafelyRetried`() {
        // Nothing left the device, so retrying costs nothing and is the correct answer.
        //
        // Asserted through the VERDICT (`failedReason`), not the status: bootstrap decides
        // synchronously, but the worker thread may move a sendable record on to ACTIVE/SENT before
        // the assertion runs, so asserting `QUEUED` exactly would be a race. The verdict is what
        // distinguishes "safe to retry" from "we may already have sent it", and it is set inside
        // the lock before the worker starts.
        bootstrapWith(
            listOf(
                record(
                    requestId = "req-2",
                    status = EveSmsQueue.Status.ACTIVE,
                    submittedOnce = false
                )
            )
        )

        val recovered = requireNotNull(EveSmsQueue.status("req-2"))
        assertNotEquals(
            "a pre-submit interrupt must NOT be marked as a possible duplicate send",
            EveSmsQueue.REASON_INTERRUPTED_AFTER_SUBMIT,
            recovered.failedReason
        )
    }

    @Test
    fun `aQueuedRecordIsNotTreatedAsAnInterruptedSubmit`() {
        // Same race-free reasoning: a plain QUEUED record may be picked up by the worker, so the
        // stable property is that restart did not reclassify it as a possible duplicate send.
        bootstrapWith(
            listOf(record(requestId = "req-3", status = EveSmsQueue.Status.QUEUED))
        )

        val recovered = requireNotNull(EveSmsQueue.status("req-3"))
        assertNotEquals(
            EveSmsQueue.REASON_INTERRUPTED_AFTER_SUBMIT,
            recovered.failedReason
        )
    }

    @Test
    fun `terminalRecordsAreNeverResurrected`() {
        bootstrapWith(
            listOf(
                record("sent", EveSmsQueue.Status.SENT, submittedOnce = true),
                record("failed", EveSmsQueue.Status.FAILED, submittedOnce = true),
                record("cancelled", EveSmsQueue.Status.CANCELLED, submittedOnce = true),
                record("superseded", EveSmsQueue.Status.SUPERSEDED, submittedOnce = true)
            )
        )

        assertEquals(
            listOf(
                EveSmsQueue.Status.SENT,
                EveSmsQueue.Status.FAILED,
                EveSmsQueue.Status.CANCELLED,
                EveSmsQueue.Status.SUPERSEDED
            ),
            listOf("sent", "failed", "cancelled", "superseded")
                .map { requireNotNull(EveSmsQueue.status(it)).status }
        )
    }

    @Test
    fun `aDeferredRecordKeepsWaitingRatherThanBeingSentEarly`() {
        // A record inside its validation backoff must not be offered just because of a restart.
        val store = bootstrapWith(
            listOf(
                record("req-4", EveSmsQueue.Status.DEFERRED).copy(deferredUntil = Long.MAX_VALUE)
            )
        )

        assertEquals(EveSmsQueue.Status.DEFERRED, requireNotNull(EveSmsQueue.status("req-4")).status)
    }

    @Test
    fun `aTerminalRecordIsRecognisedAsTerminal`() {
        // The bootstrap guard is written in terms of `terminal`, so pin what that means.
        assertTrue(record("a", EveSmsQueue.Status.SENT).terminal)
        assertTrue(record("b", EveSmsQueue.Status.FAILED).terminal)
        assertTrue(record("c", EveSmsQueue.Status.CANCELLED).terminal)
        assertTrue(record("d", EveSmsQueue.Status.SUPERSEDED).terminal)
        assertFalse(record("e", EveSmsQueue.Status.QUEUED).terminal)
        assertFalse(record("f", EveSmsQueue.Status.ACTIVE).terminal)
        assertFalse(record("g", EveSmsQueue.Status.DEFERRED).terminal)
    }

    @Test
    fun `theInterruptedReasonIsDistinctFromAnOrdinarySendFailure`() {
        // "We could not send it" and "we may have sent it and cannot tell" are different facts, and
        // only the second one requires a human. A shared reason would hide that.
        assertTrue(
            EveSmsQueue.REASON_INTERRUPTED_AFTER_SUBMIT != EveSmsQueue.REASON_DEVICE_SEND_FAILED
        )
        assertEquals("interrupted_after_submit", EveSmsQueue.REASON_INTERRUPTED_AFTER_SUBMIT)
    }
}
