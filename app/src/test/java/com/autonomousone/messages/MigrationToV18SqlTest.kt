package com.autonomousone.messages

import com.autonomousone.messages.data.MessagesDatabase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Guards MIGRATION_17_18 the way [MigrationToV16SqlTest] guards v16: the hand-written SQL
 * must produce EXACTLY the schema Room generates from GatewaySync.kt, otherwise Room throws
 * on the device at upgrade time and a real user's database fails to open.
 *
 * The decisive assertion is [the migration produces exactly the schema Room generates for v18]:
 * it builds a REAL v17 shape from 17.json, runs the REAL migration, and compares SQLite's own
 * `PRAGMA table_info` against a table built from 18.json — no string parsing, no hand-copied
 * column list that could drift from the entity set alongside the migration it is meant to check.
 */
class MigrationToV18SqlTest {

    private val dbDir = "schemas/com.autonomousone.messages.data.MessagesDatabase"
    private val table = "gateway_event_outbox"

    private fun compact(sql: String): String = sql.filterNot { it.isWhitespace() }

    private fun readSchema(version: Int): JSONObject {
        val file = File("$dbDir/$version.json")
        assertTrue(
            "Schema $version.json not found at ${file.absolutePath} — run :app:kspDebugKotlin",
            file.exists()
        )
        return JSONObject(file.readText())
    }

