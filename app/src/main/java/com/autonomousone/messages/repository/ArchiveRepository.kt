package com.autonomousone.messages.repository

import android.content.Context
import android.content.SharedPreferences

/**
 * Lightweight archive store backed by SharedPreferences.
 *
 * Android's SMS ContentProvider has no native archive concept, so we track
 * archived thread IDs locally as a Set<String> in SharedPreferences.
 * This survives process death and app restarts.
 */
class ArchiveRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "messages_archive"
        private const val KEY_ARCHIVED_THREADS = "archived_thread_ids"
    }

    /** Returns all archived thread IDs as a Set<Long>. */
    fun getArchivedIds(): Set<Long> {
        return prefs.getStringSet(KEY_ARCHIVED_THREADS, emptySet())
            ?.mapNotNull { it.toLongOrNull() }
            ?.toSet()
            ?: emptySet()
    }

    /** Returns true if the given [threadId] is archived. */
    fun isArchived(threadId: Long): Boolean = threadId in getArchivedIds()

    /** Marks [threadId] as archived. */
    fun archiveThread(threadId: Long) {
        val current = prefs.getStringSet(KEY_ARCHIVED_THREADS, emptySet())
            ?.toMutableSet() ?: mutableSetOf()
        current.add(threadId.toString())
        prefs.edit().putStringSet(KEY_ARCHIVED_THREADS, current).apply()
    }

    /** Removes [threadId] from the archive (unarchive). */
    fun unarchiveThread(threadId: Long) {
        val current = prefs.getStringSet(KEY_ARCHIVED_THREADS, emptySet())
            ?.toMutableSet() ?: mutableSetOf()
        current.remove(threadId.toString())
        prefs.edit().putStringSet(KEY_ARCHIVED_THREADS, current).apply()
    }
}
