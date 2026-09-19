package com.autonomousone.messages

import com.autonomousone.messages.data.MessagesDatabase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Guards MIGRATION_15_16 (v3.4.0 UX user-state schema) the way
 * MigrationToV4SqlTest guards v4: the hand-written SQL must EXACTLY match the
 * schema Room generates from UxEntities.kt, otherwise Room throws on the device
 * at upgrade time and a real user's database fails to open.
 *
 * The data test runs the ACTUAL migration over a REAL v15 shape built from
 * 15.json, with rows in it, and proves the non-destructive contract: every old
 * row survives, every new table starts EMPTY, and no message becomes starred,
 * trashed, spam or classified.
 */
class MigrationToV16SqlTest {

    private val dbDir = "schemas/com.autonomousone.messages.data.MessagesDatabase"
    private val v16TableNames = listOf(
        "conversation_preferences",
        "message_user_state",
        "trashed_threads",
        "message_classification",
        "conversation_classification",
        "message_assets"
    )

    private fun compact(sql: String): String = sql.filterNot { it.isWhitespace() }

    private fun schemaFile(version: Int): File =
        File("$dbDir/$version.json")

    private fun readSchema(version: Int): JSONObject {
        val file = schemaFile(version)
        if (!file.exists()) {
            fail("Schema $version.json not found at ${file.absolutePath} — run :app:kspDebugKotlin")
        }
        return JSONObject(file.readText())
    }

