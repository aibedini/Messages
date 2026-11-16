package com.autonomousone.messages.viewmodel

import com.autonomousone.messages.security.GrantRevokeReason
import com.autonomousone.messages.security.GrantTarget
import com.autonomousone.messages.security.ScopeApplyStage
import com.autonomousone.messages.security.ScopeRevokeResult
import com.autonomousone.messages.security.ScopeSelection
import kotlinx.coroutines.CancellationException

/**
 * LDV-004 PR-E (slice 2d) — the master-pause half of the conversation-scope screen.
 *
 * "Pause AI access to all conversations" is the one decision on this screen that WITHDRAWS
 * access instead of granting it (design §4.6, [ConversationGrantCoordinator.pauseAll]). It is
 * deliberately kept in its own pure file, next to [ConversationScopeEditor], for three
 * reasons:
 *
 *  1. the state transitions are testable on the JVM with a fake pause port — no Android, no
 *     Room, no Keystore (`ConversationScopePauseTest`);
 *  2. the ViewModel stays a thin launcher: it calls [startPause], suspends on the real
 *     coordinator through [runPause], then installs the returned editor — exactly the path the
 *     test drives;
 *  3. the confirmation is explicit by construction: nothing here runs until the user confirms
 *     the danger dialog on the screen, and the action leaves [ScopePauseState.NotStarted]
 *     synchronously so a second tap cannot launch a second decision.
 *
 * ## Why a failed pause keeps the selection
 *
 * The pending checkboxes are the operator's un-saved choice. A pause FAILURE changed nothing
 * (the coordinator persists only after every tombstone is enqueued), so clearing the checks
 * would lose work for no security gain. Only a pause that actually revoked the scope clears
 * the list, because only then is the persisted scope empty. That is the difference between
 * "the list reflects the revoked scope" and "the screen forgot what the user ticked".
 *
 * ## Idempotency
 *
 * [startPause] returns `null` for an already paused scope, for a pause already in flight, and
 * for an editor that is mid-save. The coordinator is idempotent too ([ScopeRevokeResult]
 * `AlreadyRevoked` / `NotGranted`), so a stale double submission cannot publish a second
 * decision; this guard only keeps the UI honest.
 *
 * No message body, conversation id or key is logged or carried here: the notice is an enum.
 */

/** One-shot outcome of the master pause; the screen maps it to Persian copy. */
enum class ScopePauseNotice {
    /** The grants were revoked and the revoked scope is durable. */
    PAUSED,

    /** The device was already paused, so there was nothing to withdraw. */
    ALREADY_PAUSED,

    /** This device never had a conversation scope: there was nothing to pause. */
    NOTHING_GRANTED,

    /** A tombstone could not be durably enqueued; the scope is unchanged. */
    FAILED_OUTBOX,

    /** Every tombstone was enqueued but the revoked scope was not persisted. */
    FAILED_PERSIST,

    /** Anything else (load/build/cancelled wiring). The scope is unchanged. */
    FAILED_OTHER,
}

/**
 * Where the master pause action stands.
 *
 * [Paused] is a property of the decision: it is reached by loading a revoked scope and by a
 * successful [runPause], and it disables the action until the operator grants access again
 * (a save resets it to [NotStarted]).
 */
sealed interface ScopePauseState {
    /** No pause attempted from this screen; the action is offered. */
    data object NotStarted : ScopePauseState

    /** A pause decision is in flight: the action is disabled and a second tap is ignored. */
    data object Pausing : ScopePauseState

    /** The device's scope is revoked (paused): every grant is withdrawn. */
    data object Paused : ScopePauseState

    /** The attempt failed at [stage]; the scope is unchanged and the action can be retried. */
    data class Failed(val stage: ScopeApplyStage) : ScopePauseState
}

/**
 * Begin a pause: returns the editor in [ScopePauseState.Pausing], or `null` when the action
 * must not run (already paused, already in flight, or a save is in flight).
 *
 * Synchronous on purpose: the caller installs the returned editor BEFORE suspending, which is
 * what makes two rapid taps produce at most one decision.
 */
