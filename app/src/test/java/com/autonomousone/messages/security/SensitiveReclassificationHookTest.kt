package com.autonomousone.messages.security

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LDV-004 PR-E slice 2c — the classification → revocation seam
 * [SensitiveReclassificationHook].
 *
 * This is the JVM seam the Android classification writers use:
 * `ClassificationRepository` (the only writer of `message_classification` /
 * `conversation_classification`, used by both the immediate
 * `MessageClassificationService` path and `ClassificationBackfillWorker`) calls
 * [SensitiveReclassificationHook.onClassified] after each committed classification batch.
 * The repository itself needs a `Context`/Room and has no JVM test stack, so everything that
 * can be decided wrongly is here and is pinned without Android:
 *
 *  - only a SENSITIVE category triggers a revoke (non-sensitive and unknown categories do not);
 *  - a thread with no cloud conversation identity is skipped;
 *  - a batch revokes a conversation once, not once per message;
 *  - a failure never escapes (classification must never fail because of revocation);
 *  - diagnostics carry a hash, never the raw conversation id.
 */
class SensitiveReclassificationHookTest {

    private val conversationIds = mapOf(41L to "conv-41", 42L to "conv-42")
    private val revokes = mutableListOf<Pair<String, String>>()
    private val outcomes = mutableListOf<SensitiveReclassificationOutcome>()
    private var nextResult: SensitiveRevocationResult = evaluated(
        ScopeRevokeResult.Revoked(ScopeState(2, revoked = true, updatedAt = 0), emptyList())
    )

    private fun hook(
        lookup: suspend (Long) -> String? = { conversationIds[it] },
        revoke: (suspend (String, String) -> SensitiveRevocationResult)? = null,
    ) = SensitiveReclassificationHook(
        conversationIdOf = lookup,
        revoke = revoke ?: { conversationId, category ->
            revokes += conversationId to category
            nextResult
        },
        onOutcome = { outcomes += it },
    )

    private fun classify(threadId: Long, category: String): SensitiveReclassificationOutcome? =
        classify(listOf(ClassifiedConversation(threadId, category)))

    private fun classify(
        classified: List<ClassifiedConversation>,
        subject: SensitiveReclassificationHook = hook(),
    ): SensitiveReclassificationOutcome? = runBlocking {
        subject.onClassified(classified)
        outcomes.lastOrNull()
    }

    private fun evaluated(vararg results: ScopeRevokeResult) =
        SensitiveRevocationResult.Evaluated(
            results.mapIndexed { index, result ->
                DeviceRevokeOutcome(deviceId = "ai-$index", result = result)
            }
        )

    private fun revoked() = ScopeRevokeResult.Revoked(
        state = ScopeState(2, revoked = true, updatedAt = 0),
        published = emptyList(),
    )

    // ── The sensitive gate ──────────────────────────────────────────────────

    @Test
    fun `a sensitive classification withdraws the conversation's grant`() {
        val outcome = classify(41L, "OTP")!!

        assertEquals(listOf("conv-41" to "OTP"), revokes)
        assertEquals(1, outcomes.size)
        assertEquals(1, outcome.devices)
        assertEquals(1, outcome.revoked)
        assertTrue(outcome.clean)
        assertFalse("the raw conversation id must not be in diagnostics", outcome.conversationRef == "conv-41")
    }

    @Test
    fun `every sensitive category triggers the revoke`() {
        for (category in ConversationScope.SENSITIVE_CATEGORIES) {
            revokes.clear()
            classify(41L, category)
            assertEquals("category=$category must revoke", listOf("conv-41" to category), revokes)
        }
    }

    @Test
    fun `non-sensitive and unknown categories never touch the coordinator`() {
        for (category in listOf("", "NORMAL", "PERSONAL", "PROMOTION", "UNKNOWN", "SOMETHING_NEW")) {
            classify(41L, category)
        }

        assertTrue("nothing to revoke for $revokes", revokes.isEmpty())
        assertTrue(outcomes.isEmpty())
    }

    // ── Conversation identity ───────────────────────────────────────────────

