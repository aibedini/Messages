package com.autonomousone.messages.viewmodel

import com.autonomousone.messages.security.GrantKind
import com.autonomousone.messages.security.GrantScopeMode
import com.autonomousone.messages.security.PublishedGrant
import com.autonomousone.messages.security.ScopeApplyResult
import com.autonomousone.messages.security.ScopeApplyStage
import com.autonomousone.messages.security.ScopeEntry
import com.autonomousone.messages.security.ScopeSelection
import com.autonomousone.messages.security.ScopeState
import com.autonomousone.messages.security.ScopeViolation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LDV-004 PR-E slice 2b — [ConversationScopeEditor], the pure state behind the scope screen.
 *
 * The ViewModel and the Composable are thin over this file, so this plain JVM test is where
 * the interaction contract lives: what is checked, what "all conversations" means, when Save
 * is offered, that a decision the screen cannot show is never silently revoked, and that a
 * coordinator outcome turns into exactly one notice and (only on success) a new persisted
 * scope.
 */
class ConversationScopeEditorTest {

    private val rowA = ScopeConversationRow("c-a", threadId = 1L, displayName = "Ali", lastActivity = 300L)
    private val rowB = ScopeConversationRow("c-b", threadId = 2L, displayName = "Sara", lastActivity = 500L)
    private val rowC = ScopeConversationRow("c-c", threadId = 3L, displayName = "Reza", lastActivity = 100L)

    private fun editor(
        persisted: ScopeSelection? = null,
        rows: List<ScopeConversationRow> = listOf(rowA, rowB, rowC),
        hidden: Int = 0,
    ): ConversationScopeEditor =
        ConversationScopeEditor.fromPersisted(rows, hidden, persisted)

    // ── Loading ─────────────────────────────────────────────────────────────

    @Test
    fun `loading an unconfigured device selects nothing and offers no save`() {
        val state = editor()

        assertEquals(3, state.totalCount)
        assertEquals(0, state.checkedVisibleCount)
        assertNull(state.selection())
        assertTrue(state.emptySelection)
        assertFalse(state.isDirty)
        assertFalse(state.canSave)
    }

    @Test
    fun `rows are ordered by last activity, newest first`() {
        val state = editor()

        assertEquals(listOf("c-b", "c-a", "c-c"), state.rows.map { it.conversationId })
    }

    @Test
    fun `a persisted conversation list arrives checked and clean`() {
        val state = editor(persisted = ScopeSelection.Conversations(setOf("c-a", "c-c")))

        assertTrue(state.isChecked("c-a"))
        assertFalse(state.isChecked("c-b"))
        assertTrue(state.isChecked("c-c"))
        assertEquals(2, state.checkedVisibleCount)
        assertEquals(ScopeSelection.Conversations(setOf("c-a", "c-c")), state.selection())
        assertFalse("nothing changed yet", state.isDirty)
        assertFalse(state.canSave)
    }

    @Test
    fun `a persisted account-wide scope arrives with every row checked`() {
        val state = editor(persisted = ScopeSelection.AccountWide)

        assertTrue(state.accountWide)
        assertTrue(state.isChecked("c-a"))
        assertTrue(state.isChecked("c-b"))
        assertTrue(state.isChecked("c-c"))
        assertEquals(3, state.checkedVisibleCount)
        assertEquals(ScopeSelection.AccountWide, state.selection())
        assertFalse(state.isDirty)
    }

    // ── Toggling ────────────────────────────────────────────────────────────

    @Test
    fun `checking a conversation makes the editor saveable`() {
        val state = editor().toggle("c-a", true)

        assertTrue(state.isChecked("c-a"))
        assertEquals(1, state.checkedVisibleCount)
        assertEquals(ScopeSelection.Conversations(setOf("c-a")), state.selection())
        assertTrue(state.isDirty)
        assertTrue(state.canSave)
    }

    @Test
    fun `unchecking the last conversation disables save again`() {
        val state = editor().toggle("c-a", true).toggle("c-a", false)

        assertNull(state.selection())
        assertTrue(state.emptySelection)
        assertFalse(state.canSave)
    }

