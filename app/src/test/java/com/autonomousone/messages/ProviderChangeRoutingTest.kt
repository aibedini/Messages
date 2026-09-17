package com.autonomousone.messages

import com.autonomousone.messages.data.ChangeRouter
import com.autonomousone.messages.observer.ProviderChangeBatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The P0 contract: an ordinary provider notification can NEVER schedule a full
 * reconcile. It routes to the narrowest repair the burst justifies.
 *
 * ChangeRouter.RepairPlan has no fullSync field at all, so this is structural as
 * well as asserted.
 */
class ProviderChangeRoutingTest {

    @Test
    fun `exact sms row is an exact mutation`() {
        val batch = ProviderChangeBatch.from("sms", "/348201")

        assertEquals(setOf(348201L), batch.smsIds)
        val plan = ChangeRouter.planRepair(batch, selfWriteThreadId = null)

        assertEquals(listOf(348201L), plan.exactSmsIds)
        assertTrue(plan.threadRepairs.isEmpty())
        assertFalse("an exact row must not trigger a tail repair", plan.tailDelta)
    }

    @Test
    fun `mms authority routes to the mms table`() {
        val batch = ProviderChangeBatch.from("mms", "/77")
        assertEquals(setOf(77L), batch.mmsIds)
        assertEquals(listOf(77L), ChangeRouter.planRepair(batch, null).exactMmsIds)
    }

    @Test
    fun `thread uri is a thread repair not a row read`() {
        val batch = ProviderChangeBatch.from("sms", "/thread/123")
        assertEquals(setOf(123L), batch.threadIds)
        assertFalse(batch.hasExactRows)

        val plan = ChangeRouter.planRepair(batch, null)
        assertEquals(listOf(123L), plan.threadRepairs)
        assertFalse(plan.tailDelta)
    }

    @Test
    fun `observer burst exact uri plus trailing generic never escalates`() {
        // The reported bug: leading exact row, then a coalesced notification
        // with no id. The old code dispatched null for the trailing edge and
        // ChangeRouter turned it into a dual-source FullSync 150 ms later.
        val burst = ProviderChangeBatch.from("sms", "/900")
            .merge(ProviderChangeBatch.from("sms", "/sms"))

        assertEquals(1, burst.unknownCount)
        val plan = ChangeRouter.planRepair(burst, null)

        assertEquals(listOf(900L), plan.exactSmsIds)
        assertFalse("a burst containing an exact row must not tail-repair", plan.tailDelta)
        assertTrue(plan.threadRepairs.isEmpty())
    }

    @Test
    fun `generic provider event routes to a bounded tail delta`() {
        val batch = ProviderChangeBatch.from("sms", "/sms")
        assertTrue(batch.isUnknownOnly)

        val plan = ChangeRouter.planRepair(batch, selfWriteThreadId = null)

        assertTrue("unknown burst -> bounded tail repair", plan.tailDelta)
        assertTrue(plan.exactSmsIds.isEmpty())
        assertTrue(plan.exactMmsIds.isEmpty())
        assertTrue(plan.threadRepairs.isEmpty())
    }

    @Test
    fun `self mark-read burst routes to that thread only`() {
        // SMS + MMS + generic callbacks from ONE mark-read: the token narrows
        // each of them to the thread and none of them escalates.
        for (path in listOf("/sms", "/mms", "/sms/thread/42")) {
            val authority = if (path.startsWith("/mms")) "mms" else "sms"
            val plan = ChangeRouter.planRepair(
                ProviderChangeBatch.from(authority, path),
                selfWriteThreadId = 42L
            )
            assertFalse("self-write must never tail-repair: " + path, plan.tailDelta)
            assertTrue(
                "self-write must be narrowed to its thread: " + path,
                plan.threadRepairs.isEmpty() || plan.threadRepairs == listOf(42L)
            )
            assertTrue(plan.exactSmsIds.isEmpty())
            assertTrue(plan.exactMmsIds.isEmpty())
        }
    }

    @Test
    fun `burst merging keeps every identified row`() {
        val merged = ProviderChangeBatch.from("sms", "/1")
            .merge(ProviderChangeBatch.from("sms", "/2"))
            .merge(ProviderChangeBatch.from("mms", "/3"))

        assertEquals(setOf(1L, 2L), merged.smsIds)
        assertEquals(setOf(3L), merged.mmsIds)
        assertEquals(0, merged.unknownCount)
        val plan = ChangeRouter.planRepair(merged, null)
        assertEquals(listOf(1L, 2L), plan.exactSmsIds)
        assertEquals(listOf(3L), plan.exactMmsIds)
    }

    @Test
    fun `the number of thread repairs in one burst is bounded`() {
        var batch = ProviderChangeBatch.EMPTY
        repeat(50) { batch = batch.merge(ProviderChangeBatch.from("sms", "/thread/" + (it + 1))) }

        val plan = ChangeRouter.planRepair(batch, null)
        assertTrue("burst must not schedule unbounded repairs", plan.threadRepairs.size <= 8)
    }
}
