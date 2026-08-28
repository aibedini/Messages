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
     * Rules (signed delta — the badge must come DOWN when a message is read):
     *  - unchanged read flag (both read, or both unread): 0 — a re-upsert of a
     *    live message (status callback, provider touch) never moves the badge;
     *  - brand-new UNREAD message: +1;
     *  - flip unread → read (user opened the thread, provider marked read): -1;
     *  - flip read → unread (provider corrected a read flag): +1.
     */
    fun compute(oldExists: Boolean, oldRead: Boolean, newRead: Boolean): Int = when {
        !oldExists -> if (newRead) 0 else 1   // brand-new: unread +1, read 0
        oldRead == newRead -> 0               // flag unchanged: badge flat
        newRead -> -1                         // flip unread → read
        else -> 1                             // flip read → unread
    }
}
