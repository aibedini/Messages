package com.autonomousone.messages

import com.autonomousone.messages.security.AskPolicyLedger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-006 §11/§16 — per-message ASK verdict contract (pure decision logic).
 *
 * Invariant under test: an UNANSWERED prompt keeps the message local forever;
 * only an explicit "Sync once" grants sync for that exact message; deny is
 * durable. Fail-closed on every default. The SharedPreferences-backed store
 * is exercised on-device; the boundary (no outbox row for unresolved ASK) is
 * pinned in SyncEligibilityTest.
 */
class AskPolicyLedgerTest {

    @Test fun `unanswered prompt is never sync allowed`() {
        // null = no verdict recorded (notification ignored / swiped away)
        assertFalse(AskPolicyLedger.resolveAskVerdict(null))
    }

    @Test fun `explicit sync once flips only that message`() {
        assertTrue(AskPolicyLedger.resolveAskVerdict(true))
    }

    @Test fun `keep local is durable and idempotent`() {
        // User answered "Keep private" → false, re-answering stays false.
        assertFalse(AskPolicyLedger.resolveAskVerdict(false))
        assertFalse(AskPolicyLedger.resolveAskVerdict(false))
    }
}
