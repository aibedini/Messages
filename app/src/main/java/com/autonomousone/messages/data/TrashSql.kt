package com.autonomousone.messages.data

/**
 * The exact SQL of the TRASH feature (v3.4.0 FEATURE 8): the ACTIVE-UI
 * projections Home reads, and the ONE permanent-delete range a purge may touch.
 *
 * WHY THESE ARE NAMED CONSTANTS
 * -----------------------------
 * "The conversation disappears", "the projection rolls back to the newest
 * ACTIVE message" and "a purge deletes ONLY the tombstone's snapshot" are SQL
 * behaviours. Keeping them as constants lets a JVM test execute the REAL
 * statements against a real in-memory SQLite database built from Room's own
 * schema (`TrashSqlTest`) instead of re-typing the SQL, which could not catch
 * drift.
 *
 * The ACTIVE-UI predicate and the tombstone predicate are NOT re-typed here:
 * both are concatenated from [MessageCutoff], so the Kotlin mirror pinned by
 * `MessageCutoffTest` and every SQL site can never disagree.
 *
 * RAW MIRROR / ACTIVE UI / TRASH
 * ------------------------------
 *  - RAW MIRROR: `messages`, untouched. Sync, integrity audit and the repair
 *    queue read it, and provider rows still exist until a purge.
 *  - ACTIVE UI: raw MINUS individually-trashed state MINUS rows hidden by a
 *    thread tombstone. The `conversations` projection (what Home shows) is an
 *    ACTIVE-UI projection, which is why every projection builder uses the
 *    ACTIVE queries below rather than `newestForThread`.
 *  - TRASH: the tombstone itself, plus (after a confirmed provider delete) the
 *    rows the tombstone stood for.
 */

/**
 * The newest ACTIVE row of one conversation.
 *
 * Order is the canonical `date DESC, source DESC, providerId DESC`, shared with
 * `MessageDao.newestForThread` so "newest" means the same thing on every path;
 * the ONLY difference is the ACTIVE-UI filter. Returns null when every row of
 * the thread is hidden (an individually-trashed row or a trashed-conversation
 * snapshot), which is exactly the signal that the conversation must leave Home.
 */
const val NEWEST_ACTIVE_FOR_THREAD_SQL: String =
    "SELECT m.* FROM messages m " +
        "WHERE m.threadId = :threadId AND " + MessageCutoff.ACTIVE_MESSAGE_FILTER_SQL + " " +
        "ORDER BY m.date DESC, m.source DESC, m.providerId DESC LIMIT 1"

/**
 * ACTIVE unread count of one conversation.
 *
 * Deliberately the same predicate as `MessageDao.countUnread` (read = 0 AND
 * type = 1, i.e. incoming only) with the ACTIVE-UI filter added: a badge over
 * messages the user cannot see would be unexplainable.
 */
const val COUNT_ACTIVE_UNREAD_SQL: String =
    "SELECT COUNT(*) FROM messages m " +
        "WHERE m.threadId = :threadId AND m.read = 0 AND m.type = 1 AND " +
        MessageCutoff.ACTIVE_MESSAGE_FILTER_SQL

/**
 * Newest ACTIVE row per thread, in ONE query.
 *
 * Same correlated-rowid shape as `MessageDao.newestPerThread` (one indexed
 * lookup per thread, no `SELECT *` + Kotlin filter), with the ACTIVE-UI filter
 * applied to the row the correlated subquery selected. A thread whose ONLY rows
 * are trashed is therefore absent from the result — which is what makes the
 * recovery rebuild drop its conversation projection instead of resurrecting a
 * trashed snippet.
 */
const val NEWEST_ACTIVE_PER_THREAD_SQL: String =
    "SELECT m.* FROM messages m " +
        "WHERE " + MessageCutoff.ACTIVE_MESSAGE_FILTER_SQL + " " +
        "AND m.rowid = (" +
        "SELECT m2.rowid FROM messages m2 " +
        "WHERE m2.threadId = m.threadId " +
        "ORDER BY m2.date DESC, m2.source DESC, m2.providerId DESC LIMIT 1)"

/**
 * ACTIVE unread count per thread — the aggregate twin of
 * `COUNT_ACTIVE_UNREAD_SQL`, used by the recovery rebuild so its read count
 * stays constant instead of one read per conversation.
 */
const val UNREAD_ACTIVE_COUNTS_BY_THREAD_SQL: String =
    "SELECT m.threadId AS threadId, COUNT(*) AS unreadCount FROM messages m " +
        "WHERE m.read = 0 AND m.type = 1 AND " + MessageCutoff.ACTIVE_MESSAGE_FILTER_SQL + " " +
        "GROUP BY m.threadId"

/**
 * PERMANENT purge of ONE tombstone's Room range.
 *
 * The predicate is the tombstone's own HIDDEN range, joined against the LIVE
 * tombstone row, so a purge can only ever delete what the tombstone actually
 * hid: everything at-or-before the cutoff for the cutoff's source, plus
 * strictly-older rows of the other source. A message that arrived AFTER the
 * cutoff is never in this set — the cross-source guard that keeps a brand-new
 * message visible is the same predicate here, so it cannot be deleted by a
 * purge either.
 *
 * MUST run while the tombstone row still exists (the purge flow deletes the
 * tombstone only after this returned); `WHERE rowid IN (...)` keeps the
 * statement bounded to the thread's own snapshot instead of scanning the table.
 */
const val DELETE_TRASHED_SNAPSHOT_SQL: String =
    "DELETE FROM messages WHERE rowid IN (" +
        "SELECT m.rowid FROM messages m " +
        "JOIN trashed_threads t ON t.threadId = m.threadId " +
        "WHERE m.threadId = :threadId AND " + MessageCutoff.HIDDEN_BY_TOMBSTONE_SQL + ")"

/**
 * User state (star / individual trash / keep-from-OTP) of the purged snapshot.
 *
 * There is deliberately NO foreign key from `message_user_state` to `messages`
 * (see [MessageUserStateEntity]), so this cleanup is explicit. It is scoped to
 * the SAME snapshot as [DELETE_TRASHED_SNAPSHOT_SQL] and MUST run BEFORE the
 * messages rows are removed — while the rows (and therefore the join) still
 * exist. A message newer than the cutoff keeps its star: only the snapshot is
 * cleaned.
 */
const val DELETE_TRASHED_SNAPSHOT_USER_STATE_SQL: String =
    "DELETE FROM message_user_state WHERE EXISTS (" +
        "SELECT 1 FROM messages m " +
        "JOIN trashed_threads t ON t.threadId = m.threadId " +
        "WHERE m.source = message_user_state.source " +
        "AND m.providerId = message_user_state.providerId " +
        "AND m.threadId = :threadId AND " + MessageCutoff.HIDDEN_BY_TOMBSTONE_SQL + ")"
