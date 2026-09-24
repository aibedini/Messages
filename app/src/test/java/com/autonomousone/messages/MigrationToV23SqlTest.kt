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
 * Guards MIGRATION_22_23 (full-mirror verification progress) the way [MigrationToV22SqlTest] guards
 * v22.
 *
 * Two things are pinned here beyond the usual shape check. The new `CREATE INDEX` is on `messages`,
 * which on a large install holds hundreds of thousands of rows — so it is the one part of this
 * migration with a real cost, and the index name must match what Room generates or Room refuses to
 * open the database. And the migration must not touch any existing row: this table is the only local
 * record of messages the user may have deleted from the Provider.
 */
class MigrationToV23SqlTest {

    private val dbDir = "schemas/com.autonomousone.messages.data.MessagesDatabase"
    private val table = "mirror_verify_state"
    private val indexTable = "messages"
    private val indexName = "index_messages_source_date_providerId"

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

    private fun tableInfo(connection: Connection, tableName: String): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(`$tableName`)").use { rows ->
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

    private fun indexNames(connection: Connection, tableName: String): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA index_list(`$tableName`)").use { rows ->
                buildList { while (rows.next()) add(rows.getString("name")) }.sorted()
            }
        }

    private fun migrate(connection: Connection) {
        MessagesDatabase.UPGRADE_TO_V23_SQL.forEach { statement ->
            connection.createStatement().use { it.execute(statement) }
        }
    }

    // ── Structural pinning ───────────────────────────────────────────────────

    @Test
    fun `migration declares the 22 to 23 boundary`() {
        assertEquals(22, MessagesDatabase.MIGRATION_22_23.startVersion)
        assertEquals(23, MessagesDatabase.MIGRATION_22_23.endVersion)
        // The GLOBAL current/previous pair belongs to the NEWEST migration, so it is asserted by
        // MigrationToV24SqlTest. Asserting it here too would make every future schema bump edit this
        // file, and a test that must be edited for unrelated reasons stops being read.
    }

    @Test
    fun `migrationOnlyCreatesAndNeverDropsDeletesOrRenames`() {
        // The mission forbids a destructive migration outright. This is that rule, executable.
        MessagesDatabase.UPGRADE_TO_V23_SQL.forEach { statement ->
            val c = statement.filterNot { it.isWhitespace() }.uppercase()
            assertTrue(
                "v23 must only CREATE: $statement",
                c.startsWith("CREATETABLE") || c.startsWith("CREATEINDEX")
            )
            listOf("DROP", "DELETE", "RENAME", "ALTER").forEach { forbidden ->
                assertTrue("v23 must never $forbidden: $statement", !c.contains(forbidden))
            }
        }
    }

    @Test
    fun `everyCounterCarriesASQLDefault`() {
        // A Kotlin default is NOT a SQL default: without one, Room's generated schema differs from the
        // migration's by a `dflt_value` and Room refuses to open the database.
        val create = MessagesDatabase.UPGRADE_TO_V23_SQL
            .first { it.contains("CREATE TABLE") }.replace(" ", "")
        listOf(
            "startedAt", "updatedAt", "completedAt", "examined", "alreadyReplicated",
            "recovered", "skippedNoDirection", "skippedNoProviderId"
        ).forEach { column ->
            assertTrue(
                "`$column` must carry DEFAULT 0",
                create.contains("`$column`INTEGERNOTNULLDEFAULT0")
            )
        }
    }

    @Test
    fun `theCursorDefaultsToTheStartSentinelNotToZero`() {
        // Zero would be a real date (the epoch) and would place the cursor at the BOTTOM of the
        // mirror, so a fresh sweep would examine nothing and immediately declare itself complete.
        // That is a "verified" claim about a mirror nobody looked at.
        val create = MessagesDatabase.UPGRADE_TO_V23_SQL
            .first { it.contains("CREATE TABLE") }.replace(" ", "")

        assertEquals(
            2,
            Regex("DEFAULT9223372036854775807").findAll(create).count()
        )
    }

    // ── Behavioural ──────────────────────────────────────────────────────────

    @Test
    fun `theMigrationProducesExactlyTheSchemaRoomGeneratesForV23`() {
        val migrated = DriverManager.getConnection("jdbc:sqlite::memory:")
        val generated = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            // A v22 database has the messages table with its four indices and no verify table.
            createFrom(22, "messages", migrated)
            migrate(migrated)
            createFrom(23, table, generated)
            createFrom(23, "messages", generated)

            assertEquals(
                "MIGRATION_22_23 must produce exactly the schema Room generates from the entities",
                tableInfo(generated, table),
                tableInfo(migrated, table)
            )
            // The index must exist under the exact name Room generates, and the messages table must
            // gain it without losing the four it already had. Comparing the whole set catches both.
            assertTrue(
                "the per-source keyset index is missing",
                indexNames(migrated, indexTable).contains(indexName)
            )
            assertEquals(
                "the migrated index set must equal Room's exactly",
                indexNames(generated, indexTable),
                indexNames(migrated, indexTable)
            )
        } finally {
            migrated.close()
            generated.close()
        }
    }

    @Test
    fun `anUpgradingInstallHasNoProgressRatherThanAFabricatedCompletion`() {
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            createFrom(22, "messages", connection)
            migrate(connection)

            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM `$table`").use { rows ->
                    rows.next()
                    assertEquals(
                        "a seeded row would claim a verification that never ran",
                        0,
                        rows.getInt(1)
                    )
                }
            }
        } finally {
            connection.close()
        }
    }

    @Test
    fun `existingMessagesSurviveTheIndexCreation`() {
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            createFrom(22, "messages", connection)
            connection.createStatement().use {
                it.execute(
                    "INSERT INTO `messages` (`source`,`providerId`,`threadId`,`normalizedAddress`," +
                        "`rawAddress`,`body`,`date`,`dateSent`,`type`,`status`,`read`,`syncState`) " +
                        "VALUES ('sms',1,7,'0912','0912','hi',100,100,1,-1,0,'SYNCED')"
                )
            }
            migrate(connection)

            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM `messages`").use { rows ->
                    rows.next()
                    assertEquals(1, rows.getInt(1))
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
            createFrom(22, "messages", connection)
            migrate(connection)
            migrate(connection)

            assertTrue(indexNames(connection, indexTable).contains(indexName))
        } finally {
            connection.close()
        }
    }
}
