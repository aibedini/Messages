package com.autonomousone.messages.repository

import com.autonomousone.messages.data.UserCategoryAssignmentDao
import com.autonomousone.messages.data.UserCategoryAssignmentEntity
import com.autonomousone.messages.data.UserCategoryDao
import com.autonomousone.messages.data.UserCategoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * A REAL SQLite engine behind the REAL Room DAO interfaces, for JVM tests.
 *
 * The repository under test is the production class, unchanged: it takes
 * `UserCategoryDao`, `UserCategoryAssignmentDao` and a transaction runner, and this
 * store supplies them. Only the Room runtime is replaced — the schema comes from the
 * shipped `18.json`, so the UNIQUE index on `normalizedName`, the composite primary key
 * and the `ON DELETE CASCADE` foreign key are the SAME constraints production has.
 *
 * That matters for the contracts Phase 4 has to prove and a mock could not:
 *  - duplicate-name rejection is enforced by the index, not by the read-then-write check;
 *  - deleting a category really does cascade its memberships;
 *  - an all-or-nothing write really does roll back (the runner COMMITs and ROLLBACKs).
 *
 * `@Insert(IGNORE)` semantics are reproduced with `INSERT OR IGNORE` returning -1 for a
 * skipped row, which is exactly what Room's generated code reports.
 */
internal class UserCategoryTestStore : AutoCloseable {

    private val schemaDir = "schemas/com.autonomousone.messages.data.MessagesDatabase"

    val connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    private var transactionDepth = 0

    init {
        createSchema(connection)
        // SQLite defaults to foreign_keys = OFF, and the pragma is a no-op inside a
        // transaction, so it must be set on the raw connection up front.
        connection.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
    }

