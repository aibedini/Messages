package com.autonomousone.messages

import com.autonomousone.messages.data.UnreadDelta
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The exact-mutation write path maintains the conversation `unreadCount` by
 * signed O(1) delta — never by recounting the thread. Every state transition
 * is covered so a 360K-message thread still pays nothing per incoming SMS,
 * AND the badge actually goes DOWN when a message is read (the old rule
 * returned 0 for unread→read, so badges never cleared on the Room path).
 */
class UnreadDeltaTest {

    @Test
    fun `brand-new unread message increments`() {
        assertEquals(1, UnreadDelta.compute(oldExists = false, oldRead = false, newRead = false))
    }

    @Test
    fun `brand-new read message does not move the badge`() {
        // Outgoing SMS lands already-read: must be 0, never -1.
        assertEquals(0, UnreadDelta.compute(oldExists = false, oldRead = false, newRead = true))
    }

    @Test
    fun `re-upsert of an already unread message stays flat`() {
        assertEquals(0, UnreadDelta.compute(oldExists = true, oldRead = false, newRead = false))
    }

    @Test
    fun `read message re-upserted as read stays flat`() {
        // Status callbacks (PENDING→SENT) re-upsert the same row: flat.
        assertEquals(0, UnreadDelta.compute(oldExists = true, oldRead = true, newRead = true))
    }

    @Test
    fun `unread-to-read flip decrements`() {
        // User opened the thread / provider marked READ → badge comes down.
        assertEquals(-1, UnreadDelta.compute(oldExists = true, oldRead = false, newRead = true))
    }

    @Test
    fun `provider correcting read-to-unread increments once`() {
        assertEquals(1, UnreadDelta.compute(oldExists = true, oldRead = true, newRead = false))
    }

    @Test
    fun `repeated upserts never accumulate`() {
        // An unread message upserted 1000 times must add exactly 1 unread.
        var delta = 0
        var exists = false
        var oldRead = false
        repeat(1_000) {
            delta += UnreadDelta.compute(exists, oldRead, newRead = false)
            exists = true
            oldRead = false
        }
        assertEquals(1, delta)
    }

    @Test
    fun `full unread lifecycle sums to zero`() {
        var delta = 0
        delta += UnreadDelta.compute(oldExists = false, oldRead = false, newRead = false) // +1
        delta += UnreadDelta.compute(oldExists = true, oldRead = false, newRead = true)   // -1
        delta += UnreadDelta.compute(oldExists = true, oldRead = true, newRead = false)   // +1
        delta += UnreadDelta.compute(oldExists = true, oldRead = false, newRead = true)   // -1
        assertEquals(0, delta)
    }
}