    /** tableName -> every CREATE statement Room expects for that table. */
    private fun createStatements(version: Int): Map<String, Set<String>> {
        val entities = readSchema(version).getJSONObject("database").getJSONArray("entities")
        val result = mutableMapOf<String, MutableSet<String>>()
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            val set = result.getOrPut(table) { mutableSetOf() }
            set.add(compact(entity.getString("createSql").replace("\${TABLE_NAME}", table)))
            val indices = entity.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                val index = indices.getJSONObject(j)
                set.add(compact(index.getString("createSql").replace("\${TABLE_NAME}", table)))
            }
        }
        return result
    }

    // ── Structural pinning ──────────────────────────────────────────────────

    @Test
    fun `migration declares the 15 to 16 boundary`() {
        assertEquals(15, MessagesDatabase.MIGRATION_15_16.startVersion)
        assertEquals(16, MessagesDatabase.MIGRATION_15_16.endVersion)
    }

    @Test
    fun `migration is additive only - no drop no alter no rebuild`() {
        MessagesDatabase.UPGRADE_TO_V16_SQL.forEach { statement ->
            val c = compact(statement)
            assertTrue(
                "v16 must only CREATE: $statement",
                c.startsWith("CREATETABLEIFNOTEXISTS") || c.startsWith("CREATEINDEXIFNOTEXISTS")
            )
        }
    }

    @Test
    fun `every migration statement matches the generated v16 schema`() {
        val expected = createStatements(16)
        MessagesDatabase.UPGRADE_TO_V16_SQL.forEach { statement ->
            val compacted = compact(statement)
            val table = expected.entries.firstOrNull { compacted in it.value }
            assertTrue(
                "Migration statement not in generated 16.json:\n$statement\n" +
                    "(Run :app:kspDebugKotlin and compare app/schemas/.../16.json)",
                table != null
            )
        }
    }

    @Test
    fun `every v16 table and index the schema expects is created by the migration`() {
        val expected = createStatements(16)
        val migration = MessagesDatabase.UPGRADE_TO_V16_SQL.map(::compact).toSet()
        v16TableNames.forEach { table ->
            val statements = expected[table]
            assertTrue("16.json does not declare table $table", statements != null)
            statements!!.forEach { statement ->
                assertTrue(
                    "16.json expects but the migration does not create:\n$statement",
                    statement in migration
                )
            }
        }
    }

    @Test
    fun `no v16 table collides with an existing table name`() {
        val v15 = createStatements(15).keys
        v16TableNames.forEach { table ->
            assertFalse(
                "v16 table $table already exists in v15 — must be a NEW table",
                v15.contains(table)
            )
        }
    }

    @Test
    fun `16 json is a strictly additive superset of 15 json`() {
        val v15 = createStatements(15)
        val v16 = createStatements(16)
        v15.forEach { (table, statements) ->
            val upgraded = v16[table]
            assertTrue("v16 lost table $table", upgraded != null)
            statements.forEach { statement ->
                assertTrue(
                    "v15 statement for $table drifted in v16:\n$statement",
                    statement in upgraded!!
                )
            }
        }
    }

    // ── Behavioural: the REAL migration over a REAL v15 shape ───────────────

    /** Builds the actual v15 schema from 15.json, then runs MIGRATION_15_16. */
    private fun migrateRealV15(seed: (Connection) -> Unit): Connection {
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        // v15 shape: every entity's CREATE TABLE + CREATE INDEX, resolved from
        // Room's own ${TABLE_NAME} placeholder. FTS content-sync triggers live in
        // database-level setupQueries and are irrelevant here.
        val v15 = readSchema(15)
        val entities = v15.getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            val tableSql = entity.getString("createSql").replace("\${TABLE_NAME}", table)
            connection.createStatement().use { it.execute(tableSql) }
            val indices = entity.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                val index = indices.getJSONObject(j)
                val indexSql = index.getString("createSql").replace("\${TABLE_NAME}", table)
                connection.createStatement().use { it.execute(indexSql) }
            }
        }
        seed(connection)
        MessagesDatabase.UPGRADE_TO_V16_SQL.forEach { statement ->
            connection.createStatement().use { it.execute(statement) }
        }
        return connection
    }

    private fun count(connection: Connection, table: String): Int =
        connection.createStatement().use { s ->
            s.executeQuery("SELECT COUNT(*) FROM `$table`").use { r ->
                r.next()
                r.getInt(1)
            }
        }

    private val SEED_MESSAGE_SQL =
        """
        INSERT INTO `messages`
            (`source`,`providerId`,`threadId`,`normalizedAddress`,`rawAddress`,
             `body`,`date`,`type`,`status`,`dateSent`,`read`,`syncState`)
        VALUES
            ('sms', 100, 7, '09120000000', '+989120000000', 'hello', 1700000000000, 1, -1, 0, 1, 'synced'),
            ('mms', 100, 7, '09120000000', '+989120000000', 'media msg', 1700000001000, 1, -1, 0, 1, 'synced')
        """.trimIndent()

    @Test
    fun `existing v15 messages and conversations survive the migration`() {
        val connection = migrateRealV15 { db ->
            db.createStatement().use {
                it.execute(SEED_MESSAGE_SQL)
                it.execute(
                    """
                    INSERT INTO `conversations`
                        (`threadId`,`normalizedAddress`,`rawAddress`,`snippet`,
                         `lastMessageDate`,`unreadCount`,`lastMessageType`,`pinned`,`archived`)
                    VALUES
                        (7, '09120000000', '+989120000000', 'media msg', 1700000001000, 0, 1, 1, 0)
                    """.trimIndent()
                )
            }
        }
        try {
            assertEquals("both messages must survive", 2, count(connection, "messages"))
            assertEquals("conversation must survive", 1, count(connection, "conversations"))
            connection.createStatement().use { s ->
                s.executeQuery("SELECT body FROM `messages` WHERE source='sms' AND providerId=100").use { r ->
                    assertTrue("SMS row identity must remain queryable", r.next())
                    assertEquals("hello", r.getString(1))
                }
            }
        } finally {
            connection.close()
        }
    }

    @Test
    fun `new v16 tables start completely empty - no user state invented`() {
        val connection = migrateRealV15 { db ->
            db.createStatement().use {
                it.execute(
                    """
                    INSERT INTO `messages`
                        (`source`,`providerId`,`threadId`,`normalizedAddress`,`rawAddress`,
                         `body`,`date`,`type`,`status`,`dateSent`,`read`,`syncState`)
                    VALUES
                        ('sms', 5, 3, '09130000000', '+989130000000', 'bank code 4432', 1700000000000, 1, -1, 0, 1, 'synced')
                    """.trimIndent()
                )
            }
        }
        try {
            v16TableNames.forEach { table ->
                assertEquals("table `$table` must be empty after migration", 0, count(connection, table))
            }
            connection.createStatement().use { s ->
                fun scalar(sql: String): Long =
                    s.executeQuery(sql).use { r -> r.next(); r.getLong(1) }
                // Explicitly pin the NON-DESTRUCTIVE contract: upgrading must not
                // invent user state, and OTP auto-delete must stay OFF.
                assertEquals(0L, scalar("SELECT COUNT(*) FROM message_user_state WHERE starred = 1"))
                assertEquals(0L, scalar("SELECT COUNT(*) FROM message_user_state WHERE trashedAt > 0"))
                assertEquals(0L, scalar("SELECT COUNT(*) FROM message_user_state WHERE purgeAt > 0"))
                assertEquals(0L, scalar("SELECT COUNT(*) FROM message_user_state WHERE keepFromOtpCleanup = 1"))
                assertEquals(0L, scalar("SELECT COUNT(*) FROM message_classification WHERE isOtp = 1"))
                assertEquals(0L, scalar("SELECT COUNT(*) FROM trashed_threads"))
                assertEquals(0L, scalar("SELECT COUNT(*) FROM conversation_preferences WHERE manualUnread = 1"))
                assertEquals(0L, scalar("SELECT COUNT(*) FROM conversation_preferences WHERE mutedUntil > 0"))
                assertEquals(0L, scalar("SELECT COUNT(*) FROM conversation_preferences WHERE spam = 1"))
                assertEquals(0L, scalar("SELECT COUNT(*) FROM message_assets"))
            }
        } finally {
            connection.close()
        }
    }

    @Test
    fun `migration is idempotent - statements are safe to re-run`() {
        val connection = migrateRealV15 { }
        try {
            // Re-running the CREATE IF NOT EXISTS list must not fail: Room
            // re-runs the migration path after a partially-failed open.
            MessagesDatabase.UPGRADE_TO_V16_SQL.forEach { statement ->
                connection.createStatement().use { it.execute(statement) }
            }
        } finally {
            connection.close()
        }
    }
}