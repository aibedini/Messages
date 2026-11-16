package com.autonomousone.messages.viewmodel

import com.autonomousone.messages.security.ScopeApplyResult
import com.autonomousone.messages.security.ScopeApplyStage
import com.autonomousone.messages.security.ScopeSelection
import com.autonomousone.messages.security.ScopeViolation

/**
 * LDV-004 PR-E (slice 2b) — the PURE state machine behind the conversation-scope screen.
 *
 * The screen and its ViewModel are thin over this file, which is why the interesting behaviour
 * can be unit-tested on the JVM with no Android, Room or Compose: which rows are checked, what
 * "all conversations" means, when Save is allowed, and what a coordinator outcome turns into.
 *
 * ## The two modes
 *
 *  - [accountWide] `true` is [ScopeSelection.AccountWide] — the operator chose "every
 *    eligible conversation". Per-conversation boxes are shown checked and DISABLED (the
 *    account grant covers them; editing one would be a lie), and Save publishes one
 *    account-scope grant.
 *  - [accountWide] `false` is an explicit set. It must never be empty — the slice-1 policy
 *    rejects an empty set as "nothing selected", so [selection] is `null` and Save is
 *    disabled until at least one conversation is chosen.
 *
 * ## Decisions the screen cannot show are preserved
 *
 * A persisted selection may name a conversation the list no longer renders (the thread was
 * deleted, or it has become sensitive). Those ids are NOT in [rows], so they cannot be
 * un-checked — and silently revoking them because the screen did not render them would be a
 * change the operator never made. They are therefore kept in [selection] (see
 * `preservedIds`), so saving leaves them exactly as they were. Only the visible rows can be
 * toggled.
 */
data class ScopeConversationRow(
    /** The opaque conversation reference a grant names (the sync event's `aggregateId`). */
    val conversationId: String,
    /** Local telephony thread id, for display/lookup only — never published. */
    val threadId: Long,
    val displayName: String,
    val lastActivity: Long,
)

/** One-shot outcome of a save attempt; the screen maps it to Persian copy. */
enum class ScopeEditorNotice {
    /** The coordinator applied the change. */
    SAVED,

    /** Nothing had changed, so nothing was published. */
    UP_TO_DATE,

    /** The user asked to save an empty cut-down selection. */
    EMPTY_SELECTION,

    /** A selected conversation is sensitive / LOCAL_ONLY; nothing was published. */
    REJECTED_UNSAFE,

    /** No CKE was available for a granted conversation. */
    FAILED_KEY,

    /** The account READ_MESSAGES epoch is not available yet. */
    FAILED_EPOCH,

    /** The payload could not be durably enqueued. */
    FAILED_OUTBOX,

    /** Every event was enqueued but the scope could not be persisted (or lost a race). */
    FAILED_PERSIST,

    /** Anything else (load/build/expiry/unexpected). The scope is unchanged. */
    FAILED_OTHER,
}

/**
 * The editor state for ONE AI device's conversation scope.
 *
 * Immutable: every interaction returns a new instance, so Compose state is just a
 * `MutableStateFlow` of this value and the tests are plain assertions.
 */
