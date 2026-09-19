package com.autonomousone.messages.sms

/**
 * v3.4.0 FEATURE 11 — Send delay / Undo Send.
 *
 * PURE decision layer: no Android, no Room, no WorkManager, so every rule below
 * is unit-testable on the JVM.
 *
 * ── The one invariant that matters ───────────────────────────────────────────
 * A delayed message is handed to the radio AT MOST ONCE. The lifecycle is
 *
 *     PENDING ──(claim)──> SENDING ──(radio accepted)──> SENT
 *        │                     │
 *        │                     └──(radio refused)──────> FAILED
 *        └──(undo before the deadline)──────────────────> CANCELLED
 *
 * The PENDING → SENDING step is a CLAIM: exactly one caller can win it. A
 * scheduled message's WorkManager job is delivered at-least-once (a work
 * interruption, a re-enqueue with REPLACE, or a redelivery after process death
 * all re-enter `doWork`), so the executor MUST re-derive permission to send from
 * the durable claim rather than from "am I running". Claiming twice is the only
 * way to double-charge a user, so it is structurally impossible here: the claim
 * is a single compare-and-set, and the executor reports "no send" whenever it
 * loses.
 *
 * Undo is the mirror image: it is a compare-and-set from PENDING, which means a
 * deadline that has already been claimed (the worker is in the radio call right
 * now) CANNOT be undone — and, just as importantly, a claimed message can never
 * be "un-cancelled", because no transition leaves SENT or CANCELLED.
 */
enum class DelayedSendState {
    /** Durable, waiting for its deadline. The ONLY undoable state. */
    PENDING,

    /** Exactly one executor owns the send and is handing it to the radio. */
    SENDING,

    /** The radio accepted the submit (carrier success is a later, separate fact). */
    SENT,

    /** The radio refused the submit. Terminal: the claim is never re-issued. */
    FAILED,

    /** The user undid the send before the deadline. Terminal. */
    CANCELLED;

    /** No transition leaves a terminal state. */
    val isTerminal: Boolean get() = this == SENT || this == FAILED || this == CANCELLED

    companion object {
        /**
         * Parses a persisted state NAME. An unknown or missing value degrades to
         * [FAILED] — the fail-CLOSED choice — because the only safe reading of
         * "this row says something I do not understand" is "do not send it".
         */
        fun from(value: String?): DelayedSendState =
            entries.firstOrNull { it.name == value } ?: FAILED
    }
}

/**
 * The complete transition table, as one pure function.
 *
 * Kept separate from both the executor and the SQL so the same policy can be
 * asserted without a database — and so a reader can see every legal edge in one
 * place. The SQL compare-and-set predicates in [DelayedSendSql] encode exactly
 * these edges; [DelayedSendStateMachine] is the reference they are checked
 * against.
 */
object DelayedSendTransitions {

    val LEGAL: Set<Pair<DelayedSendState, DelayedSendState>> = setOf(
        DelayedSendState.PENDING to DelayedSendState.SENDING,
        DelayedSendState.PENDING to DelayedSendState.CANCELLED,
        DelayedSendState.PENDING to DelayedSendState.FAILED,
        DelayedSendState.SENDING to DelayedSendState.SENT,
        DelayedSendState.SENDING to DelayedSendState.FAILED,
        DelayedSendState.SENDING to DelayedSendState.CANCELLED
    )

    fun isLegal(from: DelayedSendState, to: DelayedSendState): Boolean =
        (from to to) in LEGAL

    /**
     * States the worker may hand to the radio. Derived, never hand-listed at a
     * call site: a future state cannot silently become sendable.
     */
    val SENDABLE: Set<DelayedSendState> =
        DelayedSendState.entries.filterTo(mutableSetOf()) { state ->
            LEGAL.any { (from, to) ->
                from == state && (to == DelayedSendState.SENDING || to == DelayedSendState.SENT)
            }
        }

    /**
     * Human-readable refusal, used in diagnostics and in test failure messages.
     * Null means the transition is allowed.
     */
    fun refusal(from: DelayedSendState, to: DelayedSendState): String? = when {
        isLegal(from, to) -> null
        from.isTerminal -> "terminal state ${from.name} can never move to ${to.name}"
        else -> "illegal transition ${from.name} -> ${to.name}"
    }
}

/**
 * The lifecycle of ONE delayed send, as a value object.
 *
 * [state] is the durable truth; every reader of a `PendingDelayedSend` must go
 * through [DelayedSendStateMachine] rather than re-deriving the rules, so the
 * UI, the worker and the undo path can never disagree about what "claimed"
 * means.
 */
data class PendingDelayedSend(
    val intentId: String,
    val body: String,
    val phoneToken: String,
    val threadId: Long,
    val state: DelayedSendState,
    val dueAt: Long,
    val createdAt: Long,
    val claimedAt: Long = 0L,
    val sentRowId: Long = 0L,
    val attempts: Int = 0,
    val failureCode: String? = null
) {
    /** Undo is offered exactly while the message is still [DelayedSendState.PENDING]. */
    val canUndo: Boolean get() = state == DelayedSendState.PENDING

    /**
     * Whether an executor may attempt the radio call at [now].
     *
     * `dueAt` is a lower bound, not an equality: WorkManager may deliver late
     * (Doze, reboot) and a late delivery must still send, or a delayed message
     * would be silently dropped.
     */
    fun isSendable(now: Long): Boolean =
        state in DelayedSendTransitions.SENDABLE && now >= dueAt

    /** Milliseconds still to wait before the deadline; 0 once due. */
    fun remainingMillis(now: Long): Long = (dueAt - now).coerceAtLeast(0L)
}

