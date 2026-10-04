package com.autonomousone.messages

import com.autonomousone.messages.eve.EveQueueCodec
import com.autonomousone.messages.eve.EveSmsQueue
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The two facts a queued web-requested SMS must never lose across a restart:
 * the exact body and the line GMweb chose for it.
 *
 * `subscriptionId` is an additive field on the persisted record, so records
 * written by older builds (which have no such key) must keep loading — the
 * existing queue database is not invalidated and no queued message is dropped.
 */
class EveQueueSubscriptionPersistenceTest {

    /** In-memory stand-in for the SharedPreferences store, surviving a "restart". */
    private class CapturingStore : EveSmsQueue.Store {
        var records: List<EveSmsQueue.Record> = emptyList()
        var idempotency: Map<String, String> = emptyMap()

        override fun load(): Pair<List<EveSmsQueue.Record>, Map<String, String>> =
            records to idempotency

        override fun save(records: List<EveSmsQueue.Record>, idempotency: Map<String, String>) {
            this.records = records.toList()
            this.idempotency = idempotency.toMap()
        }
    }

    private lateinit var store: CapturingStore

    @Before
    fun setup() {
        store = CapturingStore()
        EveSmsQueue.resetForTest(store)
    }

    // ── codec round trip ─────────────────────────────────────────────────────

    @Test
    fun `multiline text and the selected sim survive an encode decode round trip`() {
        val original = record(text = "line1\nline2", subscriptionId = 7)

        val restored = EveQueueCodec.decode(EveQueueCodec.encode(original))

        assertEquals("line1\nline2", restored.text)
        assertEquals(7, restored.subscriptionId)
    }

    @Test
    fun `persian multiline with an empty middle line survives with the sim`() {
        val body = "اول\nدوم\n\nچهارم"
        val restored = EveQueueCodec.decode(EveQueueCodec.encode(record(text = body, subscriptionId = 2)))

        assertEquals(body, restored.text)
        assertEquals(2, restored.subscriptionId)
    }

    @Test
    fun `emoji survive persistence with no replacement character`() {
        val body = "سلام 👋\nخوبی؟ ❤️"
        val restored = EveQueueCodec.decode(EveQueueCodec.encode(record(text = body, subscriptionId = 1)))

        assertEquals(body, restored.text)
        assertEquals(body.toByteArray(Charsets.UTF_8).toList(), restored.text.toByteArray(Charsets.UTF_8).toList())
    }

    @Test
    fun `no explicit sim encodes and decodes as null`() {
        val restored = EveQueueCodec.decode(EveQueueCodec.encode(record(text = "x", subscriptionId = null)))

        assertNull(restored.subscriptionId)
    }

    @Test
    fun `a persisted negative id decodes to no selection rather than reaching the radio`() {
        val json = EveQueueCodec.encode(record(text = "x", subscriptionId = null))
            .put("subscriptionId", -1)

        assertNull(EveQueueCodec.decode(json).subscriptionId)
    }

    // ── old records ──────────────────────────────────────────────────────────

    @Test
    fun `a record written before the field existed still loads with no sim`() {
        val legacy = JSONObject(
            """
            {
              "requestId": "sms_pre_sim",
              "jobId": "job_pre_sim",
              "to": "+989120000001",
              "text": "legacy\nmultiline",
              "priority": "announcement",
              "priorityLevel": 10,
              "status": "QUEUED",
              "createdAt": 1700000000000
            }
            """.trimIndent()
        )

        val restored = EveQueueCodec.decode(legacy)

        assertEquals("legacy\nmultiline", restored.text)
        assertNull("absent means no explicit choice, not an error", restored.subscriptionId)
    }

    // ── the whole queue, not just the codec ──────────────────────────────────

    /** What the sender seam saw. Written from the queue's worker thread, read by the test. */
    private class Capture {
        @Volatile var text: String? = null
        @Volatile var subscriptionId: Int? = null
        private val latch = java.util.concurrent.CountDownLatch(1)

        fun record(rec: EveSmsQueue.Record) {
            text = rec.text
            subscriptionId = rec.subscriptionId
            latch.countDown()
        }

        fun awaitSender() = latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
    }

