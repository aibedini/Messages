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
 * Merging + CLAIM/ACK/NACK + quarantine semantics.
 *
 * NOTE: this accumulator is IN-PROCESS ONLY. Nothing here proves anything about
 * Android process death — after process death the accumulator is gone and
 * recovery depends on durable Room sync state, which is a separate (unfinished)
 * concern.
 *
 * WRITTEN BUT NOT EXECUTED in this revision.
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

    // ── a retry backoff must never block unrelated realtime work ────────────

    @Test
    fun `a thread in retry backoff does not block an immediate tail`() {
        val p = PendingReconciles()
        p.add(ReconcileRequest.ForThread(1))
        val first = p.claim(1000)!!
        assertEquals(listOf(1L), first.threadIds)
        p.ackThread(1, false, 1000)

        assertEquals(PendingReconciles.UnitState.BACKOFF, p.threadState(1, 1000))

        // Realtime work arrives 1 ms later, while thread 1 is in a 1 s backoff.
        p.add(ReconcileRequest.TailDelta)
        val next = p.claim(1001)
        assertNotNull("the tail must be claimable immediately", next)
        assertNotNull(next!!.tailEpoch)
        assertEquals(
            "the poison thread is still in backoff and was not retried early",
            PendingReconciles.UnitState.BACKOFF,
            p.threadState(1, 1001)
        )
    }

    @Test
    fun `a thread in retry backoff does not block a healthy thread`() {
        val p = PendingReconciles()
        p.add(ReconcileRequest.ForThread(1))
        val first = p.claim(1000)!!
        p.ackThread(1, false, 1000)

        p.add(ReconcileRequest.ForThread(2))
        val next = p.claim(1001)
        assertNotNull("the healthy thread must not wait for the poison backoff", next)
        assertEquals(listOf(2L), next!!.threadIds)
    }

    @Test
    fun `a thread in retry backoff does not block a full sync`() {
        val p = PendingReconciles()
        p.add(ReconcileRequest.ForThread(1))
        p.ackThread(1, false, 1000)

        p.add(ReconcileRequest.FullSync)
        val next = p.claim(1001)
        assertNotNull(next)
        assertTrue(next!!.fullSync)
    }

    // ── global work is never permanently abandoned ──────────────────────────

    @Test
    fun `full sync transient failures never permanently abandon it`() {
        val p = PendingReconciles()
        p.add(ReconcileRequest.FullSync)

        var now = 1_000_000L
        repeat(12) {
            val claim = p.claim(now)!!
            p.ackFullSync(claim, false, now)
            assertEquals(
                "still pending/degraded, never gone",
                PendingReconciles.UnitState.BACKOFF,
                p.fullSyncState(now)
            )
            now += 120_000L // past the capped backoff
        }

        val later = p.claim(now)
        assertNotNull("a full sync keeps retrying at a capped interval", later)
        assertTrue(later!!.fullSync)
    }

    @Test
    fun `tail transient failures never permanently abandon it`() {
        val p = PendingReconciles()
        p.add(ReconcileRequest.TailDelta)

        var now = 1_000_000L
        repeat(12) {
            val claim = p.claim(now)!!
            p.ackTail(claim, false, now)
            assertEquals(PendingReconciles.UnitState.BACKOFF, p.tailState(now))
            now += 120_000L
        }
        assertNotNull("the tail keeps retrying", p.claim(now))
    }

    @Test
    fun `a failed full sync does not consume the tail`() {
        val p = PendingReconciles()
        p.add(ReconcileRequest.FullSync)
        p.add(ReconcileRequest.TailDelta)

        val claim = p.claim(1000)!!
        assertTrue(claim.fullSync)
        assertNotNull(claim.tailEpoch)
        p.ackFullSync(claim, false, 1000)

        assertTrue("the tail was NOT covered by a failed full sync", p.hasWork())
        val retry = p.claim(1_000_000)!!
        assertNotNull("the tail is still claimable", retry.tailEpoch)
    }

    @Test
    fun `a tail arriving during a full sync is not covered by it`() {
        val p = PendingReconciles()
        p.add(ReconcileRequest.FullSync)
        val claim = p.claim(1000)!!
        assertNull(claim.tailEpoch)

        p.add(ReconcileRequest.TailDelta) // arrives while the full sync executes
        p.ackFullSync(claim, true, 1000)

        assertTrue("the newer tail was not covered", p.hasWork())
        assertNotNull(p.claim(2000)!!.tailEpoch)
    }

    // ── thread isolation and quarantine ────────────────────────────────────

    @Test
    fun `one failing thread does not lose its siblings`() {
        val p = PendingReconciles(threadChunkSize = 8, maxThreadAttempts = 3)
        (1L..8L).forEach { p.add(ReconcileRequest.ForThread(it)) }

        val h = Harness(p)
        h.runAll { it == "thread:2" }

        assertTrue(h.successes().containsAll(setOf(1L, 3L, 4L, 5L, 6L, 7L, 8L)))
        assertTrue(h.executed.count { it == "thread:2!" } >= 1)
    }

    @Test
    fun `a poison thread is quarantined and starves nobody`() {
        val p = PendingReconciles(threadChunkSize = 8, maxThreadAttempts = 3)
        p.add(ReconcileRequest.ForThread(1))
        p.add(ReconcileRequest.ForThread(2))

        val h = Harness(p)
        h.runAll { it == "thread:2" }

        assertTrue("the healthy thread ran", h.successes().contains(1L))
        assertTrue(p.quarantinedThreadIds().contains(2L))
        assertEquals(
            "quarantined is an explicit state, not a silent deletion",
            PendingReconciles.UnitState.QUARANTINED,
            p.threadState(2, h.now)
        )
        assertFalse("no infinite retry", p.hasWork())
    }

    @Test
    fun `a new provider event re-arms a quarantined thread`() {
        val p = PendingReconciles(threadChunkSize = 8, maxThreadAttempts = 2)
        p.add(ReconcileRequest.ForThread(5))
        var now = 1_000_000L
        repeat(2) {
            val c = p.claim(now)!!
            p.ackThread(5, false, now)
            now += 120_000L
        }
        assertEquals(PendingReconciles.UnitState.QUARANTINED, p.threadState(5, now))

        p.add(ReconcileRequest.ForThread(5)) // fresh provider event

        assertEquals(PendingReconciles.UnitState.PENDING, p.threadState(5, now))
        assertNotNull("and it is claimable again", p.claim(now))
    }

    // ── semantic union ─────────────────────────────────────────────────────

    @Test
    fun `fullSync is not lost when a tail arrives first`() {
        val p = PendingReconciles()
        p.add(ReconcileRequest.FullSync)
        p.add(ReconcileRequest.TailDelta)

        val claim = p.claim(1000)!!
        assertTrue(claim.fullSync)
        assertNotNull(claim.tailEpoch)
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
        assertEquals(listOf(7L), p.claim(1000)!!.threadIds)
    }

    @Test
    fun `new work arriving while in flight stays pending`() {
        val p = PendingReconciles()
        p.add(ReconcileRequest.ForThread(1))
        val claim = p.claim(1000)!!
        p.add(ReconcileRequest.ForThread(2))
        p.add(ReconcileRequest.TailDelta)

        p.ackThread(1, true, 1000)
        val next = p.claim(2000)!!
        assertEquals(listOf(2L), next.threadIds)
        assertNotNull(next.tailEpoch)
    }

    @Test
    fun `a claim is not re-claimed while in flight`() {
        val p = PendingReconciles()
        p.add(ReconcileRequest.ForThread(9))
        assertEquals(listOf(9L), p.claim(1000)!!.threadIds)
        assertNull("in-flight work is not handed out twice", p.claim(1000))

        p.ackThread(9, false, 1000)
        assertNull("still inside the retry backoff", p.claim(1000))
        assertNotNull("due once the backoff elapses", p.claim(1000 + 120_000))
    }

    @Test
    fun `next wake up is zero only when nothing is scheduled`() {
        val p = PendingReconciles()
        assertEquals(0L, p.nextWakeUpInMs(1000))

        p.add(ReconcileRequest.ForThread(3))
        p.claim(1000)
        p.ackThread(3, false, 1000)
        assertTrue("a requeued unit reports a real future wake-up", p.nextWakeUpInMs(1000) > 0L)
    }
}
