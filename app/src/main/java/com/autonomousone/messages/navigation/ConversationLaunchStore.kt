package com.autonomousone.messages.navigation

import java.util.concurrent.ConcurrentHashMap

/**
 * v2.6.9: one-frame handoff between Home and Conversation.
 *
 * NOT a database, NOT a cache — purely ephemeral: when the user taps a
 * conversation row, Home already holds everything needed to paint the last
 * bubble (threadId, phone, name, latest snippet, date, direction). Storing
 * that here lets ConversationScreen's FIRST composition show the real last
 * message instead of a blank frame followed by a "pop" when Room returns.
 *
 * Snapshots are consumed (removed) by the screen once the real window has
 * rendered, so stale snippets can never outlive their purpose.
 */
object ConversationLaunchStore {

    data class Snapshot(
        val threadId: Long,
        val phone: String,
        val name: String,
        val message: String,
        val date: Long,
        val type: Int
    )

    private val snapshots =
        ConcurrentHashMap<Long, Snapshot>()

    fun put(
        snapshot: Snapshot
    ) {
        snapshots[snapshot.threadId] =
            snapshot
    }

    fun peek(
        threadId: Long
    ): Snapshot? {
        return snapshots[threadId]
    }

    fun remove(
        threadId: Long
    ) {
        snapshots.remove(threadId)
    }
}
