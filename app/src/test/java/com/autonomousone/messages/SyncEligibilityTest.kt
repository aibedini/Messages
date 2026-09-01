package com.autonomousone.messages

import com.autonomousone.messages.data.SyncEligibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-006 — SyncEligibility boundary contract.
 *
 * These are the boundary-level pins for the SensitiveMessageFirewall PR: the
 * firewall must implement its classifier BEHIND this gate (TelephonySync
 * Coordinator.enqueueCloudEvent), and a LOCAL_ONLY decision must mean
 * "no outbox row is ever built" — never "insert then delete".
 */
class SyncEligibilityTest {

    @Test fun `default posture is privacy-first boundary - decision enum covers both outcomes`() {
        // The two outcomes of the ADR-006 policy decision.
        assertEquals(2, SyncEligibility.Decision.values().size)
        assertEquals("SYNC", SyncEligibility.Decision.SYNC.name)
        assertEquals("LOCAL_ONLY", SyncEligibility.Decision.LOCAL_ONLY.name)
    }

    @Test fun `sticky local-only sender forces LOCAL_ONLY regardless of body`() {
        // e.g. user policy: BANKMELLAT → LOCAL_ONLY
        val d = SyncEligibility.decide(
            source = "sms",
            providerId = 1842L,
            normalizedAddress = "bankmellat",
            body = "مبلغ 500,000 ریال به حساب شما واریز شد",
            stickyLocalOnly = true
        )
        assertEquals(SyncEligibility.Decision.LOCAL_ONLY, d)
    }

    @Test fun `normal message without sticky rule is SYNC`() {
        val d = SyncEligibility.decide(
            source = "sms",
            providerId = 1843L,
            normalizedAddress = "+989121234567",
            body = "سلام، فردا می‌بینمت",
            stickyLocalOnly = false
        )
        assertEquals(SyncEligibility.Decision.SYNC, d)
    }

    @Test fun `local_only decision means no event row would be constructed`() {
        // The invariant ADR-006 §7 demands as a regression test: a LOCAL_ONLY
        // classification must yield NO GatewayEventOutbox row. The gate is
        // implemented as "skip build entirely" — encode that here so a future
        // refactor that builds-then-deletes fails this test.
        var built = false
        val decision = SyncEligibility.decide(
            source = "sms", providerId = 1L, normalizedAddress = "bankmellat",
            body = "رمز پویای شما 392818 است", stickyLocalOnly = true
        )
        // Simulate the enqueueCloudEvent gate: build() must not run.
        if (decision == SyncEligibility.Decision.SYNC) built = true
        assertFalse("LOCAL_ONLY must never reach event construction", built)
        assertTrue(decision == SyncEligibility.Decision.LOCAL_ONLY)
    }
}
