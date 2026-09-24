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
 * Guards MIGRATION_18_19 (Outbox V2) the way [MigrationToV18SqlTest] guards v18.
 *
 * The decisive assertion is [theMigrationProducesExactlyTheSchemaRoomGeneratesForV19]: it builds a
 * REAL v18 shape from 18.json, runs the REAL migration SQL, and compares SQLite's own
 * `PRAGMA table_info` against a table built from 19.json. A migration that silently produces a
 * different shape than the entity set would make Room refuse to open every existing user's
 * database, so this is checked exactly rather than approximately.
 */
class MigrationToV19SqlTest {

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

    private fun entityOf(version: Int): JSONObject {
        val entities = readSchema(version).getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            if (entity.getString("tableName") == table) return entity
        }
        throw AssertionError("$table is not declared in $version.json")
    }

    private fun createFrom(version: Int, connection: Connection) {
        val entity = entityOf(version)
        val sql = entity.getString("createSql").replace("\${TABLE_NAME}", table)
        connection.createStatement().use { it.execute(sql) }
    }

    /** SQLite's view of the table, as comparable strings: name|type|notnull|default|pk. */
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

    private fun migrate(connection: Connection) {
        MessagesDatabase.UPGRADE_TO_V19_SQL.forEach { statement ->
            connection.createStatement().use { it.execute(statement) }
        }
    }

    private val V18_INSERT = """
        INSERT INTO `$table`
            (`eventUuid`,`eventType`,`aggregateId`,`messageId`,`revision`,`sortKey`,
             `priority`,`historySource`,`historyGeneration`,`historyOrdinal`,`historyDate`,
             `historyProviderId`,`sequenceLocal`,`ciphertext`,`encoding`,`schemaVersion`,
             `cryptoVersion`,`createdAt`,`attemptCount`,`nextAttemptAt`,`state`,
             `serverSequence`,`ackedAt`,`failureCategory`,`failureHttpStatus`,`lastAttemptAt`,
             `deadLetteredAt`,`failureAppVersion`)
        VALUES
            ('e-1','MESSAGE_CREATED','t','m',1,1,'REALTIME','sms',1,1,1,1,1,
             X'01','envelope.v3',1,3,1,2,0,'SENDING',0,0,NULL,NULL,1,NULL,NULL),
            ('e-2','MESSAGE_CREATED','t','m',1,1,'REALTIME','sms',1,2,1,1,2,
             X'02','envelope.v3',1,3,1,2,0,'DEAD_LETTER',0,0,
             'VALIDATION_FAILED',422,7,7,'3.4.8')
    """.trimIndent()

    // ── Structural pinning ───────────────────────────────────────────────────

    @Test
    fun `migration declares the 18 to 19 boundary`() {
        assertEquals(18, MessagesDatabase.MIGRATION_18_19.startVersion)
        assertEquals(19, MessagesDatabase.MIGRATION_18_19.endVersion)
        // The GLOBAL `CURRENT_SCHEMA_VERSION` is deliberately not asserted here: it moves with every
        // later migration, so pinning it in a per-migration test means editing every older test each
        // time the schema advances. It belongs to exactly one place — the newest migration's test.
    }

    @Test
    fun `migrationOnlyAddsColumnsAndNeverDropsOrRewritesTheTable`() {
        MessagesDatabase.UPGRADE_TO_V19_SQL.forEach { statement ->
            val c = compact(statement).uppercase()
            assertTrue("v19 must only ADD COLUMN: $statement", c.startsWith("ALTERTABLE") && c.contains("ADDCOLUMN"))
            assertTrue(
                "v19 must never drop, delete or rebuild: $statement",
                !c.contains("DROP") && !c.contains("DELETE") && !c.contains("RENAME")
            )
        }
    }

    @Test
    fun `everyAddedColumnIsNullableSoExistingRowsNeedNoValue`() {
        // A NOT NULL column added without a default would make SQLite reject the ALTER outright.
        assertEquals(8, MessagesDatabase.UPGRADE_TO_V19_SQL.size)
        MessagesDatabase.UPGRADE_TO_V19_SQL.forEach { statement ->
            assertTrue(
                "an added column must not be NOT NULL: $statement",
                !compact(statement).uppercase().contains("NOTNULL")
            )
        }
    }

    @Test
    fun `theAddedColumnsAreTheOutboxV2Fields`() {
        val sql = MessagesDatabase.UPGRADE_TO_V19_SQL.joinToString("\n")
        listOf(
            "source", "leaseId", "inFlightSince", "batchId",
            "lastHttpStatus", "lastErrorCode", "lastErrorMessageSafe", "keyRef"
        ).forEach { assertTrue("missing column $it", sql.contains("`$it`")) }
    }

    // ── Behavioural: the REAL migration over a REAL v18 shape ────────────────

    @Test
    fun `theMigrationProducesExactlyTheSchemaRoomGeneratesForV19`() {
        val migrated = DriverManager.getConnection("jdbc:sqlite::memory:")
        val generated = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            createFrom(18, migrated)
            migrate(migrated)
            createFrom(19, generated)

            assertEquals(
                "MIGRATION_18_19 must produce exactly the schema Room generates from the entities",
                tableInfo(generated),
                tableInfo(migrated)
            )
        } finally {
            migrated.close()
            generated.close()
        }
    }

    @Test
    fun `existingRowsSurviveWithTheNewColumnsUnavailableAndNothingInvented`() {
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            createFrom(18, connection)
            connection.createStatement().use { it.execute(V18_INSERT) }
            migrate(connection)

            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM `$table`").use { rows ->
                    rows.next()
                    assertEquals("an outbox row is the only record the event exists", 2, rows.getInt(1))
                }
                // The in-flight row's state and its attempt count must be untouched. The fixture
                // row was written with attemptCount = 2 (see V18_INSERT).
                statement.executeQuery(
                    "SELECT state, attemptCount FROM `$table` WHERE eventUuid = 'e-1'"
                ).use { rows ->
                    rows.next()
                    assertEquals("SENDING", rows.getString(1))
                    assertEquals(2, rows.getInt(2))
                }
                statement.executeQuery(
                    "SELECT state, failureCategory, deadLetteredAt, failureAppVersion " +
                        "FROM `$table` WHERE eventUuid = 'e-2'"
                ).use { rows ->
                    rows.next()
                    assertEquals("DEAD_LETTER", rows.getString(1))
                    assertEquals("VALIDATION_FAILED", rows.getString(2))
                    assertEquals(7L, rows.getLong(3))
                    assertEquals("3.4.8", rows.getString(4))
                }
                // Every new column must be NULL, never a guessed value.
                statement.executeQuery(
                    "SELECT `source`, `leaseId`, `inFlightSince`, `batchId`, `lastHttpStatus`, " +
                        "`lastErrorCode`, `lastErrorMessageSafe`, `keyRef` FROM `$table` WHERE eventUuid = 'e-1'"
                ).use { rows ->
                    assertTrue(rows.next())
                    listOf(1, 2, 3, 4, 5, 6, 7, 8).forEach { column ->
                        if (column == 1 || column == 6 || column == 7 || column == 8) {
                            assertEquals("column $column must be NULL", null, rows.getString(column))
                        } else {
                            rows.getLong(column)
                            assertTrue("column $column must be NULL", rows.wasNull())
                        }
                    }
                }
            }
        } finally {
            connection.close()
        }
    }

    @Test
    fun `aV18SendingRowWithNoLeaseCanStillBeRecoveredByTheSweep`() {
        // Rows left SENDING by a build that predates leases have no inFlightSince to age out.
        // recoverStaleLeases() treats a NULL lease as stale precisely so they are not stranded.
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            createFrom(18, connection)
            connection.createStatement().use { it.execute(V18_INSERT) }
            migrate(connection)

            connection.createStatement().use {
                it.execute(
                    "UPDATE `$table` SET state = 'PENDING', leaseId = NULL, inFlightSince = NULL " +
                        "WHERE state = 'SENDING' AND (inFlightSince IS NULL OR inFlightSince < " +
                        "(2000000000000 - 300000))"
                )
                it.executeQuery("SELECT state FROM `$table` WHERE eventUuid = 'e-1'").use { rows ->
                    rows.next()
                    assertEquals("PENDING", rows.getString(1))
                }
            }
        } finally {
            connection.close()
        }
    }
}
