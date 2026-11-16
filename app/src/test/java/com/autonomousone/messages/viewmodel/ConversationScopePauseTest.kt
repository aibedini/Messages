package com.autonomousone.messages.viewmodel

import com.autonomousone.messages.security.GrantRevokeReason
import com.autonomousone.messages.security.GrantTarget
import com.autonomousone.messages.security.ScopeApplyResult
import com.autonomousone.messages.security.ScopeApplyStage
import com.autonomousone.messages.security.ScopeEntry
import com.autonomousone.messages.security.ScopeRevokeResult
import com.autonomousone.messages.security.ScopeSelection
import com.autonomousone.messages.security.ScopeState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LDV-004 PR-E (slice 2d) — the master-pause transitions of the conversation-scope screen.
 *
 * [ConversationScopeViewModel.pauseAll] is deliberately thin: [ConversationScopeEditor.startPause]
 * installs the in-flight state synchronously, then the suspended [runPause] folds one
 * coordinator outcome into the editor. This plain JVM test drives exactly that path with a fake
 * pause port, so the required cases — success, failure, already revoked, nothing granted and
 * the in-flight guard — are proven without Android, Room, the Keystore or a coordinator.
 *
 * The last case pins the rule that matters to a user: a FAILED pause must not clear the ticks
 * they had not saved yet; only a pause that actually revoked the scope does.
 */
class ConversationScopePauseTest {

    private val rowA = ScopeConversationRow("c-a", threadId = 1L, displayName = "Ali", lastActivity = 300L)
    private val rowB = ScopeConversationRow("c-b", threadId = 2L, displayName = "Sara", lastActivity = 500L)
    private val device = GrantTarget(deviceId = "ai-device-1", encryptionPublicKey = ByteArray(0))

    private fun editor(
        persisted: ScopeSelection? = null,
        scopeRevoked: Boolean = false,
    ): ConversationScopeEditor = ConversationScopeEditor.fromPersisted(
        rows = listOf(rowA, rowB),
        hiddenSensitiveCount = 0,
        persisted = persisted,
        scopeRevoked = scopeRevoked,
    )

    /** The revoked `ScopeState` the coordinator returns from `pauseAll`. */
    private fun revokedState() = ScopeState(
        scopeSequence = 2L,
        revoked = true,
        updatedAt = 1_000L,
        selection = null,
        entries = listOf(
            ScopeEntry(
                conversationId = "c-a",
                scopeSequence = 2L,
                revoked = true,
                updatedAt = 1_000L,
            ),
        ),
    )

    private fun revoked(): ScopeRevokeResult = ScopeRevokeResult.Revoked(
        state = revokedState(),
        published = emptyList(),
    )

    /**
     * The fake pause port: records every call and answers with [result], or throws [failure].
     * A revoke must be key-less, so the target's key is irrelevant here.
     */
    private class FakePause(
        private val result: ScopeRevokeResult? = null,
        private val failure: Throwable? = null,
    ) {
        val calls = mutableListOf<Pair<GrantTarget, GrantRevokeReason>>()
        val errors = mutableListOf<Throwable>()

        suspend fun pause(device: GrantTarget, reason: GrantRevokeReason): ScopeRevokeResult {
            calls += device to reason
            failure?.let { throw it }
            return requireNotNull(result) { "fake needs a result or a failure" }
        }
    }

    private fun run(
        start: ConversationScopeEditor,
        port: FakePause,
    ): ConversationScopeEditor = runBlocking {
        start.runPause(device, port::pause, onError = { port.errors += it })
    }

    // ── In-flight guard ─────────────────────────────────────────────────────

    @Test
    fun `the first tap starts the pause and disables the action and save`() {
        val state = editor().toggle("c-a", true)
            .copy(notice = ScopeEditorNotice.UP_TO_DATE)
            .startPause()!!

        assertEquals(ScopePauseState.Pausing, state.pause)
        assertNull(state.pauseNotice)
        assertNull("starting a decision clears the previous save notice", state.notice)
        assertFalse("a second decision cannot start", state.pauseActionEnabled)
        assertFalse(state.canSave)
        assertTrue(state.busy)
        assertEquals("the pending selection is untouched while the pause runs", setOf("c-a"), state.selected)
    }

    @Test
    fun `a second tap while pausing is ignored`() {
        val started = editor().toggle("c-a", true).startPause()!!

        assertNull(started.startPause())
    }

    @Test
    fun `a save in flight blocks the pause`() {
        assertNull(editor().toggle("c-a", true).markSaving().startPause())
    }

    @Test
    fun `a paused scope cannot be paused again`() {
        assertNull(editor(scopeRevoked = true).startPause())

        val paused = run(editor().startPause()!!, FakePause(result = revoked()))
        assertNull(paused.startPause())
    }

    // ── Success ─────────────────────────────────────────────────────────────

    @Test
    fun `a successful pause clears the selection and shows the revoked scope`() {
        val pending = editor(persisted = ScopeSelection.Conversations(setOf("c-b")))
            .toggle("c-a", true)
        val port = FakePause(result = revoked())

        val paused = run(pending.startPause()!!, port)

        assertEquals("the decision is the operator pause", listOf(device to GrantRevokeReason.REVOKED), port.calls)
        assertEquals(ScopePauseState.Paused, paused.pause)
        assertEquals(ScopePauseNotice.PAUSED, paused.pauseNotice)
        assertNull("the revoked scope has no persisted selection", paused.persisted)
        assertFalse(paused.accountWide)
        assertEquals("every check is cleared, not just the visible ones", emptySet<String>(), paused.selected)
        assertNull(paused.selection())
        assertFalse(paused.isDirty)
        assertFalse("the revoked scope offers nothing to save", paused.canSave)
        assertFalse("and nothing more to pause", paused.pauseActionEnabled)
        assertFalse(paused.busy)
        assertNull("a pause is not a save", paused.notice)
    }

