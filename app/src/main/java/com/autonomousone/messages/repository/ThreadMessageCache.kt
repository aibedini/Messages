package com.autonomousone.messages.repository

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
 * Invalidation is per-thread (v2.6.13): a cache entry records the global
 * epoch AND the revision of its own thread. A known thread mutation bumps
 * only that thread's revision, so activity in conversation A never makes the
 * cached windows of B/C/D stale. Only an unattributable/global recovery
 * (unknown provider change) bumps [globalEpoch], which invalidates everything.
 *
 * Cache holds the last N threads (LRU), each capped at the most recent
 * [MAX_PER_THREAD] messages — older pages load on scroll-up later.
 *
 * Android-free by design: the LRU is a pure Kotlin LinkedHashMap so the
 * revision contract is unit-testable on the JVM (android.util.LruCache is a
 * non-functional stub under unitTests.isReturnDefaultValues).
 */
object ThreadMessageCache {

    private const val MAX_THREADS = 24
    private const val MAX_PER_THREAD = 400

    /**
     * Global recovery epoch. Bumped ONLY when an invalidation cannot be
     * attributed to a single thread (unknown/global provider recovery).
     */
    @Volatile
    var globalEpoch: Long = 0L
        private set

    /**
     * Monotonic counter of AUTHORITATIVE paints.
     *
     * Bumped every time the reactive Room tail (or another authoritative source)
     * publishes a window. A caller that is about to paint a CACHE result captures
     * this before it reads and re-checks it before it publishes: if it moved, an
     * authoritative window arrived in the meantime and the stale cache must be
     * DISCARDED rather than allowed to overwrite it.
     *
     * This is the fix for the production bug where Home showed a newest message that
     * was missing when the conversation was opened: the Room tail painted
     * [A, B, C], then a slower cache read published [A, B] and `messages.clear()`
     * dropped C. The invariant is:
     *
     *   CACHE MAY PAINT FIRST. CACHE MAY NEVER OVERWRITE AUTHORITATIVE ROOM STATE
     *   THAT HAS ALREADY ARRIVED.
     */
    @Volatile
    var authorityRevision: Long = 0L
        private set

    /** Records that an authoritative window was just published. */
    fun markAuthoritativePaint() {
        authorityRevision += 1L
    }

    /**
     * Per-thread revisions. Keyed by thread id when known, else by the phone
     * key hash — so every entry stored for thread 7 (with any phone key) is
     * invalidated by one `invalidateThread(7)`.
     */
    private val revisions = HashMap<Long, Long>()

    private val lock = Any()

    /**
     * Backwards-compatible alias for the pre-v2.6.13 global generation.
     *
     * Kept so existing callers outside this file (HomeViewModel) keep
     * compiling; they should migrate to [invalidateAll]. The getter/setter
     * delegate to [globalEpoch].
     */
    @Deprecated(
        "Use invalidateAll() or invalidateThread()",
        ReplaceWith("ThreadMessageCache.invalidateAll()")
    )
    var generation: Long
        get() = globalEpoch
        set(value) {
            globalEpoch = value
        }

    private class Entry(
        val messages: List<Sms>,
        val cachedAtGlobalEpoch: Long,
        val cachedAtThreadRevision: Long,
        val cachedAtMillis: Long
    )

