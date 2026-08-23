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

    private fun bootstrap(sender: (String, String) -> Boolean = { _, _ -> true }) {
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
        bootstrap { to, _ -> synchronized(sent) { sent.add(to) }; true }

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
        bootstrap { _, text -> synchronized(order) { order.add(text) }; true }

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
        bootstrap { _, _ -> false }
        val rec = EveSmsQueue.enqueue("09123456789", "hello", "critical", null).record
        EveSmsQueue.drainOne()

        val after = EveSmsQueue.status(rec.requestId)!!
        assertEquals(EveSmsQueue.Status.FAILED, after.status)
        assertTrue(after.terminal)
        assertFalse(after.successful)
        assertEquals("provider_error", after.failedReason)
    }

    @Test
    fun `queued message can be cancelled and then never sends`() {
        val sent = mutableListOf<String>()
        bootstrap { _, text -> synchronized(sent) { sent.add(text) }; true }

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
