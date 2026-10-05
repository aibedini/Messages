package com.autonomousone.messages.messaging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-conversation live-message membership.
 *
 * Physically reproduced regression: with conversation A open, an SMS for conversation B appeared
 * INSIDE A (and in A's ThreadMessageCache) until the conversation was reopened. The cause was that the
 * live collector had no membership gate at all — it only asked whether the conversation was visible,
 * and on "no" it appended the foreign message anyway.
 *
 * These tests pin the decision that must run first. They are pure (no Android), which is why the rule
 * lives in its own object instead of inside the ViewModel.
 */
class ConversationMembershipTest {

    // ── RULE 1: two authoritative thread ids decide alone ────────────────────

    @Test
    fun `the same thread belongs`() {
        assertTrue(ConversationMembership.belongs(100L, "+989120000001", 100L, "+989120000001"))
    }

    @Test
    fun `a different thread does not belong — the reproduced incident`() {
        // Conversation A (thread 100) open, SMS for B (thread 200) arrives.
        assertFalse(ConversationMembership.belongs(100L, "+989120000001", 200L, "+989120000002"))
    }

    @Test
    fun `a different thread never belongs even when the addresses normalize equal`() {
        // Exactly the trap a phone-number comparison falls into: two authoritative thread ids that
        // disagree must win over any address resemblance.
        assertFalse(
            ConversationMembership.belongs(
                currentThreadId = 100L,
                currentAddress = "09120000001",
                incomingThreadId = 200L,
                incomingAddress = "+989120000001"
            )
        )
    }

    @Test
    fun `ten messages across five conversations only match their own thread`() {
        val openThread = 100L
        val burst = listOf(100L, 200L, 300L, 400L, 500L).flatMap { thread -> listOf(thread, thread) }

        val matched = burst.filter { ConversationMembership.belongs(openThread, null, it, null) }

        assertTrue("only A's own messages may match", matched.all { it == openThread })
        assertTrue(matched.size == 2)
    }

    // ── RULE 2: bounded fallback only when an id is unavailable ─────────────

    @Test
    fun `current thread unknown but the address matches exactly — belongs`() {
        assertTrue(ConversationMembership.belongs(0L, "09120000001", 0L, "09120000001"))
    }

    @Test
    fun `current thread unknown and a different address — does not belong`() {
        assertFalse(ConversationMembership.belongs(0L, "09120000001", 0L, "09350000009"))
    }

    @Test
    fun `an incoming row without a thread id still matches its own address`() {
        // Legacy rows can carry threadId 0; the row is still this conversation's message.
        assertTrue(ConversationMembership.belongs(100L, "+989120000001", 0L, "+989120000001"))
    }

    @Test
    fun `an alphanumeric sender is compared as an address, never as digits`() {
        // Short/branded senders have no subscriber digits; a digits-only comparison would collapse
        // unrelated senders into each other.
        assertFalse(ConversationMembership.belongs(0L, "Co5-p91", 0L, "PARSIANBANK"))
        assertTrue(ConversationMembership.belongs(0L, "PARSIANBANK", 0L, "PARSIANBANK"))
    }

    @Test
    fun `branded senders stay independent when both thread ids are known`() {
        assertFalse(ConversationMembership.belongs(11L, "PARSIANBANK", 12L, "ResalatBank"))
    }

    // ── RULE 3: unknown identity matches nothing ────────────────────────────

    @Test
    fun `blank identities never match an arbitrary open conversation`() {
        assertFalse(ConversationMembership.belongs(0L, "", 0L, ""))
        assertFalse(ConversationMembership.belongs(0L, "09120000001", 0L, ""))
        assertFalse(ConversationMembership.belongs(0L, "   ", 0L, "09120000001"))
        assertFalse(ConversationMembership.belongs(0L, null, 0L, null))
    }

    @Test
    fun `an unknown thread id cannot be matched by a stale address`() {
        // The ViewModel has no identity at all yet; a message must not be adopted by it.
        assertFalse(ConversationMembership.belongs(0L, null, 200L, "+989120000002"))
    }

    @Test
    fun `a short code does not suffix-match a phone number`() {
        // 5-digit short code vs a full number: the canonical comparator requires real signal.
        assertFalse(ConversationMembership.belongs(0L, "10005", 0L, "091200010005"))
    }

    // ── the append guard ────────────────────────────────────────────────────

    @Test
    fun `the append guard rejects only foreign known threads`() {
        assertTrue(ConversationMembership.isForeignThread(100L, 200L))
        assertFalse(ConversationMembership.isForeignThread(100L, 100L))
        assertFalse("an unknown row thread cannot be judged foreign", ConversationMembership.isForeignThread(100L, 0L))
        assertFalse("an unknown current thread cannot judge anything", ConversationMembership.isForeignThread(0L, 200L))
    }
}
