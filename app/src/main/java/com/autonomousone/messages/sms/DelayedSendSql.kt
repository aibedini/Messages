package com.autonomousone.messages.sms

/**
 * v3.4.0 FEATURE 11 — the policy vocabulary of the delayed-send ledger.
 *
 * The statement TEXT lives next to the DAO that executes it (top-level
 * `internal const val` in `data/PendingDelayedSend.kt`), because Room/KSP cannot
 * read a `const val` that is declared in another file of the same compilation —
 * the same constraint that already forces `UxDaos.kt` to hold its SQL.
 *
 * ── Why compare-and-set and not check-then-act ───────────────────────────────
 * A WorkManager job is delivered AT LEAST once. `doWork` can therefore run twice
 * for one intent (the kick-off, then a retry of an interrupted run), and two
 * different code paths can race it (the user's Undo at the same millisecond the
 * deadline fires). "SELECT the row, and if it looks pending then send" has a
 * window between the read and the write in which the other side commits — and
 * that window is a duplicate SMS the user pays for twice.
 *
 * Every writer in the DAO is therefore ONE statement whose WHERE clause carries
 * the precondition, and every one is guarded by a state predicate a terminal row
 * can never satisfy:
 *
 *   claim   : PENDING -> SENDING    (WHERE state = PENDING)
 *   undo    : PENDING -> CANCELLED  (WHERE state = PENDING)
 *   refuse  : PENDING -> FAILED     (WHERE state = PENDING)
 *   sent    : SENDING -> SENT       (WHERE state = SENDING)
 *   failed  : SENDING -> FAILED     (WHERE state = SENDING)
 *
 * The claim's `state = PENDING` predicate is the whole safety property: SQLite
 * serialises the two UPDATEs, the first one changes the state, and the second
 * matches ZERO rows and reports it. The loser sends nothing.
 */
object DelayedSendSql {

    /** Stable failure code for a run that died inside the radio call. */
    const val CODE_PROCESS_DIED = "PROCESS_DIED_DURING_SEND"

    /** Current state vocabulary, taken from the enum so it cannot drift. */
    fun vocabulary(): List<String> = DelayedSendState.entries.map { it.name }
}
