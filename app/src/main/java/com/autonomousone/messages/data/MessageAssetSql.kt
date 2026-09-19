package com.autonomousone.messages.data

/**
 * The SINGLE source of truth for every `message_assets` statement.
 *
 * WHY THE SQL LIVES HERE AND NOT INLINE IN [MessageAssetDao]
 * --------------------------------------------------------
 * The Media / Links / Files browser depends on three SQL *contracts* that a
 * Room-only test cannot reach without an instrumented device:
 *
 *  1. re-index is an idempotent UPSERT, so a message can never grow a duplicate
 *     asset for the same MMS part or the same normalized link;
 *  2. deleting a message takes exactly its own assets with it (composite
 *     identity — SMS 100 and MMS 100 must not share an asset);
 *  3. paging is newest-first and BOUNDED (`LIMIT`), never a full scan.
 *
 * Hoisting the SQL into a plain Kotlin object lets `MessageAssetSqlTest` run the
 * REAL statements against a REAL SQLite database (the same sqlite-jdbc harness
 * MigrationToV16SqlTest uses) instead of asserting on a re-typed copy.
 *
 * IMPORTANT — the DAO does NOT reference these constants.
 * Room's KSP processor rejects a string TEMPLATE as an annotation argument
 * (`@Query("${MessageAssetSql.X}")`) with "No property named value was found in
 * annotation Query", which fails the whole code-generation step. The production
 * annotation therefore carries the statement as a LITERAL, and
 * `MessageAssetSqlTest` asserts each literal against its constant here, so the
 * one tested copy and the one executing copy still cannot drift apart.
 *
 * The ACTIVE-UI predicate is deliberately NOT applied here: the v16 schema
 * scaffolding defined the asset tabs as a per-conversation *content* browser
 * (`pageByKind(threadId, kind, …)`), and a message's shared media does not
 * change identity when the user trashes the message. Assets are cleaned up when
 * the provider proves the message gone (DELETE_FOR_MESSAGE_SQL + orphans).
 */
object MessageAssetSql {

    /**
     * Offset paging is safe HERE (and only here): the window is one conversation
     * and one kind, so the row count is small and the query is index-backed on
     * `(threadId, date)`. This is not the 360K-row history table.
     */
    const val PAGE_BY_KIND_SQL: String =
        "SELECT * FROM message_assets WHERE threadId = :threadId AND kind = :kind " +
            "ORDER BY date DESC, assetKey DESC LIMIT :limit OFFSET :offset"

    const val COUNT_BY_KIND_SQL: String =
        "SELECT COUNT(*) FROM message_assets WHERE threadId = :threadId AND kind = :kind"

    /**
     * LINKS tab page: the asset PLUS the message body it came from.
     *
     * The Links tab shows a locally derived snippet ("title/snippet … from the
     * message body"), and the body is not — and must not be — stored on the asset
     * row. A LEFT JOIN fetches it for the whole page in ONE query, which keeps the
     * tab at O(page size) instead of O(page size) extra message lookups.
     *
     * Ordering and bounds are identical to [PAGE_BY_KIND_SQL] so both paths page
     * the same way and can never disagree about "newest first".
     */
    const val PAGE_LINKS_WITH_BODY_SQL: String =
        "SELECT a.assetKey AS assetKey, a.source AS source, a.providerId AS providerId, " +
            "a.threadId AS threadId, a.kind AS kind, a.value AS value, " +
            "a.mimeType AS mimeType, a.displayName AS displayName, a.date AS date, " +
            "m.body AS body " +
            "FROM message_assets a LEFT JOIN messages m " +
            "ON m.source = a.source AND m.providerId = a.providerId " +
            "WHERE a.threadId = :threadId AND a.kind = :kind " +
            "ORDER BY a.date DESC, a.assetKey DESC LIMIT :limit OFFSET :offset"

    /** Per-message lookup so a bubble can render its own link/attachment chip. */
    const val FOR_MESSAGE_SQL: String =
        "SELECT * FROM message_assets WHERE source = :source AND providerId = :providerId " +
            "ORDER BY kind ASC, value ASC"

    /**
     * A deleted message must take its assets with it. No foreign key (see
     * [MessageAssetEntity]): the delete is explicit and composite-keyed.
     */
    const val DELETE_FOR_MESSAGE_SQL: String =
        "DELETE FROM message_assets WHERE source = :source AND providerId = :providerId"

    /**
     * Kind-scoped replace used by re-indexing: a message whose body changed may
     * have LOST a link, and an MMS whose parts changed may have lost an
     * attachment. Deleting the kind's set and re-inserting the freshly read set
     * is what keeps the index convergent — and it is safe precisely because the
     * caller only does it when the authoritative source (the provider row, or a
     * SUCCESSFUL part read) actually answered.
     */
    const val DELETE_FOR_MESSAGE_KIND_SQL: String =
        "DELETE FROM message_assets " +
            "WHERE source = :source AND providerId = :providerId AND kind = :kind"

    /**
     * Explicit orphan cleanup — the replacement for an FK CASCADE. Only ever
     * called for identities the provider has PROVEN gone (or after the coupled
     * local message row was removed), never on a routine refresh.
     */
    const val DELETE_ORPHANS_SQL: String =
        "DELETE FROM message_assets WHERE NOT EXISTS (" +
            "SELECT 1 FROM messages m WHERE m.source = message_assets.source " +
            "AND m.providerId = message_assets.providerId)"

    /**
     * One checkpointed backfill batch.
     *
     * KEYSET, never OFFSET: the cursor is the (date, source, providerId) tuple of
     * the last covered row, which is exactly the canonical ordering
     * `date DESC, source DESC, providerId DESC` and exactly the primary-key
     * lookup order — so batch N+1 costs the same as batch 1 and a resumed sweep
     * never re-walks 360K rows.
     *
     * Deliberately NOT "messages that have no assets": a plain SMS with no link
     * legitimately produces ZERO asset rows, so such a predicate would return it
     * forever and the sweep would never terminate. The cursor walks `messages`.
     */
    const val BACKFILL_BATCH_SQL: String =
        "SELECT source, providerId, threadId, body, date FROM messages " +
            "WHERE (date < :afterDate " +
            "OR (date = :afterDate AND (source < :afterSource " +
            "OR (source = :afterSource AND providerId < :afterProviderId)))) " +
            "ORDER BY date DESC, source DESC, providerId DESC LIMIT :limit"
}
