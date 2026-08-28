package com.autonomousone.messages.data

/**
 * Registry of provider writes THIS app made that we already know how to
 * reconcile locally (mark-read sweeps, deletes, sends).
 *
 * Why: a bulk mark-read on a conversation fires a ContentObserver burst
 * whose URIs often carry no row id (content://sms, .../thread/N). The old
 * ChangeRouter mapped every id-less URI to ReconcileRequest.FullSync —
 * so simply OPENING a chat could trigger a full dual-source window sync
 * racing the backfill crawl. That is load we create for ourselves.
 *
 * The write paths note what they touched here; ChangeRouter consumes the
 * entries within a short window and downgrades the unknown-URI fallback
 * to a targeted ForThread repair (or drops it entirely when the mutation
 * was already applied to the shadow by the write path itself).
 *
 * Small fixed-size ring: only freshness matters, this is a hint, never a
 * source of truth. Thread-safe by coarse lock — volume is a handful of
 * entries per user action.
 */
object LocalProviderWrites {

    private const val MAX_ENTRIES = 32

    /** How long an unclaimed note stays believable. */
    const val WINDOW_MS = 2_000L

    enum class Kind { MARK_READ, DELETE_THREAD, SEND }

    data class Entry(val kind: Kind, val threadId: Long, val at: Long)

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()

    fun noteMarkRead(threadId: Long) = note(Kind.MARK_READ, threadId)

    fun noteSend(threadId: Long) = note(Kind.SEND, threadId)

    private fun note(kind: Kind, threadId: Long) {
        if (threadId <= 0L) return // address-only fallbacks can't be targeted
        val now = System.currentTimeMillis()
        synchronized(lock) {
            entries.addLast(Entry(kind, threadId, now))
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
    }

    /**
     * True when a mark-read for [threadId] was noted within the window —
     * the caller (ChangeRouter) then reconciles THAT thread only instead
     * of the whole SMS+MMS universe. Entries are consumed so one write
     * cannot suppress many unrelated syncs.
     */
    fun claimRecentMarkRead(now: Long = System.currentTimeMillis()): Entry? {
        synchronized(lock) {
            prune(now)
            val idx = entries.indexOfFirst { it.kind == Kind.MARK_READ }
            return if (idx >= 0) entries.removeAt(idx) else null
        }
    }

    private fun prune(now: Long) {
        while (entries.isNotEmpty() && now - entries.first().at > WINDOW_MS) {
            entries.removeFirst()
        }
    }
}
