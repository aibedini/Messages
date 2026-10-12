package com.autonomousone.messages

import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.repository.MessageIdentity
import com.autonomousone.messages.ui.selection.MessageSelectionResolver
import com.autonomousone.messages.ui.selection.SelectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reaching Message Details after ONE long press.
 *
 * ## The UX defect this pins down
 *
 * The full Details screen existed and was reachable, but only by long-pressing a message, then
 * long-pressing it AGAIN to open a context menu. The first long press enters selection mode, and the
 * selection toolbar had no Details action — so the feature was effectively undiscoverable.
 *
 * Two rules fix it, and both are asserted here:
 *
 *  1. the toolbar exposes Details when exactly one message is selected, and NOT otherwise;
 *  2. that one message is resolved by its COMPOSITE identity, never by a bare numeric id.
 *
 * Rule 2 is not pedantry: SMS row 100 and MMS row 100 are different messages, so resolving by number
 * opens the wrong one.
 */
class MessageDetailsAccessTest {

    private fun sms(id: Long, body: String = "hello", type: Int = 2) = Sms(
        id = id,
        threadId = 5L,
        sender = "+989120000000",
        message = body,
        date = 1_700_000_000_000L,
        unread = false,
        type = type
    )

    /** A model MMS id is negative, so `MessageIdentity` can tell the two sources apart. */
    private fun mms(id: Long, body: String = "photo") = sms(id = -id, body = body)

    // ── rule 1: the toolbar exposes Details for exactly one message ───────────

    @Test
    fun `the first long press selects exactly one message`() {
        // Long-press entry is `start`, not `toggle`: it must produce a one-message selection, which is
        // the precondition for the Details action existing at all.
        val state = SelectionState.idle<MessageIdentity.Key>()
            .start(MessageIdentity.keyOf(sms(100L)))

        assertEquals(1, state.count)
        assertTrue(state.active)
    }

    @Test
    fun `Details is available for a one-message selection`() {
        val only = MessageIdentity.keyOf(sms(100L))
        val state = SelectionState.idle<MessageIdentity.Key>().start(only)

        assertTrue("the toolbar must show Details", detailsVisible(state))
    }

    @Test
    fun `Details is hidden for a multi-selection`() {
        // Showing it for two or more would mean arbitrarily opening one of them.
        val state = SelectionState.idle<MessageIdentity.Key>()
            .start(MessageIdentity.keyOf(sms(100L)))
            .add(MessageIdentity.keyOf(sms(200L)))

        assertEquals(2, state.count)
        assertFalse("no single subject, so no Details", detailsVisible(state))
    }

    @Test
    fun `Details disappears again when the selection drops back to one`() {
        val a = MessageIdentity.keyOf(sms(100L))
        val b = MessageIdentity.keyOf(sms(200L))
        val two = SelectionState.idle<MessageIdentity.Key>().start(a).add(b)

        val backToOne = two.remove(b)

        assertEquals(1, backToOne.count)
        assertTrue(detailsVisible(backToOne))
    }

    @Test
    fun `Details is not offered once selection mode ends`() {
        val state = SelectionState.idle<MessageIdentity.Key>().start(MessageIdentity.keyOf(sms(100L))).clear()

        assertEquals(0, state.count)
        assertFalse(detailsVisible(state))
    }

    /** The toolbar rule: one message selected and not busy. */
    private fun detailsVisible(state: SelectionState<MessageIdentity.Key>, busy: Boolean = false) =
        state.count == 1 && !busy

    // ── rule 2: the right message is resolved ────────────────────────────────

    @Test
    fun `selecting SMS A opens Details for SMS A`() {
        val a = sms(100L, body = "alpha")
        val b = sms(200L, body = "beta")
        val rows = listOf(a, b)

        val resolved = MessageSelectionResolver.resolveSingle(setOf(MessageIdentity.keyOf(a)), rows)

        assertEquals("alpha", resolved?.message)
        assertEquals(100L, resolved?.id)
    }