    private val lru = object : LinkedHashMap<Long, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Entry>?): Boolean =
            size > MAX_THREADS
    }

    /** Store a freshly loaded thread under the current epoch + revision. */
    fun put(threadKey: Long, phoneKey: String, messages: List<Sms>) {
        if (messages.isEmpty()) return
        val trimmed = if (messages.size > MAX_PER_THREAD)
            messages.subList(messages.size - MAX_PER_THREAD, messages.size)
        else messages
        val key = keyFor(threadKey, phoneKey)
        val epoch = globalEpoch
        synchronized(lock) {
            lru[key] = Entry(
                messages = trimmed,
                cachedAtGlobalEpoch = epoch,
                cachedAtThreadRevision = revision(revisionKey(threadKey, phoneKey)),
                cachedAtMillis = System.currentTimeMillis()
            )
        }
    }

    /**
     * Append an outgoing message to the cached thread WITHOUT bumping any
     * revision: the entry stays "fresh" so the next open paints it instantly.
     * The provider refresh on next load reconciles ids/dates.
     */
    fun append(threadKey: Long, phoneKey: String, sms: Sms) {
        val key = keyFor(threadKey, phoneKey)
        synchronized(lock) {
            val existing = lru[key] ?: return
            if (existing.messages.any { MessageIdentity.keyOf(it) == MessageIdentity.keyOf(sms) }) return
            val updated = existing.messages + sms
            val trimmed = if (updated.size > MAX_PER_THREAD)
                updated.subList(updated.size - MAX_PER_THREAD, updated.size)
            else updated
            lru[key] = Entry(
                messages = trimmed,
                cachedAtGlobalEpoch = existing.cachedAtGlobalEpoch,
                cachedAtThreadRevision = existing.cachedAtThreadRevision,
                cachedAtMillis = System.currentTimeMillis()
            )
        }
    }

    /**
     * Returns the cached thread ONLY when nothing has changed since it was
     * stored (same global epoch AND same thread revision). Null → caller must
     * do a full load.
     */
    fun getIfFresh(threadKey: Long, phoneKey: String): List<Sms>? {
        val entry = synchronized(lock) { lru[keyFor(threadKey, phoneKey)] } ?: return null
        return if (isFresh(entry, threadKey, phoneKey) && entry.messages.isNotEmpty())
            entry.messages
        else null
    }

    /**
     * Returns whatever is cached regardless of freshness (instant paint), plus
     * whether the caller should still revalidate.
     */
    fun getStale(threadKey: Long, phoneKey: String): Pair<List<Sms>, Boolean>? {
        val entry = synchronized(lock) { lru[keyFor(threadKey, phoneKey)] } ?: return null
        return entry.messages to !isFresh(entry, threadKey, phoneKey)
    }

    /** Drop one cache entry entirely (e.g. after deletion). */
    fun invalidate(threadKey: Long, phoneKey: String) {
        synchronized(lock) { lru.remove(keyFor(threadKey, phoneKey)) }
    }

    /**
     * One KNOWN thread changed: bump only its revision. Entries for that
     * thread (any phone key) are no longer fresh; B/C/D are untouched.
     */
    fun invalidateThread(threadKey: Long, phoneKey: String = "") {
        val key = revisionKey(threadKey, phoneKey)
        synchronized(lock) { revisions[key] = (revisions[key] ?: 0L) + 1L }
    }

    /**
     * Unknown/global recovery: every cached thread is stale from here on.
     * Entries are invalidated, not deleted — a stale entry still powers an
     * instant paint before revalidation.
     */
    fun invalidateAll() {
        synchronized(lock) { globalEpoch += 1L }
    }

    private fun isFresh(entry: Entry, threadKey: Long, phoneKey: String): Boolean =
        entry.cachedAtGlobalEpoch == globalEpoch &&
            entry.cachedAtThreadRevision == revision(revisionKey(threadKey, phoneKey))

    private fun revision(key: Long): Long = synchronized(lock) { revisions[key] ?: 0L }

    /** Thread-scoped revision key: thread id wins, phone hash for new chats. */
    private fun revisionKey(threadKey: Long, phoneKey: String): Long =
        if (threadKey != 0L) threadKey
        else phoneKey.hashCode().toLong() and 0x7FFFFFFFL

    private fun keyFor(threadKey: Long, phoneKey: String): Long {
        // Combine thread id with a stable hash of the phone so both lookup
        // paths (by-thread and by-phone for new chats) hit the same entry.
        val p = phoneKey.hashCode().toLong() and 0x7FFFFFFFL
        return (threadKey shl 32) or p
    }

    /** Test seam. Never call from production code. */
    internal fun resetForTest() {
        synchronized(lock) {
            lru.clear()
            revisions.clear()
            globalEpoch = 0L
            authorityRevision = 0L
        }
    }
}
