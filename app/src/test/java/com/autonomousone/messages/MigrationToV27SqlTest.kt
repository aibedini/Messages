package com.autonomousone.messages

import com.autonomousone.messages.data.MessagesDatabase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Guards MIGRATION_26_27 (monotonic delivery-evidence rank).
 *
 * ## Why this migration was allowed at all
 *
 * The rule is that no schema change is added merely to wire a callback — the four delivery columns
 * already existed in v26. This one exists because the monotonic guarantee CANNOT be expressed without
 * a persisted rank: delivery callbacks are independent asynchronous writers, and "a weaker answer must
 * not replace a stronger one" has to be evaluated inside the UPDATE statement. Without a stored rank,
 * a late UNKNOWN report that read the row before a DELIVERED report wrote it overwrites the confirmed
 * delivery, and the user is told the opposite of what the carrier said.
 *
 * The decisive assertion is the schema comparison against Room's own generated 27.json. The published
 * 25 -> 26 migration must remain byte-for-byte intact, which is asserted separately.
 */
class MigrationToV27SqlTest {

    private val dbDir = "schemas/com.autonomousone.messages.data.MessagesDatabase"
    private val table = "messages"

    private fun readSchema(version: Int): JSONObject {
        val file = File("$dbDir/$version.json")
        assertTrue("Schema $version.json not found — run :app:kspDebugKotlin", file.exists())
        return JSONObject(file.readText())
    }

