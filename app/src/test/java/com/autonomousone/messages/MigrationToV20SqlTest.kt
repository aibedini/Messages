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
 * Guards MIGRATION_19_20 (Command V2) the way [MigrationToV18SqlTest] guards v18.
 *
 * The decisive assertion is [theMigrationProducesExactlyTheSchemaRoomGeneratesForV20]: a real v19
 * shape built from 19.json, the real migration SQL, and SQLite's own `PRAGMA table_info` compared
 * against a table built from 20.json. A migration that produced a different shape than the entity
 * set would make Room refuse to open every existing user's database.
 */
class MigrationToV20SqlTest {

    private val dbDir = "schemas/com.autonomousone.messages.data.MessagesDatabase"
    private val table = "remote_commands"

    private fun readSchema(version: Int): JSONObject {
        val file = File("$dbDir/$version.json")
        assertTrue("Schema $version.json not found — run :app:kspDebugKotlin", file.exists())
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
        connection.createStatement().use {
            it.execute(entity.getString("createSql").replace("\${TABLE_NAME}", table))
        }
        val indices = entity.optJSONArray("indices") ?: return
        for (i in 0 until indices.length()) {
            val index = indices.getJSONObject(i)
            connection.createStatement().use {
                it.execute(index.getString("createSql").replace("\${TABLE_NAME}", table))
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
        MessagesDatabase.UPGRADE_TO_V20_SQL.forEach { statement ->
            connection.createStatement().use { it.execute(statement) }
        }
    }

    /** A v19 command row, in the state a claim would have left it. */
    private val V19_INSERT = """
        INSERT INTO `$table`
            (`commandId`,`type`,`ciphertext`,`encoding`,`schemaVersion`,`cryptoVersion`,
             `signature`,`senderDeviceId`,`issuedAt`,`receivedAt`,`expiresAt`,`nonce`,
             `idempotencyKey`,`state`)
        VALUES
            ('cmd-1','SEND_SMS',X'01','envelope.v1',1,0,X'02','web-1',100,200,999,'n1','idem-1','ACCEPTED'),
            ('cmd-2','MARK_THREAD_READ',X'03','envelope.v1',1,0,X'04','web-1',100,201,999,'n2','idem-2','COMPLETED')
    """.trimIndent()

    // ── Structural pinning ───────────────────────────────────────────────────

    @Test
    fun `migration declares the 19 to 20 boundary`() {
        assertEquals(19, MessagesDatabase.MIGRATION_19_20.startVersion)
        assertEquals(20, MessagesDatabase.MIGRATION_19_20.endVersion)
        // The GLOBAL current/previous pair belongs to the newest migration and is asserted by
        // MigrationToV21SqlTest. Asserting it here too would make every future schema bump edit this
        // file, and a test that must be edited for unrelated reasons stops being read.
    }

    @Test
    fun `migrationOnlyAddsColumnsAndNeverDropsOrRewritesTheTable`() {
        MessagesDatabase.UPGRADE_TO_V20_SQL.forEach { statement ->
            val c = statement.filterNot { it.isWhitespace() }.uppercase()
            assertTrue("v20 must only ADD COLUMN: $statement", c.startsWith("ALTERTABLE") && c.contains("ADDCOLUMN"))
            assertTrue(
                "v20 must never drop, delete or rebuild: $statement",
                !c.contains("DROP") && !c.contains("DELETE") && !c.contains("RENAME")
            )
        }
    }

    @Test
    fun `onlyTheAttemptCounterIsNotNullAndItCarriesADefault`() {
        // A NOT NULL column with no default makes SQLite reject the ALTER outright, so every
        // existing command row would fail to migrate. `attemptCount` is the one non-null field, and
        // it has a default precisely because "never attempted" is a real value for an old row.
        MessagesDatabase.UPGRADE_TO_V20_SQL.forEach { statement ->
            val c = statement.filterNot { it.isWhitespace() }.uppercase()
            if (c.contains("NOTNULL")) {
                assertTrue("$statement must carry a DEFAULT", c.contains("DEFAULT0"))
                assertTrue("only attemptCount may be non-null", c.contains("ATTEMPTCOUNT"))
            }
        }
        assertEquals(
            1,
            MessagesDatabase.UPGRADE_TO_V20_SQL.count {
                it.filterNot { ch -> ch.isWhitespace() }.uppercase().contains("NOTNULL")
            }
        )
    }

    @Test
    fun `theAddedColumnsAreTheCommandV2Fields`() {
        val sql = MessagesDatabase.UPGRADE_TO_V20_SQL.joinToString("\n")
        listOf(
            "attemptCount", "leaseId", "leaseExpiresAt", "claimedAt", "executedAt",
            "completedAt", "resultEventId", "lastErrorCode", "clientMessageId"
        ).forEach { assertTrue("missing column $it", sql.contains("`$it`")) }
        assertEquals(9, MessagesDatabase.UPGRADE_TO_V20_SQL.size)
    }

    // ── Behavioural ──────────────────────────────────────────────────────────

    @Test
    fun `theMigrationProducesExactlyTheSchemaRoomGeneratesForV20`() {
        val migrated = DriverManager.getConnection("jdbc:sqlite::memory:")
        val generated = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            createFrom(19, migrated)
            migrate(migrated)
            createFrom(20, generated)

            assertEquals(
                "MIGRATION_19_20 must produce exactly the schema Room generates from the entities",
                tableInfo(generated),
                tableInfo(migrated)
            )
        } finally {
            migrated.close()
            generated.close()
        }
    }

    @Test
    fun `existingCommandsSurviveWithNoLeaseAndNothingInvented`() {
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            createFrom(19, connection)
            connection.createStatement().use { it.execute(V19_INSERT) }
            migrate(connection)

            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM `$table`").use { rows ->
                    rows.next()
                    assertEquals("a command row is the only record the instruction existed", 2, rows.getInt(1))
                }
                // The claimed command must still read as claimed, not silently become executable.
                statement.executeQuery(
                    "SELECT state, attemptCount, leaseId, leaseExpiresAt, clientMessageId, " +
                        "executedAt, completedAt, resultEventId, lastErrorCode " +
                        "FROM `$table` WHERE commandId = 'cmd-1'"
                ).use { rows ->
                    assertTrue(rows.next())
                    assertEquals("ACCEPTED", rows.getString(1))
                    assertEquals(0, rows.getInt(2))
                    listOf(3, 4, 5, 6, 7, 8, 9).forEach { column ->
                        assertEquals("column $column must be NULL", null, rows.getString(column))
                    }
                }
            }
        } finally {
            connection.close()
        }
    }

    @Test
    fun `aMigratedCommandCanStillBeClaimedAndReclaimed`() {
        // The end-to-end shape of the fix: a v19 row that was claimed when the process died is
        // reclaimable after the migration, because the lease is simply absent and therefore expired.
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            createFrom(19, connection)
            connection.createStatement().use { it.execute(V19_INSERT) }
            migrate(connection)

            connection.createStatement().use {
                it.execute(
                    "UPDATE `$table` SET state = 'RECEIVED', leaseId = NULL, leaseExpiresAt = NULL " +
                        "WHERE state IN ('ACCEPTED', 'EXECUTING') " +
                        "AND leaseExpiresAt IS NOT NULL AND leaseExpiresAt < 2000"
                )
                // A pre-lease row has no leaseExpiresAt, so the predicate above deliberately does
                // NOT match it — recovery for those is the drain's job, not the lease sweep's.
                it.executeQuery("SELECT state FROM `$table` WHERE commandId = 'cmd-1'").use { rows ->
                    rows.next()
                    assertEquals("ACCEPTED", rows.getString(1))
                }
                // And a command WITH an expired lease is reclaimed.
                it.execute(
                    "UPDATE `$table` SET state = 'EXECUTING', leaseId = 'L', leaseExpiresAt = 500 " +
                        "WHERE commandId = 'cmd-1'"
                )
                it.execute(
                    "UPDATE `$table` SET state = 'RECEIVED', leaseId = NULL, leaseExpiresAt = NULL " +
                        "WHERE state IN ('ACCEPTED', 'EXECUTING') " +
                        "AND leaseExpiresAt IS NOT NULL AND leaseExpiresAt < 2000"
                )
                it.executeQuery("SELECT state, leaseId FROM `$table` WHERE commandId = 'cmd-1'").use { rows ->
                    rows.next()
                    assertEquals("RECEIVED", rows.getString(1))
                    assertEquals(null, rows.getString(2))
                }
            }
        } finally {
            connection.close()
        }
    }
}