    /** Every table and index the shipped v18 schema declares. */
    private fun createSchema(target: Connection) {
        val entities = JSONObject(File("$schemaDir/18.json").readText())
            .getJSONObject("database")
            .getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            val createSql = entity.getString("createSql").replace("\${TABLE_NAME}", table)
            // The FTS virtual table and its shadow tables are not needed here.
            if (!createSql.startsWith("CREATE TABLE IF NOT EXISTS")) continue
            target.createStatement().use { it.execute(createSql) }
            val indices = entity.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                target.createStatement().use {
                    it.execute(
                        indices.getJSONObject(j).getString("createSql")
                            .replace("\${TABLE_NAME}", table)
                    )
                }
            }
        }
    }

    // ── Transaction boundary ────────────────────────────────────────────────

    /**
     * The production runner is `MessagesDatabase.withTransaction`; this is the same
     * contract over JDBC. The nesting guard is deliberate: the repository's design is
     * "one commit point", so a nested transaction is a bug, not a scenario.
     */
    val transactions: CategoryTransactionRunner = object : CategoryTransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T {
            check(transactionDepth == 0) { "the repository must not nest transactions" }
            transactionDepth++
            connection.autoCommit = false
            return try {
                val result = block()
                connection.commit()
                result
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                transactionDepth--
                connection.autoCommit = true
            }
        }
    }

    // ── DAO implementations ─────────────────────────────────────────────────

    /**
     * Simulates a duplicate read that MISSED a concurrently committed row, so the
     * UNIQUE index — not the repository's read-then-write check — is what rejects the
     * second create. This is the only honest way to exercise that path on one
     * connection, and it is what makes the "database is the final authority" claim
     * testable.
     */
    var duplicateLookupIsBlind: Boolean = false

    /**
     * Makes every assignment INSERT throw, to prove `createAndAssign` rolls the
     * category back with it (no "created but unassigned" state can survive).
     */
    var failAssignmentWrites: Boolean = false

    val categoryDao: UserCategoryDao = object : UserCategoryDao {

        override fun observeAll(): Flow<List<UserCategoryEntity>> = flow { emit(all()) }

        override suspend fun all(): List<UserCategoryEntity> = queryCategories(
            "SELECT * FROM user_categories ORDER BY sortOrder ASC, createdAt ASC, categoryId ASC"
        )

        override suspend fun byId(categoryId: String): UserCategoryEntity? = queryCategories(
            "SELECT * FROM user_categories WHERE categoryId = ? LIMIT 1",
            listOf(categoryId)
        ).firstOrNull()

        override suspend fun byNormalizedName(normalizedName: String): UserCategoryEntity? {
            if (duplicateLookupIsBlind) return null
            return queryCategories(
                "SELECT * FROM user_categories WHERE normalizedName = ? LIMIT 1",
                listOf(normalizedName)
            ).firstOrNull()
        }

        override suspend fun existingIds(categoryIds: Collection<String>): List<String> {
            if (categoryIds.isEmpty()) return emptyList()
            val placeholders = categoryIds.joinToString(",") { "?" }
            return connection.prepareStatement(
                "SELECT categoryId FROM user_categories WHERE categoryId IN ($placeholders)"
            ).use { statement ->
                categoryIds.forEachIndexed { index, id -> statement.setString(index + 1, id) }
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1)) }
                }
            }
        }

        /** ABORT, like the production DAO: the constraint must surface, not be swallowed. */
        override suspend fun insert(row: UserCategoryEntity) {
            connection.prepareStatement(
                "INSERT INTO user_categories " +
                    "(categoryId, name, normalizedName, sortOrder, createdAt, updatedAt) " +
                    "VALUES (?,?,?,?,?,?)"
            ).use { statement ->
                statement.setString(1, row.categoryId)
                statement.setString(2, row.name)
                statement.setString(3, row.normalizedName)
                statement.setInt(4, row.sortOrder)
                statement.setLong(5, row.createdAt)
                statement.setLong(6, row.updatedAt)
                statement.executeUpdate()
            }
        }

        override suspend fun update(row: UserCategoryEntity) {
            connection.prepareStatement(
                "UPDATE user_categories SET name = ?, normalizedName = ?, sortOrder = ?, " +
                    "createdAt = ?, updatedAt = ? WHERE categoryId = ?"
            ).use { statement ->
                statement.setString(1, row.name)
                statement.setString(2, row.normalizedName)
                statement.setInt(3, row.sortOrder)
                statement.setLong(4, row.createdAt)
                statement.setLong(5, row.updatedAt)
                statement.setString(6, row.categoryId)
                statement.executeUpdate()
            }
        }

        override suspend fun updateAll(rows: List<UserCategoryEntity>) {
            rows.forEach { update(it) }
        }

        override suspend fun deleteById(categoryId: String): Int =
            connection.prepareStatement("DELETE FROM user_categories WHERE categoryId = ?")
                .use { statement ->
                    statement.setString(1, categoryId)
                    statement.executeUpdate()
                }

        override suspend fun maxSortOrder(): Int =
            scalar("SELECT COALESCE(MAX(sortOrder), -1) FROM user_categories").toInt()

        override suspend fun count(): Int =
            scalar("SELECT COUNT(*) FROM user_categories").toInt()
    }

    val assignmentDao: UserCategoryAssignmentDao = object : UserCategoryAssignmentDao {

        override fun observeAll(): Flow<List<UserCategoryAssignmentEntity>> = flow { emit(all()) }

        override suspend fun all(): List<UserCategoryAssignmentEntity> = queryAssignments(
            "SELECT * FROM user_category_assignments"
        )

        override suspend fun forScope(
            scopeType: String,
            scopeKey: String
        ): List<UserCategoryAssignmentEntity> = queryAssignments(
            "SELECT * FROM user_category_assignments WHERE scopeType = ? AND scopeKey = ?",
            listOf(scopeType, scopeKey)
        )

        override suspend fun forScopeKeys(
            scopeType: String,
            scopeKeys: Collection<String>
        ): List<UserCategoryAssignmentEntity> {
            if (scopeKeys.isEmpty()) return emptyList()
            scopeReadQueries++
            val placeholders = scopeKeys.joinToString(",") { "?" }
            return queryAssignments(
                "SELECT * FROM user_category_assignments " +
                    "WHERE scopeType = ? AND scopeKey IN ($placeholders)",
                listOf(scopeType) + scopeKeys
            )
        }

        override suspend fun forCategory(categoryId: String): List<UserCategoryAssignmentEntity> =
            queryAssignments(
                "SELECT * FROM user_category_assignments WHERE categoryId = ?",
                listOf(categoryId)
            )

        /** `INSERT OR IGNORE`, reporting -1 for a skipped row exactly like Room. */
        override suspend fun insertAll(
            rows: List<UserCategoryAssignmentEntity>
        ): List<Long> {
            if (failAssignmentWrites) {
                throw IllegalStateException("simulated assignment write failure")
            }
            return rows.map { row ->
                connection.prepareStatement(
                    "INSERT OR IGNORE INTO user_category_assignments " +
                        "(categoryId, scopeType, scopeKey, createdAt) VALUES (?,?,?,?)"
                ).use { statement ->
                    statement.setString(1, row.categoryId)
                    statement.setString(2, row.scopeType)
                    statement.setString(3, row.scopeKey)
                    statement.setLong(4, row.createdAt)
                    if (statement.executeUpdate() == 0) -1L else 1L
                }
            }
        }

        override suspend fun deleteOne(
            categoryId: String,
            scopeType: String,
            scopeKey: String
        ): Int = connection.prepareStatement(
            "DELETE FROM user_category_assignments " +
                "WHERE categoryId = ? AND scopeType = ? AND scopeKey = ?"
        ).use { statement ->
            statement.setString(1, categoryId)
            statement.setString(2, scopeType)
            statement.setString(3, scopeKey)
            statement.executeUpdate()
        }

        override suspend fun deleteForScope(
            scopeType: String,
            scopeKey: String,
            categoryIds: Collection<String>
        ): Int {
            if (categoryIds.isEmpty()) return 0
            val placeholders = categoryIds.joinToString(",") { "?" }
            return connection.prepareStatement(
                "DELETE FROM user_category_assignments " +
                    "WHERE scopeType = ? AND scopeKey = ? AND categoryId IN ($placeholders)"
            ).use { statement ->
                statement.setString(1, scopeType)
                statement.setString(2, scopeKey)
                categoryIds.forEachIndexed { index, id ->
                    statement.setString(index + 3, id)
                }
                statement.executeUpdate()
            }
        }

        override suspend fun deleteForCategory(categoryId: String): Int =
            connection.prepareStatement(
                "DELETE FROM user_category_assignments WHERE categoryId = ?"
            ).use { statement ->
                statement.setString(1, categoryId)
                statement.executeUpdate()
            }

        override suspend fun deleteCategoryForScopes(
            categoryId: String,
            scopeType: String,
            scopeKeys: Collection<String>
        ): Int {
            if (scopeKeys.isEmpty()) return 0
            batchDeleteQueries++
            val placeholders = scopeKeys.joinToString(",") { "?" }
            return connection.prepareStatement(
                "DELETE FROM user_category_assignments " +
                    "WHERE categoryId = ? AND scopeType = ? AND scopeKey IN ($placeholders)"
            ).use { statement ->
                statement.setString(1, categoryId)
                statement.setString(2, scopeType)
                scopeKeys.forEachIndexed { index, key -> statement.setString(index + 3, key) }
                statement.executeUpdate()
            }
        }

        override suspend fun count(): Int =
            scalar("SELECT COUNT(*) FROM user_category_assignments").toInt()
    }

    // ── Introspection helpers for the tests ─────────────────────────────────

    fun scalar(sql: String): Long = connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { rows ->
            check(rows.next()) { "query returned no row: $sql" }
            rows.getLong(1)
        }
    }

    fun text(sql: String): String? = connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> if (!rows.next()) null else rows.getString(1) }
    }

    fun exec(sql: String): Int =
        connection.createStatement().use { it.executeUpdate(sql) }

    /**
     * How many SCOPE-READ queries the assignment DAO has issued since the last reset.
     *
     * The performance contract is "at most one read per scope TYPE", never one per
     * conversation, and this is what makes that observable: a 100-scope multi-select
     * must not move this counter past 2.
     */
    var scopeReadQueries: Int = 0
        private set

    /** How many BATCHED deletes the assignment DAO has issued since the last reset. */
    var batchDeleteQueries: Int = 0
        private set

    fun resetQueryCounters() {
        scopeReadQueries = 0
        batchDeleteQueries = 0
    }

    fun categoryRow(categoryId: String): UserCategoryEntity? = queryCategories(
        "SELECT * FROM user_categories WHERE categoryId = ?",
        listOf(categoryId)
    ).firstOrNull()

    fun assignmentRows(): List<UserCategoryAssignmentEntity> = queryAssignments(
        "SELECT * FROM user_category_assignments"
    )

    fun membership(categoryId: String, scopeType: String, scopeKey: String): Boolean =
        scalar(
            "SELECT COUNT(*) FROM user_category_assignments " +
                "WHERE categoryId = '$categoryId' AND scopeType = '$scopeType' " +
                "AND scopeKey = '$scopeKey'"
        ) > 0

    /** Seeds a category row directly, including an arbitrary `sortOrder`. */
    fun seedCategory(
        categoryId: String,
        name: String,
        normalizedName: String = name.lowercase(),
        sortOrder: Int,
        createdAt: Long = 1L,
        updatedAt: Long = createdAt
    ) {
        exec(
            "INSERT INTO user_categories " +
                "(categoryId, name, normalizedName, sortOrder, createdAt, updatedAt) VALUES " +
                "('$categoryId','$name','$normalizedName',$sortOrder,$createdAt,$updatedAt)"
        )
    }

    fun seedAssignment(
        categoryId: String,
        scopeType: String,
        scopeKey: String,
        createdAt: Long
    ) {
        exec(
            "INSERT INTO user_category_assignments " +
                "(categoryId, scopeType, scopeKey, createdAt) VALUES " +
                "('$categoryId','$scopeType','$scopeKey',$createdAt)"
        )
    }

    /** A message row, to prove category writes never touch the mirror. */
    fun seedMessage(providerId: Long = 100) {
        exec(
            "INSERT INTO messages " +
                "(source, providerId, threadId, normalizedAddress, rawAddress, body, date, " +
                "type, status, dateSent, read, syncState) VALUES " +
                "('sms',$providerId,7,'+989121234567','+989121234567','hello',1000,1,-1,0,0,'synced')"
        )
    }

    fun seedConversation(threadId: Long = 7) {
        exec(
            "INSERT INTO conversations " +
                "(threadId, normalizedAddress, rawAddress, snippet, lastMessageDate, " +
                "unreadCount, lastMessageType, pinned, archived) VALUES " +
                "($threadId,'+989121234567','+989121234567','hello',1000,2,1,0,0)"
        )
    }

    /** A conversation preference row carrying a SMART category override. */
    fun seedSmartCategoryOverride(threadId: Long = 7, category: String = "TRANSACTION") {
        exec(
            "INSERT INTO conversation_preferences " +
                "(threadId, manualUnread, mutedUntil, customNotificationChannel, " +
                "categoryOverride, spam, spamReportedAt, spamBlockedByReport, updatedAt) " +
                "VALUES ($threadId,0,0,0,'$category',0,0,0,50)"
        )
    }

    // ── row mappers ─────────────────────────────────────────────────────────

    private fun queryCategories(
        sql: String,
        args: List<String> = emptyList()
    ): List<UserCategoryEntity> = connection.prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setString(index + 1, value) }
        statement.executeQuery().use { rows ->
            buildList {
                while (rows.next()) {
                    add(
                        UserCategoryEntity(
                            categoryId = rows.getString("categoryId"),
                            name = rows.getString("name"),
                            normalizedName = rows.getString("normalizedName"),
                            sortOrder = rows.getInt("sortOrder"),
                            createdAt = rows.getLong("createdAt"),
                            updatedAt = rows.getLong("updatedAt")
                        )
                    )
                }
            }
        }
    }

    private fun queryAssignments(
        sql: String,
        args: List<String> = emptyList()
    ): List<UserCategoryAssignmentEntity> = connection.prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setString(index + 1, value) }
        statement.executeQuery().use { rows ->
            buildList {
                while (rows.next()) {
                    add(
                        UserCategoryAssignmentEntity(
                            categoryId = rows.getString("categoryId"),
                            scopeType = rows.getString("scopeType"),
                            scopeKey = rows.getString("scopeKey"),
                            createdAt = rows.getLong("createdAt")
                        )
                    )
                }
            }
        }
    }

    override fun close() {
        connection.close()
    }
}
