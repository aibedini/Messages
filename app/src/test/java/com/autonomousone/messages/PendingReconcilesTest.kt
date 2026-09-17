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
 * Reconcile requests are NOT interchangeable, so they are merged by SEMANTIC
 * UNION. A conflated channel of typed requests silently dropped work:
 *
 *   ForThread(12), ForThread(99), TailDelta -> TailDelta
 *   startup FullSync, provider TailDelta    -> TailDelta
 */
class PendingReconcilesTest {

    private fun PendingReconciles.drainAll(): List<PendingReconciles.Work> {
        val out = mutableListOf<PendingReconciles.Work>()
        while (true) out += (drainSnapshot() ?: break)
        return out
    }

    @Test
    fun `fullSync is not lost when a tail arrives before the consumer runs`() {
        val p = PendingReconciles()
        p.add(ReconcileRequest.FullSync)
        p.add(ReconcileRequest.TailDelta)

        val work = p.drainAll()
        assertEquals(1, work.size)
        assertTrue("the startup FullSync must survive observer traffic", work[0].fullSync)
        assertFalse("FullSync subsumes the tail", work[0].tailDelta)
    }

    @Test
    fun `three thread repairs all eventually run`() {
        val p = PendingReconciles()
        p.add(ReconcileRequest.ForThread(1))
        p.add(ReconcileRequest.ForThread(2))
        p.add(ReconcileRequest.ForThread(3))

        val ids = p.drainAll().flatMap { it.threadIds }
        assertEquals(listOf(1L, 2L, 3L), ids)
    }

    @Test
    fun `tail plus twenty thread repairs runs the tail once and all threads`() {
        val p = PendingReconciles(threadChunkSize = 8)
        p.add(ReconcileRequest.TailDelta)
        (1L..20L).forEach { p.add(ReconcileRequest.ForThread(it)) }

        val work = p.drainAll()
        assertEquals("tail runs exactly once", 1, work.count { it.tailDelta })
        assertEquals((1L..20L).toList(), work.flatMap { it.threadIds })
    }

    @Test
    fun `fifty thread ids lose none`() {
        val p = PendingReconciles(threadChunkSize = 8)
        (1L..50L).forEach { p.add(ReconcileRequest.ForThread(it)) }

        val ids = p.drainAll().flatMap { it.threadIds }
        assertEquals(50, ids.size)
        assertEquals((1L..50L).toList(), ids)
        assertTrue("drained in chunks", p.drainAll().isEmpty())
    }

    @Test
    fun `the same thread repeated one hundred times repairs once`() {
        val p = PendingReconciles()
        repeat(100) { p.add(ReconcileRequest.ForThread(7)) }

        assertEquals(listOf(7L), p.drainAll().flatMap { it.threadIds })
    }

    @Test
    fun `work arriving during execution is processed next drain`() {
        val p = PendingReconciles()
        p.add(ReconcileRequest.ForThread(1))

        val first = p.drainSnapshot()
        assertEquals(listOf(1L), first!!.threadIds)
        assertFalse("the snapshot consumed everything it returned", p.hasWork())

        // ...the consumer is now executing; new work arrives mid-execution.
        p.add(ReconcileRequest.ForThread(2))
        p.add(ReconcileRequest.TailDelta)

        val second = p.drainSnapshot()
        assertEquals(listOf(2L), second!!.threadIds)
        assertTrue(second.tailDelta)
        assertNull("nothing left", p.drainSnapshot())
    }

    @Test
    fun `a snapshot only removes the ids it returned`() {
        val p = PendingReconciles(threadChunkSize = 2)
        (1L..5L).forEach { p.add(ReconcileRequest.ForThread(it)) }

        val first = p.drainSnapshot()!!
        assertEquals(listOf(1L, 2L), first.threadIds)
        assertEquals(3, p.pendingThreadCount())
    }

    @Test
    fun `empty accumulator has no work`() {
        val p = PendingReconciles()
        assertFalse(p.hasWork())
        assertNull(p.drainSnapshot())
        assertNotNull(p.drainSnapshot() ?: "null is correct")
    }

    @Test
    fun `non-positive thread ids are ignored`() {
        val p = PendingReconciles()
        p.add(ReconcileRequest.ForThread(0))
        p.add(ReconcileRequest.ForThread(-5))
        assertFalse(p.hasWork())
    }
}
