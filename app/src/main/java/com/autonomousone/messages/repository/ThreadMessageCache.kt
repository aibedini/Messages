package com.autonomousone.messages.repository

import android.util.LruCache
import com.autonomousone.messages.model.Sms

/**
 * Per-thread message cache — the "Google Messages trick" for instant chat
 * opening.
 *
 * Strategy (stale-while-revalidate):
 *  1. On chat open, the ViewModel asks for the CACHED list first. If present,
 *     it paints immediately (zero spinner) and revalidates in the background.
 *  2. The fresh provider query then atomically replaces the cache + UI.
 *
 * Invalidation is conservative: any Sms/MMS ContentObserver change, any send,
 * or any mark-as-read bumps the generation counter so stale entries are never
 * trusted twice. Cache holds the last N threads (LRU), each capped at the most
 * recent [MAX_PER_THREAD] messages — older pages load on scroll-up later.
 */
object ThreadMessageCache {

    private const val MAX_THREADS = 24
    private const val MAX_PER_THREAD = 400

    /** Bumped whenever the provider data may have changed. */
    @Volatile
    var generation: Long = 0

    private class Entry(
        val messages: List<Sms>,
        val cachedAtGeneration: Long,
        val cachedAtMillis: Long
    )

    private val lru = object : LruCache<Long, Entry>(MAX_THREADS) {}

    /** Store a freshly loaded thread. */
    fun put(threadKey: Long, phoneKey: String, messages: List<Sms>) {
        if (messages.isEmpty()) return
        val trimmed = if (messages.size > MAX_PER_THREAD)
            messages.subList(messages.size - MAX_PER_THREAD, messages.size)
        else messages
        val key = keyFor(threadKey, phoneKey)
        synchronized(lru) { lru.put(key, Entry(trimmed, generation, System.currentTimeMillis())) }
    }

    /**
     * Append an outgoing message to the cached thread WITHOUT bumping the
     * generation: the cache entry stays "fresh" so the next open paints it
     * instantly. The provider refresh on next load reconciles ids/dates.
     */
    fun append(threadKey: Long, phoneKey: String, sms: Sms) {
        val key = keyFor(threadKey, phoneKey)
        synchronized(lru) {
            val existing = lru.get(key) ?: return
            if (existing.messages.any { it.id == sms.id }) return
            val updated = existing.messages + sms
            val trimmed = if (updated.size > MAX_PER_THREAD)
                updated.subList(updated.size - MAX_PER_THREAD, updated.size)
            else updated
            lru.put(key, Entry(trimmed, existing.cachedAtGeneration, System.currentTimeMillis()))
        }
    }

    /**
     * Returns the cached thread ONLY when nothing has changed since it was
     * stored (same generation). Null → caller must do a full load.
     */
    fun getIfFresh(threadKey: Long, phoneKey: String): List<Sms>? {
        val entry = synchronized(lru) { lru.get(keyFor(threadKey, phoneKey)) } ?: return null
        return if (entry.cachedAtGeneration == generation && entry.messages.isNotEmpty())
            entry.messages
        else null
    }

    /**
     * Returns whatever is cached regardless of freshness (instant paint), plus
     * whether the caller should still revalidate.
     */
    fun getStale(threadKey: Long, phoneKey: String): Pair<List<Sms>, Boolean>? {
        val entry = synchronized(lru) { lru.get(keyFor(threadKey, phoneKey)) } ?: return null
        val fresh = entry.cachedAtGeneration == generation
        return entry.messages to !fresh
    }

    /** Drop one thread (e.g. after deletion). */
    fun invalidate(threadKey: Long, phoneKey: String) {
        synchronized(lru) { lru.remove(keyFor(threadKey, phoneKey)) }
    }

    private fun keyFor(threadKey: Long, phoneKey: String): Long {
        // Combine thread id with a stable hash of the phone so both lookup
        // paths (by-thread and by-phone for new chats) hit the same entry.
        val p = phoneKey.hashCode().toLong() and 0x7FFFFFFFL
        return (threadKey shl 32) or p
    }
}
