package com.autonomousone.messages.repository

/**
 * v3.4.0 FEATURE 1 — index arithmetic for the ↑ / ↓ "3 of 14" control.
 *
 * Android-free and total: every input maps to a defined index, so the UI can
 * never render `0 of 0` or an out-of-range counter while a page is loading.
 *
 * DEFINED BEHAVIOUR (chosen, not accidental):
 *  - navigation WRAPS: ↓ from the last hit goes to the first (the result list
 *    scrolls back up), ↑ from the first goes to the last. Wrapping is what users
 *    expect from in-message search, and it makes "stuck at the end of the
 *    results" impossible.
 *  - the SINGLE-hit case is DISABLED, not wrapped: with one result there is
 *    nowhere to go, so both functions are no-ops and the UI renders the ↑ / ↓
 *    buttons DISABLED (`enabled = total > 1`) instead of clickable no-ops.
 *
 * A ZERO total is the only "no position" case: [index] is -1 and the UI hides
 * the counter entirely (it renders Loading / Empty instead).
 *
 * Both rules are pinned by InConversationSearchLogicTest.
 */
object ConversationSearchNavigation {

    /** Sentinel: no hits, so there is no position to show. */
    const val NO_INDEX = -1

    /** First position when hits exist. */
    const val FIRST_INDEX = 0

    /** Wrap-around next. `-1` (no hits) stays `-1`. */
    fun next(index: Int, total: Int): Int {
        if (total <= 0) return NO_INDEX
        if (index < 0 || index >= total) return FIRST_INDEX
        return (index + 1) % total
    }

    /** Wrap-around previous. `-1` (no hits) stays `-1`. */
    fun previous(index: Int, total: Int): Int {
        if (total <= 0) return NO_INDEX
        if (index < 0 || index >= total) return FIRST_INDEX
        return (index - 1 + total) % total
    }

    /**
     * Landing position after a (re)query. A page of results is presented from
     * its OLDEST hit, which is also the top of the reverse-chronological list.
     */
    fun initial(total: Int): Int = if (total <= 0) NO_INDEX else FIRST_INDEX

    /** "3 of 14" numerator; `0` when there is nothing to show. */
    fun displayOrdinal(index: Int, total: Int): Int =
        if (total <= 0 || index < 0) 0 else index + 1
}