    /**
     * Bootstraps the queue over [store] and returns what the sender seam received.
     *
     * `bootstrap` starts the worker, so the drain may be performed by the worker or by the
     * explicit `drainOne()` below — either way exactly one send happens (the job is polled
     * once), and the latch makes the assertion deterministic instead of racy.
     */
    private fun drainAndCapture(store: EveSmsQueue.Store): Capture {
        val capture = Capture()
        EveSmsQueue.resetForTest(store)
        EveSmsQueue.bootstrap(store, { rec -> capture.record(rec); true })
        EveSmsQueue.stop()
        EveSmsQueue.drainOne()
        return capture
    }

    @Test
    fun `the sim and the exact body are handed to the sender after a restart`() {
        val body = "سلام\nخوبی؟\n\nفردا می‌بینمت."
        val enqueued = EveSmsQueue.enqueue(
            to = "+989120000009",
            text = body,
            priority = "critical",
            idempotencyKey = null,
            subscriptionId = 7
        ).record
        assertEquals(7, enqueued.subscriptionId)
        EveSmsQueue.awaitPersistence()

        // Process death: new process, same store, sender seams in fresh.
        val capture = drainAndCapture(store)

        assertTrue("the sender seam must be reached", capture.awaitSender())
        val persisted = store.records.firstOrNull { it.requestId == enqueued.requestId }
        assertEquals("the body is durable", body, persisted?.text)
        assertEquals("the selected SIM is durable", 7, persisted?.subscriptionId)
        assertEquals("the body reached the sender byte-for-byte", body, capture.text)
        assertEquals("the selected SIM reached the sender", 7, capture.subscriptionId)
    }

    @Test
    fun `a record with no explicit sim reaches the sender with a null override`() {
        val body = "line1\nline2"
        val enqueued = EveSmsQueue.enqueue(
            to = "+989120000010",
            text = body,
            priority = "announcement",
            idempotencyKey = null
        ).record
        EveSmsQueue.awaitPersistence()

        val capture = drainAndCapture(store)

        assertTrue("the sender seam must be reached", capture.awaitSender())
        assertEquals(body, capture.text)
        assertNull("no explicit choice must stay null, never a guessed id", capture.subscriptionId)
        assertEquals(enqueued.requestId, store.records.first { it.requestId == enqueued.requestId }.requestId)
    }

    @Test
    fun `an old persisted record with no sim still drains and is not dropped`() {
        store.records = listOf(record(requestId = "sms_old", text = "old\nbody", subscriptionId = null))

        val capture = drainAndCapture(store)

        assertTrue("the sender seam must be reached", capture.awaitSender())
        assertNotNull("the old record must still be loadable", EveSmsQueue.status("sms_old"))
        assertEquals("old\nbody", capture.text)
        assertNull(capture.subscriptionId)
        // The sender callback completes BEFORE the queue writes the terminal status, and on CI the
        // worker (not this thread) is often the one draining — so reading the status the instant the
        // callback fires is a race. Wait for the terminal state instead of reading it mid-write; the
        // assertion itself is unchanged.
        assertEquals(EveSmsQueue.Status.SENT, awaitTerminalStatus("sms_old"))
    }

    /** Bounded wait for a terminal queue status, so a worker-thread write cannot race the assert. */
    private fun awaitTerminalStatus(
        requestId: String,
        timeoutMs: Long = 5_000L
    ): EveSmsQueue.Status? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val status = EveSmsQueue.status(requestId)?.status
            if (status != null && status != EveSmsQueue.Status.QUEUED && status != EveSmsQueue.Status.ACTIVE) {
                return status
            }
            Thread.sleep(10)
        }
        return EveSmsQueue.status(requestId)?.status
    }

    private fun record(
        requestId: String = "sms_sim",
        text: String,
        subscriptionId: Int?
    ) = EveSmsQueue.Record(
        requestId = requestId,
        jobId = "job_sim",
        to = "+989120000001",
        text = text,
        priority = "critical",
        priorityLevel = 1,
        status = EveSmsQueue.Status.QUEUED,
        createdAt = 1L,
        subscriptionId = subscriptionId
    )
}
