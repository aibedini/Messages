package com.autonomousone.messages

import com.autonomousone.messages.repository.MessageIdentity
import com.autonomousone.messages.ui.selection.SelectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FEATURE 9 — the multi-select reducer is pure, so every rule the UI depends on
 * is pinned here instead of being re-derived inside a composable:
 * start / toggle / add / remove / clear / exit and survival across a refresh.
 */
class SelectionStateTest {

    @Test
    fun `idle state is not active and has no keys`() {
        val state = SelectionState.idle<Long>()
        assertFalse(state.active)
        assertTrue(state.isEmpty)
        assertEquals(0, state.count)
    }

    @Test
    fun `start enters selection mode with exactly one key`() {
        val state = SelectionState.idle<Long>().start(42L)
        assertTrue(state.active)
        assertEquals(setOf(42L), state.keys)
    }

    @Test
    fun `toggle adds and removes keys`() {
        var state = SelectionState.idle<Long>().start(1L)
        state = state.toggle(2L)
        state = state.toggle(3L)
        assertEquals(setOf(1L, 2L, 3L), state.keys)
        assertEquals(3, state.count)

        state = state.toggle(2L)
        assertEquals(setOf(1L, 3L), state.keys)
        assertTrue(state.active)
    }

    @Test
    fun `toggling the last key off exits selection mode`() {
        val state = SelectionState.idle<Long>().start(7L).toggle(7L)
        assertFalse(state.active)
        assertTrue(state.isEmpty)
    }

    @Test
    fun `toggle on an idle state starts a selection instead of doing nothing`() {
        val state = SelectionState.idle<Long>().toggle(9L)
        assertTrue(state.active)
        assertEquals(setOf(9L), state.keys)
    }

    @Test
    fun `remove of an unknown key is a no-op`() {
        val state = SelectionState.idle<Long>().start(1L)
        assertEquals(state, state.remove(2L))
    }

    @Test
    fun `clear always leaves selection mode`() {
        val state = SelectionState.idle<Long>().start(1L).toggle(2L).clear()
        assertFalse(state.active)
        assertTrue(state.isEmpty)
    }

    @Test
    fun `addAll never removes an already selected key`() {
        val state = SelectionState.idle<Long>().start(1L).addAll(listOf(2L, 3L))
        assertEquals(setOf(1L, 2L, 3L), state.keys)
    }

    // ── Survival across a (simulated) Room refresh ──────────────────────────

    @Test
    fun `selection survives a refresh that still contains the selected rows`() {
        val before = SelectionState.idle<Long>().start(1L).toggle(2L)

        val after = before.afterListRefresh(setOf(1L, 2L, 3L, 4L))

        assertEquals(before, after)
        assertEquals(setOf(1L, 2L), after.keys)
        assertTrue(after.active)
    }

    @Test
    fun `a refresh drops only the keys that provably vanished`() {
        val before = SelectionState.idle<Long>().start(1L).toggle(2L).toggle(3L)

        val after = before.afterListRefresh(setOf(1L, 3L))

        assertEquals(setOf(1L, 3L), after.keys)
        assertTrue(after.active)
    }

    @Test
    fun `a refresh that removes everything exits selection mode`() {
        val before = SelectionState.idle<Long>().start(1L).toggle(2L)

        val after = before.afterListRefresh(setOf(9L))

        assertFalse(after.active)
        assertTrue(after.isEmpty)
    }

    @Test
    fun `an empty list is not evidence of removal`() {
        // A transient empty Room emission (bootstrap/rebuild) must never silently
        // drop a live selection.
        val before = SelectionState.idle<Long>().start(5L)

        val after = before.afterListRefresh(emptySet())

        assertEquals(before, after)
        assertTrue(after.active)
    }

    @Test
    fun `reconcile on an idle state stays idle`() {
        val state = SelectionState.idle<Long>().afterListRefresh(setOf(1L, 2L))
        assertFalse(state.active)
    }

    // ── Composite message identity ──────────────────────────────────────────

    @Test
    fun `SMS 100 and MMS 100 are distinct selection keys`() {
        val sms100 = MessageIdentity.Key(MessageIdentity.SOURCE_SMS, 100L)
        val mms100 = MessageIdentity.Key(MessageIdentity.SOURCE_MMS, 100L)

        assertNotEquals(sms100, mms100)

        val state = SelectionState.idle<MessageIdentity.Key>().start(sms100).toggle(mms100)

        assertEquals(2, state.count)
        assertTrue(sms100 in state.keys)
        assertTrue(mms100 in state.keys)
        assertEquals(2, state.keys.toSet().size)
    }

    @Test
    fun `composite keys survive a refresh keyed by identity`() {
        val sms100 = MessageIdentity.keyOf(100L)
        val mms100 = MessageIdentity.keyOf(-100L)
        val before = SelectionState.idle<MessageIdentity.Key>().start(sms100).toggle(mms100)

        val after = before.afterListRefresh(setOf(sms100, mms100))

        assertEquals(before, after)
        assertEquals(2, after.count)
    }
}