    @Test
    fun `a thread without a cloud conversation identity is skipped`() {
        val outcome = classify(99L, "OTP")!!

        assertTrue(revokes.isEmpty())
        assertTrue(outcome.skipped)
        assertNull(outcome.error)
        assertEquals("-", outcome.conversationRef)
    }

    @Test
    fun `a non-positive thread id is ignored`() {
        classify(
            listOf(ClassifiedConversation(0, "OTP"), ClassifiedConversation(-3, "OTP"))
        )

        assertTrue(revokes.isEmpty())
        assertTrue(outcomes.isEmpty())
    }

    // ── Batching / deduplication ────────────────────────────────────────────

    @Test
    fun `a batch of messages in one conversation revokes it once`() {
        classify(
            listOf(
                ClassifiedConversation(41L, "OTP"),
                ClassifiedConversation(41L, "OTP_SECURITY_CODE"),
                ClassifiedConversation(41L, "NORMAL"),
            )
        )

        assertEquals("one revocation per conversation", listOf("conv-41" to "OTP"), revokes)
    }

    @Test
    fun `a batch with two sensitive conversations revokes both`() {
        classify(
            listOf(
                ClassifiedConversation(41L, "OTP"),
                ClassifiedConversation(42L, "TRANSACTION"),
            )
        )

        assertEquals(
            listOf("conv-41" to "OTP", "conv-42" to "TRANSACTION"),
            revokes,
        )
    }

    // ── Fail-safe ───────────────────────────────────────────────────────────

    @Test
    fun `a throwing revoke is captured as an error outcome and does not propagate`() {
        var captured: SensitiveReclassificationOutcome? = null
        runBlocking {
            SensitiveReclassificationHook(
                conversationIdOf = { conversationIds[it] },
                revoke = { _, _ -> throw IllegalStateException("outbox gone") },
                onOutcome = { captured = it },
            ).onClassified(41L, "OTP")
        }

        assertEquals("outbox gone", captured!!.error?.message)
        assertFalse(captured!!.clean)
    }

    @Test
    fun `a failing conversation lookup is reported and never escapes`() {
        var captured: SensitiveReclassificationOutcome? = null
        runBlocking {
            SensitiveReclassificationHook(
                conversationIdOf = { throw IllegalStateException("database gone") },
                revoke = { _, _ -> error("must not be called") },
                onOutcome = { captured = it },
            ).onClassified(41L, "OTP")
        }

        assertEquals("database gone", captured!!.error?.message)
    }

    @Test
    fun `a failed device enumeration is not reported as clean`() {
        nextResult = SensitiveRevocationResult.Failed(null)

        val outcome = classify(41L, "OTP")!!

        assertTrue(outcome.enumerationFailed)
        assertFalse(outcome.clean)
    }

    @Test
    fun `per-device outcomes are counted honestly`() {
        nextResult = evaluated(
            revoked(),
            ScopeRevokeResult.AlreadyRevoked,
            ScopeRevokeResult.NotGranted,
            ScopeRevokeResult.Failed(ScopeApplyStage.OUTBOX),
            ScopeRevokeResult.Failed(ScopeApplyStage.LOAD),
        )

        val outcome = classify(41L, "READ_OTP")!!

        assertEquals(5, outcome.devices)
        assertEquals(1, outcome.revoked)
        assertEquals(1, outcome.alreadyRevoked)
        assertEquals(1, outcome.notGranted)
        assertEquals(2, outcome.failed)
        assertFalse(outcome.clean)
    }

    // ── Diagnostics privacy ─────────────────────────────────────────────────

    @Test
    fun `the diagnostic reference is a stable hash, never the conversation id`() {
        val first = conversationRef("conv-41")
        val second = conversationRef("conv-41")
        val other = conversationRef("conv-42")

        assertEquals("stable", first, second)
        assertNotEquals("different conversations differ", first, other)
        assertEquals("12 hex characters", 12, first.length)
        assertTrue(first.all { it in "0123456789abcdef" })
        assertFalse(first.contains("conv"))
    }

    @Test
    fun `no outcome carries the raw conversation id`() {
        classify(41L, "OTP")

        val rendered = outcomes.joinToString { it.toString() }
        assertFalse("diagnostics must not carry the routing id", rendered.contains("conv-41"))
    }
}