/**
 * Every decision the delay feature makes, as pure methods.
 *
 * Deliberately an object with no state: the durable row IS the state, and the
 * only writer of that row is the compare-and-set in [DelayedSendSql]. Anything
 * that needs "what should happen next" asks here; anything that needs "what is
 * true" reads the row.
 */
object DelayedSendStateMachine {

    /** Result of trying to take the single send permission. */
    sealed interface Claim {
        /** This caller owns the send. */
        data class Won(val intentId: String) : Claim

        /**
         * Somebody else already owns it, or it can never be sent. Either way the
         * caller MUST NOT send. [reason] is diagnostics-only.
         */
        data class Lost(val intentId: String, val reason: String) : Claim
    }

    /** Result of trying to undo. */
    sealed interface Undo {
        /** Flipped to [DelayedSendState.CANCELLED] atomically. Show the copy. */
        data class Cancelled(val intentId: String, val body: String) : Undo

        /**
         * Too late — the deadline was already claimed, or the message is already
         * gone. The worker owns delivery; the UI must not claim otherwise.
         */
        data class TooLate(val intentId: String, val state: DelayedSendState?) : Undo
    }

    /** The outcome of one executor run. */
    sealed interface Execution {
        /** The radio accepted the submit for this intent. */
        data class Sent(val intentId: String) : Execution

        /** The claim was won but telephony refused. Terminal. */
        data class Failed(val intentId: String, val code: String) : Execution

        /**
         * Nothing was sent and nothing will be: the claim was lost (a duplicate
         * worker, an undo that won the race, or a terminal row). This is the
         * ONLY outcome a repeated execution may produce.
         */
        data class Skipped(val intentId: String, val reason: String) : Execution
    }

    /**
     * The send-authorisation predicate, independent of any store.
     *
     * A caller may send iff the row is still [DelayedSendState.PENDING] and its
     * deadline has arrived. This is the Kotlin mirror of the claim SQL's WHERE
     * clause, and is what makes "two workers, one send" true rather than
     * merely likely.
     */
    fun mayClaim(current: DelayedSendState, dueAt: Long, now: Long): Boolean =
        current == DelayedSendState.PENDING && now >= dueAt

    /** Whether [state] still permits an undo. */
    fun mayUndo(state: DelayedSendState): Boolean = state == DelayedSendState.PENDING

    /**
     * An idempotency guard the executor consults BEFORE touching the radio, in
     * addition to the atomic claim. Belt and braces on purpose: the claim is the
     * authority, this catches an obviously-stale re-entry early.
     */
    fun isIdempotentlySendable(row: PendingDelayedSend, now: Long): Boolean =
        row.isSendable(now)

    /**
     * WorkManager retry policy for a failed radio call.
     *
     * Deliberately NO retry once the claim is held: the send opportunity is
     * consumed by the claim, and re-issuing it is exactly how a user gets billed
     * twice for one message. A failed delayed send is surfaced as a failed
     * message the user can resend deliberately.
     */
    fun shouldRetryAfterFailure(): Boolean = false
}

/**
 * Why a send is (or is not) delayed.
 *
 * The delay is a COMPOSER affordance: the user is looking at the thread and can
 * see a countdown and an Undo. Every other producer of SMS has no such surface,
 * so it must never be silently deferred. Making the decision an exhaustive
 * `when` over an enum is what keeps a future send source from inheriting the
 * delay by accident — a new source stops compiling until it is classified.
 */
enum class SendSource {
    /** The in-conversation composer. The ONLY delayed source. */
    COMPOSER,

    /** Notification direct reply. No in-notification Undo exists → immediate. */
    NOTIFICATION_REPLY,

    /** REST gateway / external API. Callers own their own timing contract. */
    GATEWAY,

    /** The long-press Schedule Send flow. Already has an explicit send time. */
    SCHEDULED,

    /** App automation (rules, EVE queue, remote commands). Must never be held back. */
    AUTOMATED,

    /** Incoming share / external compose intent. */
    EXTERNAL
}

/**
 * The single place that decides whether a send waits.
 *
 * [DelayPlan.Immediate] means "call SmsSender exactly as the app always has":
 * delay 0 is not a special case of the delayed path, it is the absence of it,
 * so the default configuration keeps the pre-3.4.0 byte-for-byte behaviour.
 */
sealed interface DelayPlan {
    /** No delay applies: use the direct [SmsSender] path, unchanged. */
    data object Immediate : DelayPlan

    /** Durable pending send with a deadline [dueAt], owned by the scheduler. */
    data class Delayed(val delayMillis: Long, val dueAt: Long) : DelayPlan
}

object DelayedSendGate {

    /** A delay of 0 (the default) and every non-composer source short-circuit here. */
    fun plan(source: SendSource, delayMillis: Long, now: Long): DelayPlan {
        if (!appliesTo(source)) return DelayPlan.Immediate
        if (!isDelayActive(delayMillis)) return DelayPlan.Immediate
        return DelayPlan.Delayed(delayMillis = delayMillis, dueAt = now + delayMillis)
    }

    /**
     * Only the composer is delayed. Written as an exhaustive `when` so adding a
     * [SendSource] without deciding its policy is a compile error, not a silent
     * behaviour change.
     */
    fun appliesTo(source: SendSource): Boolean = when (source) {
        SendSource.COMPOSER -> true
        SendSource.NOTIFICATION_REPLY -> false
        SendSource.GATEWAY -> false
        SendSource.SCHEDULED -> false
        SendSource.AUTOMATED -> false
        SendSource.EXTERNAL -> false
    }

    /** A zero (or negative) delay is the OFF setting — never schedule for "now". */
    fun isDelayActive(delayMillis: Long): Boolean = delayMillis > 0L
}
