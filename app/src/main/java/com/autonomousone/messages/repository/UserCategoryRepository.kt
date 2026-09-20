package com.autonomousone.messages.repository

import android.content.Context
import androidx.room.withTransaction
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.UserCategoryAssignmentDao
import com.autonomousone.messages.data.UserCategoryAssignmentEntity
import com.autonomousone.messages.data.UserCategoryDao
import com.autonomousone.messages.data.UserCategoryEntity
import com.autonomousone.messages.utils.DiagnosticLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest
import java.util.UUID

// ═══════════════════════════════════════════════════════════════════════════════
// TYPED RESULTS
//
// Every write returns one of these. SQLite and Room exceptions never reach the UI:
// a duplicate name is a PRODUCT decision ("you already have this category"), not an
// error dialog, and a caller that has to catch `SQLiteConstraintException` to render
// that is a caller that will eventually render it as a crash.
// ═══════════════════════════════════════════════════════════════════════════════

sealed interface CreateCategoryResult {

    data class Created(val category: UserCategoryEntity) : CreateCategoryResult

    data object EmptyName : CreateCategoryResult

    data object TooLong : CreateCategoryResult

    /** Another category already owns this normalized name. */
    data object Duplicate : CreateCategoryResult
}

sealed interface RenameCategoryResult {

    data class Renamed(val category: UserCategoryEntity) : RenameCategoryResult

    data object NotFound : RenameCategoryResult

    data object EmptyName : RenameCategoryResult

    data object TooLong : RenameCategoryResult

    /** Another category already owns this normalized name. */
    data object Duplicate : RenameCategoryResult
}

sealed interface DeleteCategoryResult {

    /** The row is gone, and the FK cascade took its memberships with it. */
    data object Deleted : DeleteCategoryResult

    data object NotFound : DeleteCategoryResult
}

/**
 * The outcome of one scope's membership write.
 *
 * `Applied.inserted`/`Applied.removed` are ROW COUNTS, not a success flag: an
 * `inserted = 0` assign means every requested membership already existed, which is a
 * success (the state the caller asked for holds). Nothing is ever partially applied:
 * if any requested category id does not exist, NO row is written.
 */
sealed interface CategoryAssignmentResult {

    data class Applied(
        val inserted: Int,
        val removed: Int
    ) : CategoryAssignmentResult

    data class UnknownCategories(
        val categoryIds: Set<String>
    ) : CategoryAssignmentResult
}

/** The multi-select counterpart of [CategoryAssignmentResult]. */
sealed interface BatchAssignmentResult {

    data class Applied(
        val inserted: Int,
        val removed: Int,
        val scopes: Int
    ) : BatchAssignmentResult

    data class UnknownCategories(
        val categoryIds: Set<String>
    ) : BatchAssignmentResult
}

/**
 * `createAndAssign` is ONE operation, so it has ONE result: there is no state in
 * which the category exists but the user's selected conversations are unassigned.
 */
sealed interface CreateAndAssignResult {

    data class Created(
        val category: UserCategoryEntity,
        val assignedScopes: Int
    ) : CreateAndAssignResult

    data object EmptyName : CreateAndAssignResult

    data object TooLong : CreateAndAssignResult

    data object Duplicate : CreateAndAssignResult
}

/**
 * The ONE transaction boundary the repository uses.
 *
 * Production is `MessagesDatabase.withTransaction`. It is an explicit dependency so
 * the repository's atomicity contract — including the create-and-assign rollback — is
 * verifiable on the JVM against a real SQLite engine, without Room and without a
 * device.
 */
internal interface CategoryTransactionRunner {
    suspend fun <T> inTransaction(block: suspend () -> T): T
}

