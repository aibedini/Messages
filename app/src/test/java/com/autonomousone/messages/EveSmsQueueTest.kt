package com.autonomousone.messages

import com.autonomousone.messages.eve.EveSmsQueue
import com.autonomousone.messages.eve.eveIsoTimestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EveSmsQueueTest {

    private lateinit var store: EveSmsQueue.MemoryStore

    @Before
    fun setup() {
        store = EveSmsQueue.MemoryStore()
        EveSmsQueue.resetForTest(store)
    }

    private fun bootstrap(sender: (EveSmsQueue.Record) -> Boolean = { true }) {
        EveSmsQueue.bootstrap(store, sender)
        // Stop the worker so tests drive drainOne() deterministically.
        EveSmsQueue.stop()
    }

    @Test
    fun `priority levels match the eve spec`() {
        assertEquals(1, EveSmsQueue.PRIORITY_LEVELS["critical"])
        assertEquals(3, EveSmsQueue.PRIORITY_LEVELS["expired"])
        assertEquals(6, EveSmsQueue.PRIORITY_LEVELS["expiring"])
        assertEquals(10, EveSmsQueue.PRIORITY_LEVELS["announcement"])
    }

    @Test
    fun `idempotency key returns the same request without a new sms`() {
        val sent = mutableListOf<String>()
        bootstrap { record -> synchronized(sent) { sent.add(record.to) }; true }

        val first = EveSmsQueue.enqueue("09123456789", "hi", "critical", "eve-key-1")
        val second = EveSmsQueue.enqueue("09123456789", "hi", "critical", "eve-key-1")

        assertTrue(first.created)
        assertFalse(second.created)
        assertEquals(first.record.requestId, second.record.requestId)

        EveSmsQueue.drainOne()
        EveSmsQueue.drainOne() // nothing left → no-op
        assertEquals(1, sent.size)
    }

    @Test
    fun `higher priority is sent first`() {
        val order = mutableListOf<String>()
        bootstrap { record -> synchronized(order) { order.add(record.text) }; true }

        EveSmsQueue.enqueue("09120000001", "announcement", "announcement", null)
        EveSmsQueue.enqueue("09120000002", "expiring", "expiring", null)
        EveSmsQueue.enqueue("09120000003", "critical", "critical", null)

        EveSmsQueue.drainOne(); EveSmsQueue.drainOne(); EveSmsQueue.drainOne()
        assertEquals(listOf("critical", "expiring", "announcement"), order)
    }

    @Test
    fun `status flows queued to active to sent`() {
        bootstrap()
        val rec = EveSmsQueue.enqueue("09123456789", "hello", "critical", null).record
        assertEquals(EveSmsQueue.Status.QUEUED, rec.status)

        EveSmsQueue.drainOne()
        val after = EveSmsQueue.status(rec.requestId)!!
        assertEquals(EveSmsQueue.Status.SENT, after.status)
        assertTrue(after.terminal)
        assertTrue(after.successful)
        assertTrue(after.sentAt > 0)
        assertNotNull(eveIsoTimestamp(after.sentAt))
    }

    @Test
    fun `failing sender marks failed with reason`() {
        bootstrap { false }
        val rec = EveSmsQueue.enqueue("09123456789", "hello", "critical", null).record
        EveSmsQueue.drainOne()

        val after = EveSmsQueue.status(rec.requestId)!!
        assertEquals(EveSmsQueue.Status.FAILED, after.status)
        assertTrue(after.terminal)
        assertFalse(after.successful)
        assertEquals("provider_error", after.failedReason)
    }

    @Test
    fun `terminal records carry gmweb-compatible verification fields`() {
        bootstrap()
        val okRec = EveSmsQueue.enqueue("09120000001", "ok", "critical", null).record
        EveSmsQueue.drainOne()
        val sent = EveSmsQueue.status(okRec.requestId)!!
        assertEquals("confirmed", sent.verificationStatus)

        bootstrap { false }
        val badRec = EveSmsQueue.enqueue("09120000002", "bad", "critical", null).record
        EveSmsQueue.drainOne()
        val failed = EveSmsQueue.status(badRec.requestId)!!
        assertEquals("manual_review_required", failed.verificationStatus)

        // Round-trip through persistence keeps the new fields (SharedPrefs contract).
        val restored = EveSmsQueue.Record(
            requestId = failed.requestId, jobId = failed.jobId, to = failed.to,
            text = failed.text, priority = failed.priority,
            priorityLevel = failed.priorityLevel, status = failed.status,
            createdAt = failed.createdAt, sentAt = failed.sentAt,
            failedReason = failed.failedReason, submittedOnce = failed.submittedOnce,
            verificationStatus = failed.verificationStatus,
            verificationAttempts = failed.verificationAttempts
        )
        assertEquals(failed.verificationStatus, restored.verificationStatus)
    }

    @Test
    fun `queued message can be cancelled and then never sends`() {
        val sent = mutableListOf<String>()
        bootstrap { record -> synchronized(sent) { sent.add(record.text) }; true }

        val rec = EveSmsQueue.enqueue("09123456789", "cancel me", "announcement", null).record
        val result = EveSmsQueue.cancel(rec.requestId)!!
        assertTrue(result.ok)

        EveSmsQueue.drainOne(); EveSmsQueue.drainOne()
        assertEquals(0, sent.size)
        assertEquals(EveSmsQueue.Status.CANCELLED, EveSmsQueue.status(rec.requestId)!!.status)
        assertTrue(EveSmsQueue.status(rec.requestId)!!.terminal)
        assertFalse(EveSmsQueue.status(rec.requestId)!!.successful)
    }

    @Test
    fun `sent message is not cancellable`() {
        bootstrap()
        val rec = EveSmsQueue.enqueue("09123456789", "done deal", "critical", null).record
        EveSmsQueue.drainOne()

        val result = EveSmsQueue.cancel(rec.requestId)!!
        assertFalse(result.ok)
        assertEquals("not_cancellable", result.reason)
    }

    @Test
    fun `unknown request id returns null status and cancel`() {
        bootstrap()
        assertNull(EveSmsQueue.status("sms_nope"))
        assertNull(EveSmsQueue.cancel("sms_nope"))
    }

    // ── §49 on the LEGACY pull path ──────────────────────────────────────────
    //
    // The pull bridge is the default-active transport, so this is where the web's correlation key
    // actually has to survive. It used to stop at `Record.correlationId`: the queue's sender seam
    // took `(to, text)` and there was no parameter for it, so a web-requested send reached GMweb as
    // an uncorrelated message whose later DELIVERED/FAILED could not be matched to its bubble.

    @Test
    fun `theWebsCorrelationKeyIsHandedToTheSender`() {
        val seen = mutableListOf<String?>()
        bootstrap { record -> synchronized(seen) { seen.add(record.correlationId) }; true }

        EveSmsQueue.enqueue(
            "09123456789", "hello", "critical", null,
            meta = EveSmsQueue.GatewayMeta(gatewayRequestId = "gw-1", correlationId = "corr-7")
        )
        EveSmsQueue.drainOne()

        assertEquals(listOf("corr-7"), seen)
    }

    @Test
    fun `aTaskWithNoCorrelationSendsNullRatherThanAnEmptyKey`() {
        // An empty string is a key that matches nothing but looks present; absence must travel as
        // absence, or GMweb would be told to look up a bubble called "".
        val seen = mutableListOf<String?>()
        bootstrap { record -> synchronized(seen) { seen.add(record.correlationId) }; true }

        EveSmsQueue.enqueue("09123456789", "hello", "critical", null)
        EveSmsQueue.drainOne()

        assertEquals(listOf<String?>(null), seen)
    }

    @Test
    fun `theCorrelationKeySurvivesAPersistenceRoundTripBeforeTheSend`() {
        // The key is only useful if it is still there when the send eventually happens — which may be
        // after a reboot, since the queue is durable. `MemoryStore.load()` returns nothing, so this
        // needs a store that actually round-trips.
        val store = RecordingStore()
        EveSmsQueue.resetForTest(store)
        EveSmsQueue.enqueue(
            "09123456789", "hello", "critical", null,
            meta = EveSmsQueue.GatewayMeta(gatewayRequestId = "gw-9", correlationId = "corr-persist")
        )
        EveSmsQueue.awaitPersistence()
        EveSmsQueue.stop()

        // A fresh process: reload from disk, then send.
        val seen = mutableListOf<String?>()
        EveSmsQueue.bootstrap(store, sender = { record ->
            synchronized(seen) { seen.add(record.correlationId) }
            true
        })
        EveSmsQueue.stop()
        EveSmsQueue.drainOne()

        assertEquals(listOf("corr-persist"), seen)
    }

    private class RecordingStore : EveSmsQueue.Store {
        private var records: List<EveSmsQueue.Record> = emptyList()
        private var idem: Map<String, String> = emptyMap()
        override fun load(): Pair<List<EveSmsQueue.Record>, Map<String, String>> = records to idem
        override fun save(records: List<EveSmsQueue.Record>, idempotency: Map<String, String>) {
            this.records = records.toList()
            this.idem = idempotency.toMap()
        }
    }

    @Test
    fun `capacity reflects pending counts per priority`() {
        bootstrap()
        EveSmsQueue.enqueue("09120000001", "a", "announcement", null)
        EveSmsQueue.enqueue("09120000002", "b", "announcement", null)
        EveSmsQueue.enqueue("09120000003", "c", "critical", null)

        val pending = EveSmsQueue.pendingByPriority()
        assertEquals(2, pending["announcement"])
        assertEquals(1, pending["critical"])
        assertEquals(3, EveSmsQueue.totalPending())
    }
}
