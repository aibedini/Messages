package com.autonomousone.messages.repository

import com.autonomousone.messages.model.Sms

/**
 * Reconciles a conversation-list row against messages we KNOW are newer.
 *
 * Why this exists: the Home list is built from `Telephony.Threads` (one row per
 * thread, with the provider's own SNIPPET/DATE/READ columns). That table is
 * maintained by the platform SMS provider, and it only updates when a message
 * row is correctly associated with the thread. If an outgoing row lands without
 * a THREAD_ID — or the provider associates it late — the thread row keeps the
 * OLD snippet and date while the conversation screen (which queries by address)
 * happily shows the newer message. The user then sees the list and the chat
 * disagree, e.g. list says "٩٩٩٥ · 2:01 pm" while the thread's last message is
 * an outgoing one at 4:25 pm.
 *
 * Rather than trust one source blindly, the list row is reconciled against the
 * newest message actually known for that thread. This is the same
 * "single source of truth, last-write-wins by timestamp" reconciliation
 * WhatsApp/Telegram clients apply between their local store and the server.
 */
object ThreadSnippet {

    /**
     * Returns [row] updated to reflect [newest] when [newest] is genuinely
     * newer. Returns [row] unchanged when there is nothing newer to apply.
     *
     * Read state is intentionally NOT copied from an outgoing message: a thread
     * whose newest message is your own send is by definition read.
     */
    fun reconcile(row: Sms, newest: Sms?): Sms {
        if (newest == null) return row
        if (newest.date <= row.date) return row
        return row.copy(
            message = newest.message,
            date = newest.date,
            type = newest.type,
            // Your own outgoing message can never leave a thread unread.
            unread = if (newest.type == 2) false else row.unread
        )
    }

    /**
     * Applies [newestByThread] (threadId → newest known message) across a whole
     * conversation list, preserving order-by-date afterwards.
     */
    fun reconcileAll(rows: List<Sms>, newestByThread: Map<Long, Sms>): List<Sms> {
        if (newestByThread.isEmpty()) return rows
        return rows.map { reconcile(it, newestByThread[it.threadId]) }
    }
}