    /** Room's own CREATE TABLE for one entity, with ${TABLE_NAME} resolved. */
    private fun entityOf(version: Int, table: String): JSONObject {
        val entities = readSchema(version).getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            if (entity.getString("tableName") == table) return entity
        }
        throw AssertionError("$table is not declared in $version.json")
    }

    private fun createFrom(version: Int, connection: Connection, table: String = this.table) {
        val entity = entityOf(version, table)
        val sql = entity.getString("createSql").replace("\${TABLE_NAME}", table)
        connection.createStatement().use { it.execute(sql) }
    }

    /** SQLite's view of the table, as comparable strings: name|type|notnull|default|pk. */
    private fun tableInfo(connection: Connection, table: String): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(`$table`)").use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            listOf(
                                rows.getString("name"),
                                rows.getString("type"),
                                rows.getInt("notnull").toString(),
                                rows.getString("dflt_value") ?: "-",
                                rows.getInt("pk").toString()
                            ).joinToString("|")
                        )
                    }
                }.sorted()
            }
        }

    private fun migrate(connection: Connection) {
        MessagesDatabase.UPGRADE_TO_V18_SQL.forEach { statement ->
            connection.createStatement().use { it.execute(statement) }
        }
    }

    /** Each new column's NULL-ness, read column by column so `wasNull` refers to its own read. */
    private fun nullFlags(connection: Connection): Map<String, Boolean> =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT `failureCategory`, `failureHttpStatus`, `lastAttemptAt`, " +
                    "`deadLetteredAt`, `failureAppVersion` FROM `$table`"
            ).use { rows ->
                assertTrue("the migrated row must still exist", rows.next())
                val flags = linkedMapOf<String, Boolean>()
                flags["failureCategory"] = rows.getString(1) == null
                rows.getInt(2)
                flags["failureHttpStatus"] = rows.wasNull()
                rows.getLong(3)
                flags["lastAttemptAt"] = rows.wasNull()
                rows.getLong(4)
                flags["deadLetteredAt"] = rows.wasNull()
                flags["failureAppVersion"] = rows.getString(5) == null
                flags
            }
        }

    // ── Structural pinning ──────────────────────────────────────────────────

    @Test
    fun `migration declares the 17 to 18 boundary`() {
        assertEquals(17, MessagesDatabase.MIGRATION_17_18.startVersion)
        assertEquals(18, MessagesDatabase.MIGRATION_17_18.endVersion)
    }

    @Test
    fun `v18 adds only safe dead letter metadata`() {
        val sql = MessagesDatabase.UPGRADE_TO_V18_SQL.joinToString("\n")

        assertEquals(5, MessagesDatabase.UPGRADE_TO_V18_SQL.size)
        assertTrue(sql.contains("ALTER TABLE `gateway_event_outbox` ADD COLUMN"))
        listOf(
            "failureCategory",
            "failureHttpStatus",
            "lastAttemptAt",
            "deadLetteredAt",
            "failureAppVersion"
        ).forEach { assertTrue(sql.contains("`$it`")) }
        listOf("ciphertext", "apiKey", "signature", "phone", "body").forEach {
            assertTrue("migration must not add sensitive metadata: $it", !sql.contains(it, ignoreCase = true))
        }
    }

    @Test
    fun `migration only adds columns and never drops or rewrites the table`() {
        MessagesDatabase.UPGRADE_TO_V18_SQL.forEach { statement ->
            val c = compact(statement).uppercase()
            assertTrue("v18 must only ADD COLUMN: $statement", c.startsWith("ALTERTABLE") && c.contains("ADDCOLUMN"))
            assertTrue(
                "v18 must never drop, delete or rebuild: $statement",
                !c.contains("DROP") && !c.contains("DELETE") && !c.contains("RENAME")
            )
        }
    }

    // ── Behavioural: the REAL migration over a REAL v17 shape ───────────────

    @Test
    fun `the migration produces exactly the schema Room generates for v18`() {
        val migrated = DriverManager.getConnection("jdbc:sqlite::memory:")
        val generated = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            createFrom(17, migrated)
            migrate(migrated)
            createFrom(18, generated)

            assertEquals(
                "MIGRATION_17_18 must produce exactly the schema Room generates from the entities",
                tableInfo(generated, table),
                tableInfo(migrated, table)
            )
        } finally {
            migrated.close()
            generated.close()
        }
    }

    @Test
    fun `existing dead letter rows survive with metadata unavailable and nothing invented`() {
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            createFrom(17, connection)
            connection.createStatement().use {
                it.execute(
                    """
                    INSERT INTO `$table`
                        (`eventUuid`,`eventType`,`aggregateId`,`messageId`,`revision`,`sortKey`,
                         `priority`,`historySource`,`historyGeneration`,`historyOrdinal`,`historyDate`,
                         `historyProviderId`,`sequenceLocal`,`ciphertext`,`encoding`,`schemaVersion`,
                         `cryptoVersion`,`createdAt`,`attemptCount`,`nextAttemptAt`,`state`,
                         `serverSequence`,`ackedAt`)
                    VALUES
                        ('e-1','MESSAGE_CREATED','t','m',1,1,'REALTIME','sms',1,1,1,1,1,
                         X'01','envelope.v3',1,3,1,3,0,'DEAD_LETTER',0,0)
                    """.trimIndent()
                )
            }

            migrate(connection)

            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM `$table`").use { rows ->
                    rows.next()
                    assertEquals("the historical row must survive", 1, rows.getInt(1))
                }
                statement.executeQuery("SELECT `state` FROM `$table`").use { rows ->
                    rows.next()
                    assertEquals("the migration must not resurrect the row", "DEAD_LETTER", rows.getString(1))
                }
            }
            nullFlags(connection).forEach { (column, isNull) ->
                assertTrue("$column must migrate as NULL, never a guessed value", isNull)
            }
        } finally {
            connection.close()
        }
    }

    @Test
    fun `the summary aggregate reports an empty dead letter set without inventing rows`() {
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            createFrom(17, connection)
            migrate(connection)
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT COUNT(*), SUM(CASE WHEN `deadLetteredAt` IS NOT NULL THEN 1 ELSE 0 END) " +
                        "FROM `$table` WHERE `state` = 'DEAD_LETTER'"
                ).use { rows ->
                    rows.next()
                    assertEquals(0, rows.getInt(1))
                    // SUM over zero rows is NULL; Room reads it as 0 through getInt. Pin that
                    // so an aggregate reader can never crash on a clean install.
                    assertEquals(0, rows.getInt(2))
                    assertTrue(rows.wasNull())
                }
            }
        } finally {
            connection.close()
        }
    }
}
