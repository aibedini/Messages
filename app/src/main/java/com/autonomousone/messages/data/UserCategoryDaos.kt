package com.autonomousone.messages.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Room v18 — CUSTOM category storage.
 *
 * Conversation-scale by construction: neither DAO here ever touches `messages`, the
 * Telephony provider or contacts. Every statement is a primary-key / index lookup or a
 * bounded list read, so category rendering on a 360K-message device is unaffected by
 * conversation history size.
 */

@Dao
interface UserCategoryDao {

    /**
     * The user's categories in their explicit order.
     *
     * `sortOrder` is the user's ordering; `createdAt` and `categoryId` are deterministic
     * tie-breakers so the chip row can never reorder itself between emissions.
     */
    @Query(
        """
        SELECT * FROM user_categories
        ORDER BY sortOrder ASC, createdAt ASC, categoryId ASC
        """
    )
    fun observeAll(): Flow<List<UserCategoryEntity>>

    @Query(
        """
        SELECT * FROM user_categories
        ORDER BY sortOrder ASC, createdAt ASC, categoryId ASC
        """
    )
    suspend fun all(): List<UserCategoryEntity>

    @Query("SELECT * FROM user_categories WHERE categoryId = :categoryId LIMIT 1")
    suspend fun byId(categoryId: String): UserCategoryEntity?

    /** Duplicate-protection lookup. The UNIQUE index remains the final authority. */
    @Query("SELECT * FROM user_categories WHERE normalizedName = :normalizedName LIMIT 1")
    suspend fun byNormalizedName(normalizedName: String): UserCategoryEntity?

    /**
     * ABORT on conflict on purpose: a duplicate `normalizedName` is a case the repository
     * must translate into a typed result, never silently swallow with REPLACE (which
     * would delete the existing category row and, through the FK cascade, every
     * membership attached to it).
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: UserCategoryEntity)

    /** Rename only: the caller preserves `categoryId`, `sortOrder` and `createdAt`. */
    @Update
    suspend fun update(row: UserCategoryEntity)

    @Query("DELETE FROM user_categories WHERE categoryId = :categoryId")
    suspend fun deleteById(categoryId: String): Int

    /** Next `sortOrder` value; -1 when there are no categories yet. */
    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM user_categories")
    suspend fun maxSortOrder(): Int

    @Query("SELECT COUNT(*) FROM user_categories")
    suspend fun count(): Int
}

@Dao
interface UserCategoryAssignmentDao {

    /** Every membership. Conversation-scale (one row per assignment, never per message). */
    @Query("SELECT * FROM user_category_assignments")
    fun observeAll(): Flow<List<UserCategoryAssignmentEntity>>

    @Query("SELECT * FROM user_category_assignments")
    suspend fun all(): List<UserCategoryAssignmentEntity>

    /** Memberships of ONE conversation scope. */
    @Query(
        """
        SELECT * FROM user_category_assignments
        WHERE scopeType = :scopeType AND scopeKey = :scopeKey
        """
    )
    suspend fun forScope(scopeType: String, scopeKey: String): List<UserCategoryAssignmentEntity>

    /**
     * Batched read for many scopes of ONE type.
     *
     * Used by the tri-state multi-select, which needs the membership of every selected
     * conversation: ONE query per scope TYPE instead of one query per conversation.
     */
    @Query(
        """
        SELECT * FROM user_category_assignments
        WHERE scopeType = :scopeType AND scopeKey IN (:scopeKeys)
        """
    )
    suspend fun forScopeKeys(
        scopeType: String,
        scopeKeys: Collection<String>
    ): List<UserCategoryAssignmentEntity>

    /** Who is in this category — the category filter's member set. */
    @Query("SELECT * FROM user_category_assignments WHERE categoryId = :categoryId")
    suspend fun forCategory(categoryId: String): List<UserCategoryAssignmentEntity>

    /**
     * Assign. IGNORE on conflict makes re-assigning IDEMPOTENT: the primary key is
     * `(categoryId, scopeType, scopeKey)`, so an existing membership keeps its original
     * `createdAt` instead of being replaced.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<UserCategoryAssignmentEntity>): List<Long>

    @Query(
        """
        DELETE FROM user_category_assignments
        WHERE categoryId = :categoryId AND scopeType = :scopeType AND scopeKey = :scopeKey
        """
    )
    suspend fun deleteOne(categoryId: String, scopeType: String, scopeKey: String): Int

    /**
     * Remove SEVERAL memberships of one scope, leaving the scope's other categories
     * untouched (that is what makes `replaceAssignments` a delta operation).
     */
    @Query(
        """
        DELETE FROM user_category_assignments
        WHERE scopeType = :scopeType AND scopeKey = :scopeKey AND categoryId IN (:categoryIds)
        """
    )
    suspend fun deleteForScope(
        scopeType: String,
        scopeKey: String,
        categoryIds: Collection<String>
    ): Int

    /**
     * Explicit per-category cleanup.
     *
     * Not required for correctness — the FK cascade already removes memberships when the
     * category row is deleted — but it lets the repository make the intent explicit and
     * it is what the delete contract test asserts against.
     */
    @Query("DELETE FROM user_category_assignments WHERE categoryId = :categoryId")
    suspend fun deleteForCategory(categoryId: String): Int

    @Query("SELECT COUNT(*) FROM user_category_assignments")
    suspend fun count(): Int
}