/**
 * CUSTOM category storage (v3.5.0 Phase 4).
 *
 * ── What this owns ───────────────────────────────────────────────────────────
 * Categories and their memberships. Nothing else. It never reads `messages`, never
 * touches the Telephony provider or the contacts provider, and never reads a message
 * body. Every statement it issues is a primary-key/index lookup or a bounded list read
 * over two conversation-scale tables, so a 360 000-message device pays exactly the
 * same cost for it as a fresh one.
 *
 * ── What this deliberately does NOT own ──────────────────────────────────────
 *  - **Smart Categories.** `ConversationPreferenceEntity.categoryOverride` is a
 *    different, single-valued axis. Creating or deleting a custom category must never
 *    read or write it. The two are independent: a conversation can be automatically
 *    `TRANSACTION` and simultaneously belong to `VPN`, `مشتری‌ها` and `مهم`.
 *  - **Conversations and messages.** Deleting a category deletes memberships (through
 *    the FK cascade) and nothing else. Deleting a conversation never deletes a
 *    membership.
 *  - **Home UI models.** [observeCategories] and [observeAssignments] expose the stored
 *    rows; projecting them into chips, badges or tri-state selection is the UI layer's
 *    job.
 *
 * ── Identity ─────────────────────────────────────────────────────────────────
 * A category IS its `categoryId` (a UUID). The name is presentation, and the
 * normalized name is duplicate protection — renaming `VPN` → `VPN Clients` cannot
 * orphan a single membership.
 *
 * ── Transactions ─────────────────────────────────────────────────────────────
 * Every compound write is ONE `withTransaction`. Public transactional methods never
 * call one another; each composes private `…InTransaction` helpers instead, so there
 * is exactly one commit point and no ambiguous nested-transaction semantics.
 */