    @Test
    fun `a pause that was already revoked is reported as already paused`() {
        val port = FakePause(result = ScopeRevokeResult.AlreadyRevoked)

        val paused = run(editor().toggle("c-a", true).startPause()!!, port)

        assertEquals(ScopePauseState.Paused, paused.pause)
        assertEquals(ScopePauseNotice.ALREADY_PAUSED, paused.pauseNotice)
        assertEquals("the authoritative store says nothing is granted", emptySet<String>(), paused.selected)
        assertNull(paused.persisted)
    }

    @Test
    fun `a device that never had a scope is reported without pretending it was revoked`() {
        val port = FakePause(result = ScopeRevokeResult.NotGranted)

        val paused = run(editor().startPause()!!, port)

        assertEquals(ScopePauseState.Paused, paused.pause)
        assertEquals(ScopePauseNotice.NOTHING_GRANTED, paused.pauseNotice)
        assertEquals(emptySet<String>(), paused.selected)
        assertNull(paused.persisted)
    }

    // ── Failure ─────────────────────────────────────────────────────────────

    @Test
    fun `a failed pause keeps the pending selection and offers a retry`() {
        val pending = editor(persisted = ScopeSelection.Conversations(setOf("c-b")))
            .toggle("c-a", true)
        val port = FakePause(result = ScopeRevokeResult.Failed(ScopeApplyStage.OUTBOX))

        val failed = run(pending.startPause()!!, port)

        assertEquals(ScopePauseState.Failed(ScopeApplyStage.OUTBOX), failed.pause)
        assertEquals(ScopePauseNotice.FAILED_OUTBOX, failed.pauseNotice)
        assertEquals("a failure must not lose the operator's ticks", setOf("c-a", "c-b"), failed.selected)
        assertEquals(
            "a failure must not move the persisted scope",
            ScopeSelection.Conversations(setOf("c-b")),
            failed.persisted,
        )
        assertEquals(ScopeSelection.Conversations(setOf("c-a", "c-b")), failed.selection())
        assertTrue("the un-published selection can still be saved", failed.canSave)
        assertTrue("and the pause can be retried", failed.pauseActionEnabled)
        assertEquals(
            "the retry starts a new decision",
            ScopePauseState.Pausing,
            failed.startPause()!!.pause,
        )
    }

    @Test
    fun `every pause failure stage maps to a notice and keeps the scope`() {
        val expected = mapOf(
            ScopeApplyStage.OUTBOX to ScopePauseNotice.FAILED_OUTBOX,
            ScopeApplyStage.PERSIST to ScopePauseNotice.FAILED_PERSIST,
            ScopeApplyStage.LOAD to ScopePauseNotice.FAILED_OTHER,
            ScopeApplyStage.BUILD to ScopePauseNotice.FAILED_OTHER,
        )
        for ((stage, notice) in expected) {
            val failed = run(
                editor().toggle("c-a", true).startPause()!!,
                FakePause(result = ScopeRevokeResult.Failed(stage, publishedBeforeFailure = 1)),
            )

            assertEquals("stage=$stage", notice, failed.pauseNotice)
            assertEquals("stage=$stage", ScopePauseState.Failed(stage), failed.pause)
            assertEquals("stage=$stage", setOf("c-a"), failed.selected)
        }
    }

    @Test
    fun `a throwing pause port becomes a typed failure and is reported to the caller`() {
        val port = FakePause(failure = IllegalStateException("wiring gone"))

        val failed = run(editor().toggle("c-a", true).startPause()!!, port)

        assertEquals(ScopePauseState.Failed(ScopeApplyStage.BUILD), failed.pause)
        assertEquals(ScopePauseNotice.FAILED_OTHER, failed.pauseNotice)
        assertEquals(1, port.errors.size)
        assertEquals(setOf("c-a"), failed.selected)
        assertTrue(failed.canSave)
    }

    // ── Loaded state and one-shot notice ────────────────────────────────────

    @Test
    fun `loading a revoked scope starts paused with the action disabled`() {
        val loaded = editor(scopeRevoked = true)

        assertEquals(ScopePauseState.Paused, loaded.pause)
        assertNull(loaded.pauseNotice)
        assertFalse(loaded.pauseActionEnabled)
        assertNull(loaded.startPause())
    }

    @Test
    fun `granting again resets the pause state so it can be paused later`() {
        val paused = run(editor().startPause()!!, FakePause(result = revoked()))
        val granted = paused.copy(selected = setOf("c-a")).applyResult(
            ScopeApplyResult.Applied(
                state = ScopeState(
                    scopeSequence = 3L,
                    revoked = false,
                    updatedAt = 2_000L,
                    selection = ScopeSelection.Conversations(setOf("c-a")),
                ),
                published = emptyList(),
            ),
        )

        assertEquals(ScopePauseState.NotStarted, granted.pause)
        assertNull(granted.pauseNotice)
        assertTrue(granted.pauseActionEnabled)
        assertEquals(ScopeSelection.Conversations(setOf("c-a")), granted.persisted)
    }

    @Test
    fun `the pause notice is one-shot`() {
        val paused = run(editor().startPause()!!, FakePause(result = revoked()))
        val dismissed = paused.dismissPauseNotice()

        assertNull(dismissed.pauseNotice)
        assertEquals("dismissing a notice is not a decision", ScopePauseState.Paused, dismissed.pause)
        assertFalse(dismissed.pauseActionEnabled)
    }
}
