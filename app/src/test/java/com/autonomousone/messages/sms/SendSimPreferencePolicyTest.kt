package com.autonomousone.messages.sms

import com.autonomousone.messages.sms.SendSimPreferencePolicy.Assertion
import com.autonomousone.messages.sms.SendSimPreferencePolicy.Block
import com.autonomousone.messages.sms.SendSimPreferencePolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sticky-SIM precedence truth table, asserted exhaustively.
 *
 * This is the rule that decides which physical line carries a private message, so it is tested as a
 * total function over its inputs rather than sampled: the two failure modes it exists to prevent —
 * "a pinned conversation silently falls back to the default line" and "a request silently overrides
 * the user's stored choice" — both produce a *plausible* send, and nothing downstream can detect them
 * after the fact.
 *
 * ## The distinction under test
 *
 * ```text
 * NO PREFERENCE              => default allowed
 * PREFERENCE, UNRESOLVED     => send blocked
 * ```
 */
class SendSimPreferencePolicyTest {

    private val refA = "sim:v1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val refB = "sim:v1:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

    private fun decide(
        preferredRef: String? = null,
        preferredAssertion: Assertion? = null,
        preferredRefWellFormed: Boolean = true,
        override: Int? = null,
        assertedRef: String? = null,
        assertedAssertion: Assertion? = null,
        assertedWellFormed: Boolean = true
    ) = SendSimPreferencePolicy.decide(
        conversationPreferredRef = preferredRef,
        preferredAssertion = preferredAssertion,
        preferredRefWellFormed = preferredRefWellFormed,
        oneShotOverride = override,
        assertedRef = assertedRef,
        assertedAssertion = assertedAssertion,
        assertedRefWellFormed = assertedWellFormed
    )

    // ── no preference => default allowed ─────────────────────────────────────

    @Test
    fun `no preference and no assertion uses the platform default`() {
        assertEquals(Decision.UsePlatformDefault, decide())
    }

    @Test
    fun `no preference and no assertion is NOT a block`() {
        // The whole point of the distinction: an un-pinned conversation must keep sending normally.
        val decision = decide()

        assertEquals(false, decision is Decision.Blocked)
    }

    @Test
    fun `a blank preference is treated as no preference`() {
        assertEquals(Decision.UsePlatformDefault, decide(preferredRef = ""))
        assertEquals(Decision.UsePlatformDefault, decide(preferredRef = "   "))
    }

    @Test
    fun `no preference with a resolved assertion sends on the asserted line`() {
        assertEquals(
            Decision.Send(9),
            decide(assertedRef = refA, assertedAssertion = Assertion.Resolved(9))
        )
    }

    @Test
    fun `no preference with a dead assertion is blocked, never silently defaulted`() {
        assertEquals(
            Decision.Blocked(Block.ASSERTED_SIM_UNAVAILABLE),
            decide(assertedRef = refA, assertedAssertion = Assertion.NotActive)
        )
    }

    @Test
    fun `no preference with an unreadable inventory falls back to the default`() {
        // The app cannot disprove the asserted line, and refusing every send because a SIM list was
        // withheld would be worse than the failure it guards.
        assertEquals(
            Decision.UsePlatformDefault,
            decide(assertedRef = refA, assertedAssertion = Assertion.InventoryUnavailable)
        )
    }

    // ── preference exists and resolves ───────────────────────────────────────

    @Test
    fun `a resolvable preference sends on the preferred line`() {
        assertEquals(
            Decision.Send(5),
            decide(preferredRef = refA, preferredAssertion = Assertion.Resolved(5))
        )
    }

    @Test
    fun `a resolvable preference sends on it even with no assertion at all`() {
        // mission §30: the device enforces its own stored preference; it does not wait for GMweb.
        assertEquals(
            Decision.Send(5),
            decide(preferredRef = refA, preferredAssertion = Assertion.Resolved(5))
        )
    }

    @Test
    fun `an assertion naming the same line as the preference is not a conflict`() {
        assertEquals(
            Decision.Send(5),
            decide(
                preferredRef = refA,
                preferredAssertion = Assertion.Resolved(5),
                assertedRef = refA,
                assertedAssertion = Assertion.Resolved(5)
            )
        )
    }

    // ── preference exists but cannot be resolved => BLOCK ────────────────────

    @Test
    fun `a preference whose sim is gone blocks the send`() {
        assertEquals(
            Decision.Blocked(Block.PREFERRED_SIM_UNAVAILABLE),
            decide(preferredRef = refA, preferredAssertion = Assertion.NotActive)
        )
    }

    @Test
    fun `a preference whose sim is gone never falls back to the default line`() {
        val decision = decide(preferredRef = refA, preferredAssertion = Assertion.NotActive)

        assertEquals(false, decision == Decision.UsePlatformDefault)
    }

    @Test
    fun `a preference with an unreadable inventory blocks rather than guessing`() {
        assertEquals(
            Decision.Blocked(Block.PREFERRED_SIM_UNAVAILABLE),
            decide(preferredRef = refA, preferredAssertion = Assertion.InventoryUnavailable)
        )
    }

