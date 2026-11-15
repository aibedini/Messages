package com.autonomousone.messages.ui.conversation

import com.autonomousone.messages.messaging.QuickReply
import com.autonomousone.messages.model.Sms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The visibility rules of the suggestion slot (XR-GA-017), asserted as a pure function.
 *
 * These are the rules a user perceives as "the cards are gone":
 * a newer inbound message, an expired batch, a LOCAL_ONLY conversation, or nothing delivered at all.
 * Every one of them is a value here, so none of them depends on a Compose tree being mounted.
 */
class SuggestionSlotPolicyTest {

    private val now = 1_800_000_000_000L

    private fun card(
        id: String = "sg_1",
        text: String = "باشه",
        badge: SuggestionBadge = SuggestionBadge.KNOWLEDGE,
        anchor: String = "msg-1",
        expiresAtMs: Long = now + 60_000L,
        receivedAtMs: Long = now - 1_000L
    ) = SuggestionCardUi(
        id = id,
        text = text,
        badge = badge,
        anchorMessageId = anchor,
        expiresAtMs = expiresAtMs,
        receivedAtMs = receivedAtMs,
        generation = 7,
        segments = 1
    )

    private fun quick(text: String = "Done ✅") = QuickReply("/c1", text)

    private fun resolve(
        cards: List<SuggestionCardUi> = listOf(card()),
        newestInboundMessageId: String? = "msg-1",
        threadLocalOnly: Boolean = false,
        expiresNow: Long = now,
        quickReplies: List<QuickReply> = listOf(quick()),
        composerEmpty: Boolean = true
    ) = SuggestionSlotPolicy.resolve(
        cards = cards,
        newestInboundMessageId = newestInboundMessageId,
        threadLocalOnly = threadLocalOnly,
        nowMs = expiresNow,
        quickReplies = quickReplies,
        composerEmpty = composerEmpty
    )

    // ── cards ────────────────────────────────────────────────────────────────

    @Test
    fun `live cards anchored at the newest inbound message are visible`() {
        val state = resolve(cards = listOf(card(id = "sg_1"), card(id = "sg_2")))

        assertTrue(state is SuggestionSlotState.Cards)
        assertEquals(listOf("sg_1", "sg_2"), (state as SuggestionSlotState.Cards).cards.map { it.id })
    }

    @Test
    fun `at most three cards are rendered`() {
        val four = (1..4).map { card(id = "sg_$it") }

        val state = resolve(cards = four) as SuggestionSlotState.Cards

        assertEquals(SuggestionSlotPolicy.MAX_CARDS, state.cards.size)
        assertEquals(listOf("sg_1", "sg_2", "sg_3"), state.cards.map { it.id })
    }

    @Test
    fun `a newer inbound message hides the cards`() {
        val state = resolve(cards = listOf(card(anchor = "msg-1")), newestInboundMessageId = "msg-2")

        // Falls back to the local quick replies, never to a stale AI answer.
        assertTrue(state is SuggestionSlotState.QuickReplies)
    }

    @Test
    fun `a newer inbound message hides the cards even when there is no fallback`() {
        val state = resolve(
            cards = listOf(card(anchor = "msg-1")),
            newestInboundMessageId = "msg-2",
            quickReplies = emptyList()
        )

        assertEquals(SuggestionSlotState.Hidden, state)
    }

    @Test
    fun `an expired batch is hidden`() {
        val state = resolve(cards = listOf(card(expiresAtMs = now - 1L)))

        assertTrue(state is SuggestionSlotState.QuickReplies)
    }

    @Test
    fun `a batch expiring exactly now is hidden`() {
        val state = resolve(
            cards = listOf(card(expiresAtMs = now)),
            quickReplies = emptyList()
        )

        assertEquals(SuggestionSlotState.Hidden, state)
    }

    @Test
    fun `cards stay visible while the composer already has text`() {
        val state = resolve(cards = listOf(card()), composerEmpty = false)

        assertTrue(
            "after tapping a card the user may want to pick another",
            state is SuggestionSlotState.Cards
        )
    }

    @Test
    fun `a thread with no inbound message shows nothing`() {
        val state = resolve(cards = listOf(card()), newestInboundMessageId = null, quickReplies = emptyList())

        assertEquals(SuggestionSlotState.Hidden, state)
    }

    // ── LOCAL_ONLY ───────────────────────────────────────────────────────────

    @Test
    fun `a LOCAL_ONLY thread never shows a card`() {
        val state = resolve(threadLocalOnly = true)

        assertEquals(SuggestionSlotState.Hidden, state)
    }

    @Test
    fun `a LOCAL_ONLY thread does not fall back to quick replies either`() {
        val state = resolve(threadLocalOnly = true, quickReplies = listOf(quick()))

        assertEquals(SuggestionSlotState.Hidden, state)
    }

    // ── fallback ─────────────────────────────────────────────────────────────

    @Test
    fun `no delivered cards falls back to the local quick replies`() {
        val state = resolve(cards = emptyList(), quickReplies = listOf(quick("Done ✅"), quick("Later")))

        assertEquals(
            listOf("/c1", "/c1"),
            (state as SuggestionSlotState.QuickReplies).replies.map { it.shortcut }
        )
    }

    @Test
    fun `the fallback is capped`() {
        val replies = (1..5).map { QuickReply("/c$it", "reply $it") }

        val state = resolve(cards = emptyList(), quickReplies = replies) as SuggestionSlotState.QuickReplies

        assertEquals(SuggestionSlotPolicy.MAX_QUICK_REPLIES, state.replies.size)
    }

