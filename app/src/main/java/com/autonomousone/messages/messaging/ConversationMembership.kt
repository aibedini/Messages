package com.autonomousone.messages.messaging

import com.autonomousone.messages.repository.ContactRepository

/**
 * Does a live event belong to the conversation this ViewModel is showing?
 *
 * **This is a different question from visibility, and it must be answered FIRST.**
 *
 * The regression this exists to kill: `observeIncomingSms()` only asked "is this conversation
 * visible?" and, when the answer was no, appended the message anyway ("so the bubble still appears").
 * A live SMS for conversation B therefore landed inside conversation A — the rendered list, the
 * animation and `ThreadMessageCache` were all poisoned by a foreign thread, and the rows only
 * disappeared on the next reload because the durable provider/Room data was never wrong.
 *
 * Order of decisions, always:
 *
 * ```text
 * 1. MEMBERSHIP  — belongs to this conversation?   no  -> drop the event entirely
 *                                                  yes -> 2
 * 2. VISIBILITY  — is that conversation RESUMED?   yes -> append + read authority applies
 *                                                  no  -> append, preserve unread, no read write
 * ```
 *
 * Pure and therefore testable: no Android, no Context, no ViewModel state.
 */
object ConversationMembership {

    /**
     * RULE 1 — two authoritative thread ids decide alone.
     *
     * Telephony thread ids are the canonical conversation identity in this app, so when both sides
     * have one they are compared directly and **nothing else may override the answer** — not a
     * normalized phone, not a suffix match, not a display name. That matters for branded/short-code
     * senders, where an address comparison is exactly the thing that goes wrong.
     *
     * RULE 2 — a bounded address fallback, only when a thread id is genuinely unavailable.
     *
     * If either side has no thread id (`0`), the project's canonical same-conversation comparison is
     * used. It is deliberately conservative: blank input never matches, and a suffix match requires
     * real signal (≥ 7 characters), so a short code or an alphanumeric sender id cannot accidentally
     * equal a phone number.
     *
     * RULE 3 — an unknown identity matches nothing.
     */
    fun belongs(
        currentThreadId: Long,
        currentAddress: String?,
        incomingThreadId: Long,
        incomingAddress: String?
    ): Boolean {
        val currentKnown = currentThreadId > 0L
        val incomingKnown = incomingThreadId > 0L

        // RULE 1: both authoritative — the ids decide, and a disagreement is final.
        if (currentKnown && incomingKnown) return incomingThreadId == currentThreadId

        // RULE 3: nothing to compare with.
        val current = currentAddress?.trim().orEmpty()
        val incoming = incomingAddress?.trim().orEmpty()
        if (current.isBlank() || incoming.isBlank()) return false

        // RULE 2a: the SAME address is the same conversation, whatever kind of sender it is.
        //
        // This step is what makes short codes, branded senders ("PARSIANBANK") and alphanumeric ids
        // work at all: the canonical phone comparator below is digit-oriented, so a letter-only sender
        // normalises to nothing and would never match even itself. Equality of the full address string
        // is strong evidence and cannot weaken the cross-thread guarantee.
        if (current.equals(incoming, ignoreCase = true)) return true

        // RULE 2b: differing spellings of the same number (local vs E.164, separators) — the project's
        // canonical, deliberately conservative comparator.
        return ContactRepository.sameConversation(incoming, current)
    }

    /** Convenience for the append guard: a foreign, known thread may never enter this conversation. */
    fun isForeignThread(currentThreadId: Long, rowThreadId: Long): Boolean =
        currentThreadId > 0L && rowThreadId > 0L && rowThreadId != currentThreadId
}