    @Test
    fun `a preference with no resolution at all blocks rather than guessing`() {
        // The caller failed to attempt resolution. That is not evidence of absence, and it is even
        // less evidence of a valid line, so it must not become a default-line send.
        assertEquals(
            Decision.Blocked(Block.PREFERRED_SIM_UNAVAILABLE),
            decide(preferredRef = refA, preferredAssertion = null)
        )
    }

    @Test
    fun `an unknown but well formed preference is unavailable, not invalid`() {
        // A ref from a previous install is well-formed and simply not this device's. It is reported as
        // unavailable so the user can reselect; INVALID_SIM_REFERENCE is reserved for corruption.
        assertEquals(
            Decision.Blocked(Block.PREFERRED_SIM_UNAVAILABLE),
            decide(preferredRef = refB, preferredAssertion = Assertion.NotActive)
        )
    }

    // ── malformed references ─────────────────────────────────────────────────

    @Test
    fun `a malformed stored preference is reported as invalid`() {
        assertEquals(
            Decision.Blocked(Block.INVALID_SIM_REFERENCE),
            decide(preferredRef = "sim:v1:not-hex", preferredRefWellFormed = false)
        )
    }

    @Test
    fun `a malformed asserted reference is reported as invalid`() {
        assertEquals(
            Decision.Blocked(Block.INVALID_SIM_REFERENCE),
            decide(assertedRef = "sim:v9:zz", assertedWellFormed = false)
        )
    }

    @Test
    fun `a malformed assertion is rejected even when a one-shot override is present`() {
        // A caller that both names a line and sends a corrupt ref is confused; guessing which it meant
        // is how a wrong-line send happens.
        assertEquals(
            Decision.Blocked(Block.INVALID_SIM_REFERENCE),
            decide(override = 7, assertedRef = "sim:v1:bad", assertedWellFormed = false)
        )
    }

    // ── one-shot override ────────────────────────────────────────────────────

    @Test
    fun `an explicit one-shot override wins over a resolvable preference`() {
        assertEquals(
            Decision.Send(9),
            decide(preferredRef = refA, preferredAssertion = Assertion.Resolved(5), override = 9)
        )
    }

    @Test
    fun `an explicit one-shot override wins even when the preference is unavailable`() {
        // The user (or an automation) explicitly named a line for this one message. That is a stronger
        // statement than the pinned conversation, so it is honoured rather than blocked.
        assertEquals(
            Decision.Send(9),
            decide(preferredRef = refA, preferredAssertion = Assertion.NotActive, override = 9)
        )
    }

    @Test
    fun `an override with no preference sends on the override`() {
        assertEquals(Decision.Send(9), decide(override = 9))
    }

    // ── conflict protection (mission §31) ────────────────────────────────────

    @Test
    fun `a request asserting a different line than the preference is a typed conflict`() {
        assertEquals(
            Decision.Blocked(Block.SIM_PREFERENCE_CONFLICT),
            decide(
                preferredRef = refA,
                preferredAssertion = Assertion.Resolved(5),
                assertedRef = refB,
                assertedAssertion = Assertion.Resolved(9)
            )
        )
    }

    @Test
    fun `a conflict is never resolved by silently picking one side`() {
        val decision = decide(
            preferredRef = refA,
            preferredAssertion = Assertion.Resolved(5),
            assertedRef = refB,
            assertedAssertion = Assertion.Resolved(9)
        )

        assertEquals(false, decision is Decision.Send)
    }

    @Test
    fun `a request asserting a dead line with a live preference is blocked`() {
        assertEquals(
            Decision.Blocked(Block.ASSERTED_SIM_UNAVAILABLE),
            decide(
                preferredRef = refA,
                preferredAssertion = Assertion.Resolved(5),
                assertedRef = refB,
                assertedAssertion = Assertion.NotActive
            )
        )
    }

    @Test
    fun `an unreadable inventory does not turn a matching assertion into a conflict`() {
        assertEquals(
            Decision.Send(5),
            decide(
                preferredRef = refA,
                preferredAssertion = Assertion.Resolved(5),
                assertedRef = refA,
                assertedAssertion = Assertion.InventoryUnavailable
            )
        )
    }

    // ── totality ─────────────────────────────────────────────────────────────

    @Test
    fun `every combination of inputs yields a decision and never throws`() {
        val refs = listOf(null, "", refA)
        val assertions = listOf(
            null,
            Assertion.Resolved(5),
            Assertion.Resolved(9),
            Assertion.NotActive,
            Assertion.InventoryUnavailable
        )
        val overrides = listOf(null, 5, 9)
        var count = 0

        for (preferredRef in refs) {
            for (preferredAssertion in assertions) {
                for (override in overrides) {
                    for (assertedRef in refs) {
                        for (assertedAssertion in assertions) {
                            val decision = decide(
                                preferredRef = preferredRef,
                                preferredAssertion = preferredAssertion,
                                override = override,
                                assertedRef = assertedRef,
                                assertedAssertion = assertedAssertion
                            )
                            // A refused send must always carry a typed reason, never a bare refusal.
                            if (decision is Decision.Blocked) {
                                assertEquals(
                                    "a block must carry a reason",
                                    true,
                                    decision.reason in Block.entries
                                )
                            }
                            count++
                        }
                    }
                }
            }
        }

        assertEquals("the table must be exercised end to end", 675, count)
    }
}
