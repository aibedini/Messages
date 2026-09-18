package com.autonomousone.messages.viewmodel

import com.autonomousone.messages.model.Sms

/**
 * One optimistic prediction about a single thread, layered ON TOP of the
 * durable Room conversation projection while the underlying write is still in
 * flight.
 *
 * Phase 8: after the Room bootstrap window is ready, `ConversationDao.observeAll()`
 * is the single durable owner of the Home list. Realtime events no longer
 * REPLACE that list — they add an override here and [HomeConversationState.render]
 * merges the two:
 *
 *     RenderedConversations = RoomConversations + OptimisticOverrides
 *
 * Every override carries an identity ([threadId]) and a monotonic [revision].
 * The revision orders multiple updates to the SAME thread (the newest wins) so
 * a stale event can never shadow a fresher one. Each field is independently
 * reconcilable so an entry can carry several predictions at once (for example a
 * brand-new incoming row that the user then opens and reads).
 *
 * This file deliberately imports NOTHING from Android/Compose/Room so the merge
 * rules are exercised by a plain-JVM unit test (HomeConversationStateTest).
 */
data class ConversationOverride(
    /** Thread identity. Overrides with a non-positive id are never recorded. */
    val threadId: Long,
    /** Monotonic order assigned by [HomeConversationState]; newer revisions win. */
    val revision: Long,
    /**
     * Replacement row for an incoming or pending-outgoing message. It wins over
     * a Room row that is OLDER than it (row.date < override.date); once Room
     * reports a row at or after that date the override is retired.
     */
    val row: Sms? = null,
    /** Local mark-read: force unread == false on the durable row. */
    val forceRead: Boolean = false,
    /** Immediate delete/removal: suppress the thread entirely. */
    val removed: Boolean = false
)

/** The merged Home projection: Room + overrides, split and pin-sorted. */
data class RenderedConversations(
    val main: List<Sms>,
    val archived: List<Sms>
)

/**
 * Stateful, Android-free holder for the optimistic overrides.
 *
 * Contract:
 *  - [onRoomConversations] is called with every durable Room emission. It only
 *    RETIRES overrides that Room has caught up with; it never renders.
 *  - [render] is the single merge point. It is pure with respect to the state
 *    except that it reads the override map; the Room list is passed in so the
 *    caller can apply its own view of the data (for example a blocklist filter).
 *  - An override is retired as soon as Room contains the state it predicted, so
 *    an overlay can never live forever.
 *  - An override always wins over a Room emission it has not caught up with, so
 *    an OLDER Room emission can never overwrite a NEWER optimistic override.
 */
class HomeConversationState {

    private var nextRevision: Long = 0L
    private val overrides = LinkedHashMap<Long, ConversationOverride>()

    /** Number of live overrides. Test/diagnostics seam. */
    val overrideCount: Int get() = overrides.size

    /**
     * Records the latest durable Room emission and retires every override Room
     * has caught up with. Rendering is a separate step ([render]).
     */
    fun onRoomConversations(rows: List<Sms>) {
        reconcile(rows)
    }

    /** New incoming conversation observed before Room commits the row. */
    fun recordIncoming(row: Sms) {
        upsert(row.threadId) { it.copy(row = row) }
    }

    /** Outgoing message that is not yet visible in the Room projection. */
    fun recordOutgoingPending(row: Sms) {
        upsert(row.threadId) { it.copy(row = row) }
    }

    /** Local mark-read: the durable unread count is zero in the UI already. */
    fun recordMarkRead(threadId: Long) {
        upsert(threadId) { it.copy(forceRead = true) }
    }

    /** Immediate delete/removal: hide the thread before the durable delete lands. */
    fun recordRemoval(threadId: Long) {
        upsert(threadId) { it.copy(removed = true) }
    }

