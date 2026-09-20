package com.autonomousone.messages.repository

/**
 * v3.4.5 P0 — the authority state machine for ONE conversation open.
 *
 * ── The production bug this exists to make impossible ────────────────────────
 *
 * A conversation could open showing NO history, or only the single bubble
 * Home's launch snapshot provides. The screen had a conversation, the loader
 * had delivered nothing, and closing/reopening the app "fixed" it — the
 * signature of an ordering race, not of missing data.
 *
 * The sequence was:
 *
 *   startRoomTail()                     Room emits [] while the shadow is
 *                                       still backfilling
 *   ThreadMessageCache.markAuthoritativePaint()   ← the v3.4.3 guard, GLOBAL
 *   cache read returns a FRESH [A, B]
 *   authorityMoved == true              ← because of that empty emission
 *                                       (or of an unrelated conversation!)
 *   cache DISCARDED
 *   if (!stale.second) return@launch    ← "fresh cache" early-return still taken
 *   messages stays empty → LaunchPreview forever
 *
 * Three separate defects, all pinned here:
 *
 *  A. The authority clock was GLOBAL. An authoritative paint in conversation A
 *     discarded conversation B's cache. It is now per conversation
 *     ([ThreadMessageCache.authorityRevision]).
 *  B. An EMPTY Room tail claimed authority. An empty emission is not proof
 *     that a Home-visible conversation has no messages; it is the normal state
 *     of a Room shadow that has not caught up yet. [onRoomTail] refuses it.
 *  C. A DISCARDED cache could still finish the load. "Nothing was painted" and
 *     "everything that could be painted is on screen" are different states, and
 *     [mayFinishInitialLoad] only accepts the second.
 *
 * The original v3.4.3 protection is preserved verbatim: a cache read that
 * raced an authoritative window for the SAME conversation is discarded, so
 * Room's [A, B, C] can never be overwritten by the slower cache's [A, B].
 *
 * Pure and Android-free: this is a decision object over counts and clocks, so
 * every branch is exercised on the JVM without Room, a ContentResolver or a
 * ViewModel.
 */
class ConversationOpenLoad(
    /** Thread id when known, otherwise 0 (the phone key then owns identity). */
    val threadKey: Long,
    /** Normalized/raw address of the open conversation, when known. */
    val phoneKey: String
) {

    /** Which source owns the visible window right now. */
    enum class Source { NONE, CACHE, ROOM, PROVIDER, ERROR }

    /**
     * The authority revision of THIS conversation as it stood BEFORE the cache
     * read for this open started. Everything the cache does is judged against
     * this snapshot — never against a process-wide counter.
     */
    val capturedRevision: Long = ThreadMessageCache.authorityRevision(threadKey, phoneKey)

    /** At least one source actually published rows for this open. */
    var cachePainted: Boolean = false
        private set

    /** A non-empty Room window (tail or instant-open shadow) is on screen. */
    var roomPainted: Boolean = false
        private set

    /** The bounded provider page published rows for this open. */
    var providerPainted: Boolean = false
        private set

    /** The bounded provider page was attempted (success or failure). */
    var providerAttempted: Boolean = false
        private set

    /** The provider page failed (threw). */
    var failed: Boolean = false
        private set

    /** Rows the last provider attempt observed. 0 when it failed or was empty. */
    var providerRows: Int = 0
        private set

    /** Rows published by the winning source. Diagnostics only. */
    var paintedRows: Int = 0
        private set

    /** The authority revision of this conversation right now. */
    val authorityRevisionNow: Long
        get() = ThreadMessageCache.authorityRevision(threadKey, phoneKey)

    /** An authoritative window for THIS conversation arrived during the read. */
    val authorityMoved: Boolean
        get() = authorityRevisionNow != capturedRevision

    /** Which source owns the window (diagnostics + tests). */
    val source: Source
        get() = when {
            roomPainted -> Source.ROOM
            cachePainted -> Source.CACHE
            providerPainted -> Source.PROVIDER
            failed -> Source.ERROR
            else -> Source.NONE
        }

    /** At least one source published a usable window. */
    val hasUsableSource: Boolean
        get() = cachePainted || roomPainted || providerPainted

    /**
     * A reactive Room tail emission carrying [rows] rows.
     *
     * @return whether the caller may publish it. An EMPTY tail returns false:
     *   it neither publishes nor claims authority, so it can never suppress the
     *   cache/provider fallback. Explicit deletions do not depend on this —
     *   Trash, individual delete and conversation delete all write their own
     *   state and mutate the visible window directly.
     */
    fun onRoomTail(rows: Int): Boolean {
        if (rows <= 0) return false
        claimAuthority(rows)
        return true
    }

    /**
     * The instant-open Room shadow window (the fresh-process path). Same rule:
     * only a NON-EMPTY window is authoritative.
     */
    fun onRoomShadow(rows: Int): Boolean = onRoomTail(rows)

    /**
     * May the cache result of this open be published?
     *
     * MUST be evaluated on the thread that performs the publish, immediately
     * before the write: the Room publish and the cache publish both run on the
     * main dispatcher, so a check-then-write on that dispatcher cannot be
     * interleaved by the other. Evaluating it earlier (on IO) reopens the very
     * race this class closes.
     */
    fun mayPaintCache(): Boolean =
        !authorityMoved && !roomPainted && !providerPainted

    /** The cache result was published: [rows] rows are on screen. */
    fun onCachePainted(rows: Int) {
        cachePainted = true
        paintedRows = rows
    }

    /**
     * The initial load may stop here.
     *
     * "A FRESH cache was painted" is enough. "A non-empty authoritative Room
     * window is on screen" is enough. "A cache read was DISCARDED" is NOT
     * enough, which is the v3.4.5 fix: [cachePainted] is only true when
     * something was actually published.
     */
    fun mayFinishInitialLoad(cacheFresh: Boolean): Boolean =
        roomPainted || (cachePainted && cacheFresh)

    /** The bounded provider page returned [rows] rows (0 = genuinely empty). */
    fun onProviderResult(rows: Int) {
        providerAttempted = true
        providerRows = rows
        if (rows > 0) {
            providerPainted = true
            paintedRows = rows
        }
    }

    /** The bounded provider page threw. */
    fun onFailed() {
        failed = true
    }

    private fun claimAuthority(rows: Int) {
        ThreadMessageCache.markAuthoritativePaint(threadKey, phoneKey)
        roomPainted = true
        paintedRows = rows
    }
}

/**
 * Monotonic open generation. Every [next] call supersedes everything before
 * it, so a slow load of the PREVIOUS conversation can never paint over the
 * one the user is actually looking at.
 *
 * Extracted from the ViewModel (v3.4.5) so the "rapidly open A → B → C" guard
 * is verifiable without an Android ViewModel: the counter is the contract, the
 * ViewModel only reads it.
 */
class ConversationOpenGeneration {

    @Volatile
    var current: Long = 0L
        private set

    /** Supersedes every previous open and returns the token for this one. */
    fun next(): Long {
        current += 1L
        return current
    }

    /** Whether [token] still owns the screen. */
    fun isCurrent(token: Long): Boolean = token == current
}
