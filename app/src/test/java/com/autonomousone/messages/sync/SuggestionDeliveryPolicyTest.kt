package com.autonomousone.messages.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three rules that decide whether a delivered suggestion may become a card (XR-GA-017):
 * expiry, generation and the anchor message.
 *
 * The anchor rule is asserted most heavily because it is the one that protects the user from a stale
 * AI answer: a payload built from an older view of the conversation must never be rendered as if it
 * answered the newest message.
 */
class SuggestionDeliveryPolicyTest {

    private val anchor = SuggestionDeliveryPolicy.Anchor(
        canonicalId = "msg-newest",
        aliases = listOf("sms:42")
    )
    private val previous = SuggestionDeliveryPolicy.Anchor(
        canonicalId = "msg-previous",
        aliases = listOf("sms:41")
    )
    private val now = 1_800_000_000_000L

    private fun decide(
        generation: Int = 112,
        currentGeneration: Int = 112,
        expiresAtMs: Long = now + 60_000L,
        forMessageRef: String = anchor.canonicalId,
        anchors: List<SuggestionDeliveryPolicy.Anchor> = listOf(anchor, previous)
    ) = SuggestionDeliveryPolicy.decide(
        commandGeneration = generation,
        currentGeneration = currentGeneration,
        expiresAtMs = expiresAtMs,
        nowMs = now,
        forMessageRef = forMessageRef,
        anchors = anchors
    )

    private fun accepted(outcome: SuggestionDeliveryPolicy.Outcome): String {
        assertTrue("expected acceptance, got $outcome", outcome is SuggestionDeliveryPolicy.Outcome.Accept)
        return (outcome as SuggestionDeliveryPolicy.Outcome.Accept).anchorMessageId
    }

    private fun rejected(
        outcome: SuggestionDeliveryPolicy.Outcome
    ): SuggestionDeliveryPolicy.Outcome.Reject {
        assertTrue("expected rejection, got $outcome", outcome is SuggestionDeliveryPolicy.Outcome.Reject)
        return outcome as SuggestionDeliveryPolicy.Outcome.Reject
    }

    // ── fresh payloads ───────────────────────────────────────────────────────

    @Test
    fun `the newest anchor with a matching generation and a live expiry is accepted`() {
        assertEquals(anchor.canonicalId, accepted(decide()))
    }

    @Test
    fun `a generation AHEAD of this device is accepted`() {
        // A device whose mirror is behind must never reject a fresh suggestion: the comparison is
        // one-directional on purpose.
        assertEquals(anchor.canonicalId, accepted(decide(generation = 120, currentGeneration = 112)))
    }

    @Test
    fun `the provider-style composite alias resolves to the same anchor`() {
        assertEquals(anchor.canonicalId, accepted(decide(forMessageRef = "sms:42")))
    }

    // ── expired ──────────────────────────────────────────────────────────────

    @Test
    fun `an expiry in the past is refused`() {
        val rejected = rejected(decide(expiresAtMs = now - 1L))

        assertEquals(SyncErrorCode.SUGGESTIONS_EXPIRED, rejected.code)
        assertEquals("expired", rejected.detail)
    }

    @Test
    fun `an expiry exactly at now is refused`() {
        assertEquals(
            SyncErrorCode.SUGGESTIONS_EXPIRED,
            rejected(decide(expiresAtMs = now)).code
        )
    }

    // ── stale generation ─────────────────────────────────────────────────────

    @Test
    fun `an older generation is refused as stale`() {
        val rejected = rejected(decide(generation = 111, currentGeneration = 112))

        assertEquals(SyncErrorCode.SUGGESTIONS_STALE, rejected.code)
        assertEquals("generation", rejected.detail)
    }

    @Test
    fun `one more inbound message is enough to make the previous generation stale`() {
        assertEquals(
            SyncErrorCode.SUGGESTIONS_STALE,
            rejected(decide(generation = 112, currentGeneration = 113)).code
        )
    }

    // ── anchor ───────────────────────────────────────────────────────────────

    @Test
    fun `an anchor that is no longer the newest inbound message is refused`() {
        val rejected = rejected(decide(forMessageRef = previous.canonicalId))

        assertEquals(SyncErrorCode.SUGGESTIONS_STALE, rejected.code)
        assertEquals("anchor_superseded", rejected.detail)
    }

    @Test
    fun `an anchor this device does not have is refused as unknown`() {
        val rejected = rejected(decide(forMessageRef = "msg-that-never-arrived"))

        assertEquals(SyncErrorCode.SUGGESTIONS_UNKNOWN_MESSAGE, rejected.code)
    }

    @Test
    fun `a thread with no inbound message at all cannot deliver a card`() {
        val rejected = rejected(decide(anchors = emptyList()))

        assertEquals(SyncErrorCode.SUGGESTIONS_UNKNOWN_MESSAGE, rejected.code)
    }

    @Test
    fun `expiry is decided before an unknown anchor so the reason matches the payload`() {
        // Both are wrong; expiry is absolute and cheaper to state, and GMweb can act on it without
        // re-reading the conversation.
        assertEquals(
            SyncErrorCode.SUGGESTIONS_EXPIRED,
            rejected(decide(expiresAtMs = now - 5L, forMessageRef = "missing")).code
        )
    }

    // ── the same rules as the UI applies ─────────────────────────────────────

    @Test
    fun `showable requires the newest anchor and a live expiry`() {
        assertTrue(
            SuggestionDeliveryPolicy.isShowable(
                anchorMessageId = "msg-newest",
                newestInboundMessageId = "msg-newest",
                expiresAtMs = now + 1L,
                nowMs = now
            )
        )
        assertFalse(
            "a newer inbound message hides the card",
            SuggestionDeliveryPolicy.isShowable(
                anchorMessageId = "msg-newest",
                newestInboundMessageId = "msg-newer",
                expiresAtMs = now + 1L,
                nowMs = now
            )
        )
        assertFalse(
            "an expired card is hidden",
            SuggestionDeliveryPolicy.isShowable(
                anchorMessageId = "msg-newest",
                newestInboundMessageId = "msg-newest",
                expiresAtMs = now,
                nowMs = now
            )
        )
        assertFalse(
            "a thread with no inbound message shows nothing",
            SuggestionDeliveryPolicy.isShowable(
                anchorMessageId = "msg-newest",
                newestInboundMessageId = null,
                expiresAtMs = now + 1L,
                nowMs = now
            )
        )
    }
}
