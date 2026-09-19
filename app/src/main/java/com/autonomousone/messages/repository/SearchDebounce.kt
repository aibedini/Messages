package com.autonomousone.messages.repository

/**
 * v3.4.0 FEATURE 1 — when an in-conversation search query is allowed to run.
 *
 * Android-free on purpose so the debounce POLICY is unit-testable on the JVM
 * ([InConversationSearchDebounceTest]). The coroutine timing that enforces
 * [DEBOUNCE_MS] lives in `ConversationSearchRepository` (injectable delay, so
 * tests drive it with zero wall-clock waiting).
 *
 * Rules:
 *  - a single character is never executed: it would return the whole thread,
 *    which for a 100K-message conversation is exactly the materialisation the
 *    feature must avoid;
 *  - leading/trailing whitespace does not count toward the minimum;
 *  - a blank query is a CANCEL, not an empty-result search (the UI goes back to
 *    its idle state rather than rendering "no results").
 */
object SearchDebounce {

    /** Minimum characters before FTS4 is touched at all. */
    const val MIN_QUERY_LENGTH = 2

    /** Quiet period after the last keystroke, in milliseconds. */
    const val DEBOUNCE_MS = 280L

    /** True when [raw] is long enough to execute (after trimming). */
    fun isExecutable(raw: String): Boolean = raw.trim().length >= MIN_QUERY_LENGTH

    /** Self-describing reason, used only for diagnostics. */
    fun skipReason(raw: String): String =
        if (raw.isBlank()) "blank" else "too-short(${raw.trim().length})"
}
