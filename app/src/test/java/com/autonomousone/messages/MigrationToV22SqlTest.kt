package com.autonomousone.messages

import com.autonomousone.messages.data.ControlPlaneAuthStateEntity
import com.autonomousone.messages.data.MessagesDatabase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Guards MIGRATION_21_22 (the control plane's standing verdict on this device) the way
 * [MigrationToV21SqlTest] guards v21.
 *
 * The decisive assertion is [theMigrationProducesExactlyTheSchemaRoomGeneratesForV22]: a real v21
 * shape built from 21.json, the real migration SQL, and SQLite's own `PRAGMA table_info` compared
 * against a table built from 22.json. A mismatch would make Room refuse to open every existing user's
 * database.
 */
class MigrationToV22SqlTest {

    private val dbDir = "schemas/com.autonomousone.messages.data.MessagesDatabase"
    private val table = "control_plane_auth_state"

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

    private fun migrate(connection: Connection) {
        MessagesDatabase.UPGRADE_TO_V22_SQL.forEach { statement ->
            connection.createStatement().use { it.execute(statement) }
        }
    }

    // ── Structural pinning ───────────────────────────────────────────────────

    @Test
    fun `migration declares the 21 to 22 boundary`() {
        assertEquals(21, MessagesDatabase.MIGRATION_21_22.startVersion)
        assertEquals(22, MessagesDatabase.MIGRATION_21_22.endVersion)
        // The GLOBAL current/previous pair belongs to the NEWEST migration, so it is asserted by
        // MigrationToV23SqlTest. Asserting it here too would make every future schema bump edit this
        // file, and a test that must be edited for unrelated reasons stops being read.
    }

    @Test
    fun `migrationOnlyCreatesAndNeverDropsDeletesOrRenames`() {
        // The mission forbids a destructive migration outright. This is that rule, executable — and it
        // matters more than usual here: this table is the only durable record that the server has
        // refused this device.
        MessagesDatabase.UPGRADE_TO_V22_SQL.forEach { statement ->
            val c = statement.filterNot { it.isWhitespace() }.uppercase()
            assertTrue("v22 must only CREATE: $statement", c.startsWith("CREATETABLE"))
            listOf("DROP", "DELETE", "RENAME", "ALTER").forEach { forbidden ->
                assertTrue("v22 must never $forbidden: $statement", !c.contains(forbidden))
            }
        }
    }

    @Test
    fun `everyCounterCarriesASQLDefault`() {
        // A Kotlin default is NOT a SQL default: without one, Room's generated schema differs from the
        // migration's by a `dflt_value` and Room refuses to open the database. `lastRejectionStatus` is
        // the one nullable column, because "no rejection yet" is genuinely unknown rather than zero.
        val create = MessagesDatabase.UPGRADE_TO_V22_SQL.single()
        listOf(
            "revokedAt", "consecutiveRejections", "lastAcceptedAt", "clearedAt", "updatedAt"
        ).forEach { column ->
            assertTrue(
                "`$column` must carry DEFAULT 0",
                create.replace(" ", "").contains("`$column`INTEGERNOTNULLDEFAULT0")
            )
        }
        assertTrue(
            "the rejection status must be nullable so absence is not stored as a status",
            create.replace(" ", "").contains("`lastRejectionStatus`INTEGER,")
        )
    }

    @Test
    fun `theTableHoldsExactlyOneRowByConstruction`() {
        // It is a property of the device, not a log. A second row would mean two answers to "is this
        // device refused?", and nothing in the app could say which one to believe.
        val create = MessagesDatabase.UPGRADE_TO_V22_SQL.single().filterNot { it.isWhitespace() }
        assertTrue("id must be the primary key", create.contains("PRIMARYKEY(`id`)"))
        assertEquals(
            "and a single-row table needs no index",
            1,
            MessagesDatabase.UPGRADE_TO_V22_SQL.size
        )
        assertEquals(1, ControlPlaneAuthStateEntity.SINGLETON_ID)
    }

    // ── Behavioural ──────────────────────────────────────────────────────────

    @Test
    fun `theMigrationProducesExactlyTheSchemaRoomGeneratesForV22`() {
        val migrated = DriverManager.getConnection("jdbc:sqlite::memory:")
        val generated = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            // A v21 database simply has no such table; the migration creates it.
            createFrom(21, "remote_commands", migrated)
            migrate(migrated)
            createFrom(22, table, generated)

            assertEquals(
                "MIGRATION_21_22 must produce exactly the schema Room generates from the entities",
                tableInfo(generated),
                tableInfo(migrated)
            )
        } finally {
            migrated.close()
            generated.close()
        }
    }

    @Test
    fun `anUpgradingInstallHasNoVerdictRatherThanAFabricatedOne`() {
        // Zero rows is the honest starting state: this build has not made an authenticated call yet, so
        // it has no evidence either way. A seeded row would be indistinguishable from a verified one.
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            createFrom(21, "remote_commands", connection)
            migrate(connection)

            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM `$table`").use { rows ->
                    rows.next()
                    assertEquals(0, rows.getInt(1))
                }
            }
        } finally {
            connection.close()
        }
    }

    @Test
    fun `migratingTwiceIsANoOp`() {
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            createFrom(21, "remote_commands", connection)
            migrate(connection)
            migrate(connection)

            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM `$table`").use { rows ->
                    rows.next()
                    assertEquals(0, rows.getInt(1))
                }
            }
        } finally {
            connection.close()
        }
    }

    @Test
    fun `aStoredVerdictSurvivesTheRoundTripWithItsCounters`() {
        // The point of the table: after a process death the app must still know it was refused, and how
        // strong the evidence was — an in-memory registry forgets both.
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            createFrom(21, "remote_commands", connection)
            migrate(connection)
            connection.createStatement().use {
                it.execute(
                    "INSERT INTO `$table` (`id`,`revokedAt`,`consecutiveRejections`," +
                        "`lastRejectionStatus`,`lastAcceptedAt`,`clearedAt`,`updatedAt`) " +
                        "VALUES (1, 1700, 3, 403, 1600, 0, 1700)"
                )
                it.executeQuery(
                    "SELECT revokedAt, consecutiveRejections, lastRejectionStatus FROM `$table` " +
                        "WHERE id = 1"
                ).use { rows ->
                    assertTrue(rows.next())
                    assertEquals(1700L, rows.getLong(1))
                    assertEquals(3, rows.getInt(2))
                    assertEquals(403, rows.getInt(3))
                }
                // A replace (what the DAO does) must not accumulate rows.
                it.execute(
                    "INSERT OR REPLACE INTO `$table` (`id`,`revokedAt`,`consecutiveRejections`," +
                        "`lastRejectionStatus`,`lastAcceptedAt`,`clearedAt`,`updatedAt`) " +
                        "VALUES (1, 0, 0, 403, 1800, 1800, 1800)"
                )
                it.executeQuery("SELECT COUNT(*) FROM `$table`").use { rows ->
                    rows.next()
                    assertEquals(1, rows.getInt(1))
                }
            }
        } finally {
            connection.close()
        }
    }
}
