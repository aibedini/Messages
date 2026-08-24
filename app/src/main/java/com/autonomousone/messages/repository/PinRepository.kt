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
        val current = prefs.getStringSet(KEY_PINNED_THREADS, emptySet())
            ?.toMutableSet() ?: mutableSetOf()
        current.add(threadId.toString())
        prefs.edit().putStringSet(KEY_PINNED_THREADS, current).apply()
    }

    fun unpinThread(threadId: Long) {
        val current = prefs.getStringSet(KEY_PINNED_THREADS, emptySet())
            ?.toMutableSet() ?: mutableSetOf()
        current.remove(threadId.toString())
        prefs.edit().putStringSet(KEY_PINNED_THREADS, current).apply()
    }
}
