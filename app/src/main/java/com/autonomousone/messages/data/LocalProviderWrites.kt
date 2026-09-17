package com.autonomousone.messages.data

/**
 * Registry of provider writes THIS app made that we already know how to
 * reconcile locally (mark-read sweeps, deletes, sends).
 *
 * Why: a bulk mark-read on a conversation fires a ContentObserver burst whose
 * URIs often carry no row id (content://sms, .../thread/N). Mapping every
 * id-less URI to ReconcileRequest.FullSync meant that simply OPENING a chat
 * could trigger a dual-source reconcile racing the backfill crawl.
 *
 * IMPORTANT — this used to be a ONE-SHOT claim: the first id-less callback
 * consumed the hint, so the 2nd/3rd callback of the SAME mark-read burst (SMS +
 * MMS + threads table) found nothing and escalated to FullSync. The hint is now
 * an OPERATION TOKEN that stays valid for its whole window and is shared by
 * every callback the operation causes:
 *
 *   operationId, kind, threadId, startedAt, expiresAt
 *
 * It is a hint, never a source of truth, and only ever NARROWS a repair — it can
 * never suppress an unrelated external provider change (an unknown event with
 * no matching token still reconciles).
 */
object LocalProviderWrites {

    private const val MAX_ENTRIES = 32

    /** How long a note stays believable. */
    const val WINDOW_MS = 2_000L

    enum class Kind { MARK_READ, DELETE_THREAD, SEND }

    data class Entry(
        val operationId: Long,
        val kind: Kind,
        val threadId: Long,
        val startedAt: Long,
        val expiresAt: Long
    )

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private var nextOperationId = 1L

    fun noteMarkRead(threadId: Long) = note(Kind.MARK_READ, threadId)

    fun noteSend(threadId: Long) = note(Kind.SEND, threadId)

    private fun note(kind: Kind, threadId: Long) {
        if (threadId <= 0L) return // address-only fallbacks can't be targeted
        val now = System.currentTimeMillis()
        synchronized(lock) {
            entries.addLast(
                Entry(
                    operationId = nextOperationId++,
                    kind = kind,
                    threadId = threadId,
                    startedAt = now,
                    expiresAt = now + WINDOW_MS
                )
            )
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
    }

    /**
     * Newest still-valid mark-read operation, or null.
     *
     * NON-CONSUMING on purpose: one mark-read legitimately causes several
     * provider callbacks, and every one of them must be able to narrow itself to
     * that thread. The token expires by time, not by being read.
     */
    fun activeMarkRead(now: Long = System.currentTimeMillis()): Entry? = synchronized(lock) {
        prune(now)
        entries.lastOrNull { it.kind == Kind.MARK_READ }
    }

    /** Any still-valid self-write operation (mark-read, delete, send). */
    fun activeOperation(now: Long = System.currentTimeMillis()): Entry? = synchronized(lock) {
        prune(now)
        entries.lastOrNull()
    }

    private fun prune(now: Long) {
        entries.removeAll { it.expiresAt <= now }
    }

    /** Test hook: forget every note. */
    internal fun clearForTest() = synchronized(lock) {
        entries.clear()
        nextOperationId = 1L
    }
}
