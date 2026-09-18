package com.autonomousone.messages

import com.autonomousone.messages.data.PendingReconciles
import com.autonomousone.messages.data.ReconcileRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Merging + CLAIM/ACK/NACK semantics.
 *
 * Two properties are proven here:
 *  - requests are merged by SEMANTIC UNION, never last-value-wins;
 *  - receiving work is not completing it: claimed work that fails is REQUEUED,
 *    and one failing unit never discards its siblings.
 */
class PendingReconcilesTest {

    /** Mirrors the coordinator's consumer loop: claim, execute, ack/nack. */
    private class Harness(val p: PendingReconciles) {
        val executed = mutableListOf<String>()
        var now = 1_000_000L

        fun runAll(fail: (String) -> Boolean = { false }) {
            var guard = 0
            while (guard++ < 400) {
                val claim = p.claim(now) ?: break
                if (claim.fullSync) {
                    val ok = !fail("fullsync")
                    executed += if (ok) "fullsync" else "fullsync!"
                    p.ackFullSync(claim, ok, now)
                } else if (claim.tailEpoch != null) {
                    val ok = !fail("tail")
                    executed += if (ok) "tail" else "tail!"
                    p.ackTail(claim, ok, now)
                }
                claim.threadIds.forEach { id ->
                    val ok = !fail("thread:" + id)
                    executed += if (ok) "thread:" + id else "thread:" + id + "!"
                    p.ackThread(id, ok, now)
                }
                now += 5_000L
            }
        }

        fun successes(): Set<Long> = executed
            .filter { it.startsWith("thread:") && !it.endsWith("!") }
            .map { it.removePrefix("thread:").toLong() }
            .toSet()
    }

    // ── semantic union ──────────────────────────────────────────────────────

    @Test
    fun `fullSync is not lost when a tail arrives first`() {
        val p = PendingReconciles()
        p.add(ReconcileRequest.FullSync)
        p.add(ReconcileRequest.TailDelta)

        val claim = p.claim(1000)!!
        assertTrue("the startup FullSync must survive observer traffic", claim.fullSync)
        assertNotNull("the tail is claimed with it", claim.tailEpoch)
    }

    @Test
    fun `three thread repairs all run`() {
        val p = PendingReconciles()
        p.add(ReconcileRequest.ForThread(1))
        p.add(ReconcileRequest.ForThread(2))
        p.add(ReconcileRequest.ForThread(3))

        assertEquals(setOf(1L, 2L, 3L), Harness(p).also { it.runAll() }.successes())
    }

    @Test
    fun `fifty thread ids lose none`() {
        val p = PendingReconciles(threadChunkSize = 8)
        (1L..50L).forEach { p.add(ReconcileRequest.ForThread(it)) }

        val h = Harness(p)
        h.runAll()

        assertEquals((1L..50L).toSet(), h.successes())
        assertFalse(p.hasWork())
    }

    @Test
    fun `duplicate ids dedupe`() {
        val p = PendingReconciles()
        repeat(100) { p.add(ReconcileRequest.ForThread(7)) }

        val claim = p.claim(1000)!!
        assertEquals(listOf(7L), claim.threadIds)
    }

    @Test
    fun `non-positive thread ids are ignored`() {
        val p = PendingReconciles()
        p.add(ReconcileRequest.ForThread(0))
        p.add(ReconcileRequest.ForThread(-5))
        assertFalse(p.hasWork())
    }

    // ── P0 BLOCKER 1: work must not disappear on failure ────────────────────

    @Test
    fun `a failed full sync is requeued not lost`() {
        val p = PendingReconciles(maxAttempts = 3)
        p.add(ReconcileRequest.FullSync)

        val claim = p.claim(1000)!!
        assertTrue(claim.fullSync)
        p.ackFullSync(claim, false, 1000)

        assertTrue("work must still be pending after a failure", p.hasWork())
        assertNull("but not while inside the retry backoff", p.claim(1000))

        val retry = p.claim(1_000_000)!!
        assertTrue("the full sync is retried", retry.fullSync)
        p.ackFullSync(retry, true, 1_000_000)
        assertFalse(p.hasWork())
    }

    @Test
    fun `a failed full sync does not consume the tail`() {
        val p = PendingReconciles(maxAttempts = 5)
        p.add(ReconcileRequest.FullSync)
        p.add(ReconcileRequest.TailDelta)

        val claim = p.claim(1000)!!
        assertTrue(claim.fullSync)
        assertNotNull(claim.tailEpoch)

        p.ackFullSync(claim, false, 1000)

        // The tail was claimed together with the full sync, but the full sync did
        // NOT prove success, so the tail must still be pending.
        assertTrue("the tail was NOT covered by a failed full sync", p.hasWork())
        val retry = p.claim(1_000_000)!!
        assertNotNull("the tail is still claimable", retry.tailEpoch)
    }