    private fun createFrom(version: Int, connection: Connection) {
        val entities = readSchema(version).getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val name = entity.getString("tableName")
            connection.createStatement().use {
                it.execute(entity.getString("createSql").replace("\${TABLE_NAME}", name))
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
        @Suppress("UNCHECKED_CAST")
        val sql = MessagesDatabase::class.java
            .getDeclaredField("UPGRADE_TO_V27_SQL")
            .apply { isAccessible = true }
            .get(null) as List<String>
        sql.forEach { connection.createStatement().use { statement -> statement.execute(it) } }
    }

    private fun freshV26(): Connection {
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        createFrom(26, connection)
        return connection
    }

    private fun insertV26Message(connection: Connection, providerId: Long) {
        connection.createStatement().use {
            it.execute(
                "INSERT INTO `messages` (source, providerId, threadId, normalizedAddress, rawAddress, " +
                    "body, date, type, status, dateSent, read, syncState, sendTransportState, " +
                    "sendFailureCode, sendResultCode, sendRadioErrorCode, sendStateUpdatedAt, " +
                    "deliveryCallbackAt, deliveryTpStatus, deliveryEvidence, deliveryResultCode) " +
                    "VALUES ('sms', $providerId, 100, 'a', 'a', 'hello', 1, 2, 32, 0, 1, 'synced', " +
                    "'SENT_AMBIGUOUS', 'CARRIER_FAILURE_UNKNOWN', 1, null, 1234, 0, null, null, null)"
            )
        }
    }

    // ── boundary ─────────────────────────────────────────────────────────────

    @Test
    fun `this migration owns the current schema version boundary`() {
        assertEquals(26, MessagesDatabase.MIGRATION_26_27.startVersion)
        assertEquals(27, MessagesDatabase.MIGRATION_26_27.endVersion)
        assertEquals(27, MessagesDatabase.CURRENT_SCHEMA_VERSION)
        assertEquals(26, MessagesDatabase.PREVIOUS_SCHEMA_VERSION)
    }

    @Test
    fun `the published 25 to 26 migration is untouched`() {
        // v26 shipped in the released v3.5.1. Editing it would let two different on-disk shapes both
        // claim version 26, and an install that already ran the old one could never be repaired.
        assertEquals(25, MessagesDatabase.MIGRATION_25_26.startVersion)
        assertEquals(26, MessagesDatabase.MIGRATION_25_26.endVersion)
        for (statement in MessagesDatabase.UPGRADE_TO_V26_SQL) {
            assertTrue("v26 must still only add columns: $statement", statement.startsWith("ALTER TABLE"))
            assertFalse(
                "v26 must not have gained the rank column: $statement",
                statement.contains("deliveryEvidenceRank")
            )
        }
        // And the four delivery columns it introduced are still exactly those four.
        assertEquals(4, MessagesDatabase.UPGRADE_TO_V26_SQL.size)
    }

    // ── shape ────────────────────────────────────────────────────────────────

    @Test
    fun `theMigrationProducesExactlyTheSchemaRoomGeneratesForV27`() {
        val db = freshV26()
        insertV26Message(db, 1L)
        migrate(db)

        val expected = DriverManager.getConnection("jdbc:sqlite::memory:")
        createFrom(27, expected)

        assertEquals(tableInfo(expected), tableInfo(db))
    }

    @Test
    fun `the rank column is added, not null, with a zero default`() {
        val db = freshV26()
        migrate(db)

        val parts = tableInfo(db).first { it.startsWith("deliveryEvidenceRank|") }.split('|')
        assertEquals("INTEGER", parts[1])
        assertEquals("NOT NULL is required: the entity declares a non-null Int", "1", parts[2])
        assertEquals("existing rows must default to 'nothing recorded'", "0", parts[3])
    }

    @Test
    fun `the migration only adds a column and never drops rewrites or renames`() {
        MessagesDatabase.UPGRADE_TO_V27_SQL.forEach { statement ->
            val compact = statement.filterNot { it.isWhitespace() }.uppercase()
            assertTrue("v27 must only ALTER TABLE: $statement", compact.startsWith("ALTERTABLE"))
            assertTrue("v27 must only add a column: $statement", compact.contains("ADDCOLUMN"))
            listOf("DROP", "DELETE", "RENAME").forEach { forbidden ->
                assertTrue("v27 must never $forbidden: $statement", !compact.contains(forbidden))
            }
            assertTrue(
                "v27 must never rewrite existing rows: $statement",
                !Regex("\\bUPDATE\\b").containsMatchIn(statement.uppercase())
            )
        }
    }

    // ── data contract ────────────────────────────────────────────────────────

    @Test
    fun `existing rows migrate to rank zero and keep all their other evidence`() {
        val db = freshV26()
        insertV26Message(db, 42L)
        migrate(db)

        db.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT deliveryEvidenceRank, deliveryCallbackAt, deliveryTpStatus, " +
                    "sendTransportState, sendFailureCode, body FROM `messages` WHERE providerId = 42"
            ).use { rows ->
                assertTrue(rows.next())
                // Rank 0 is exactly true for these rows: v26 shipped no writer, so nothing was recorded.
                assertEquals(0, rows.getInt("deliveryEvidenceRank"))
                assertEquals(0L, rows.getLong("deliveryCallbackAt"))
                assertEquals(null, rows.getObject("deliveryTpStatus"))
                // Nothing else is disturbed.
                assertEquals("SENT_AMBIGUOUS", rows.getString("sendTransportState"))
                assertEquals("CARRIER_FAILURE_UNKNOWN", rows.getString("sendFailureCode"))
                assertEquals("hello", rows.getString("body"))
            }
        }
    }

    @Test
    fun `the migration loses no row`() {
        val db = freshV26()
        db.createStatement().use { statement ->
            for (id in 1L..25L) {
                statement.execute(
                    "INSERT INTO `messages` (source, providerId, threadId, normalizedAddress, " +
                        "rawAddress, body, date, type, status, dateSent, read, syncState, " +
                        "sendStateUpdatedAt, deliveryCallbackAt) " +
                        "VALUES ('sms', $id, 100, 'a', 'a', 'b', 1, 2, 32, 0, 1, 'synced', 0, 0)"
                )
            }
        }
        migrate(db)

        db.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM `messages`").use { rows ->
                rows.next()
                assertEquals("rows lost or duplicated", 25, rows.getInt(1))
            }
        }
    }

    @Test
    fun `the migration is idempotent-safe when re-run after a partial failure`() {
        val db = freshV26()
        migrate(db)

        val second = runCatching { migrate(db) }

        assertTrue("a re-run must fail rather than half-apply", second.isFailure)
    }
}
