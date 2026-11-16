package com.autonomousone.messages.security

import com.autonomousone.messages.data.MessageCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LDV-004 PR-E slice 1 — pure conversation-scope policy.
 *
 * Plain JVM test: no Android, no Room, no crypto. Covers the validation matrix, the
 * sensitive-category gate (including drift against the production vocabularies), and the
 * `scopeSequence` / revoke semantics the future UI and publisher will rely on.
 */
class ConversationScopeTest {

    // ── Validation matrix ───────────────────────────────────────────────────

    @Test
    fun `account-wide selection is explicitly valid`() {
        val result = ConversationScope.validate(ScopeSelection.AccountWide)
        assertTrue(result is ScopeValidation.Valid)
        assertEquals(ScopeSelection.AccountWide, (result as ScopeValidation.Valid).selection)
    }

    @Test
    fun `an empty conversation set is invalid and must not look like account-wide`() {
        val result = ConversationScope.validate(ScopeSelection.Conversations(emptySet()))
        assertTrue(result is ScopeValidation.Invalid)
        assertEquals(
            ScopeViolation.EMPTY_CONVERSATION_SET,
            (result as ScopeValidation.Invalid).violation,
        )
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) {
                ConversationScope.requireValid(ScopeSelection.Conversations(emptySet()))
            }.message!!.contains(ScopeViolation.EMPTY_CONVERSATION_SET.name)
        )
    }

    @Test
    fun `duplicate ids collapse and the canonical form is sorted`() {
        val result = ConversationScope.validateIds(listOf("b", "a", "b", "a"))
        assertTrue(result is ScopeValidation.Valid)
        val selection = (result as ScopeValidation.Valid).selection
            as ScopeSelection.Conversations
        assertEquals(setOf("a", "b"), selection.ids)
        assertEquals(listOf("a", "b"), selection.ids.toList())
    }

    @Test
    fun `blank ids are rejected`() {
        for (blank in listOf("", " ", "\t\n")) {
            val result = ConversationScope.validateIds(listOf("ok", blank))
            assertTrue("blank id [$blank] must be rejected", result is ScopeValidation.Invalid)
            assertEquals(
                ScopeViolation.BLANK_CONVERSATION_ID,
                (result as ScopeValidation.Invalid).violation,
            )
        }
    }

    @Test
    fun `oversized ids are rejected`() {
        val result = ConversationScope.validateIds(
            listOf("x".repeat(ConversationScope.MAX_CONVERSATION_ID_LENGTH + 1)),
        )
        assertTrue(result is ScopeValidation.Invalid)
        assertEquals(
            ScopeViolation.CONVERSATION_ID_TOO_LONG,
            (result as ScopeValidation.Invalid).violation,
        )
        // The maximum length itself is allowed.
        val atLimit = "x".repeat(ConversationScope.MAX_CONVERSATION_ID_LENGTH)
        assertTrue(ConversationScope.validateIds(listOf(atLimit)) is ScopeValidation.Valid)
    }

    @Test
    fun `more than 500 conversations are rejected and 500 are accepted`() {
        val tooMany = (1..ConversationScope.MAX_CONVERSATIONS + 1).map { "c$it" }
        val rejected = ConversationScope.validateIds(tooMany)
        assertTrue(rejected is ScopeValidation.Invalid)
        assertEquals(
            ScopeViolation.TOO_MANY_CONVERSATIONS,
            (rejected as ScopeValidation.Invalid).violation,
        )

        val atLimit = (1..ConversationScope.MAX_CONVERSATIONS).map { "c$it" }
        val valid = ConversationScope.validateIds(atLimit)
        assertTrue(valid is ScopeValidation.Valid)
        assertEquals(
            ConversationScope.MAX_CONVERSATIONS,
            ((valid as ScopeValidation.Valid).selection as ScopeSelection.Conversations).ids.size,
        )
    }

    // ── Sensitive / LOCAL_ONLY gate ────────────────────────────────────────

    @Test
    fun `every sensitive category is ineligible regardless of case or padding`() {
        for (category in ConversationScope.SENSITIVE_CATEGORIES) {
            assertFalse(
                "$category must never be eligible",
                ConversationScope.eligible("conversation-1", category),
            )
            assertFalse(
                "$category lowercase must never be eligible",
                ConversationScope.eligible("conversation-1", category.lowercase()),
            )
            assertFalse(
                "$category padded must never be eligible",
                ConversationScope.eligible("conversation-1", "  $category  "),
            )
        }
        // One representative from each vocabulary family.
        assertFalse(ConversationScope.eligible("c", "OTP_SECURITY_CODE"))
        assertFalse(ConversationScope.eligible("c", "READ_FINANCIAL_NOTIFICATIONS"))
        assertFalse(ConversationScope.eligible("c", "TRANSACTION"))
        assertFalse(ConversationScope.eligible("c", "LOCAL_ONLY"))
    }

    @Test
    fun `normal conversations are eligible`() {
        for (category in ConversationScope.NON_SENSITIVE_CATEGORIES) {
            assertTrue(
                "$category should be eligible",
                ConversationScope.eligible("conversation-1", category),
            )
        }
        assertTrue(ConversationScope.eligible("conversation-1", "normal"))
    }

    @Test
    fun `unknown categories fail closed`() {
        assertFalse(ConversationScope.eligible("conversation-1", "SOMETHING_NEW"))
        assertFalse(ConversationScope.eligible("conversation-1", "READ_EVERYTHING"))
    }

    // LDV-004 PR-E slice 2c: the revoke-side twin of `eligible`. A classification hook uses
    // it to decide whether an already-granted conversation must lose its grant.
    @Test
    fun `isSensitiveCategory matches the sensitive set and ignores case and padding`() {
        for (category in ConversationScope.SENSITIVE_CATEGORIES) {
            assertTrue("$category must be sensitive", ConversationScope.isSensitiveCategory(category))
            assertTrue(
                "$category lowercase must be sensitive",
                ConversationScope.isSensitiveCategory(category.lowercase()),
            )
            assertTrue(
                "$category padded must be sensitive",
                ConversationScope.isSensitiveCategory("  $category  "),
            )
        }
        for (category in ConversationScope.NON_SENSITIVE_CATEGORIES) {
            assertFalse(
                "$category must not trigger a revoke",
                ConversationScope.isSensitiveCategory(category),
            )
        }
        assertFalse(
            "an unknown category cannot have been granted, so it revokes nothing",
            ConversationScope.isSensitiveCategory("SOMETHING_NEW"),
        )
        assertTrue(ConversationScope.isSensitiveCategory(ConversationScope.LOCAL_ONLY))
    }

    @Test
    fun `blank ids and an explicit local-only verdict are never eligible`() {
        assertFalse(ConversationScope.eligible("", ""))
        assertFalse(ConversationScope.eligible("   ", ""))
        assertFalse(
            ConversationScope.eligible(
                "x".repeat(ConversationScope.MAX_CONVERSATION_ID_LENGTH + 1),
                "",
            )
        )
        assertFalse(ConversationScope.eligible("conversation-1", "", localOnly = true))
        assertTrue(ConversationScope.eligible("conversation-1", "", localOnly = false))
    }

    @Test
    fun `a scope containing one sensitive conversation is rejected whole`() {
        val categories = mapOf(
            "normal-1" to "PERSONAL",
            "otp-1" to "READ_OTP",
        )
        val result = ConversationScope.validate(
            ScopeSelection.Conversations(setOf("normal-1", "otp-1")),
        ) { id -> categories.getValue(id) }
        assertTrue(result is ScopeValidation.Invalid)
        assertEquals(
            ScopeViolation.SENSITIVE_CONVERSATION,
            (result as ScopeValidation.Invalid).violation,
        )
        assertEquals("otp-1", result.conversationId)

        val accepted = ConversationScope.validate(
            ScopeSelection.Conversations(setOf("normal-1")),
        ) { id -> categories.getValue(id) }
        assertTrue(accepted is ScopeValidation.Valid)
    }

    @Test
    fun `the sensitive lists match the production vocabularies`() {
        // SensitiveMessageFirewall.Category: everything except NORMAL is sensitive.
        assertEquals(
            SensitiveMessageFirewall.Category.entries
                .filter { it != SensitiveMessageFirewall.Category.NORMAL }
                .map { it.name }
                .toSet(),
            ConversationScope.SENSITIVE_FIREWALL_CATEGORIES,
        )
        // TelephonySyncCoordinator assigns exactly these keyring domains to sensitive verdicts.
        assertEquals(
            ConversationKeyRepository.KEYRING_DOMAINS - ConversationKeyRepository.MESSAGE_DOMAIN,
            ConversationScope.SENSITIVE_DOMAINS,
        )
        // The capability contract calls the same set "sensitive capabilities".
        assertEquals(
            PairingCapabilityContract.SENSITIVE_CAPABILITIES.toSet(),
            ConversationScope.SENSITIVE_DOMAINS,
        )
        // The local classifier's OTP/TRANSACTION categories are sensitive too.
        for (category in listOf(MessageCategory.OTP, MessageCategory.TRANSACTION)) {
            assertTrue(
                "${category.name} must be sensitive",
                category.name in ConversationScope.SENSITIVE_CATEGORIES,
            )
        }
        for (category in listOf(
            MessageCategory.PERSONAL,
            MessageCategory.PROMOTION,
            MessageCategory.UNKNOWN,
        )) {
            assertTrue(
                "${category.name} must be non-sensitive",
                category.name in ConversationScope.NON_SENSITIVE_CATEGORIES,
            )
        }
    }

    // ── ScopeState sequence / revoke semantics ─────────────────────────────

    private fun state(vararg ids: String, updatedAt: Long = 1_000L): ScopeState =
        ScopeState.initial(ScopeSelection.Conversations(ids.toSet()), updatedAt = updatedAt)

    @Test
    fun `initial state starts at sequence one with granted entries`() {
        val state = state("b", "a", updatedAt = 42L)
        assertEquals(ScopeState.INITIAL_SEQUENCE, state.scopeSequence)
        assertFalse(state.revoked)
        assertTrue(state.isActive)
        assertEquals(setOf("a", "b"), state.activeConversationIds)
        assertEquals(listOf("a", "b"), state.entries.map { it.conversationId })
        assertTrue(state.entries.all { !it.revoked && it.scopeSequence == 1L })
        assertTrue(state.grants("a"))
        assertFalse(state.grants("missing"))
    }

    @Test
    fun `nextSequence is monotonic and never mutates the old state`() {
        val state = state("a", updatedAt = 1_000L)
        val next = state.nextSequence(updatedAt = 2_000L)
        assertEquals(2L, next.scopeSequence)
        assertEquals(2_000L, next.updatedAt)
        assertEquals(1L, state.scopeSequence)
        assertEquals(1_000L, state.updatedAt)
        assertEquals(3L, next.nextSequence().scopeSequence)
        assertTrue(next.grants("a"))
    }

    @Test
    fun `revoke removes one conversation and records a revoked entry`() {
        val state = state("a", "b")
        val revoked = state.revoke("a", updatedAt = 3_000L)
        assertEquals(2L, revoked.scopeSequence)
        assertFalse(revoked.revoked)
        assertFalse(revoked.grants("a"))
        assertTrue(revoked.grants("b"))
        assertEquals(setOf("b"), revoked.activeConversationIds)
        assertEquals(setOf("a"), revoked.revokedConversationIds)
        val entry = revoked.entries.single { it.conversationId == "a" }
        assertTrue(entry.revoked)
        assertEquals(2L, entry.scopeSequence)
        assertEquals(3_000L, entry.updatedAt)
        assertEquals(1L, state.scopeSequence) // original untouched
    }

    @Test
    fun `revoking the last conversation pauses the scope`() {
        val paused = state("only").revoke("only", updatedAt = 2_000L)
        assertEquals(2L, paused.scopeSequence)
        assertTrue(paused.revoked)
        assertNull(paused.selection)
        assertFalse(paused.isActive)
        assertFalse(paused.grants("only"))
        assertTrue(paused.activeConversationIds.isEmpty())
    }

    @Test
    fun `revoke and revokeAll are idempotent`() {
        val revoked = state("a", "b").revoke("a")
        assertSame(revoked, revoked.revoke("a"))
        assertSame(revoked, revoked.revoke("never-granted"))
        val paused = revoked.revokeAll()
        assertSame(paused, paused.revokeAll())
        assertSame(paused, paused.revoke("b"))
    }

    @Test
    fun `revokeAll pauses every conversation`() {
        val paused = state("a", "b").revokeAll(updatedAt = 5_000L)
        assertEquals(2L, paused.scopeSequence)
        assertTrue(paused.revoked)
        assertNull(paused.selection)
        assertEquals(setOf("a", "b"), paused.revokedConversationIds)
        assertFalse(paused.grants("a"))
        assertFalse(paused.grants("b"))
    }

    @Test
    fun `revokeAll also works for an account-wide scope`() {
        val account = ScopeState.initial(ScopeSelection.AccountWide, updatedAt = 1_000L)
        assertTrue(account.grants("anything"))
        val paused = account.revokeAll(updatedAt = 2_000L)
        assertTrue(paused.revoked)
        assertNull(paused.selection)
        assertFalse(paused.grants("anything"))
    }

    @Test
    fun `per-conversation revoke refuses an account-wide scope`() {
        val account = ScopeState.initial(ScopeSelection.AccountWide, updatedAt = 1_000L)
        assertThrows(IllegalArgumentException::class.java) { account.revoke("conversation-1") }
        assertThrows(IllegalArgumentException::class.java) { account.revoke(" ") }
    }

    @Test
    fun `withSelection allocates a sequence and tombstones removed conversations`() {
        val state = state("a", "b", updatedAt = 1_000L)
        val narrowed = state.withSelection(
            ScopeSelection.Conversations(setOf("b", "c")),
            updatedAt = 2_000L,
        )
        assertEquals(2L, narrowed.scopeSequence)
        assertEquals(setOf("b", "c"), narrowed.activeConversationIds)
        assertFalse(narrowed.grants("a"))
        assertTrue(narrowed.grants("b"))
        assertTrue(narrowed.grants("c"))
        assertEquals(setOf("a"), narrowed.revokedConversationIds)
        val revokedEntry = narrowed.entries.single { it.conversationId == "a" }
        assertTrue(revokedEntry.revoked)
        assertEquals(2L, revokedEntry.scopeSequence)
        val regranted = narrowed.entries.single { it.conversationId == "b" }
        assertFalse(regranted.revoked)
        assertEquals(2L, regranted.scopeSequence)
    }

    @Test
    fun `withSelection converts a paused scope back to an active one`() {
        val paused = state("a").revokeAll(updatedAt = 2_000L)
        val reopened = paused.withSelection(
            ScopeSelection.Conversations(setOf("b")),
            updatedAt = 3_000L,
        )
        assertFalse(reopened.revoked)
        assertEquals(3L, reopened.scopeSequence)
        assertTrue(reopened.grants("b"))
        assertFalse(reopened.grants("a"))
    }

    @Test
    fun `scope state construction is deterministic`() {
        val first = ScopeState.initial(
            ScopeSelection.Conversations(setOf("b", "a")),
            updatedAt = 1_000L,
        )
        val second = ScopeState.initial(
            ScopeSelection.Conversations(setOf("a", "b", "a")),
            updatedAt = 1_000L,
        )
        assertEquals(first, second)
        assertEquals(listOf("a", "b"), first.entries.map { it.conversationId })
        assertEquals(first.revoke("a", 2_000L), second.revoke("a", 2_000L))
    }

    @Test
    fun `invalid scope transitions throw instead of persisting`() {
        assertThrows(IllegalArgumentException::class.java) {
            ScopeState.initial(ScopeSelection.Conversations(emptySet()), updatedAt = 1_000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            state("a").withSelection(ScopeSelection.Conversations(emptySet()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScopeState(
                scopeSequence = 1L,
                revoked = false,
                updatedAt = 1_000L,
                selection = null,
                entries = emptyList(),
            )
        }
    }
}