    @Test
    fun `toggling off a persisted conversation is a real change`() {
        val state = editor(persisted = ScopeSelection.Conversations(setOf("c-a", "c-b")))
            .toggle("c-b", false)

        assertEquals(ScopeSelection.Conversations(setOf("c-a")), state.selection())
        assertTrue(state.isDirty)
        assertTrue(state.canSave)
    }

    @Test
    fun `an unknown conversation id is ignored`() {
        val state = editor().toggle("c-missing", true)

        assertEquals(0, state.checkedVisibleCount)
        assertNull(state.selection())
    }

    // ── All conversations ───────────────────────────────────────────────────

    @Test
    fun `all conversations selects the account scope and makes rows read-only`() {
        val state = editor().toggle("c-a", true).setAllConversations(true)

        assertTrue(state.accountWide)
        assertEquals(ScopeSelection.AccountWide, state.selection())
        assertEquals(3, state.checkedVisibleCount)
        assertTrue(state.canSave)
        assertEquals(
            "a row toggle is meaningless in account mode",
            state,
            state.toggle("c-b", false),
        )
        assertTrue(state.toggle("c-b", false).accountWide)
    }

    @Test
    fun `turning all conversations off restores the last persisted list`() {
        val state = editor(persisted = ScopeSelection.Conversations(setOf("c-a")))
            .setAllConversations(true)
            .setAllConversations(false)

        assertFalse(state.accountWide)
        assertEquals(ScopeSelection.Conversations(setOf("c-a")), state.selection())
        assertFalse("back to the persisted decision", state.isDirty)
    }

    @Test
    fun `turning all conversations off for an account-wide device selects nothing`() {
        val state = editor(persisted = ScopeSelection.AccountWide).setAllConversations(false)

        assertFalse(state.accountWide)
        assertNull("there is no per-conversation list to restore", state.selection())
        assertTrue(state.emptySelection)
        assertFalse(state.canSave)
    }

    // ── Decisions the screen cannot show ────────────────────────────────────

    @Test
    fun `a persisted conversation that is no longer listed is preserved`() {
        // c-x is not in the list (thread gone / became sensitive). It must not be silently
        // revoked just because it cannot be rendered.
        val state = editor(persisted = ScopeSelection.Conversations(setOf("c-a", "c-x")))

        assertEquals(ScopeSelection.Conversations(setOf("c-a", "c-x")), state.selection())
        assertFalse("loading alone is not a change", state.isDirty)

        // Toggling a visible row keeps c-x exactly as it was.
        val changed = state.toggle("c-a", false)
        assertEquals(ScopeSelection.Conversations(setOf("c-x")), changed.selection())
        assertTrue(changed.canSave)
    }

    @Test
    fun `an unlisted conversation does not make the editor count it as checked`() {
        val state = editor(persisted = ScopeSelection.Conversations(setOf("c-x")))

        assertEquals(0, state.checkedVisibleCount)
        assertFalse(state.isChecked("c-a"))
        assertFalse("nothing visible changed, so there is nothing to save", state.canSave)
        assertEquals(ScopeSelection.Conversations(setOf("c-x")), state.selection())
    }

    // ── Saving ──────────────────────────────────────────────────────────────

    @Test
    fun `markSaving disables the buttons and clears the previous notice`() {
        val state = editor(persisted = ScopeSelection.Conversations(setOf("c-a")))
            .copy(notice = ScopeEditorNotice.UP_TO_DATE)
            .markSaving()

        assertTrue(state.saving)
        assertNull(state.notice)
        assertFalse(state.canSave)
    }

