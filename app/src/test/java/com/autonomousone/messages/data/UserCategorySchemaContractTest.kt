package com.autonomousone.messages.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The v18 schema contract (v3.5.0 Phase 3).
 *
 * Written BEFORE any membership row can exist, because this is the point of no return:
 * once assignments are persisted, a wrong primary key or a missing index is a data
 * problem rather than a code change. It pins the KSP-GENERATED `18.json` structurally
 * (never by comparing JSON text) against the contract the entities promise, and it pins
 * `UPGRADE_TO_V18_SQL` to that same schema so the migrated database and a fresh install
 * cannot drift apart.
 *
 * It also fails the build if the migration ever grows a destructive or provider-touching
 * statement, which is the property that makes this release non-destructive.
 */
class UserCategorySchemaContractTest {

    private val schemaDir = "schemas/com.autonomousone.messages.data.MessagesDatabase"

    private fun schema(version: Int): JSONObject {
        val file = File("$schemaDir/$version.json")
        assertTrue(
            "schema $version.json not generated at ${file.absolutePath} — run :app:kspDebugKotlin",
            file.isFile
        )
        return JSONObject(file.readText()).getJSONObject("database")
    }

    private fun entity(version: Int, table: String): JSONObject {
        val entities = schema(version).getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val candidate = entities.getJSONObject(i)
            if (candidate.getString("tableName") == table) return candidate
        }
        throw AssertionError("table $table not found in $version.json")
    }

    private fun columnNames(entity: JSONObject): List<String> {
        val fields = entity.getJSONArray("fields")
        return (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }
    }

    private fun indexNames(entity: JSONObject): List<String> {
        val indices = entity.optJSONArray("indices") ?: return emptyList()
        return (0 until indices.length()).map { indices.getJSONObject(it).getString("name") }
    }

    private fun uniqueIndexNames(entity: JSONObject): List<String> {
        val indices = entity.optJSONArray("indices") ?: return emptyList()
        return (0 until indices.length())
            .map { indices.getJSONObject(it) }
            .filter { it.getBoolean("unique") }
            .map { it.getString("name") }
    }

    // ── version + additive-ness ─────────────────────────────────────────────

    @Test
    fun `the database version is 18 and v17 is untouched`() {
        assertEquals(18, schema(18).getInt("version"))
        assertEquals(18, MessagesDatabase.CURRENT_SCHEMA_VERSION)
        assertEquals(17, MessagesDatabase.PREVIOUS_SCHEMA_VERSION)
        // 17.json must still describe v17, i.e. the release before this feature.
        assertEquals(17, schema(17).getInt("version"))
    }

    @Test
    fun `v18 contains every table v17 had plus the two new ones`() {
        val v17 = schema(17).getJSONArray("entities").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).getString("tableName") }.toSet()
        }
        val v18 = schema(18).getJSONArray("entities").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).getString("tableName") }.toSet()
        }

        assertTrue("an upgrade must not remove a table", v18.containsAll(v17))
        assertEquals(v17 + setOf("user_categories", "user_category_assignments"), v18)
    }

    // ── user_categories ─────────────────────────────────────────────────────

    @Test
    fun `user_categories columns match the entity contract`() {
        assertEquals(
            listOf("categoryId", "name", "normalizedName", "sortOrder", "createdAt", "updatedAt"),
            columnNames(entity(18, "user_categories"))
        )
    }

    @Test
    fun `user_categories is keyed by categoryId only`() {
        val pk = entity(18, "user_categories").getJSONObject("primaryKey")
        val columns = pk.getJSONArray("columnNames").let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }
        assertEquals(listOf("categoryId"), columns)
    }

    @Test
    fun `normalizedName is uniquely indexed and sortOrder is indexed`() {
        val table = entity(18, "user_categories")

        assertEquals(listOf("index_user_categories_normalizedName"), uniqueIndexNames(table))
        assertTrue(indexNames(table).contains("index_user_categories_sortOrder"))
    }

    // ── user_category_assignments ───────────────────────────────────────────

    @Test
    fun `assignment columns match the entity contract`() {
        assertEquals(
            listOf("categoryId", "scopeType", "scopeKey", "createdAt"),
            columnNames(entity(18, "user_category_assignments"))
        )
    }

    @Test
    fun `the assignment primary key is the membership triple`() {
        val pk = entity(18, "user_category_assignments").getJSONObject("primaryKey")
        val columns = pk.getJSONArray("columnNames").let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }
        // The triple is what makes re-assigning idempotent by construction.
        assertEquals(listOf("categoryId", "scopeType", "scopeKey"), columns)
    }

    @Test
    fun `the only foreign key is categoryId with cascade delete`() {
        val keys = entity(18, "user_category_assignments").getJSONArray("foreignKeys")
        assertEquals("exactly one foreign key", 1, keys.length())

        val fk = keys.getJSONObject(0)
        assertEquals("user_categories", fk.getString("table"))
        assertEquals("CASCADE", fk.getString("onDelete"))
        assertEquals(
            listOf("categoryId"),
            fk.getJSONArray("columns").let { arr -> (0 until arr.length()).map { arr.getString(it) } }
        )
        assertEquals(
            listOf("categoryId"),
            fk.getJSONArray("referencedColumns").let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            }
        )
    }

    @Test
    fun `no foreign key points at messages or conversations`() {
        // An ADDRESS membership must survive the provider recreating a thread, and a
        // THREAD membership is valid with no projection row at all.
        listOf("user_category_assignments", "user_categories").forEach { table ->
            val keys = entity(18, table).optJSONArray("foreignKeys") ?: return@forEach
            for (i in 0 until keys.length()) {
                val target = keys.getJSONObject(i).getString("table")
                assertFalse(
                    "$table must not reference $target",
                    target == "messages" || target == "conversations"
                )
            }
        }
    }

    @Test
    fun `assignment scope and category indices exist`() {
        val names = indexNames(entity(18, "user_category_assignments"))

        assertTrue(names.contains("index_user_category_assignments_categoryId"))
        assertTrue(names.contains("index_user_category_assignments_scopeType_scopeKey"))
    }

    // ── the migration matches the generated schema ──────────────────────────

    private val normalizedMigration: List<String> =
        MessagesDatabase.UPGRADE_TO_V18_SQL.map { it.replace(Regex("\\s+"), " ").trim() }

    @Test
    fun `the migration creates exactly the generated tables`() {
        assertEquals(6, normalizedMigration.size)

        val creates = normalizedMigration.filter { it.startsWith("CREATE TABLE") }
        assertEquals(2, creates.size)
        assertTrue(creates.any { it.contains("`user_categories`") })
        assertTrue(creates.any { it.contains("`user_category_assignments`") })
    }

    @Test
    fun `the migration create statements match the generated createSql`() {
        listOf("user_categories", "user_category_assignments").forEach { table ->
            val expected = entity(18, table)
                .getString("createSql")
                .replace("\${TABLE_NAME}", table)
                .replace(Regex("\\s+"), " ")
                .trim()
            val actual = normalizedMigration.first { it.contains("CREATE TABLE IF NOT EXISTS `$table`") }

            assertEquals("migration DDL drifted from 18.json for $table", expected, actual)
        }
    }

    @Test
    fun `the migration indices match the generated index sql`() {
        listOf("user_categories", "user_category_assignments").forEach { table ->
            val indices = entity(18, table).getJSONArray("indices")
            for (i in 0 until indices.length()) {
                val expected = indices.getJSONObject(i)
                    .getString("createSql")
                    .replace("\${TABLE_NAME}", table)
                    .replace(Regex("\\s+"), " ")
                    .trim()
                assertTrue(
                    "migration is missing an index for $table: $expected",
                    normalizedMigration.contains(expected)
                )
            }
        }
    }

    @Test
    fun `the migration creates the parent table before the child`() {
        val parentAt = normalizedMigration.indexOfFirst { it.contains("`user_categories`") }
        val childAt = normalizedMigration.indexOfFirst {
            it.contains("CREATE TABLE IF NOT EXISTS `user_category_assignments`")
        }

        assertTrue("parent table must be created first (FK)", parentAt in 0 until childAt)
    }

    // ── non-destructive / local-only guard ──────────────────────────────────

    @Test
    fun `the migration contains no destructive or provider statement`() {
        // Each statement must be a pure CREATE of a table or an index. Anything that
        // could remove or rewrite existing state is rejected outright.
        //
        // The check is anchored to STATEMENT STARTS on purpose: the assignment table
        // legitimately contains `ON UPDATE NO ACTION` inside its foreign key clause, and
        // a naive substring search for "UPDATE " called that a destructive statement.
        val allowedStart = Regex("^CREATE (TABLE|UNIQUE INDEX|INDEX) IF NOT EXISTS ", RegexOption.IGNORE_CASE)

        normalizedMigration.forEach { statement ->
            assertTrue(
                "the v18 migration may only CREATE tables and indices, but found: $statement",
                allowedStart.containsMatchIn(statement)
            )
        }

        // No DML or DDL that removes or rewrites anything, anywhere in the text.
        val forbidden = listOf(
            "DROP TABLE", "DROP INDEX", "DELETE FROM", "ALTER TABLE",
            "TRUNCATE", "INSERT INTO", "REPLACE INTO"
        )
        normalizedMigration.forEach { statement ->
            forbidden.forEach { token ->
                assertFalse(
                    "v18 migration must not contain '$token': $statement",
                    statement.uppercase().contains(token)
                )
            }
        }
    }

    @Test
    fun `the migration never touches the message table or telephony`() {
        normalizedMigration.forEach { statement ->
            val lower = statement.lowercase()
            assertFalse("must not reference messages", lower.contains("messages"))
            assertFalse("must not reference conversations", lower.contains("conversations"))
            listOf("telephony", "contentresolver", "provider", "sms").forEach { token ->
                assertFalse("must not reference $token", lower.contains(token))
            }
        }
    }
}