data class ConversationScopeEditor(
    val rows: List<ScopeConversationRow> = emptyList(),
    /** How many conversations were hidden because they are sensitive / LOCAL_ONLY. */
    val hiddenSensitiveCount: Int = 0,
    /** The last decision read from (or written to) the database; `null` = never configured. */
    val persisted: ScopeSelection? = null,
    /** The "all conversations" choice ([ScopeSelection.AccountWide]). */
    val accountWide: Boolean = false,
    /** Explicitly checked conversation ids (visible ones; preserved ids live in [persisted]). */
    val selected: Set<String> = emptySet(),
    val saving: Boolean = false,
    val notice: ScopeEditorNotice? = null,
) {
    private val visibleIds: Set<String>
        get() = rows.mapTo(mutableSetOf()) { it.conversationId }

    /** Checked rows as shown: every row in account-wide mode, else the explicit set. */
    val checkedVisibleCount: Int
        get() = if (accountWide) rows.size else rows.count { it.conversationId in effectiveIds() }

    /** Whether a row is drawn checked: account-wide covers every conversation. */
    fun isChecked(conversationId: String): Boolean =
        accountWide || conversationId in effectiveIds()

    /** Total offerable conversations (excludes hidden sensitive ones). */
    val totalCount: Int get() = rows.size

    /**
     * The selection Save would send, or `null` when it is not publishable.
     *
     * `null` means either "account-wide is off and nothing is selected", which the policy
     * rejects, or a state that cannot arise from this editor.
     */
    fun selection(): ScopeSelection? {
        if (accountWide) return ScopeSelection.AccountWide
        val ids = effectiveIds()
        return if (ids.isEmpty()) null else ScopeSelection.Conversations(ids)
    }

    /** True when [selection] differs from what is persisted. */
    val isDirty: Boolean
        get() = selection()?.let { it != persisted } ?: false

    /** Save is offered only for a real, publishable change. */
    val canSave: Boolean
        get() = !saving && selection() != null && isDirty

    /** True when the editor is in explicit mode with nothing selected. */
    val emptySelection: Boolean
        get() = !accountWide && effectiveIds().isEmpty()

    /**
     * Toggle one VISIBLE conversation.
     *
     * Ignored in account-wide mode (the account grant already covers every conversation) and
     * for an id that is not in [rows] (a preserved decision the screen cannot show).
     */
    fun toggle(conversationId: String, enabled: Boolean): ConversationScopeEditor {
        if (accountWide || conversationId !in visibleIds) return this
        val next = if (enabled) selected + conversationId else selected - conversationId
        return if (next == selected) this else copy(selected = next, notice = null)
    }

    /**
     * Flip the "all conversations" switch.
     *
     * Turning it OFF returns to the individual selection that was last persisted (if any) so
     * the operator does not lose their previous list, but never invents checkmarks: a device
     * that was account-wide has no per-conversation list to restore.
     */
    fun setAllConversations(enabled: Boolean): ConversationScopeEditor {
        if (enabled == accountWide) return this
        return if (enabled) {
            copy(accountWide = true, notice = null)
        } else {
            copy(
                accountWide = false,
                selected = (persisted as? ScopeSelection.Conversations)?.ids.orEmpty(),
                notice = null,
            )
        }
    }

    /** Begin a save: clears the previous notice and disables the buttons. */
    fun markSaving(): ConversationScopeEditor = copy(saving = true, notice = null)

    /**
     * Fold the coordinator's outcome into the editor.
     *
     * Only [ScopeApplyResult.Applied] moves [persisted]; every failure keeps the last
     * approved scope, which is exactly design §4.6's failure rule ("shown, but the device
     * keeps the last published scope — never a silently broader one").
     */
    fun applyResult(result: ScopeApplyResult): ConversationScopeEditor = when (result) {
        is ScopeApplyResult.Applied -> copy(
            persisted = result.state.selection,
            accountWide = result.state.selection is ScopeSelection.AccountWide,
            selected = result.state.activeConversationIds,
            saving = false,
            notice = ScopeEditorNotice.SAVED,
        )

        ScopeApplyResult.Unchanged -> copy(saving = false, notice = ScopeEditorNotice.UP_TO_DATE)

        is ScopeApplyResult.Rejected -> copy(
            saving = false,
            notice = if (result.violation == ScopeViolation.EMPTY_CONVERSATION_SET) {
                ScopeEditorNotice.EMPTY_SELECTION
            } else {
                ScopeEditorNotice.REJECTED_UNSAFE
            },
        )

        is ScopeApplyResult.Failed -> copy(
            saving = false,
            notice = when (result.stage) {
                ScopeApplyStage.KEY -> ScopeEditorNotice.FAILED_KEY
                ScopeApplyStage.EPOCH -> ScopeEditorNotice.FAILED_EPOCH
                ScopeApplyStage.OUTBOX -> ScopeEditorNotice.FAILED_OUTBOX
                ScopeApplyStage.PERSIST -> ScopeEditorNotice.FAILED_PERSIST
                ScopeApplyStage.LOAD,
                ScopeApplyStage.BUILD,
                ScopeApplyStage.EXPIRY,
                -> ScopeEditorNotice.FAILED_OTHER
            },
        )
    }

    private fun effectiveIds(): Set<String> = selected + preservedIds()

    /**
     * Persisted ids the current [rows] cannot show. They stay selected so a save neither
     * grants nor revokes something the operator never saw.
     */
    private fun preservedIds(): Set<String> =
        ((persisted as? ScopeSelection.Conversations)?.ids ?: emptySet()) - visibleIds

    companion object {
        /**
         * Build the editor for a freshly loaded screen.
         *
         * @param rows the eligible conversations, most recent first.
         * @param hiddenSensitiveCount conversations omitted because the policy refuses them.
         * @param persisted the stored decision (`null` = never configured / paused).
         */
        fun fromPersisted(
            rows: List<ScopeConversationRow>,
            hiddenSensitiveCount: Int,
            persisted: ScopeSelection?,
        ): ConversationScopeEditor = ConversationScopeEditor(
            rows = rows.sortedByDescending { it.lastActivity },
            hiddenSensitiveCount = hiddenSensitiveCount,
            persisted = persisted,
            accountWide = persisted is ScopeSelection.AccountWide,
            selected = (persisted as? ScopeSelection.Conversations)?.ids.orEmpty(),
        )
    }
}
