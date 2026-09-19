package com.autonomousone.messages.ui.home

/**
 * v3.4.0 FEATURE 15 — where a REPORTED-SPAM conversation may appear.
 *
 * This is deliberately a pure object rather than inline filters in the ViewModel:
 * "hidden from the inbox but not lost" is the kind of rule that silently rots, and
 * the two halves of it (inbox hides, Spam chip shows) must be provably the same
 * predicate. Android-free, so it is unit-tested directly.
 *
 * THE RULES
 * ---------
 *  1. A conversation the user reported as spam is NEVER shown in the normal inbox
 *     — not in All, not in Unread, and not in Archived.
 *  2. It stays VISIBLE under the Spam category, because a report is a reversible
 *     local decision, not a delete. Messages are never removed by a report.
 *  3. Nothing else changes: a report does not make any other thread visible or
 *     invisible, and an empty report set is always a no-op.
 */
object HomeSpamVisibility {

    /** Inbox rows: everything EXCEPT the reported-spam conversations. */
    fun inboxThreadIds(
        allThreadIds: Collection<Long>,
        spamThreadIds: Set<Long>
    ): List<Long> {
        if (spamThreadIds.isEmpty()) return allThreadIds.toList()
        return allThreadIds.filter { it !in spamThreadIds }
    }

    /**
     * The rows the SPAM category may show: exactly the reported conversations,
     * order-preserved so the caller's canonical (newest-first) ordering survives.
     */
    fun spamThreadIdsInOrder(
        allThreadIds: Collection<Long>,
        spamThreadIds: Set<Long>
    ): List<Long> {
        if (spamThreadIds.isEmpty()) return emptyList()
        return allThreadIds.filter { it in spamThreadIds }
    }

    /**
     * True when a row must be hidden from the list currently being rendered.
     *
     * @param spamCategorySelected the SPAM chip is the active category, which is the
     *   ONLY context where a reported conversation is shown.
     */
    fun isHiddenFromCurrentList(
        threadId: Long,
        spamThreadIds: Set<Long>,
        spamCategorySelected: Boolean
    ): Boolean {
        if (threadId !in spamThreadIds) return false
        return !spamCategorySelected
    }
}
