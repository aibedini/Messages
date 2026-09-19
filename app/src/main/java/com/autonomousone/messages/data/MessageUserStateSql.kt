package com.autonomousone.messages.data

/**
 * The exact SQL of the message user-state writers and readers (v3.4.0 FEATURE 7).
 *
 * WHY THESE ARE NAMED CONSTANTS
 * -----------------------------
 * Starring is the one piece of user state that must SURVIVE a provider refresh
 * and must be cleaned up ONLY through an explicit orphan path. Those are SQL
 * behaviours, so they are pinned by tests that execute the REAL statements
 * against a real in-memory SQLite database built from Room's own `16.json`
 * schema (`MessageUserStateSqlTest`). If the statement lived only as an inline
 * annotation string, that test could only re-type it — and a re-typed copy
 * cannot catch drift.
 *
 * IMPORTANT — the DAO does NOT reference these constants.
 * Room's KSP processor rejects a string TEMPLATE as an annotation argument
 * (`@Query("$SET_STARRED_SQL")`) with "No property named value was found in
 * annotation Query", which fails the whole KSP/Room code-generation step. The
 * production annotation is therefore a LITERAL of exactly the text below, and
 * `MessageUserStateSqlTest` asserts the literal against this constant, so the
 * tested copy and the executing copy cannot drift apart.
 *
 * The ACTIVE-UI predicate is NOT re-typed here either: it is concatenated from
 * [MessageCutoff], exactly like every other UX query.
 */

/**
 * Star / unstar ONE message.
 *
 * The insert branch carries pure defaults and the conflict branch patches ONLY
 * `starred`/`starredAt`, so starring can never un-trash a row and un-starring can
 * never clear `keepFromOtpCleanup` — the two flags that exempt a message from OTP
 * cleanup survive independently.
 */
const val SET_STARRED_SQL: String =
    "INSERT INTO message_user_state " +
        "(source, providerId, threadId, starred, starredAt, trashedAt, purgeAt, " +
        "keepFromOtpCleanup, updatedAt) " +
        "VALUES (:source, :providerId, :threadId, :starred, :starredAt, 0, 0, 0, :now) " +
        "ON CONFLICT(source, providerId) DO UPDATE SET " +
        "starred = excluded.starred, " +
        "starredAt = excluded.starredAt, " +
        "updatedAt = excluded.updatedAt"

/** Opt a message out of (or back into) the global OTP cleanup. */
const val SET_KEEP_FROM_OTP_CLEANUP_SQL: String =
    "INSERT INTO message_user_state " +
        "(source, providerId, threadId, starred, starredAt, trashedAt, purgeAt, " +
        "keepFromOtpCleanup, updatedAt) " +
        "VALUES (:source, :providerId, :threadId, 0, 0, 0, 0, :keep, :now) " +
        "ON CONFLICT(source, providerId) DO UPDATE SET " +
        "keepFromOtpCleanup = excluded.keepFromOtpCleanup, " +
        "updatedAt = excluded.updatedAt"

/** Count of starred rows in one conversation, for the Conversation Info badge. */
const val COUNT_STARRED_IN_THREAD_SQL: String =
    "SELECT COUNT(*) FROM message_user_state WHERE threadId = :threadId AND starred = 1"

/**
 * Explicit orphan cleanup — the replacement for an FK CASCADE.
 *
 * Only ever runs for identities the provider has PROVEN gone. A routine provider
 * refresh must NOT call it: a refresh that deletes and re-inserts a row would
 * otherwise take the user's star (and trash flag) with it.
 */
const val DELETE_ORPHANS_SQL: String =
    "DELETE FROM message_user_state " +
        "WHERE NOT EXISTS (" +
        "SELECT 1 FROM messages m " +
        "WHERE m.source = message_user_state.source " +
        "AND m.providerId = message_user_state.providerId)"

/** The selected columns of a starred row: composite identity + content. */
private const val STARRED_COLUMNS: String =
    "m.source AS source, m.providerId AS providerId, m.threadId AS threadId, " +
        "m.body AS body, m.date AS date, m.rawAddress AS rawAddress, " +
        "m.normalizedAddress AS normalizedAddress, us.starredAt AS starredAt"

/**
 * Canonical newest-first order. Mirrors `MessageDao.newestPerThread`'s
 * `date DESC, source DESC, providerId DESC` so the Starred list, Home and the
 * conversation window agree on what "newest" means, and so SMS 100 / MMS 100 tie
 * deterministically instead of by insertion order.
 */
private const val STARRED_ORDER: String =
    "ORDER BY m.date DESC, m.source DESC, m.providerId DESC LIMIT :limit OFFSET :offset"

/**
 * Global Starred browser. ACTIVE-UI filtered: a starred message inside a trashed
 * conversation belongs to Trash, not to Starred.
 */
const val STARRED_PAGE_SQL: String =
    "SELECT " + STARRED_COLUMNS +
        " FROM message_user_state us" +
        " JOIN messages m ON m.source = us.source AND m.providerId = us.providerId" +
        " WHERE us.starred = 1 AND " + MessageCutoff.ACTIVE_MESSAGE_FILTER_SQL + " " +
        STARRED_ORDER

/** In-conversation Starred list: the same rows pinned to one thread. */
const val STARRED_PAGE_IN_THREAD_SQL: String =
    "SELECT " + STARRED_COLUMNS +
        " FROM message_user_state us" +
        " JOIN messages m ON m.source = us.source AND m.providerId = us.providerId" +
        " WHERE us.starred = 1 AND us.threadId = :threadId AND " +
        MessageCutoff.ACTIVE_MESSAGE_FILTER_SQL + " " +
        STARRED_ORDER
