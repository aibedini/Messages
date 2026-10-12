package com.autonomousone.messages.data

/**
 * What a conversation's unread counter must become after a read.
 *
 * ## The bug this removes
 *
 * The read path used to end with an unconditional `UPDATE conversations SET unreadCount = 0`. That is
 * correct for an UNBOUNDED read — every message in the thread is read, so zero is the truth.
 *
 * It is wrong for a BOUNDED read. A bounded read deliberately stops at a horizon so that a message
 * arriving after the user left stays unread; but zeroing the counter then tells the user "all read"
 * while `messages` still holds an unread row. The conversation row and the message rows disagree, and
 * the badge is a lie until the next full reconcile repairs it.
 *
 * The truthful answer after a bounded read is the number of unread INCOMING messages still in the
 * mirror, because that is exactly the predicate the rest of the unread machinery uses.
 *
 * ## Why it suspends
 *
 * The count is a DAO call and the caller is already inside a coroutine within a Room transaction.
 * Bridging that with `runBlocking` would block the very thread the transaction needs, so the rule
 * suspends with the rest of the transaction instead.
 */
internal object ConversationReadProjection {

    /**
     * @param bounded whether this read was limited to a horizon.
     * @param unreadIncomingInThread counts unread incoming messages still present for this ONE thread.
     *   Invoked only for a bounded read, so an unbounded one never pays for a count it does not need.
     * @return the value the conversation's `unreadCount` must be set to. A negative count is clamped to
     *   0 so a failed count can never render as a negative badge.
     */
    suspend fun remainingUnreadAfter(
        bounded: Boolean,
        unreadIncomingInThread: suspend () -> Int
    ): Int = if (bounded) unreadIncomingInThread().coerceAtLeast(0) else 0
}
