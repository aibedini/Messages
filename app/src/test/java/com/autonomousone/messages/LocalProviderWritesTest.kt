package com.autonomousone.messages

import com.autonomousone.messages.data.LocalProviderWrites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * A bulk mark-read on an OPEN chat must reconcile ONLY that thread.
 *
 * The registry is the handoff between the write path and ChangeRouter. It is an
 * OPERATION TOKEN, not a one-shot claim: one mark-read legitimately causes
 * several provider callbacks (SMS + MMS + threads table), and every one of them
 * must be able to narrow itself to that thread. The token expires by time.
 */
class LocalProviderWritesTest {

    @Before
    fun setUp() {
        LocalProviderWrites.clearForTest()
    }

    @Test
    fun `mark-read token survives several provider callbacks`() {
        LocalProviderWrites.noteMarkRead(42L)

        // Three callbacks of the SAME operation must all see the token; the old
        // one-shot claim let the 2nd and 3rd fall through to a full reconcile.
        repeat(3) {
            val active = LocalProviderWrites.activeMarkRead()
            assertNotNull("token must stay valid for its whole window", active)
            assertEquals(42L, active!!.threadId)
        }
    }

    @Test
    fun `token carries an operation id and an expiry`() {
        LocalProviderWrites.noteMarkRead(7L)
        val entry = LocalProviderWrites.activeMarkRead()!!
        assertEquals(LocalProviderWrites.Kind.MARK_READ, entry.kind)
        assertEquals(7L, entry.threadId)
        assertEquals(entry.startedAt + LocalProviderWrites.WINDOW_MS, entry.expiresAt)
    }

    @Test
    fun `non-positive thread ids are never noted`() {
        LocalProviderWrites.noteMarkRead(0L)
        LocalProviderWrites.noteMarkRead(-1L)
        assertNull(LocalProviderWrites.activeMarkRead())
        assertNull(LocalProviderWrites.activeOperation())
    }

    @Test
    fun `tokens expire outside the window`() {
        LocalProviderWrites.noteMarkRead(7L)
        val future = System.currentTimeMillis() + LocalProviderWrites.WINDOW_MS + 1000L
        assertNull(LocalProviderWrites.activeMarkRead(future))
    }

    @Test
    fun `newest token wins when a burst notes several`() {
        LocalProviderWrites.noteMarkRead(1L)
        LocalProviderWrites.noteMarkRead(2L)
        LocalProviderWrites.noteMarkRead(3L)
        assertEquals(3L, LocalProviderWrites.activeMarkRead()!!.threadId)
        assertEquals(3L, LocalProviderWrites.activeOperation()!!.threadId)
    }
}
