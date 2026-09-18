package com.autonomousone.messages

import com.autonomousone.messages.data.PendingReconciles
import com.autonomousone.messages.data.PendingReconciles.UnitState
import com.autonomousone.messages.data.ReconcileRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Merging + CLAIM/ACK/NACK + GENERATION semantics.
 *
 * NOTE: this accumulator is IN-PROCESS ONLY. Nothing here proves anything about
 * Android process death — after process death the accumulator is gone and
 * recovery depends on durable Room sync_state, which is unfinished.
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
                claim.threads.forEach { claimed ->
                    val key = "thread:" + claimed.threadId
                    val ok = !fail(key)
                    executed += if (ok) key else key + "!"
                    p.ackThread(claimed, ok, now)
                }
                now += 5_000L
            }
        }

        fun successes(): Set<Long> = executed
            .filter { it.startsWith("thread:") && !it.endsWith("!") }
            .map { it.removePrefix("thread:").toLong() }
            .toSet()
    }

    // ── P0-A1: an in-flight thread must not swallow a newer event ───────────

    @Test
    fun `an in-flight thread is not completed by the old ack when a new event arrives`() {
        val p = PendingReconciles()
        p.add(ReconcileRequest.ForThread(42))

        val claim = p.claim(1000)!!
        val claimed = claim.threads.single()
        assertEquals(42L, claimed.threadId)

        // A NEW provider event for the SAME thread arrives while the repair for
        // the old generation is still executing.
        p.add(ReconcileRequest.ForThread(42))

        // The old claim reports success.
        p.ackThread(claimed, true, 1000)

        assertEquals(
            "the newer event must NOT be acknowledged by the old claim",
            UnitState.PENDING,
            p.threadState(42, 1001)
        )
        assertNotNull("and it is immediately claimable again", p.claim(1001))
    }

    @Test
    fun `an in-flight thread failure keeps the newest generation pending`() {
        val p = PendingReconciles()
        p.add(ReconcileRequest.ForThread(42))
        val claimed = p.claim(1000)!!.threads.single()

        p.add(ReconcileRequest.ForThread(42)) // newer event
        p.ackThread(claimed, false, 1000)     // old claim fails

        assertEquals(UnitState.PENDING, p.threadState(42, 1001))
        assertNotNull(
            "the newer generation must be immediately claimable",
            p.claim(1001)
        )
    }

    @Test
    fun `repeated in-flight generations all get repaired`() {
        val p = PendingReconciles()
        p.add(ReconcileRequest.ForThread(7))
        val first = p.claim(1000)!!.threads.single()

        p.add(ReconcileRequest.ForThread(7))
        p.add(ReconcileRequest.ForThread(7))
        p.ackThread(first, true, 1000)

        assertEquals(UnitState.PENDING, p.threadState(7, 1001))
        assertNotNull(p.claim(1001))
    }

    // ── P0-A2: FullSync / TailDelta generations ─────────────────────────────

    @Test
    fun `a full sync requested while one is in flight remains pending`() {
        val p = PendingReconciles()
        p.add(ReconcileRequest.FullSync)

        val claim = p.claim(1000)!!
        assertTrue(claim.fullSync)

        // A NEW FullSync request arrives while the first one is executing.
        p.add(ReconcileRequest.FullSync)

        // The first one succeeds.
        p.ackFullSync(claim, true, 1000)

        assertTrue("the newer FullSync must remain pending", p.hasWork())
        assertEquals(UnitState.PENDING, p.fullSyncState(1001))
        val next = p.claim(1001)!!
        assertTrue(next.fullSync)
    }

    @Test
    fun `a tail requested while a tail is in flight remains pending`() {
        val p = PendingReconciles()
        p.add(ReconcileRequest.TailDelta)
        val claim = p.claim(1000)!!

        p.add(ReconcileRequest.TailDelta)
        p.ackTail(claim, true, 1000)

        assertNotNull("the newer tail is still pending", p.claim(1001))
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
        assertNotNull(retry.tailEpoch)
    }

    @Test
    fun `a tail arriving during a full sync is not covered by it`() {
        val p = PendingReconciles()
        p.add(ReconcileRequest.FullSync)
        val claim = p.claim(1000)!!
        assertNull(claim.tailEpoch)

        p.add(ReconcileRequest.TailDelta)
        p.ackFullSync(claim, true, 1000)

        assertTrue(p.hasWork())
        assertNotNull(p.claim(2000)!!.tailEpoch)
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
            assertEquals(UnitState.BACKOFF, p.fullSyncState(now))
            now += 120_000L
        }
        assertNotNull("keeps retrying at a capped interval", p.claim(now))
    }

    @Test
    fun `tail transient failures never permanently abandon it`() {
        val p = PendingReconciles()
        p.add(ReconcileRequest.TailDelta)
        var now = 1_000_000L
        repeat(12) {
            val claim = p.claim(now)!!
            p.ackTail(claim, false, now)
            assertEquals(UnitState.BACKOFF, p.tailState(now))
            now += 120_000L
        }
        assertNotNull(p.claim(now))
    }

    // ── retry backoff never blocks unrelated realtime work ──────────────────

    @Test
    fun `a thread in retry backoff does not block an immediate tail`() {
        val p = PendingReconciles()
        p.add(ReconcileRequest.ForThread(1))
        val claimed = p.claim(1000)!!.threads.single()
        p.ackThread(claimed, false, 1000)
        assertEquals(UnitState.BACKOFF, p.threadState(1, 1000))

        p.add(ReconcileRequest.TailDelta)
        val next = p.claim(1001)
        assertNotNull("the tail must be claimable immediately", next)
        assertNotNull(next!!.tailEpoch)
        assertEquals(UnitState.BACKOFF, p.threadState(1, 1001))
    }

    @Test
    fun `a thread in retry backoff does not block a healthy thread`() {
        val p = PendingReconciles()
        p.add(ReconcileRequest.ForThread(1))
        val claimed = p.claim(1000)!!.threads.single()
        p.ackThread(claimed, false, 1000)

        p.add(ReconcileRequest.ForThread(2))
        val next = p.claim(1001)!!
        assertEquals(listOf(2L), next.threads.map { it.threadId })
    }

    @Test
    fun `a thread in retry backoff does not block a full sync`() {
        val p = PendingReconciles()
        p.add(ReconcileRequest.ForThread(1))
        p.ackThread(p.claim(1000)!!.threads.single(), false, 1000)

        p.add(ReconcileRequest.FullSync)
        assertTrue(p.claim(1001)!!.fullSync)
    }

    // ── thread isolation, quarantine ───────────────────────────────────────

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

        assertTrue(h.successes().contains(1L))
        assertTrue(p.quarantinedThreadIds().contains(2L))
        assertEquals(UnitState.QUARANTINED, p.threadState(2, h.now))
        assertFalse(p.hasWork())
    }

    @Test
    fun `a new provider event re-arms a quarantined thread`() {
        val p = PendingReconciles(threadChunkSize = 8, maxThreadAttempts = 2)
        p.add(ReconcileRequest.ForThread(5))
        var now = 1_000_000L
        repeat(2) {
            p.ackThread(p.claim(now)!!.threads.single(), false, now)
            now += 120_000L
        }
        assertEquals(UnitState.QUARANTINED, p.threadState(5, now))

        p.add(ReconcileRequest.ForThread(5))

        assertEquals(UnitState.PENDING, p.threadState(5, now))
        assertNotNull(p.claim(now))
    }

    // ── semantic union / dedupe / in-flight ────────────────────────────────

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
    fun `a claim is not re-claimed while in flight`() {
        val p = PendingReconciles()
        p.add(ReconcileRequest.ForThread(9))
        assertEquals(listOf(9L), p.claim(1000)!!.threads.map { it.threadId })
        assertNull("in-flight work is not handed out twice", p.claim(1000))
    }

    @Test
    fun `next wake up is zero only when nothing is scheduled`() {
        val p = PendingReconciles()
        assertEquals(0L, p.nextWakeUpInMs(1000))

        p.add(ReconcileRequest.ForThread(3))
        p.ackThread(p.claim(1000)!!.threads.single(), false, 1000)
        assertTrue(p.nextWakeUpInMs(1000) > 0L)
    }

    // ── generation-scoped backoff: a new generation never inherits it ───────

    @Test
    fun `a new tail generation is immediately claimable despite old backoff`() {
        val p = PendingReconciles()
        p.add(ReconcileRequest.TailDelta)
        var now = 1_000_000L
        p.ackTail(p.claim(now)!!, false, now)
        assertEquals(UnitState.BACKOFF, p.tailState(now))

        // A NEW provider event arrives while the old generation is in backoff.
        p.add(ReconcileRequest.TailDelta)

        assertEquals(
            "the new generation must not inherit the old failure's backoff",
            UnitState.PENDING,
            p.tailState(now)
        )
        assertNotNull("and it is immediately claimable", p.claim(now))
    }

    @Test
    fun `an old tail failure does not delay a newer in-flight generation`() {
        val p = PendingReconciles()
        p.add(ReconcileRequest.TailDelta)
        val claim = p.claim(1000)!!

        p.add(ReconcileRequest.TailDelta) // newer generation while in flight
        p.ackTail(claim, false, 1000)     // the OLD generation fails

        assertEquals(UnitState.PENDING, p.tailState(1000))
        assertNotNull(p.claim(1000))
    }

    @Test
    fun `a new full sync generation is immediately claimable despite old backoff`() {
        val p = PendingReconciles()
        p.add(ReconcileRequest.FullSync)
        val now = 1_000_000L
        p.ackFullSync(p.claim(now)!!, false, now)
        assertEquals(UnitState.BACKOFF, p.fullSyncState(now))

        p.add(ReconcileRequest.FullSync)

        assertEquals(UnitState.PENDING, p.fullSyncState(now))
        assertNotNull(p.claim(now))
    }

    @Test
    fun `an old full sync failure does not delay a newer generation`() {
        val p = PendingReconciles()
        p.add(ReconcileRequest.FullSync)
        val claim = p.claim(1000)!!

        p.add(ReconcileRequest.FullSync)
        p.ackFullSync(claim, false, 1000)

        assertEquals(UnitState.PENDING, p.fullSyncState(1000))
        assertNotNull("the newer generation is not pushed into backoff", p.claim(1000))
    }

    @Test
    fun `an old thread failure does not backoff a newer generation`() {
        val p = PendingReconciles()
        p.add(ReconcileRequest.ForThread(8))
        val claimed = p.claim(1000)!!.threads.single()

        p.add(ReconcileRequest.ForThread(8)) // newer event
        p.ackThread(claimed, false, 1000)    // old generation fails

        assertEquals(
            "the newer generation retries immediately, not in the old backoff",
            UnitState.PENDING,
            p.threadState(8, 1000)
        )
        assertNotNull(p.claim(1000))
    }
}