    @Test
    fun `an applied result becomes the new persisted scope`() {
        val appliedState = ScopeState(
            scopeSequence = 4,
            revoked = false,
            updatedAt = 1_000L,
            selection = ScopeSelection.Conversations(setOf("c-a", "c-b")),
            entries = listOf(
                ScopeEntry("c-a", 4, revoked = false, updatedAt = 1_000L),
                ScopeEntry("c-b", 4, revoked = false, updatedAt = 1_000L),
            ),
        )
        val state = editor().toggle("c-a", true).markSaving().applyResult(
            ScopeApplyResult.Applied(
                state = appliedState,
                published = listOf(
                    PublishedGrant("grant-1", GrantKind.GRANT, GrantScopeMode.CONVERSATION, "c-a", 4),
                ),
            ),
        )

        assertEquals(ScopeEditorNotice.SAVED, state.notice)
        assertFalse(state.saving)
        assertEquals(ScopeSelection.Conversations(setOf("c-a", "c-b")), state.persisted)
        assertEquals(setOf("c-a", "c-b"), state.selected)
        assertFalse("the persisted state is the new baseline", state.isDirty)
    }

    @Test
    fun `an applied account-wide result flips the mode`() {
        val appliedState = ScopeState(
            scopeSequence = 2,
            revoked = false,
            updatedAt = 500L,
            selection = ScopeSelection.AccountWide,
        )
        val state = editor().toggle("c-a", true).markSaving().applyResult(
            ScopeApplyResult.Applied(appliedState, emptyList()),
        )

        assertTrue(state.accountWide)
        assertEquals(ScopeSelection.AccountWide, state.persisted)
        assertEquals(ScopeSelection.AccountWide, state.selection())
        assertTrue(state.isChecked("c-b"))
    }

    @Test
    fun `an unchanged result keeps the baseline and says so`() {
        val state = editor(persisted = ScopeSelection.Conversations(setOf("c-a")))
            .markSaving()
            .applyResult(ScopeApplyResult.Unchanged)

        assertEquals(ScopeEditorNotice.UP_TO_DATE, state.notice)
        assertEquals(ScopeSelection.Conversations(setOf("c-a")), state.persisted)
        assertFalse(state.saving)
    }

    @Test
    fun `a rejected selection publishes nothing and is reported`() {
        val empty = editor().markSaving()
            .applyResult(ScopeApplyResult.Rejected(ScopeViolation.EMPTY_CONVERSATION_SET))
        assertEquals(ScopeEditorNotice.EMPTY_SELECTION, empty.notice)
        assertFalse(empty.saving)

        val sensitive = editor().markSaving().applyResult(
            ScopeApplyResult.Rejected(ScopeViolation.SENSITIVE_CONVERSATION, "c-a"),
        )
        assertEquals(ScopeEditorNotice.REJECTED_UNSAFE, sensitive.notice)
        assertNull(sensitive.persisted)
    }

    @Test
    fun `every failure stage maps to a notice and keeps the baseline`() {
        val expected = mapOf(
            ScopeApplyStage.KEY to ScopeEditorNotice.FAILED_KEY,
            ScopeApplyStage.EPOCH to ScopeEditorNotice.FAILED_EPOCH,
            ScopeApplyStage.OUTBOX to ScopeEditorNotice.FAILED_OUTBOX,
            ScopeApplyStage.PERSIST to ScopeEditorNotice.FAILED_PERSIST,
            ScopeApplyStage.LOAD to ScopeEditorNotice.FAILED_OTHER,
            ScopeApplyStage.BUILD to ScopeEditorNotice.FAILED_OTHER,
            ScopeApplyStage.EXPIRY to ScopeEditorNotice.FAILED_OTHER,
        )
        for ((stage, notice) in expected) {
            val state = editor(persisted = ScopeSelection.Conversations(setOf("c-a")))
                .markSaving()
                .applyResult(ScopeApplyResult.Failed(stage, publishedBeforeFailure = 1))

            assertEquals("stage=$stage", notice, state.notice)
            assertEquals(
                "a failure must not move the approved scope (stage=$stage)",
                ScopeSelection.Conversations(setOf("c-a")),
                state.persisted,
            )
            assertFalse(state.saving)
        }
    }

    @Test
    fun `the hidden sensitive count is carried through`() {
        val state = editor(hidden = 4)

        assertEquals(4, state.hiddenSensitiveCount)
    }
}