class UserCategoryRepository internal constructor(
    private val categoryDao: UserCategoryDao,
    private val assignmentDao: UserCategoryAssignmentDao,
    private val transactions: CategoryTransactionRunner,
    /** Injectable so tests can assert on the exact ids written. */
    private val uuidFactory: () -> String = { UUID.randomUUID().toString() },
    /**
     * Diagnostics sink. Injected so a test can assert that a category NAME never
     * reaches the log.
     */
    private val diagnostics: (String, String) -> Unit = { category, message ->
        DiagnosticLog.event(category, message)
    }
) {

    constructor(
        database: MessagesDatabase,
        uuidFactory: () -> String = { UUID.randomUUID().toString() }
    ) : this(
        categoryDao = database.userCategoryDao(),
        assignmentDao = database.userCategoryAssignmentDao(),
        transactions = object : CategoryTransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> T): T =
                database.withTransaction { block() }
        },
        uuidFactory = uuidFactory
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // OBSERVATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * The user's categories in their explicit order.
     *
     * The Flow is Room's own invalidation-tracked stream, so a create/rename/delete
     * updates every observer without polling. Building the chip row (system categories
     * first, unread counts, `99+`) is the Home layer's concern.
     */
    fun observeCategories(): Flow<List<UserCategoryEntity>> = categoryDao.observeAll()

    /**
     * Every membership. Deliberately the raw association table: it is one row per
     * (category, conversation), so it is conversation-scale and safe to observe whole.
     */
    fun observeAssignments(): Flow<List<UserCategoryAssignmentEntity>> =
        assignmentDao.observeAll()

    // ═══════════════════════════════════════════════════════════════════════════
    // CREATE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Create a category.
     *
     * The name is validated BEFORE the transaction opens (a rejected name must not cost
     * a write transaction), and the duplicate check inside it is an optimization, not
     * the guarantee: the UNIQUE index on `normalizedName` is the final authority, and a
     * concurrent create that slips past the read is translated from its constraint
     * violation into [CreateCategoryResult.Duplicate].
     */
    suspend fun createCategory(
        rawName: String,
        now: Long = System.currentTimeMillis()
    ): CreateCategoryResult {
        val valid = when (val validation = UserCategoryName.validate(rawName)) {
            UserCategoryNameValidation.Empty -> return CreateCategoryResult.EmptyName
            UserCategoryNameValidation.TooLong -> return CreateCategoryResult.TooLong
            is UserCategoryNameValidation.Valid -> validation
        }

        val result = try {
            transactions.inTransaction { createCategoryInTransaction(valid, now) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            if (isDuplicateNameViolation(error)) return CreateCategoryResult.Duplicate
            throw error
        }

        (result as? CreateCategoryResult.Created)?.let { created ->
            diagnostics(
                USER_CATEGORY_CREATE,
                "category=${categoryToken(created.category.categoryId)} " +
                    "sortOrder=${created.category.sortOrder}"
            )
        }
        return result
    }

    private suspend fun createCategoryInTransaction(
        valid: UserCategoryNameValidation.Valid,
        now: Long
    ): CreateCategoryResult {
        if (categoryDao.byNormalizedName(valid.normalizedName) != null) {
            return CreateCategoryResult.Duplicate
        }
        val row = UserCategoryEntity(
            categoryId = uuidFactory(),
            name = valid.displayName,
            normalizedName = valid.normalizedName,
            sortOrder = nextSortOrderInTransaction(),
            createdAt = now,
            updatedAt = now
        )
        categoryDao.insert(row)
        return CreateCategoryResult.Created(row)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RENAME
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Rename a category in place.
     *
     * `categoryId`, `sortOrder` and `createdAt` are preserved byte for byte; only
     * `name`, `normalizedName` and `updatedAt` change. Memberships are never touched —
     * that is the whole reason the identity is a UUID and not the name.
     *
     * Re-applying a category's OWN normalized name is allowed on purpose, so
     * `vpn` → `VPN` and `"VPN  Clients"` → `"VPN Clients"` are ordinary renames rather
     * than duplicate errors.
     */
    suspend fun renameCategory(
        categoryId: String,
        rawName: String,
        now: Long = System.currentTimeMillis()
    ): RenameCategoryResult {
        val valid = when (val validation = UserCategoryName.validate(rawName)) {
            UserCategoryNameValidation.Empty -> return RenameCategoryResult.EmptyName
            UserCategoryNameValidation.TooLong -> return RenameCategoryResult.TooLong
            is UserCategoryNameValidation.Valid -> validation
        }

        val result = try {
            transactions.inTransaction { renameCategoryInTransaction(categoryId, valid, now) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            if (isDuplicateNameViolation(error)) return RenameCategoryResult.Duplicate
            throw error
        }

        if (result is RenameCategoryResult.Renamed) {
            diagnostics(
                USER_CATEGORY_RENAME,
                "category=${categoryToken(categoryId)}"
            )
        }
        return result
    }

    private suspend fun renameCategoryInTransaction(
        categoryId: String,
        valid: UserCategoryNameValidation.Valid,
        now: Long
    ): RenameCategoryResult {
        val existing = categoryDao.byId(categoryId) ?: return RenameCategoryResult.NotFound
        val owner = categoryDao.byNormalizedName(valid.normalizedName)
        if (owner != null && owner.categoryId != categoryId) {
            return RenameCategoryResult.Duplicate
        }
        val renamed = existing.copy(
            name = valid.displayName,
            normalizedName = valid.normalizedName,
            updatedAt = now
        )
        categoryDao.update(renamed)
        return RenameCategoryResult.Renamed(renamed)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DELETE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Delete a category.
     *
     * The memberships go with it through the verified `ON DELETE CASCADE` on
     * `user_category_assignments.categoryId`. Nothing else is deleted: not a message,
     * not a conversation, and not a smart-category override.
     */
    suspend fun deleteCategory(categoryId: String): DeleteCategoryResult {
        val result = transactions.inTransaction {
            if (categoryDao.deleteById(categoryId) > 0) {
                DeleteCategoryResult.Deleted
            } else {
                DeleteCategoryResult.NotFound
            }
        }
        if (result is DeleteCategoryResult.Deleted) {
            diagnostics(USER_CATEGORY_DELETE, "category=${categoryToken(categoryId)}")
        }
        return result
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ASSIGN / REMOVE — one scope
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Add memberships to ONE conversation scope.
     *
     * All-or-nothing: if ANY requested category id is unknown, no membership is
     * written, so a stale UI selection can never produce a half-applied assign (and an
     * orphan row could not exist anyway — the FK forbids it).
     *
     * Re-assigning an existing membership is idempotent and keeps its original
     * `createdAt`.
     */
    suspend fun assign(
        scope: UserCategoryScope,
        categoryIds: Set<String>,
        now: Long = System.currentTimeMillis()
    ): CategoryAssignmentResult {
        if (categoryIds.isEmpty()) return CategoryAssignmentResult.Applied(0, 0)
        val stored = UserCategoryScopeCodec.encode(scope)

        val result = transactions.inTransaction {
            val missing = missingCategoryIdsInTransaction(categoryIds)
            if (missing.isNotEmpty()) return@inTransaction unknownAssignment(missing)
            val rows = categoryIds.map { categoryId ->
                UserCategoryAssignmentEntity(
                    categoryId = categoryId,
                    scopeType = stored.scopeType,
                    scopeKey = stored.scopeKey,
                    createdAt = now
                )
            }
            CategoryAssignmentResult.Applied(insertedCount(rows), 0)
        }

        if (result is CategoryAssignmentResult.Applied) {
            diagnostics(
                USER_CATEGORY_ASSIGN,
                "scopes=1 types=${stored.scopeType} inserted=${result.inserted} removed=${result.removed}"
            )
        }
        return result
    }

    /**
     * Remove memberships from ONE conversation scope.
     *
     * Only the named categories are removed; every other category on that conversation
     * is untouched (that is what makes this a delta, not a replace). No existence check
     * is needed — removing a membership that is not there is a successful no-op.
     */
    suspend fun remove(
        scope: UserCategoryScope,
        categoryIds: Set<String>
    ): CategoryAssignmentResult {
        if (categoryIds.isEmpty()) return CategoryAssignmentResult.Applied(0, 0)
        val stored = UserCategoryScopeCodec.encode(scope)

        val removed = transactions.inTransaction {
            assignmentDao.deleteForScope(stored.scopeType, stored.scopeKey, categoryIds)
        }
        diagnostics(
            USER_CATEGORY_REMOVE,
            "scopes=1 types=${stored.scopeType} inserted=0 removed=$removed"
        )
        return CategoryAssignmentResult.Applied(0, removed)
    }

    /**
     * The primary Conversation Info API: make the scope's membership exactly
     * [desiredCategoryIds].
     *
     * A DELTA, never a delete-all/insert-all. Unchanged memberships are not rewritten,
     * so they keep their original `createdAt` — "when did I put this conversation in
     * VPN" survives every later edit of the same sheet.
     *
     * The desired ids are validated BEFORE anything is mutated, so an unknown id rolls
     * the whole operation back with the previous membership intact.
     */
    suspend fun replaceAssignments(
        scope: UserCategoryScope,
        desiredCategoryIds: Set<String>,
        now: Long = System.currentTimeMillis()
    ): CategoryAssignmentResult {
        val stored = UserCategoryScopeCodec.encode(scope)

        val result = transactions.inTransaction {
            val missing = missingCategoryIdsInTransaction(desiredCategoryIds)
            if (missing.isNotEmpty()) return@inTransaction unknownAssignment(missing)

            val current = assignmentDao
                .forScope(stored.scopeType, stored.scopeKey)
                .map { it.categoryId }
                .toSet()

            val toAdd = desiredCategoryIds - current
            val toRemove = current - desiredCategoryIds

            val inserted = if (toAdd.isEmpty()) {
                0
            } else {
                insertedCount(
                    toAdd.map { categoryId ->
                        UserCategoryAssignmentEntity(
                            categoryId = categoryId,
                            scopeType = stored.scopeType,
                            scopeKey = stored.scopeKey,
                            createdAt = now
                        )
                    }
                )
            }
            val removed = if (toRemove.isEmpty()) {
                0
            } else {
                assignmentDao.deleteForScope(stored.scopeType, stored.scopeKey, toRemove)
            }
            CategoryAssignmentResult.Applied(inserted, removed)
        }

        if (result is CategoryAssignmentResult.Applied) {
            diagnostics(
                USER_CATEGORY_ASSIGN,
                "scopes=1 types=${stored.scopeType} replaced=true " +
                    "inserted=${result.inserted} removed=${result.removed}"
            )
        }
        return result
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BATCH / MULTI-SELECT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Assign or unassign ONE category across MANY conversation scopes — the Home
     * multi-select action.
     *
     * ONE transaction, whatever the scope count, and at most TWO statements beyond the
     * single validation query: memberships are written with one batched insert, and
     * removed with one delete per scope TYPE (`ADDRESS`, `THREAD`). There is
     * deliberately no per-scope query anywhere.
     */
    suspend fun applyToScopes(
        scopes: Set<UserCategoryScope>,
        categoryId: String,
        desiredAssigned: Boolean,
        now: Long = System.currentTimeMillis()
    ): BatchAssignmentResult {
        if (scopes.isEmpty()) return BatchAssignmentResult.Applied(0, 0, 0)
        // Deduplicate the encoded form: two scopes that persist identically are one row.
        val encoded = scopes.map { UserCategoryScopeCodec.encode(it) }.distinct()

        val result = transactions.inTransaction {
            val missing = missingCategoryIdsInTransaction(setOf(categoryId))
            if (missing.isNotEmpty()) return@inTransaction BatchAssignmentResult.UnknownCategories(missing)

            if (desiredAssigned) {
                val rows = encoded.map { stored ->
                    UserCategoryAssignmentEntity(
                        categoryId = categoryId,
                        scopeType = stored.scopeType,
                        scopeKey = stored.scopeKey,
                        createdAt = now
                    )
                }
                BatchAssignmentResult.Applied(insertedCount(rows), 0, encoded.size)
            } else {
                val removed = encoded
                    .groupBy({ it.scopeType }, { it.scopeKey })
                    .entries
                    .sumOf { (scopeType, scopeKeys) ->
                        assignmentDao.deleteCategoryForScopes(categoryId, scopeType, scopeKeys)
                    }
                BatchAssignmentResult.Applied(0, removed, encoded.size)
            }
        }

        if (result is BatchAssignmentResult.Applied) {
            diagnostics(
                USER_CATEGORY_ASSIGN,
                "scopes=${result.scopes} assigned=$desiredAssigned " +
                    "inserted=${result.inserted} removed=${result.removed}"
            )
        }
        return result
    }

    /**
     * Membership of MANY scopes at once — what the tri-state multi-select needs.
     *
     * At most TWO queries (one per scope type) for the whole selection, whatever its
     * size, and every requested scope is present in the result — including the ones with
     * no membership, which come back as an empty set rather than a missing key. That
     * makes "indeterminate" (`some selected, some not`) computable without a second
     * round trip and without the caller having to distinguish "empty" from "absent".
     */
    suspend fun categoryMembershipForScopes(
        scopes: Set<UserCategoryScope>
    ): Map<UserCategoryScope, Set<String>> {
        // Deterministic key order so callers (and tests) never depend on a hash order.
        val result = LinkedHashMap<UserCategoryScope, Set<String>>(scopes.size)
        if (scopes.isEmpty()) return result
        scopes.forEach { result[it] = emptySet() }

        val encoded = scopes.map { it to UserCategoryScopeCodec.encode(it) }
        encoded.groupBy({ it.second.scopeType }).forEach { (scopeType, entries) ->
            val scopeKeys = entries.map { it.second.scopeKey }.distinct()
            val byScopeKey = assignmentDao
                .forScopeKeys(scopeType, scopeKeys)
                .groupBy({ it.scopeKey }, { it.categoryId })
            entries.forEach { (scope, stored) ->
                result[scope] = (byScopeKey[stored.scopeKey] ?: emptyList()).toSet()
            }
        }
        return result
    }

    /** The memberships of ONE scope: [UserCategoryAssignmentDao.forScope]. */
    suspend fun categoryIdsForScope(scope: UserCategoryScope): Set<String> {
        val stored = UserCategoryScopeCodec.encode(scope)
        return assignmentDao
            .forScope(stored.scopeType, stored.scopeKey)
            .map { it.categoryId }
            .toSet()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CREATE + ASSIGN
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Create a category AND put the given conversations in it, atomically.
     *
     * This is the Conversation Info / multi-select "New category…" action, so it must
     * not be `createCategory()` followed by `applyToScopes()`: two independent
     * transactions would leave a created-but-unassigned category behind if the second
     * one failed, which is a visible, user-reported state. It goes through the
     * transaction-local helpers instead, so a failure anywhere rolls back EVERYTHING.
     */
    suspend fun createAndAssign(
        rawName: String,
        scopes: Set<UserCategoryScope>,
        now: Long = System.currentTimeMillis()
    ): CreateAndAssignResult {
        val valid = when (val validation = UserCategoryName.validate(rawName)) {
            UserCategoryNameValidation.Empty -> return CreateAndAssignResult.EmptyName
            UserCategoryNameValidation.TooLong -> return CreateAndAssignResult.TooLong
            is UserCategoryNameValidation.Valid -> validation
        }
        val encoded = scopes.map { UserCategoryScopeCodec.encode(it) }.distinct()

        val result = try {
            transactions.inTransaction {
                val created = createCategoryInTransaction(valid, now)
                val category = when (created) {
                    is CreateCategoryResult.Created -> created.category
                    CreateCategoryResult.Duplicate -> return@inTransaction CreateAndAssignResult.Duplicate
                    // Unreachable: the name was already validated above.
                    CreateCategoryResult.EmptyName -> return@inTransaction CreateAndAssignResult.EmptyName
                    CreateCategoryResult.TooLong -> return@inTransaction CreateAndAssignResult.TooLong
                }
                if (encoded.isNotEmpty()) {
                    val rows = encoded.map { stored ->
                        UserCategoryAssignmentEntity(
                            categoryId = category.categoryId,
                            scopeType = stored.scopeType,
                            scopeKey = stored.scopeKey,
                            createdAt = now
                        )
                    }
                    assignmentDao.insertAll(rows)
                }
                CreateAndAssignResult.Created(category, encoded.size)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            if (isDuplicateNameViolation(error)) return CreateAndAssignResult.Duplicate
            throw error
        }

        if (result is CreateAndAssignResult.Created) {
            diagnostics(
                USER_CATEGORY_CREATE,
                "category=${categoryToken(result.category.categoryId)} " +
                    "scopes=${result.assignedScopes} createdAndAssigned=true"
            )
        }
        return result
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // transaction-local helpers — never call public transactional APIs
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Next `sortOrder`.
     *
     * `maxSortOrder()` is -1 for an empty table, so the first category is 0 and the row
     * order is simply append. At `Int.MAX_VALUE` — reachable only by a user who has
     * created 2 147 483 647 categories, i.e. never, but the contract has to be correct —
     * the existing categories are RESEQUENCED to `0…n-1` inside the same transaction
     * (preserving their order, because `all()` is ordered by `sortOrder, createdAt,
     * categoryId`) and the new one takes `n`. `Int.MAX_VALUE + 1` is never computed.
     */
    private suspend fun nextSortOrderInTransaction(): Int {
        val currentMax = categoryDao.maxSortOrder()
        if (currentMax != Int.MAX_VALUE) return currentMax + 1

        val ordered = categoryDao.all()
        val resequenced = ordered.mapIndexed { index, row ->
            if (row.sortOrder == index) row else row.copy(sortOrder = index)
        }
        categoryDao.updateAll(resequenced)
        return resequenced.size
    }

    /**
     * Which of [categoryIds] do not exist. ONE query, and no query at all for an empty
     * set (a collection bind expands per element, and an empty `IN ()` is not valid SQL).
     */
    private suspend fun missingCategoryIdsInTransaction(
        categoryIds: Set<String>
    ): Set<String> {
        if (categoryIds.isEmpty()) return emptySet()
        val existing = categoryDao.existingIds(categoryIds).toSet()
        return categoryIds - existing
    }

    private fun unknownAssignment(missing: Set<String>): CategoryAssignmentResult =
        CategoryAssignmentResult.UnknownCategories(missing)

    /** Room's `@Insert(IGNORE)` returns -1 for a row it skipped. */
    private suspend fun insertedCount(rows: List<UserCategoryAssignmentEntity>): Int =
        if (rows.isEmpty()) 0 else assignmentDao.insertAll(rows).count { it != -1L }

    companion object {

        // ── Diagnostic categories ───────────────────────────────────────────────
        // Messages carry a tokenized category id, scope TYPES and counts only. A
        // category name is user content and a scope key is a phone number or a
        // thread id; neither is ever logged.

        const val USER_CATEGORY_CREATE = "USER_CATEGORY_CREATE"
        const val USER_CATEGORY_RENAME = "USER_CATEGORY_RENAME"
        const val USER_CATEGORY_DELETE = "USER_CATEGORY_DELETE"
        const val USER_CATEGORY_ASSIGN = "USER_CATEGORY_ASSIGN"
        const val USER_CATEGORY_REMOVE = "USER_CATEGORY_REMOVE"

        @Volatile
        private var instance: UserCategoryRepository? = null

        /** The process-wide instance, bound to the app's Room database. */
        fun get(context: Context): UserCategoryRepository =
            instance ?: synchronized(this) {
                instance ?: UserCategoryRepository(MessagesDatabase.get(context))
                    .also { instance = it }
            }
    }
}

/**
 * Deterministic, non-reversible reference to a category id.
 *
 * The id is a random UUID rather than user content, but a diagnostic line has no
 * reason to carry it in the clear, and this keeps every category log line correlatable
 * without becoming a second identity for the row.
 */
private fun categoryToken(categoryId: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(categoryId.toByteArray(Charsets.UTF_8))
    return digest.take(5).joinToString("") { "%02x".format(it) }
}

/**
 * True when [error] is the UNIQUE-index violation on `user_categories.normalizedName`
 * and nothing else.
 *
 * The database, not a read-then-write check, is the authority on duplicate names, so a
 * concurrent create must be translated rather than crash. But translating ANY SQLite
 * failure into `Duplicate` would hide real corruption, so both the constraint kind and
 * the column are required. The message text is stable across the engines this app runs
 * on (`UNIQUE constraint failed: user_categories.normalizedName`), and Room may wrap the
 * driver's exception, so the cause chain is walked.
 */
private fun isDuplicateNameViolation(error: Throwable): Boolean {
    var cause: Throwable? = error
    var depth = 0
    while (cause != null && depth < 8) {
        val message = cause.message.orEmpty()
        if (message.contains("UNIQUE", ignoreCase = true) &&
            message.contains("normalizedName", ignoreCase = true)
        ) {
            return true
        }
        cause = cause.cause
        depth++
    }
    return false
}