    /**
     * Undo of a [recordRemoval]: the removal flag is dropped but any coexisting
     * incoming/outgoing prediction is preserved. The entry is discarded when it
     * carries nothing else.
     */
    fun clearRemoval(threadId: Long) {
        val entry = overrides[threadId] ?: return
        val cleared = entry.copy(removed = false, revision = ++nextRevision)
        if (cleared.row == null && !cleared.forceRead) overrides.remove(threadId)
        else overrides[threadId] = cleared
    }

    /**
     * Drops every prediction for the thread. Used after a delete settles (or
     * fails) so the durable Room projection becomes the truth again, and as the
     * undo path when the pending job is already gone.
     */
    fun clearOverride(threadId: Long) {
        overrides.remove(threadId)
    }

    fun hasOverride(threadId: Long): Boolean = overrides.containsKey(threadId)

    /** Optimistic rows currently predicted (used to resolve a thread by phone). */
    fun overrideRows(): List<Sms> = overrides.values.mapNotNull { it.row }

    /**
     * Merge: durable [room] rows plus the live overrides, split into the main
     * and archived tabs by [archived], each sorted pinned-first then newest
     * first. Pin/archive membership is decided by threadId and therefore
     * survives an incoming update to the thread.
     */
    fun render(
        room: List<Sms>,
        archived: Set<Long>,
        pinned: Set<Long>
    ): RenderedConversations {
        val byThread = LinkedHashMap<Long, Sms>(room.size)
        room.forEach { row -> if (row.threadId !in byThread) byThread[row.threadId] = row }

        // Ascending revision: the newest prediction for a thread is applied last.
        overrides.values.sortedBy { it.revision }.forEach { override ->
            if (override.removed) {
                byThread.remove(override.threadId)
                return@forEach
            }
            val row = override.row ?: byThread[override.threadId]
            val merged = if (row != null && override.forceRead) row.copy(unread = false) else row
            if (merged != null) byThread[override.threadId] = merged
        }

        val main = ArrayList<Sms>(byThread.size)
        val archivedOut = ArrayList<Sms>()
        byThread.values.forEach { row ->
            if (row.threadId in archived) archivedOut.add(row) else main.add(row)
        }
        sortByPin(main, pinned)
        sortByPin(archivedOut, pinned)
        return RenderedConversations(main, archivedOut)
    }

    private fun upsert(
        threadId: Long,
        transform: (ConversationOverride) -> ConversationOverride
    ) {
        if (threadId <= 0L) return
        val current = overrides[threadId] ?: ConversationOverride(threadId, nextRevision)
        overrides[threadId] = transform(current).copy(revision = ++nextRevision)
    }

    /**
     * Retire every override Room has caught up with.
     *
     * A row override is caught up when Room holds a row at or after its date (or
     * Room intentionally has no row yet for a removal); a read override when the
     * durable row is already read; a removal when the row is gone. An entry is
     * dropped only when EVERY prediction it carries is satisfied.
     */
    private fun reconcile(rows: List<Sms>) {
        if (overrides.isEmpty()) return
        val byThread = HashMap<Long, Sms>(rows.size)
        rows.forEach { row -> if (row.threadId !in byThread) byThread[row.threadId] = row }

        val iterator = overrides.entries.iterator()
        while (iterator.hasNext()) {
            val override = iterator.next().value
            val roomRow = byThread[override.threadId]
            val overrideRow = override.row

            val rowCaughtUp = overrideRow == null ||
                (roomRow != null && roomRow.date >= overrideRow.date)
            val readCaughtUp = !override.forceRead || roomRow == null || !roomRow.unread
            val removalCaughtUp = !override.removed || roomRow == null

            if (rowCaughtUp && readCaughtUp && removalCaughtUp) iterator.remove()
        }
    }

    private fun sortByPin(list: MutableList<Sms>, pinned: Set<Long>) {
        list.sortWith { a, b ->
            val pa = a.threadId in pinned
            val pb = b.threadId in pinned
            when {
                pa != pb -> if (pa) -1 else 1
                else -> b.date.compareTo(a.date)
            }
        }
    }
}
