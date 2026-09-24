package com.autonomousone.messages

import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.PendingInboundSmsEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Guards MIGRATION_23_24 (inbound messages held until they can be written) the way
 * [MigrationToV23SqlTest] guards v23.
 *
 * The unique index is the part that has to be exactly right: it is what makes a redelivered broadcast
 * idempotent, and an index that Room expects but the migration does not create makes Room refuse to open
 * the database — on the table that holds the only copy of a message that arrived.
 */
class MigrationToV24SqlTest {

    private val dbDir = "schemas/com.autonomousone.messages.data.MessagesDatabase"
    private val table = "pending_inbound_sms"

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
                buildList { while (rows.next()) add(rows.getString("name")) }.sorted()
            }
        }

    private fun migrate(connection: Connection) {
        MessagesDatabase.UPGRADE_TO_V24_SQL.forEach { statement ->
            connection.createStatement().use { it.execute(statement) }
        }
    }

    private fun insert(
        connection: Connection,
        fingerprint: String,
        state: String = PendingInboundSmsEntity.STATE_PENDING
    ) {
        connection.prepareStatement(
            "INSERT OR IGNORE INTO `$table` (`pduFingerprint`,`address`,`body`,`dateMs`," +
                "`threadId`,`createdAt`,`state`) VALUES (?,?,?,?,?,?,?)"
        ).use {
            it.setString(1, fingerprint)
            it.setString(2, "+989120000000")
            it.setString(3, "hello")
            it.setLong(4, 1_700_000_000_000)
            it.setLong(5, 3)
            it.setLong(6, 1_700_000_000_500)
            it.setString(7, state)
            it.execute()
        }
    }

    // ── Structural pinning ───────────────────────────────────────────────────

    @Test
    fun `migration declares the 23 to 24 boundary and is the current version`() {
        assertEquals(23, MessagesDatabase.MIGRATION_23_24.startVersion)
        assertEquals(24, MessagesDatabase.MIGRATION_23_24.endVersion)
        assertEquals(24, MessagesDatabase.CURRENT_SCHEMA_VERSION)
        assertEquals(23, MessagesDatabase.PREVIOUS_SCHEMA_VERSION)
    }

    @Test
    fun `migrationOnlyCreatesAndNeverDropsDeletesOrRenames`() {
        MessagesDatabase.UPGRADE_TO_V24_SQL.forEach { statement ->
            val c = statement.filterNot { it.isWhitespace() }.uppercase()
            assertTrue(
                "v24 must only CREATE: $statement",
                c.startsWith("CREATETABLE") || c.startsWith("CREATEUNIQUEINDEX") || c.startsWith("CREATEINDEX")
            )
            listOf("DROP", "DELETE", "RENAME", "ALTER").forEach { forbidden ->
                assertTrue("v24 must never $forbidden: $statement", !c.contains(forbidden))
            }
        }
    }

    @Test
    fun `everyCounterAndFlagCarriesASQLDefault`() {
        // A Kotlin default is NOT a SQL default: without one, Room's generated schema differs from the
        // migration's by a `dflt_value` and Room refuses to open the database.
        val create = MessagesDatabase.UPGRADE_TO_V24_SQL
            .first { it.contains("CREATE TABLE") }.replace(" ", "")
        assertTrue("attempts must default to 0", create.contains("`attempts`INTEGERNOTNULLDEFAULT0"))
        assertTrue("state must default to PENDING", create.contains("`state`TEXTNOTNULLDEFAULT'PENDING'"))
        assertTrue("the last error must be nullable", create.contains("`lastError`TEXT,"))
    }

    // ── Behavioural ──────────────────────────────────────────────────────────

    @Test
    fun `theMigrationProducesExactlyTheSchemaRoomGeneratesForV24`() {
        val migrated = DriverManager.getConnection("jdbc:sqlite::memory:")
        val generated = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            createFrom(23, "remote_commands", migrated)
            migrate(migrated)
            createFrom(24, table, generated)

            assertEquals(
                "MIGRATION_23_24 must produce exactly the schema Room generates from the entities",
                tableInfo(generated),
                tableInfo(migrated)
            )
            assertEquals(
                "and the same indices — the unique one is the idempotency guarantee",
                indexNames(generated),
                indexNames(migrated)
            )
        } finally {
            migrated.close()
            generated.close()
        }
    }

    @Test
    fun `theFingerprintIndexIsUniqueSoARedeliveredBroadcastCannotHoldTheMessageTwice`() {
        // The property the whole design rests on: a retry inserts into the PROVIDER, and a second held row
        // for the same broadcast would be a second message.
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            createFrom(23, "remote_commands", connection)
            migrate(connection)

            insert(connection, "pdu-same")
            insert(connection, "pdu-same")
            insert(connection, "pdu-other")

            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM `$table`").use { rows ->
                    rows.next()
                    assertEquals(2, rows.getInt(1))
                }
            }
        } finally {
            connection.close()
        }
    }

    @Test
    fun `aFailedRowIsKeptRatherThanDeleted`() {
        // The durable record of a lost message. Deleting it would restore exactly the silence this table
        // exists to remove, so the migration's shape must not tempt anyone into treating FAILED as garbage.
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            createFrom(23, "remote_commands", connection)
            migrate(connection)
            insert(connection, "pdu-failed", PendingInboundSmsEntity.STATE_FAILED)

            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT COUNT(*) FROM `$table` WHERE state = '${PendingInboundSmsEntity.STATE_FAILED}'"
                ).use { rows ->
                    rows.next()
                    assertEquals(1, rows.getInt(1))
                }
            }
        } finally {
            connection.close()
        }
    }

    @Test
    fun `anUpgradingInstallHoldsNothingYet`() {
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            createFrom(23, "remote_commands", connection)
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
}
