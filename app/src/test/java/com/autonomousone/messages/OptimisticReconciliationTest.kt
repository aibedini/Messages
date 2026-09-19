package com.autonomousone.messages

import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.repository.ConversationWindow
import com.autonomousone.messages.repository.MessageIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 v3.4.3 — the "ghost optimistic bubble" half of the bug.
 *
 * An optimistic send carries a synthetic timestamp id, so its composite
 * (source, providerId) identity can never match the real Telephony row.
 * [ConversationWindow.mergeRoomTail] keeps every visible identity the Room tail
 * does not mention — that is what protects older pages — so the synthetic row
 * survived forever and painted a permanent clock bubble beside the confirmed
 * one.
 *
 * These are pure window tests; the ViewModel's reconciliation delegates here.
 */
class OptimisticReconciliationTest {

    private fun sms(
        id: Long,
        date: Long = id,
        body: String = "m" + id,
        type: Int = 2,
        status: Int = 32
    ) = Sms(
        id = id,
        threadId = 7L,
        sender = "+98912",
        message = body,
        date = date,
        unread = false,
        type = type,
        status = status
    )

    // ── 6. immediateSend_oneVisibleBubble ───────────────────────────────────
    @Test
    fun `an immediate send promotes the optimistic bubble onto the provider identity`() {
        val optimistic = sms(id = 1_000L, date = 1_000L, body = "hi")

        val promoted = ConversationWindow.reconcileOwnOptimistic(
            visible = listOf(optimistic),
            optimisticId = 1_000L,
            providerRowId = 500L,
            fallbackDate = 1_000L
        )

        assertEquals(listOf(500L), promoted.map { it.id })
        assertEquals(MessageIdentity.Key("sms", 500L), MessageIdentity.keyOf(promoted.single().id))

        // The Room tail for the SAME identity enriches, never doubles.
        val merged = ConversationWindow.mergeRoomTail(
            visible = promoted,
            roomTail = listOf(sms(500L, 1_000L, "hi", status = 0))
        )
        assertEquals(1, merged.size)
        assertEquals(0, merged.single().status)
    }

    // ── 9. RoomTailDoesNotKeepConfirmedGhost ────────────────────────────────
    @Test
    fun `a confirmed send leaves no synthetic ghost behind`() {
        val optimistic = sms(id = 111L, date = 111L, body = "ghost")
        val promoted = ConversationWindow.reconcileOwnOptimistic(listOf(optimistic), 111L, 700L, 111L)

        val merged = ConversationWindow.mergeRoomTail(
            visible = promoted,
            roomTail = listOf(sms(700L, 111L, "ghost", status = 0))
        )

        assertEquals(listOf(700L), merged.map { it.id })
        assertTrue("the synthetic id must be gone", merged.none { it.id == 111L })
    }

    @Test
    fun `when the live outgoing event won the race the optimistic row is dropped`() {
        val optimistic = sms(id = 222L, date = 222L, body = "hi")
        val alreadyReal = sms(id = 800L, date = 222L, body = "hi")

        val reconciled = ConversationWindow.reconcileOwnOptimistic(
            visible = listOf(optimistic, alreadyReal),
            optimisticId = 222L,
            providerRowId = 800L,
            fallbackDate = 222L
        )

        assertEquals(listOf(800L), reconciled.map { it.id })
    }

    @Test
    fun `a refused dispatch drops the synthetic row instead of orphaning it`() {
        val optimistic = sms(id = 333L, date = 333L, body = "refused")
        val reconciled = ConversationWindow.reconcileOwnOptimistic(
            visible = listOf(optimistic),
            optimisticId = 333L,
            providerRowId = null,
            fallbackDate = 333L
        )
        assertTrue(reconciled.isEmpty())
    }

    // ── 7. sameTextTwice_isTwoMessages ──────────────────────────────────────
    @Test
    fun `the same text sent twice stays two messages`() {
        val first = sms(id = 1_000L, date = 1_000L, body = "hello")
        val second = sms(id = 1_001L, date = 1_001L, body = "hello")

        val afterFirst = ConversationWindow.reconcileOwnOptimistic(
            listOf(first, second), first.id, 500L, first.date
        )
        val afterBoth = ConversationWindow.reconcileOwnOptimistic(
            afterFirst, second.id, 501L, second.date
        )

        assertEquals(listOf(500L, 501L), afterBoth.map { it.id })

        // Room confirms both: still two, because identity — not body + time — wins.
        val merged = ConversationWindow.mergeRoomTail(
            visible = afterBoth,
            roomTail = listOf(sms(500L, 1_000L, "hello"), sms(501L, 1_001L, "hello"))
        )
        assertEquals(2, merged.size)
        assertEquals(2, merged.map { MessageIdentity.keyOf(it.id) }.distinct().size)
    }

    // ── 5. delayOn_onePendingBubble ─────────────────────────────────────────
    @Test
    fun `a held send leaves exactly one pending bubble`() {
        val genericOptimistic = sms(id = 4_000L, date = 4_000L, body = "held")
        val ledgerBubble = sms(id = 5_555L, date = 9_000L, body = "held", status = STATUS_DELAYED_PENDING)

        // The ViewModel drops the generic bubble; the durable ledger row is the
        // only pending representation on screen.
        val afterHold = ConversationWindow.removeRow(listOf(genericOptimistic), genericOptimistic.id)
        assertTrue(afterHold.isEmpty())

        val painted = ConversationWindow.mergeRoomTail(
            visible = afterHold,
            roomTail = emptyList(),
            optimistic = listOf(ledgerBubble)
        )
        assertEquals(1, painted.size)
        assertEquals(1, painted.count { it.status == STATUS_DELAYED_PENDING })
    }

    // ── 8. externalOutgoingStillAppears ─────────────────────────────────────
    @Test
    fun `an outgoing event with no local optimistic row maps to its provider identity`() {
        val eventId = MessageIdentity.outgoingEventId(providerRowId = 600L, fallbackDate = 1_234L)
        assertEquals(600L, eventId)

        val appended = sms(eventId, 1_234L, "external")
        // appendLiveMessage's duplicate rule is exactly this predicate.
        val duplicateOnSecondEvent = listOf(appended).any {
            ConversationWindow.identity(it.id) == ConversationWindow.identity(eventId)
        }
        assertTrue("a repeated event must dedupe on identity", duplicateOnSecondEvent)

        val merged = ConversationWindow.mergeRoomTail(
            visible = listOf(appended),
            roomTail = listOf(sms(600L, 1_234L, "external", status = 0))
        )
        assertEquals(1, merged.size)
    }

    @Test
    fun `removing an absent row is a no-op`() {
        val rows = listOf(sms(1L), sms(2L))
        assertEquals(rows, ConversationWindow.removeRow(rows, 99L))
    }

    private companion object {
        /** Mirrors ConversationViewModel.STATUS_DELAYED_PENDING. */
        const val STATUS_DELAYED_PENDING = 999
    }
}
