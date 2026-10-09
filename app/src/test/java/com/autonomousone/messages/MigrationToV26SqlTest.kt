package com.autonomousone.messages

import com.autonomousone.messages.data.MessagesDatabase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Guards MIGRATION_25_26 (durable delivery evidence).
 *
 * ## Why this migration exists at all rather than an edit to v25
 *
 * v25 was RELEASED (tag `v3.5.0`, APK published, release workflow green). Editing a shipped migration
 * would let two different on-disk shapes both claim version 25, and an install that had already run the
 * old one could never be repaired. This test therefore asserts that v25 is untouched and that v26 is
 * purely additive on top of it.
 *
 * The decisive assertion is the schema comparison: a real v25 `messages` table built from 25.json, the
 * real v26 migration SQL, and SQLite's own `PRAGMA table_info` compared field-for-field against a table
 * built from 26.json. A shape mismatch makes Room refuse to open the database — at startup, on every
 * existing install, with the user's message history already on disk.
 */
class MigrationToV26SqlTest {

    private val dbDir = "schemas/com.autonomousone.messages.data.MessagesDatabase"
    private val table = "messages"

    /** The delivery columns v26 adds. */
    private val addedColumns = listOf(
        "deliveryCallbackAt",
        "deliveryTpStatus",
        "deliveryEvidence",
        "deliveryResultCode"
    )

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
            .getDeclaredField("UPGRADE_TO_V26_SQL")
            .apply { isAccessible = true }
            .get(null) as List<String>
        sql.forEach { connection.createStatement().use { statement -> statement.execute(it) } }
    }

    private fun freshV25(): Connection {
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        createFrom(25, connection)
        return connection
    }

    private fun insertV25Message(connection: Connection, providerId: Long) {
        connection.createStatement().use {
            it.execute(
                "INSERT INTO `messages` (source, providerId, threadId, normalizedAddress, rawAddress, " +
                    "body, date, type, status, dateSent, read, syncState, sendTransportState, " +
                    "sendFailureCode, sendResultCode, sendRadioErrorCode, sendStateUpdatedAt) " +
                    "VALUES ('sms', $providerId, 100, 'a', 'a', 'hello', 1, 2, 32, 0, 1, 'synced', " +
                    "'SENT_AMBIGUOUS', 'CARRIER_FAILURE_UNKNOWN', 1, null, 1234)"
            )
        }
    }

    // ── boundary ─────────────────────────────────────────────────────────────

    @Test
    fun `this migration owns the current schema version boundary`() {
        assertEquals(25, MessagesDatabase.MIGRATION_25_26.startVersion)
        assertEquals(26, MessagesDatabase.MIGRATION_25_26.endVersion)
        assertEquals(26, MessagesDatabase.CURRENT_SCHEMA_VERSION)
        assertEquals(25, MessagesDatabase.PREVIOUS_SCHEMA_VERSION)
    }

    @Test
    fun `the released 24 to 25 migration is untouched by this change`() {
        // v25 shipped in tag v3.5.0. Its SQL must still describe exactly the v25 shape, or an install
        // that already migrated would hold a database no build can open.
        assertEquals(24, MessagesDatabase.MIGRATION_24_25.startVersion)
        assertEquals(25, MessagesDatabase.MIGRATION_24_25.endVersion)
        for (statement in MessagesDatabase.UPGRADE_TO_V25_SQL) {
            assertTrue(
                "v25 must still only add columns on conversation_preferences/messages/conversations",
                statement.startsWith("ALTER TABLE")
            )
            assertFalse(
                "v25 must not have gained a delivery column: $statement",
                addedColumns.any { statement.contains(it) }
            )
        }
    }

    // ── shape ────────────────────────────────────────────────────────────────

    @Test
    fun `theMigrationProducesExactlyTheSchemaRoomGeneratesForV26`() {
        val db = freshV25()
        insertV25Message(db, 1L)
        migrate(db)

        val expected = DriverManager.getConnection("jdbc:sqlite::memory:")
        createFrom(26, expected)

        assertEquals(tableInfo(expected), tableInfo(db))
    }

    @Test
    fun `the four delivery columns are added`() {
        val db = freshV25()
        migrate(db)

        val names = tableInfo(db).map { it.substringBefore('|') }
        for (column in addedColumns) {
            assertTrue("$column was not added", names.contains(column))
        }
    }

    @Test
    fun `the delivery timestamp is not null with a zero default`() {
        val db = freshV25()
        migrate(db)

        val parts = tableInfo(db).first { it.startsWith("deliveryCallbackAt|") }.split('|')
        assertEquals("INTEGER", parts[1])
        assertEquals("NOT NULL is required: the entity declares a non-null Long", "1", parts[2])
        assertEquals("a NOT NULL column added to a populated table needs a default", "0", parts[3])
    }

    @Test
    fun `every other delivery column is nullable so absence is representable`() {
        val db = freshV25()
        migrate(db)

        for (column in listOf("deliveryTpStatus", "deliveryEvidence", "deliveryResultCode")) {
            val parts = tableInfo(db).first { it.startsWith("$column|") }.split('|')
            assertFalse(
                "$column must be nullable: null means 'no report arrived', which is not a value",
                parts[2] == "1"
            )
        }
    }

    @Test
    fun `the migration only adds columns and never drops rewrites or renames`() {
        MessagesDatabase.UPGRADE_TO_V26_SQL.forEach { statement ->
            val compact = statement.filterNot { it.isWhitespace() }.uppercase()
            assertTrue("v26 must only ALTER TABLE: $statement", compact.startsWith("ALTERTABLE"))
            assertTrue("v26 must only add columns: $statement", compact.contains("ADDCOLUMN"))
            listOf("DROP", "DELETE", "RENAME").forEach { forbidden ->
                assertTrue("v26 must never $forbidden: $statement", !compact.contains(forbidden))
            }
            assertTrue(
                "v26 must never rewrite existing rows: $statement",
                !Regex("\\bUPDATE\\b").containsMatchIn(statement.uppercase())
            )
        }
    }

    // ── data contract ────────────────────────────────────────────────────────

    @Test
    fun `an existing message migrates to no delivery evidence, never a fabricated one`() {
        val db = freshV25()
        insertV25Message(db, 42L)
        migrate(db)

        db.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT deliveryCallbackAt, deliveryTpStatus, deliveryEvidence, deliveryResultCode, " +
                    "sendTransportState, sendFailureCode, body FROM `messages` WHERE providerId = 42"
            ).use { rows ->
                assertTrue(rows.next())
                assertEquals(0L, rows.getLong("deliveryCallbackAt"))
                // NULL, not a guessed verdict: an invented "DELIVERED" is the false-delivery bug this
                // column exists to prevent, and an invented FAILED would wrongly blame the carrier.
                assertNull(rows.getString("deliveryTpStatus"))
                assertNull(rows.getString("deliveryEvidence"))
                assertNull(rows.getString("deliveryResultCode"))
                // And the pre-existing transport evidence is completely untouched.
                assertEquals("SENT_AMBIGUOUS", rows.getString("sendTransportState"))
                assertEquals("CARRIER_FAILURE_UNKNOWN", rows.getString("sendFailureCode"))
                assertEquals("hello", rows.getString("body"))
            }
        }
    }

    @Test
    fun `the migration loses no row`() {
        val db = freshV25()
        db.createStatement().use { statement ->
            for (id in 1L..25L) {
                statement.execute(
                    "INSERT INTO `messages` (source, providerId, threadId, normalizedAddress, " +
                        "rawAddress, body, date, type, status, dateSent, read, syncState, " +
                        "sendStateUpdatedAt) VALUES ('sms', $id, 100, 'a', 'a', 'b', 1, 2, 32, 0, 1, " +
                        "'synced', 0)"
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
        val db = freshV25()
        migrate(db)

        val second = runCatching { migrate(db) }

        assertTrue("a re-run must fail rather than half-apply", second.isFailure)
    }
}
