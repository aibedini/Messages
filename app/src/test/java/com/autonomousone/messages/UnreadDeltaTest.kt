package com.autonomousone.messages

import com.autonomousone.messages.data.UnreadDelta
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The exact-mutation write path maintains the conversation `unreadCount` by
 * O(1) delta — never by recounting the thread. All four state transitions are
 * covered here so a 360K-message thread still pays nothing per incoming SMS.
 */
class UnreadDeltaTest {

    @Test
    fun `brand-new unread message increments`() {
        assertEquals(1, UnreadDelta.compute(oldExists = false, oldRead = false, newRead = false))
    }

    @Test
    fun `brand-new read message does not increment`() {
        assertEquals(0, UnreadDelta.compute(oldExists = false, oldRead = false, newRead = true))
    }

    @Test
    fun `re-upsert of an already unread message stays flat`() {
        assertEquals(0, UnreadDelta.compute(oldExists = true, oldRead = false, newRead = false))
    }

    @Test
    fun `read message re-upserted as read stays flat`() {
        assertEquals(0, UnreadDelta.compute(oldExists = true, oldRead = true, newRead = true))
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
}
