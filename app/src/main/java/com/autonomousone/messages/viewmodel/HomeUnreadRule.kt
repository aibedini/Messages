package com.autonomousone.messages.viewmodel

import com.autonomousone.messages.model.Sms

/**
 * The single definition of what Home's unread badge means.
 *
 * ```text
 * renderedUnread = realUnread OR manualUnread
 * ```
 *
 * ## Why this is its own object
 *
 * It is the rule that produced a P0: a conversation the user had once marked unread could never clear
 * its badge by being opened, because the OR re-added the highlight on every render no matter how
 * correctly the messages had been read. Two places used to describe unread — the provider's READ state
 * and the user's bookmark — and only one of them was ever cleared by reading.
 *
 * Kept pure and separate from [HomeViewModel] so the interaction is testable without a database, a
 * ViewModel, or Android.
 */
internal object HomeUnreadRule {

    /**
     * Apply the `manualUnread` bookmark on top of the real read state.
     *
     * The bookmark is applied at RENDER time and never written into the durable row, so it can never
     * fight the provider's READ column. The flip side is that it must be durably CLEARED by a real read,
     * or the badge is permanent — which is exactly the bug this rule documents.
     */
    fun applyManualUnread(rows: List<Sms>, manuallyUnreadIds: Set<Long>): List<Sms> {
        if (manuallyUnreadIds.isEmpty()) return rows
        return rows.map { row ->
            if (!row.unread && row.threadId in manuallyUnreadIds) row.copy(unread = true) else row
        }
    }
}
