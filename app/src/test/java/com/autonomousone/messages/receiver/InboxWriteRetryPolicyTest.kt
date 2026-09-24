package com.autonomousone.messages.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persisting an inbound SMS, and the two failures that used to look identical.
 *
 * The defect: `insertIntoInbox` returned `(-1, null)` for any failure, and the caller then logged
 * `decision=defer-to-provider-observer` — the same line a *successful* write produces when the row is
 * not readable yet. This app is the default SMS app, so when the write genuinely failed nothing else
 * wrote the row, and the ContentObserver that line defers to had nothing to find. An inbound message
 * could be lost with no record that it arrived.
 */
class InboxWriteRetryPolicyTest {

    /** Records what the loop did, so the attempts and the waits can both be asserted. */
    private class FakeWrite(private val outcomes: List<InboxWriteAttempt>) {
        var calls = 0
            private set
        fun write(): InboxWriteAttempt = outcomes[minOf(calls++, outcomes.lastIndex)]
    }

    @Test
    fun `aSuccessfulWriteIsNotRetried`() {
        val write = FakeWrite(listOf(InboxWriteAttempt.Wrote(42)))
        val sleeps = mutableListOf<Long>()

        val outcome = InboxWriteRetryPolicy.persist(write = { write.write() }, sleep = { sleeps += it })

        assertEquals(InboxPersistOutcome.Persisted(rowId = 42, attempts = 1), outcome)
        assertEquals(1, write.calls)
        assertTrue("and nothing waits", sleeps.isEmpty())
    }

    @Test
    fun `aTransientFailureIsRetriedAndSucceeds`() {
        // The case worth spending a broadcast window on: a provider that was momentarily unavailable.
        val write = FakeWrite(
            listOf(
                InboxWriteAttempt.Threw("database is locked"),
                InboxWriteAttempt.Threw("database is locked"),
                InboxWriteAttempt.Wrote(7)
            )
        )
        val sleeps = mutableListOf<Long>()

        val outcome = InboxWriteRetryPolicy.persist(write = { write.write() }, sleep = { sleeps += it })

        assertEquals(InboxPersistOutcome.Persisted(rowId = 7, attempts = 3), outcome)
        assertEquals(listOf(50L, 150L), sleeps)
    }

    @Test
    fun `aPersistentFailureGivesUpAndKeepsTheReason`() {
        // Losing the message is bad; losing the REASON is worse, because it is the only thing that says
        // whether this was a full disk, a locked provider or a permission problem.
        val write = FakeWrite(listOf(InboxWriteAttempt.Threw("disk full")))
        val sleeps = mutableListOf<Long>()

        val outcome = InboxWriteRetryPolicy.persist(write = { write.write() }, sleep = { sleeps += it })

        assertEquals(InboxPersistOutcome.Failed(attempts = 3, reason = "disk full"), outcome)
        assertEquals("exactly the configured number of attempts", 3, write.calls)
        assertEquals("and no wait after the last attempt", listOf(50L, 150L), sleeps)
    }

    @Test
    fun `aProviderThatReturnsNoRowIdCountsAsAFailure`() {
        // `insert` returning a URI without a usable id is not a success — there is nothing to read back,
        // so the caller must not proceed as though a row exists. The receiver maps that to `Threw`, and
        // this pins the mapping's consequence: it is retried like any other failure.
        val write = FakeWrite(listOf(InboxWriteAttempt.Threw("no-row-id")))

        val outcome = InboxWriteRetryPolicy.persist(write = { write.write() }, sleep = {})

        assertTrue(outcome is InboxPersistOutcome.Failed)
        assertEquals("no-row-id", (outcome as InboxPersistOutcome.Failed).reason)
    }

    @Test
    fun `everyRetryFitsInsideABroadcastWindow`() {
        // This runs on a thread the system is timing (`goAsync`). Three attempts and two waits must fit
        // in well under a second, or the retry itself becomes the reason the message is lost.
        val totalWait = InboxWriteRetryPolicy.BACKOFF_MS.sum()

        assertTrue("total wait is $totalWait ms", totalWait < 500)
        assertEquals(InboxWriteRetryPolicy.MAX_ATTEMPTS - 1, InboxWriteRetryPolicy.BACKOFF_MS.size)
        assertTrue(InboxWriteRetryPolicy.MAX_ATTEMPTS >= 2)
    }

    @Test
    fun `theBackoffGrowsAndIsBounded`() {
        assertEquals(50L, InboxWriteRetryPolicy.backoffBefore(1))
        assertEquals(150L, InboxWriteRetryPolicy.backoffBefore(2))
        // Never indexed out of range, however it is called — a crash in the retry path would lose the
        // message the retry existed to save. Below the first attempt it degrades to the FIRST backoff,
        // and beyond the list it stays at the longest one.
        assertEquals(50L, InboxWriteRetryPolicy.backoffBefore(0))
        assertEquals(150L, InboxWriteRetryPolicy.backoffBefore(99))
    }

    @Test
    fun `shouldRetryIsFalseOnlyAfterTheLastAttempt`() {
        assertTrue(InboxWriteRetryPolicy.shouldRetry(0))
        assertTrue(InboxWriteRetryPolicy.shouldRetry(1))
        assertTrue(InboxWriteRetryPolicy.shouldRetry(InboxWriteRetryPolicy.MAX_ATTEMPTS - 1))
        assertFalse(InboxWriteRetryPolicy.shouldRetry(InboxWriteRetryPolicy.MAX_ATTEMPTS))
    }

    @Test
    fun `anEmptyOutcomeListIsNotSilentlyTreatedAsSuccess`() {
        // Defensive: the loop must always report a failure when nothing succeeded, never fall through
        // with a stale reason or a null outcome.
        val outcome = InboxWriteRetryPolicy.persist(
            write = { InboxWriteAttempt.Threw(null) },
            sleep = {},
            maxAttempts = 1
        )

        assertEquals(InboxPersistOutcome.Failed(attempts = 1, reason = null), outcome)
    }
}
