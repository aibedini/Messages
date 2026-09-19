package com.autonomousone.messages.repository

import android.content.Context
import android.content.SharedPreferences

/**
 * Pinned-conversation store backed by SharedPreferences — same pattern as
 * [ArchiveRepository]: Android's SMS provider has no native "pin" concept,
 * so we track pinned thread IDs locally. Pinned conversations always sort
 * above everything else in the Home list.
 */
class PinRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "messages_pins"
        private const val KEY_PINNED_THREADS = "pinned_thread_ids"
    }

    /** Returns all pinned thread IDs as a Set<Long>. */
    fun getPinnedIds(): Set<Long> {
        return prefs.getStringSet(KEY_PINNED_THREADS, emptySet())
            ?.mapNotNull { it.toLongOrNull() }
            ?.toSet()
            ?: emptySet()
    }

    fun isPinned(threadId: Long): Boolean = threadId in getPinnedIds()

    fun pinThread(threadId: Long) {
        setPinned(listOf(threadId), pinned = true)
    }

    fun unpinThread(threadId: Long) {
        setPinned(listOf(threadId), pinned = false)
    }

    /**
     * FEATURE 10: pin / unpin a whole selection in ONE commit.
     *
     * Idempotent: a commit with no membership change writes nothing, so a bulk
     * "Pin" over already-pinned rows is a quiet no-op.
     *
     * @return the number of threads whose membership actually changed.
     */
    fun setPinned(threadIds: Collection<Long>, pinned: Boolean): Int {
        val ids = threadIds.filter { it > 0L }.map { it.toString() }.toSet()
        if (ids.isEmpty()) return 0
        val stored = prefs.getStringSet(KEY_PINNED_THREADS, emptySet()).orEmpty()
        val affected = if (pinned) ids - stored else ids intersect stored
        if (affected.isEmpty()) return 0
        val current = stored.toMutableSet()
        if (pinned) current.addAll(affected) else current.removeAll(affected)
        prefs.edit().putStringSet(KEY_PINNED_THREADS, current).apply()
        return affected.size
    }
}
