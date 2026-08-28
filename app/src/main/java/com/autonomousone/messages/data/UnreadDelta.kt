package com.autonomousone.messages.data

/**
 * Pure unread-count bookkeeping for the exact-mutation write path.
 *
 * The conversation projection's `unreadCount` is maintained by DELTA, not by
 * recounting the thread — O(1) regardless of thread size. This object holds the
 * delta rule so it can be unit-tested without a database.
 */
object UnreadDelta {

    /**
     * @param oldExists true when the message row already existed before the upsert
     * @param oldRead   the row's previous `read` value (meaningless when !oldExists)
     * @param newRead   the row's new `read` value
     *
     * Rules:
     *  - a READ message never increments (reading a message in the shadow must
     *    not re-inflate the badge);
     *  - a brand-new UNREAD message adds 1;
     *  - an existing message that flips read → unread (provider corrected a
     *    read flag) adds 1;
     *  - anything else (already unread, or read → read) adds 0.
     */
    fun compute(oldExists: Boolean, oldRead: Boolean, newRead: Boolean): Int = when {
        newRead -> 0
        !oldExists -> 1
        oldRead -> 1
        else -> 0
    }
}
