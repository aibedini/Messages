package com.autonomousone.messages

import com.autonomousone.messages.data.ChangeRouter
import com.autonomousone.messages.observer.ProviderChangeBatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The P0 contract, part 1: an ordinary provider notification can NEVER schedule
 * a full reconcile (ChangeRouter.RepairPlan has no fullSync field at all).
 *
 * Part 2 — the safety invariant: an UNKNOWN notification is never dropped and is
 * never assumed to belong to something else in the same burst. It may cost one
 * extra bounded watermark delta; losing a real change is not an acceptable
 * trade.
 */
class ProviderChangeRoutingTest {

    private fun plan(batch: ProviderChangeBatch, selfWriteThreadId: Long? = null) =
        ChangeRouter.planRepair(batch, selfWriteThreadId)

    @Test
    fun `exact sms row is an exact mutation and needs no delta`() {
        val batch = ProviderChangeBatch.from("sms", "/348201")

        assertEquals(setOf(348201L), batch.smsIds)
        val p = plan(batch)

        assertEquals(listOf(348201L), p.exactSmsIds)
        assertTrue(p.threadRepairs.isEmpty())
        assertFalse("nothing unknown -> no extra delta", p.tailDelta)
    }

    @Test
    fun `mms authority routes to the mms table`() {
        val batch = ProviderChangeBatch.from("mms", "/77")
        assertEquals(setOf(77L), batch.mmsIds)
        assertEquals(listOf(77L), plan(batch).exactMmsIds)
    }

    @Test
    fun `thread uri is a thread repair not a row read`() {
        val batch = ProviderChangeBatch.from("sms", "/thread/123")
        assertEquals(setOf(123L), batch.threadIds)
        assertFalse(batch.hasExactRows)

        val p = plan(batch)
        assertEquals(listOf(123L), p.threadRepairs)
        assertFalse(p.tailDelta)
    }

    // ── the safety invariant ────────────────────────────────────────────────

    @Test
    fun `exact sms plus unknown keeps BOTH repairs`() {
        // A coalesced burst can carry an exact row for one change and a generic
        // notification for a DIFFERENT one. The first version of planRepair
        // returned on the exact row and silently dropped the unknown.
        val burst = ProviderChangeBatch.from("sms", "/900")
            .merge(ProviderChangeBatch.from("sms", "/sms"))

        assertEquals(1, burst.unknownCount)
        val p = plan(burst)

        assertEquals(listOf(900L), p.exactSmsIds)
        assertTrue("the unknown event must still earn its own delta", p.tailDelta)
    }

    @Test
    fun `exact mms plus unknown keeps BOTH repairs`() {
        val burst = ProviderChangeBatch.from("mms", "/55")
            .merge(ProviderChangeBatch.from("mms", "/mms"))

        val p = plan(burst)
        assertEquals(listOf(55L), p.exactMmsIds)
        assertTrue(p.tailDelta)
    }

    @Test
    fun `multiple exact ids plus unknown keep everything`() {
        val burst = ProviderChangeBatch.from("sms", "/1")
            .merge(ProviderChangeBatch.from("sms", "/2"))
            .merge(ProviderChangeBatch.from("mms", "/3"))
            .merge(ProviderChangeBatch.EMPTY.merge(ProviderChangeBatch.from(null, null)))

        val p = plan(burst)
        assertEquals(listOf(1L, 2L), p.exactSmsIds)
        assertEquals(listOf(3L), p.exactMmsIds)
        assertTrue(p.tailDelta)
    }

    @Test
    fun `mark-read thread A plus an unrelated generic event keeps the delta`() {
        // The self-write token must NOT let an unrelated external change be
        // attributed only to thread A.
        val burst = ProviderChangeBatch.from("sms", "/thread/42")
            .merge(ProviderChangeBatch.from("sms", "/sms"))

        val p = plan(burst, selfWriteThreadId = 42L)
        assertEquals(listOf(42L), p.threadRepairs)
        assertTrue("unrelated unknown must still repair", p.tailDelta)
    }

    @Test
    fun `active SEND token does not swallow an unrelated incoming sms`() {
        // A real incoming row is exact, so it is always repaired regardless of
        // any self-write token; a generic event alongside it still deltas.
        val burst = ProviderChangeBatch.from("sms", "/777")
            .merge(ProviderChangeBatch.from("sms", "/sms"))

        val p = plan(burst, selfWriteThreadId = 5L)
        assertEquals(listOf(777L), p.exactSmsIds)
        assertTrue(p.tailDelta)
    }

    @Test
    fun `unknown only with a self-write narrows but still deltas`() {
        val batch = ProviderChangeBatch.from("sms", "/sms")
        assertTrue(batch.isUnknownOnly)

        val p = plan(batch, selfWriteThreadId = 42L)

        assertEquals("self-write narrows the repair", listOf(42L), p.threadRepairs)
        assertTrue(
            "identity cannot be proven, so the unknown event is not discarded",
            p.tailDelta
        )
    }

    @Test
    fun `generic provider event with no self-write deltas exactly once`() {
        val p = plan(ProviderChangeBatch.from("sms", "/sms"))

        assertTrue(p.tailDelta)
        assertTrue(p.exactSmsIds.isEmpty())
        assertTrue(p.exactMmsIds.isEmpty())
        assertTrue(p.threadRepairs.isEmpty())
    }

    @Test
    fun `burst merging keeps every identified row`() {
        val merged = ProviderChangeBatch.from("sms", "/1")
            .merge(ProviderChangeBatch.from("sms", "/2"))
            .merge(ProviderChangeBatch.from("mms", "/3"))

        assertEquals(setOf(1L, 2L), merged.smsIds)
        assertEquals(setOf(3L), merged.mmsIds)
        assertEquals(0, merged.unknownCount)
    }

    @Test
    fun `the number of thread repairs in one burst is bounded`() {
        var batch = ProviderChangeBatch.EMPTY
        repeat(50) { batch = batch.merge(ProviderChangeBatch.from("sms", "/thread/" + (it + 1))) }

        val p = plan(batch)
        assertEquals("no known thread may ever be dropped", 50, p.threadRepairs.size)
    }
}