    @Test
    fun `the fallback is suppressed while the composer has text`() {
        val state = resolve(cards = emptyList(), composerEmpty = false)

        assertEquals(SuggestionSlotState.Hidden, state)
    }

    @Test
    fun `no cards and no quick replies is the quiet empty state`() {
        val state = resolve(cards = emptyList(), quickReplies = emptyList())

        assertEquals(SuggestionSlotState.Hidden, state)
    }

    // ── badge mapping ────────────────────────────────────────────────────────

    @Test
    fun `a knowledge badge is shown only when the sender says knowledge`() {
        assertEquals(SuggestionBadge.KNOWLEDGE, SuggestionBadge.of("approved_template", "knowledge"))
        assertEquals(SuggestionBadge.KNOWLEDGE, SuggestionBadge.of("anything", "knowledge"))
    }

    @Test
    fun `an unknown or missing badge degrades to needs review, never to confidence`() {
        assertEquals(SuggestionBadge.NEEDS_REVIEW, SuggestionBadge.of("generated", "needs_review"))
        assertEquals(SuggestionBadge.NEEDS_REVIEW, SuggestionBadge.of("generated", ""))
        assertEquals(SuggestionBadge.NEEDS_REVIEW, SuggestionBadge.of("", "brand_new"))
        assertEquals(SuggestionBadge.NEEDS_REVIEW, SuggestionBadge.of("brand_new_kind", ""))
        // The KIND is a semantic claim, not decoration: an approved template with no badge field is
        // still an approved template and earns the knowledge badge.
        assertEquals(SuggestionBadge.KNOWLEDGE, SuggestionBadge.of("approved_template", ""))
    }

    // ── placement ────────────────────────────────────────────────────────────

    private fun sms(id: Long, date: Long): Sms = Sms(
        id = id,
        threadId = 9L,
        sender = "+98912",
        message = "body",
        date = date,
        unread = false,
        type = 1
    )

    @Test
    fun `the slot is inserted directly under its anchor message`() {
        val newest = sms(id = 2L, date = 2_000L)
        val older = sms(id = 1L, date = 1_000L)
        // Reverse-layout data order: newest first.
        val items = listOf(ChatListItem.MessageItem(newest), ChatListItem.MessageItem(older))
        val anchorId = suggestionAnchorId(newest)

        val withSlot = withSuggestionSlot(items, SuggestionSlotState.Cards(listOf(card(anchor = anchorId))), anchorId)

        assertEquals(3, withSlot.size)
        assertTrue(
            "index 0 paints at the visual bottom, so the slot must sit at the anchor's index",
            withSlot[0] is ChatListItem.SuggestionRow
        )
        assertSame(newest, (withSlot[1] as ChatListItem.MessageItem).sms)
        assertSame(older, (withSlot[2] as ChatListItem.MessageItem).sms)
    }

    @Test
    fun `the slot does not reorder or mutate messages`() {
        val newest = sms(id = 2L, date = 2_000L)
        val older = sms(id = 1L, date = 1_000L)
        val items = listOf(ChatListItem.MessageItem(newest), ChatListItem.MessageItem(older))
        val anchorId = suggestionAnchorId(older)

        val withSlot = withSuggestionSlot(items, SuggestionSlotState.Cards(listOf(card(anchor = anchorId))), anchorId)

        assertEquals(listOf(newest, older), withSlot.filterIsInstance<ChatListItem.MessageItem>().map { it.sms })
        assertTrue("the slot sits between the anchor and the message below it", withSlot[1] is ChatListItem.SuggestionRow)
    }

    @Test
    fun `nothing is inserted when the slot is hidden`() {
        val items = listOf(ChatListItem.MessageItem(sms(id = 1L, date = 1_000L)))

        val withSlot = withSuggestionSlot(items, SuggestionSlotState.Hidden, "anchor")

        assertSame(items, withSlot)
    }

    @Test
    fun `nothing is inserted when the anchor is not in the loaded window`() {
        val items = listOf(ChatListItem.MessageItem(sms(id = 1L, date = 1_000L)))

        val withSlot = withSuggestionSlot(
            items,
            SuggestionSlotState.Cards(listOf(card(anchor = "not-loaded"))),
            "not-loaded"
        )

        assertSame(items, withSlot)
    }

    @Test
    fun `the anchor of a card slot is the card batch anchor`() {
        val state = SuggestionSlotState.Cards(listOf(card(anchor = "msg-9")))

        assertEquals("msg-9", SuggestionSlotPolicy.anchorOf(state, newestInboundMessageId = "msg-1"))
    }

    @Test
    fun `the anchor of a quick-reply slot is the newest inbound message`() {
        val state = SuggestionSlotState.QuickReplies(listOf(quick()))

        assertEquals("msg-1", SuggestionSlotPolicy.anchorOf(state, newestInboundMessageId = "msg-1"))
    }

    @Test
    fun `a hidden slot has no anchor`() {
        assertEquals(null, SuggestionSlotPolicy.anchorOf(SuggestionSlotState.Hidden, "msg-1"))
    }

    // ── the composer contract ────────────────────────────────────────────────

    @Test
    fun `inserting a card is the identity on its text`() {
        val value = card(text = "  سلام، الان می‌بینم  ")

        assertEquals(value.text, value.composerDraft())
    }

    @Test
    fun `a card with no text can never be inserted`() {
        // The parser refuses blank text, so this is the last line of defence for a hand-built card.
        assertEquals("", card(text = "").composerDraft())
    }
}