fun ConversationScopeEditor.startPause(): ConversationScopeEditor? = when {
    pause is ScopePauseState.Pausing -> null
    pause == ScopePauseState.Paused -> null
    saving -> null
    else -> copy(
        pause = ScopePauseState.Pausing,
        // Starting a new decision supersedes whatever the previous one had to say.
        notice = null,
        pauseNotice = null,
    )
}

/**
 * Fold one [ScopeRevokeResult] into the editor.
 *
 *  - [ScopeRevokeResult.Revoked] / `AlreadyRevoked` / `NotGranted` all leave the device with
 *    no readable conversation, so the editor shows the revoked (empty) scope: no persisted
 *    selection, no checks, no account-wide mode. The notice distinguishes what happened.
 *  - [ScopeRevokeResult.Failed] changes nothing but the pause state: the persisted scope and
 *    the operator's pending checks stay exactly as they were, so a retry can publish them.
 */
fun ConversationScopeEditor.applyPauseResult(result: ScopeRevokeResult): ConversationScopeEditor =
    when (result) {
        is ScopeRevokeResult.Revoked -> revoked(
            persistedSelection = result.state.selection,
            notice = ScopePauseNotice.PAUSED,
        )

        ScopeRevokeResult.AlreadyRevoked -> revoked(
            persistedSelection = null,
            notice = ScopePauseNotice.ALREADY_PAUSED,
        )

        ScopeRevokeResult.NotGranted -> revoked(
            persistedSelection = null,
            notice = ScopePauseNotice.NOTHING_GRANTED,
        )

        is ScopeRevokeResult.Failed -> copy(
            pause = ScopePauseState.Failed(result.stage),
            pauseNotice = when (result.stage) {
                ScopeApplyStage.OUTBOX -> ScopePauseNotice.FAILED_OUTBOX
                ScopeApplyStage.PERSIST -> ScopePauseNotice.FAILED_PERSIST
                else -> ScopePauseNotice.FAILED_OTHER
            },
        )
    }

/** Clear the one-shot pause notice; the pause state itself (and its button) is unchanged. */
fun ConversationScopeEditor.dismissPauseNotice(): ConversationScopeEditor =
    if (pauseNotice == null) this else copy(pauseNotice = null)

/**
 * Run ONE pause-all decision through [pauseAll] and fold the outcome into the editor.
 *
 * This is the exact body of `ConversationScopeViewModel.pauseAll` after it has installed
 * [startPause]. A throwable from the port is a typed [ScopeApplyStage.BUILD] failure — the
 * coordinator already maps its own stages, so the only throwable that can surface here is a
 * wiring exception (a missing Keystore/database handle), and it must not crash the screen.
 *
 * @param pauseAll the coordinator call. Production passes
 *   `ConversationGrantAdapters.ConversationGrantWiring.create(context)`'s `pauseAll`; tests
 *   pass a fake and assert the device/reason.
 * @param onError invoked with the wiring exception (never a payload or key) for the caller to
 *   log. The default does nothing, which keeps this file Android-free.
 */
suspend fun ConversationScopeEditor.runPause(
    device: GrantTarget,
    pauseAll: suspend (GrantTarget, GrantRevokeReason) -> ScopeRevokeResult,
    onError: (Throwable) -> Unit = {},
): ConversationScopeEditor {
    val result = try {
        pauseAll(device, GrantRevokeReason.REVOKED)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        onError(t)
        ScopeRevokeResult.Failed(ScopeApplyStage.BUILD, cause = t)
    }
    return applyPauseResult(result)
}

/**
 * The revoked editor: the persisted decision is gone, nothing is checked, and the action is
 * disabled until the operator saves a new grant (which resets [ScopePauseState] to
 * [ScopePauseState.NotStarted] through [ConversationScopeEditor.applyResult]).
 */
private fun ConversationScopeEditor.revoked(
    persistedSelection: ScopeSelection?,
    notice: ScopePauseNotice,
): ConversationScopeEditor = copy(
    persisted = persistedSelection,
    accountWide = false,
    selected = emptySet(),
    saving = false,
    notice = null,
    pause = ScopePauseState.Paused,
    pauseNotice = notice,
)