    @Test
    fun `a tail arriving during a full sync is not covered by it`() {
        val p = PendingReconciles()
        p.add(ReconcileRequest.FullSync)

        val claim = p.claim(1000)!!
        assertTrue(claim.fullSync)
        assertNull(claim.tailEpoch)

        // New work arrives while the full sync is executing.
        p.add(ReconcileRequest.TailDelta)
        p.ackFullSync(claim, true, 1000)

        assertTrue("the newer tail was not covered by the earlier full sync", p.hasWork())
        val next = p.claim(2000)!!
        assertNotNull(next.tailEpoch)
    }

    @Test
    fun `one failing thread does not lose its siblings`() {
        val p = PendingReconciles(threadChunkSize = 8, maxAttempts = 3)
        (1L..8L).forEach { p.add(ReconcileRequest.ForThread(it)) }

        val h = Harness(p)
        h.runAll { it == "thread:2" }

        assertTrue(
            "3..8 must still execute even though 2 failed in the same claim",
            h.successes().containsAll(setOf(1L, 3L, 4L, 5L, 6L, 7L, 8L))
        )
        assertTrue("the failing thread was retried", h.executed.count { it == "thread:2!" } >= 1)
    }

    @Test
    fun `a permanently failing thread is abandoned and starves nobody`() {
        val p = PendingReconciles(threadChunkSize = 8, maxAttempts = 3)
        p.add(ReconcileRequest.ForThread(1))
        p.add(ReconcileRequest.ForThread(2))

        val h = Harness(p)
        h.runAll { it == "thread:2" }

        assertTrue("the healthy thread ran", h.successes().contains(1L))
        assertTrue("the poison thread is abandoned, not retried forever", p.abandonedThreadIds().contains(2L))
        assertFalse("no infinite retry: everything is settled", p.hasWork())
    }

    @Test
    fun `fifty threads with a first-attempt failure lose none`() {
        val p = PendingReconciles(threadChunkSize = 8, maxAttempts = 3)
        (1L..50L).forEach { p.add(ReconcileRequest.ForThread(it)) }

        val attempted = HashMap<Long, Int>()
        val h = Harness(p)
        h.runAll { key ->
            if (!key.startsWith("thread:")) return@runAll false
            val id = key.removePrefix("thread:").toLong()
            val a = (attempted[id] ?: 0) + 1
            attempted[id] = a
            a == 1 // fail the first attempt of every thread exactly once
        }

        assertEquals("zero silent loss", (1L..50L).toSet(), h.successes())
        assertFalse(p.hasWork())
    }

    @Test
    fun `new work arriving while in flight stays pending`() {
        val p = PendingReconciles()
        p.add(ReconcileRequest.ForThread(1))

        val claim = p.claim(1000)!!
        assertEquals(listOf(1L), claim.threadIds)
        assertEquals(1, p.inFlightThreadCount())

        p.add(ReconcileRequest.ForThread(2))
        p.add(ReconcileRequest.TailDelta)
        assertTrue("new work is not swallowed by the in-flight claim", p.hasWork())

        p.ackThread(1, true, 1000)
        val next = p.claim(2000)!!
        assertEquals(listOf(2L), next.threadIds)
        assertNotNull(next.tailEpoch)
    }

    @Test
    fun `a claim is not re-claimed while in flight`() {
        val p = PendingReconciles()
        p.add(ReconcileRequest.ForThread(9))

        val first = p.claim(1000)!!
        assertEquals(listOf(9L), first.threadIds)
        assertNull("in-flight work is not handed out twice", p.claim(1000))

        p.ackThread(9, false, 1000) // nack -> requeued with backoff
        assertTrue(p.hasWork())
        assertNull("still inside the retry backoff", p.claim(1000))
        assertNotNull("due once the backoff elapses", p.claim(1000 + 60_000))
    }

    @Test
    fun `next wake up is zero only when there is no pending work`() {
        val p = PendingReconciles()
        assertEquals(0L, p.nextWakeUpInMs(1000))

        p.add(ReconcileRequest.ForThread(3))
        assertTrue("due immediately", p.nextWakeUpInMs(1000) >= 0L)

        p.claim(1000)
        p.ackThread(3, false, 1000)
        assertTrue("a requeued unit reports a real future wake-up", p.nextWakeUpInMs(1000) > 0L)
    }
}