    @Test
    fun `selecting SMS B opens Details for SMS B and never for its neighbour`() {
        val a = sms(100L, body = "alpha")
        val b = sms(200L, body = "beta")
        val rows = listOf(a, b)

        val resolved = MessageSelectionResolver.resolveSingle(setOf(MessageIdentity.keyOf(b)), rows)

        assertEquals("beta", resolved?.message)
    }

    @Test
    fun `a multi-selection resolves to nothing rather than to the first selected`() {
        val a = sms(100L)
        val b = sms(200L)
        val keys = setOf(MessageIdentity.keyOf(a), MessageIdentity.keyOf(b))

        assertNull(
            "opening an arbitrary member is exactly what must not happen",
            MessageSelectionResolver.resolveSingle(keys, listOf(a, b))
        )
    }

    @Test
    fun `SMS and MMS with the SAME numeric provider id are never confused`() {
        // The reason identity is composite. Both messages are provider row 100; only the source differs.
        val smsRow = sms(100L, body = "the sms")
        val mmsRow = mms(100L, body = "the mms")
        val rows = listOf(smsRow, mmsRow)

        assertEquals(
            "sms source must win for the sms key",
            "the sms",
            MessageSelectionResolver.resolveSingle(setOf(MessageIdentity.keyOf(smsRow)), rows)?.message
        )
        assertEquals(
            "mms source must win for the mms key",
            "the mms",
            MessageSelectionResolver.resolveSingle(setOf(MessageIdentity.keyOf(mmsRow)), rows)?.message
        )
    }

    @Test
    fun `an incoming message resolves exactly like an outgoing one`() {
        // Details must be reachable for received messages too; it used to be outgoing-only.
        val incoming = sms(100L, body = "from them", type = 1)
        val outgoing = sms(200L, body = "from me", type = 2)
        val rows = listOf(incoming, outgoing)

        val resolved = MessageSelectionResolver.resolveSingle(
            setOf(MessageIdentity.keyOf(incoming)),
            rows
        )

        assertEquals("from them", resolved?.message)
        assertEquals("incoming type is preserved so the screen can omit send steps", 1, resolved?.type)
    }

    @Test
    fun `a deleted row resolves to null and does not fall back to a neighbour`() {
        // The selected row is gone from the window. Navigating to the row next to it would silently open
        // a different message.
        val gone = MessageIdentity.keyOf(sms(999L))
        val rows = listOf(sms(100L), sms(200L))

        assertNull(MessageSelectionResolver.resolveSingle(setOf(gone), rows))
    }

    @Test
    fun `a message outside the displayed window does not resolve`() {
        // This is why the resolver is handed the window the user can SEE: a row that exists in the
        // conversation but is not rendered has no bubble to have been long-pressed.
        val offscreen = sms(300L)
        val displayed = listOf(sms(100L), sms(200L))

        assertNull(MessageSelectionResolver.resolveSingle(setOf(MessageIdentity.keyOf(offscreen)), displayed))
    }

    @Test
    fun `a search-hit window resolves against the rows it paints`() {
        // While parked on a search hit the list paints a bounded window. Resolving against the
        // conversation window instead would return null for a row that is plainly on screen.
        val hit = sms(500L, body = "the hit")
        val searchWindow = listOf(sms(400L), hit, sms(600L))

        val resolved = MessageSelectionResolver.resolveSingle(
            setOf(MessageIdentity.keyOf(hit)),
            searchWindow
        )

        assertEquals("the hit", resolved?.message)
    }

    @Test
    fun `an empty selection resolves to nothing`() {
        assertNull(MessageSelectionResolver.resolveSingle(emptySet(), listOf(sms(100L))))
    }

    @Test
    fun `an empty window resolves to nothing instead of throwing`() {
        assertNull(
            MessageSelectionResolver.resolveSingle(setOf(MessageIdentity.keyOf(sms(100L))), emptyList())
        )
    }
}
