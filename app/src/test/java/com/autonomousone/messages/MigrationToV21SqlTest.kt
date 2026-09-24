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
 * Guards MIGRATION_20_21 (history-sync sessions, mission §25/§70) the way
 * [MigrationToV20SqlTest] guards v20.
 *
 * The decisive assertion is [theMigrationProducesExactlyTheSchemaRoomGeneratesForV21]: a real v20
 * shape built from 20.json, the real migration SQL, and SQLite's own `PRAGMA table_info` compared
 * against a table built from 21.json. A migration that produced a different shape than the entity
 * set would make Room refuse to open every existing user's database — on a database that is the only
 * local record of messages the user may have deleted from the Provider.
 */
class MigrationToV21SqlTest {

    private val dbDir = "schemas/com.autonomousone.messages.data.MessagesDatabase"
    private val table = "history_sync_sessions"

    private fun readSchema(version: Int): JSONObject {
        val file = File("$dbDir/$version.json")
        assertTrue("Schema $version.json not found — run :app:kspDebugKotlin", file.exists())
        return JSONObject(file.readText())
    }

    private fun entityOf(version: Int, tableName: String): JSONObject? {
        val entities = readSchema(version).getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            if (entity.getString("tableName") == tableName) return entity
        }
        return null
    }

    private fun createFrom(version: Int, tableName: String, connection: Connection) {
        val entity = entityOf(version, tableName)
            ?: throw AssertionError("$tableName is not declared in $version.json")
        connection.createStatement().use {
            it.execute(entity.getString("createSql").replace("\${TABLE_NAME}", tableName))
        }
        val indices = entity.optJSONArray("indices") ?: return
        for (i in 0 until indices.length()) {
            val index = indices.getJSONObject(i)
            connection.createStatement().use {
                it.execute(index.getString("createSql").replace("\${TABLE_NAME}", tableName))
            }
        }
    }

    private fun tableInfo(connection: Connection): List<String> =
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

    private fun indexNames(connection: Connection): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA index_list(`$table`)").use { rows ->
                buildList {
                    while (rows.next()) add(rows.getString("name"))
                }.sorted()
            }
        }

    private fun migrate(connection: Connection) {
        MessagesDatabase.UPGRADE_TO_V21_SQL.forEach { statement ->
            connection.createStatement().use { it.execute(statement) }
        }
    }

    // ── Structural pinning ───────────────────────────────────────────────────

    @Test
    fun `migration declares the 20 to 21 boundary`() {
        assertEquals(20, MessagesDatabase.MIGRATION_20_21.startVersion)
        assertEquals(21, MessagesDatabase.MIGRATION_20_21.endVersion)
        // The GLOBAL current/previous pair belongs to the NEWEST migration, so it is asserted by
        // MigrationToV22SqlTest. Asserting it here too would make every future schema bump edit this
        // file, and a test that must be edited for unrelated reasons stops being read.
    }

    @Test
    fun `migrationOnlyCreatesAndNeverDropsDeletesOrRenames`() {
        // The mission forbids a destructive migration outright. This is that rule, executable.
        MessagesDatabase.UPGRADE_TO_V21_SQL.forEach { statement ->
            val c = statement.filterNot { it.isWhitespace() }.uppercase()
            assertTrue("v21 must only CREATE: $statement", c.startsWith("CREATETABLE") || c.startsWith("CREATEINDEX"))
            listOf("DROP", "DELETE", "RENAME", "ALTER").forEach { forbidden ->
                assertTrue("v21 must never $forbidden: $statement", !c.contains(forbidden))
            }
        }
    }

    @Test
    fun `everyCounterAndFlagCarriesASQLDefault`() {
        // A Kotlin default is NOT a SQL default. Without one, Room's generated schema would differ
        // from the migration's by a `dflt_value`, and Room refuses to open the database — so the
        // entity's `@ColumnInfo(defaultValue = ...)` annotations and this SQL must agree.
        val create = MessagesDatabase.UPGRADE_TO_V21_SQL.first { it.contains("CREATE TABLE") }
        listOf(
            "finishedAt", "eligible", "enqueued", "failed", "skippedLocalOnly",
            "skippedAskPending", "skippedNoDirection", "skippedSyncOff", "scanExhausted"
        ).forEach { column ->
            assertTrue(
                "`$column` must carry DEFAULT 0 so existing-shaped inserts and the entity agree",
                create.replace(" ", "").contains("`$column`INTEGERNOTNULLDEFAULT0")
            )
        }
    }

    @Test
    fun `theSessionTableRecordsEverySkipReasonSeparately`() {
        // §70 wants every discrepancy EXPLAINABLE, so the parts have to exist as columns. A single
        // `skipped` total would say a number without saying whose decision produced it.
        val create = MessagesDatabase.UPGRADE_TO_V21_SQL.first { it.contains("CREATE TABLE") }
        listOf("skippedLocalOnly", "skippedAskPending", "skippedNoDirection", "skippedSyncOff")
            .forEach { assertTrue("missing reason column $it", create.contains("`$it`")) }
        assertTrue("the session identity must be the primary key", create.contains("PRIMARY KEY(`sessionId`)"))
    }

    // ── Behavioural ──────────────────────────────────────────────────────────

    @Test
    fun `theMigrationProducesExactlyTheSchemaRoomGeneratesForV21`() {
        val migrated = DriverManager.getConnection("jdbc:sqlite::memory:")
        val generated = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            // A v20 database simply has no such table; the migration creates it.
            createFrom(20, "remote_commands", migrated)
            migrate(migrated)
            createFrom(21, table, generated)

            assertEquals(
                "MIGRATION_20_21 must produce exactly the schema Room generates from the entities",
                tableInfo(generated),
                tableInfo(migrated)
            )
            assertEquals(
                "and the same indexes, or a session lookup silently loses its index",
                indexNames(generated),
                indexNames(migrated)
            )
        } finally {
            migrated.close()
            generated.close()
        }
    }

    @Test
    fun `anExistingDatabaseKeepsItsRowsAndGetsZeroSessions`() {
        // The honest starting state for an upgrade: no session has been accounted for yet. A
        // backfilled "everything was fine" row would be a fabricated measurement — the exact failure
        // §70 exists to prevent.
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            createFrom(20, "remote_commands", connection)
            connection.createStatement().use {
                it.execute(
                    "INSERT INTO `remote_commands` (`commandId`,`type`,`ciphertext`,`encoding`," +
                        "`schemaVersion`,`cryptoVersion`,`signature`,`senderDeviceId`,`issuedAt`," +
                        "`receivedAt`,`expiresAt`,`nonce`,`idempotencyKey`,`state`,`attemptCount`) " +
                        "VALUES ('c','SEND_SMS',X'01','envelope.v1',1,0,X'02','web-1',1,1,0,'n1','i','RECEIVED',0)"
                )
            }
            migrate(connection)

            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM `history_sync_sessions`").use { rows ->
                    rows.next()
                    assertEquals(0, rows.getInt(1))
                }
                statement.executeQuery("SELECT COUNT(*) FROM `remote_commands`").use { rows ->
                    rows.next()
                    assertEquals("no existing row may be touched", 1, rows.getInt(1))
                }
            }
        } finally {
            connection.close()
        }
    }

    @Test
    fun `migratingTwiceIsANoOp`() {
        // The migration is written with IF NOT EXISTS because a partially-applied upgrade can be
        // retried; if it threw, the app would be stuck unable to open its own database.
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            createFrom(20, "remote_commands", connection)
            migrate(connection)
            migrate(connection)

            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM `history_sync_sessions`").use { rows ->
                    rows.next()
                    assertEquals(0, rows.getInt(1))
                }
            }
        } finally {
            connection.close()
        }
    }

    @Test
    fun `theArithmeticTheSessionStoresCanBeCheckedInSql`() {
        // The columns exist so §70's identity is answerable by the database, not by a UI. A row
        // whose parts do not sum to `eligible` is a bug in the accounting and must be findable.
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            createFrom(20, "remote_commands", connection)
            migrate(connection)
            connection.createStatement().use {
                it.execute(
                    "INSERT INTO `history_sync_sessions` (`sessionId`,`source`,`generation`," +
                        "`startedAt`,`updatedAt`,`eligible`,`enqueued`,`failed`," +
                        "`skippedLocalOnly`,`skippedAskPending`,`skippedNoDirection`," +
                        "`skippedSyncOff`) VALUES " +
                        "('balanced','sms',4,1,1,10,7,1,1,1,0,0)," +
                        "('unbalanced','sms',4,2,2,10,7,1,1,0,0,0)"
                )
                it.executeQuery(
                    "SELECT sessionId FROM `history_sync_sessions` WHERE eligible <> " +
                        "(enqueued + failed + skippedLocalOnly + skippedAskPending + " +
                        "skippedNoDirection + skippedSyncOff)"
                ).use { rows ->
                    assertTrue(rows.next())
                    assertEquals("unbalanced", rows.getString(1))
                    assertTrue("exactly one row is unbalanced", !rows.next())
                }
            }
        } finally {
            connection.close()
        }
    }
}
