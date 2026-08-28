package com.autonomousone.messages

import com.autonomousone.messages.data.LocalProviderWrites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A bulk mark-read on an OPEN chat must reconcile ONLY that thread.
 * The registry is the handoff between the write path and ChangeRouter:
 * note → claim (exactly once) → ForThread. Stale notes expire so one
 * forgotten entry can't suppress a real future sync.
 */
class LocalProviderWritesTest {

    /** The registry is a process singleton; start every test from empty. */
    private fun drain() {
        while (LocalProviderWrites.claimRecentMarkRead() != null) { /* consume */ }
    }

    @Test
    fun `mark-read is claimed exactly once`() {
        drain()
        LocalProviderWrites.noteMarkRead(42L)
        val first = LocalProviderWrites.claimRecentMarkRead()
        assertNotNull(first)
        assertEquals(42L, first!!.threadId)
        // Consumed — the second claim must miss so the next observer
        // event is treated as genuinely unknown.
        assertNull(LocalProviderWrites.claimRecentMarkRead())
    }

    @Test
    fun `non-positive thread ids are never noted`() {
        drain()
        // Address-only fallback (threadId == 0) can't target a thread, so
        // recording it would downgrade a needed FullSync to nothing.
        LocalProviderWrites.noteMarkRead(0L)
        LocalProviderWrites.noteMarkRead(-1L)
        assertNull(LocalProviderWrites.claimRecentMarkRead())
    }

    @Test
    fun `entries expire outside the window`() {
        LocalProviderWrites.noteMarkRead(7L)
        val future = System.currentTimeMillis() + LocalProviderWrites.WINDOW_MS + 1_000
        assertNull(LocalProviderWrites.claimRecentMarkRead(future))
    }

    @Test
    fun `ring keeps the newest notes when flooded`() {
        repeat(40) { LocalProviderWrites.noteMarkRead((it + 1).toLong()) }
        // Oldest entries evicted; claims return survivors in order.
        val claimed = generateSequence { LocalProviderWrites.claimRecentMarkRead() }
            .map { it.threadId }
            .take(8)
            .toList()
        assertEquals(8, claimed.size)
        assert(claimed.all { it >= 9L })
    }
}
