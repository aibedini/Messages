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
 * Guards MIGRATION_24_25 (sticky per-conversation SIM + durable app-owned send state) the way
 * [MigrationToV20SqlTest] guards v20.
 *
 * The decisive assertion is [everyTouchedTableMigratesToExactlyTheSchemaRoomGeneratesForV25]: the real
 * v24 tables built from 24.json, the real migration SQL, and SQLite's own `PRAGMA table_info` compared
 * field-for-field against tables built from 25.json. A migration whose on-disk shape differs from the
 * entity set makes Room refuse to open the database — on every existing install, at startup, with the
 * user's read model already on disk.
 *
 * v25 carries TWO features because it is one release. Both halves are covered here, on all three
 * tables the migration touches, because a migration that is right on one table and wrong on another
 * fails exactly as hard.
 */
class MigrationToV25SqlTest {

    private val dbDir = "schemas/com.autonomousone.messages.data.MessagesDatabase"

    /** Every table the migration alters. */
    private val tables = listOf("conversation_preferences", "messages", "conversations")

    /** The full v24 column list of each table, for seeding realistic pre-upgrade rows. */
    private fun readSchema(version: Int): JSONObject {
        val file = File("$dbDir/$version.json")
        assertTrue("Schema $version.json not found — run :app:kspDebugKotlin", file.exists())
        return JSONObject(file.readText())
    }

    private fun entityOf(version: Int, table: String): JSONObject {
        val entities = readSchema(version).getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            if (entity.getString("tableName") == table) return entity
        }
        throw AssertionError("$table is not declared in $version.json")
    }

    private fun createFrom(version: Int, table: String, connection: Connection) {
        val entity = entityOf(version, table)
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
        @Suppress("UNCHECKED_CAST")
        val sql = MessagesDatabase::class.java
            .getDeclaredField("UPGRADE_TO_V25_SQL")
            .apply { isAccessible = true }
            .get(null) as List<String>
        sql.forEach { connection.createStatement().use { statement -> statement.execute(it) } }
    }

    /** A v24 database with all three touched tables present. */
    private fun freshV24(): Connection {
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        tables.forEach { createFrom(24, it, connection) }
        return connection
    }

    private fun v25Reference(table: String): Connection {
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        createFrom(25, table, connection)
        return connection
    }

    // ── shape ────────────────────────────────────────────────────────────────

    @Test
    fun `this migration declares its own 24 to 25 boundary`() {
        // The GLOBAL version constants are deliberately NOT asserted here: they move with every later
        // migration, and pinning them in a historical migration's test makes that test fail for a
        // reason that has nothing to do with v25. They belong to the NEWEST migration's test — the same
        // reasoning MigrationToV19SqlTest and MigrationToV24SqlTest already record.
        assertEquals(24, MessagesDatabase.MIGRATION_24_25.startVersion)
        assertEquals(25, MessagesDatabase.MIGRATION_24_25.endVersion)
    }

    @Test
    fun `everyTouchedTableMigratesToExactlyTheSchemaRoomGeneratesForV25`() {
        val db = freshV24()
        migrate(db)

        for (table in tables) {
            val expected = v25Reference(table)
            assertEquals(
                "$table shape diverged from the Room-generated v25 entity",
                tableInfo(expected, table),
                tableInfo(db, table)
            )
        }
    }

    @Test
    fun `the sticky sim columns are added`() {
        val db = freshV24()
        migrate(db)

        val names = tableInfo(db, "conversation_preferences").map { it.substringBefore('|') }

        for (column in listOf(
            "preferredSimRef",
            "preferredSimSlotIndex",
            "preferredSimDisplayName",
            "preferredSimCarrierName",
            "preferredSimUpdatedAt"
        )) {
            assertTrue("$column was not added", names.contains(column))
        }
    }

    @Test
    fun `the durable send state columns are added`() {
        val db = freshV24()
        migrate(db)

        val names = tableInfo(db, "messages").map { it.substringBefore('|') }

        for (column in listOf(
            "sendTransportState",
            "sendFailureCode",
            "sendResultCode",
            "sendRadioErrorCode",
            "sendStateUpdatedAt"
        )) {
            assertTrue("$column was not added", names.contains(column))
        }
    }

    @Test
    fun `the conversation projection gains its denormalized ui state`() {
        val db = freshV24()
        migrate(db)

        val names = tableInfo(db, "conversations").map { it.substringBefore('|') }

        assertTrue(names.contains("lastMessageUiState"))
    }

    @Test
    fun `the authority columns are nullable so absence is representable`() {
        val db = freshV24()
        migrate(db)

        // A NOT NULL authority would force "no preference" / "no app state" to be encoded as a
        // sentinel string, which is exactly how an empty or fake value later compares equal to real.
        for ((table, column) in listOf(
            "conversation_preferences" to "preferredSimRef",
            "messages" to "sendTransportState",
            "messages" to "sendFailureCode",
            "conversations" to "lastMessageUiState"
        )) {
            val row = tableInfo(db, table).first { it.startsWith("$column|") }
            assertFalse("$table.$column must be nullable", row.split('|')[2] == "1")
        }
    }

    @Test
    fun `the not null timestamp columns carry a zero default`() {
        val db = freshV24()
        migrate(db)

        for ((table, column) in listOf(
            "conversation_preferences" to "preferredSimUpdatedAt",
            "messages" to "sendStateUpdatedAt"
        )) {
            // Format is name|type|notnull|default|pk, so the default is the fourth field.
            val parts = tableInfo(db, table).first { it.startsWith("$column|") }.split('|')
            assertEquals("$table.$column must be NOT NULL", "1", parts[2])
            assertEquals("$table.$column needs a default on a populated table", "0", parts[3])
        }
    }

    @Test
    fun `the migration only adds columns and never drops rewrites or renames`() {
        // A sticky SIM is user intent that cannot be recovered by re-sending a message, and a send
        // verdict is the evidence behind the bubble. Neither may be lost to a table rebuild.
        val touched = mutableSetOf<String>()
        MessagesDatabase.UPGRADE_TO_V25_SQL.forEach { statement ->
            val compact = statement.filterNot { it.isWhitespace() }.uppercase()
            assertTrue("v25 must only ALTER TABLE: $statement", compact.startsWith("ALTERTABLE"))
            assertTrue("v25 must only add columns: $statement", compact.contains("ADDCOLUMN"))
            listOf("DROP", "DELETE", "RENAME").forEach { forbidden ->
                assertTrue("v25 must never $forbidden: $statement", !compact.contains(forbidden))
            }
            // `UPDATE` is checked as a whole word: `DEFAULT` contains the letters, so a substring test
            // would fail on the very clause that makes the NOT NULL column addable.
            assertTrue(
                "v25 must never rewrite existing rows: $statement",
                !Regex("\\bUPDATE\\b").containsMatchIn(statement.uppercase())
            )
            // The table name is taken from the ORIGINAL statement: `compact` is uppercased, and table
            // names are case-sensitive in the comparison below.
            touched += Regex("ALTER TABLE `([^`]+)`").find(statement)!!.groupValues[1]        }
        assertEquals("every touched table must be asserted above", tables.toSet(), touched)
    }

    // ── data contract ────────────────────────────────────────────────────────

    @Test
    fun `an existing conversation migrates to no preference, never an inferred one`() {
        val db = freshV24()
        db.createStatement().use {
            it.execute(
                "INSERT INTO `conversation_preferences` (threadId, manualUnread, mutedUntil, " +
                    "customNotificationChannel, categoryOverride, spam, spamReportedAt, " +
                    "spamBlockedByReport, updatedAt) VALUES (100, 0, 0, 0, NULL, 0, 0, 0, 55)"
            )
        }
        migrate(db)

        db.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT preferredSimRef, preferredSimSlotIndex, preferredSimDisplayName, " +
                    "preferredSimCarrierName, preferredSimUpdatedAt FROM `conversation_preferences` " +
                    "WHERE threadId = 100"
            ).use { rows ->
                assertTrue(rows.next())
                assertNull("a guessed preference would pin the thread to a line nobody chose",
                    rows.getString("preferredSimRef"))
                assertNull(rows.getString("preferredSimSlotIndex"))
                assertNull(rows.getString("preferredSimDisplayName"))
                assertNull(rows.getString("preferredSimCarrierName"))
                assertEquals(0L, rows.getLong("preferredSimUpdatedAt"))
            }
        }
    }

    @Test
    fun `pre-existing user state on the same row survives the migration`() {
        val db = freshV24()
        db.createStatement().use {
            it.execute(
                "INSERT INTO `conversation_preferences` (threadId, manualUnread, mutedUntil, " +
                    "customNotificationChannel, categoryOverride, spam, spamReportedAt, " +
                    "spamBlockedByReport, updatedAt) VALUES (7, 1, 4102444800000, 1, 'OTP', 1, 123, 1, 99)"
            )
        }
        migrate(db)

        db.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT manualUnread, mutedUntil, customNotificationChannel, categoryOverride, " +
                    "spam, spamReportedAt, spamBlockedByReport, updatedAt FROM " +
                    "`conversation_preferences` WHERE threadId = 7"
            ).use { rows ->
                assertTrue(rows.next())
                assertEquals(1, rows.getInt("manualUnread"))
                assertEquals(4102444800000L, rows.getLong("mutedUntil"))
                assertEquals(1, rows.getInt("customNotificationChannel"))
                assertEquals("OTP", rows.getString("categoryOverride"))
                assertEquals(1, rows.getInt("spam"))
                assertEquals(123L, rows.getLong("spamReportedAt"))
                assertEquals(1, rows.getInt("spamBlockedByReport"))
                assertEquals(99L, rows.getLong("updatedAt"))
            }
        }
    }

    @Test
    fun `an existing message migrates to no app-owned transport state`() {
        val db = freshV24()
        db.createStatement().use {
            it.execute(
                "INSERT INTO `messages` (source, providerId, threadId, normalizedAddress, rawAddress, " +
                    "body, date, type, status, dateSent, read, syncState) " +
                    "VALUES ('sms', 42, 100, '+989120000000', '+98 912 000 0000', 'hello', 1000, 2, 32, 0, 1, 'synced')"
            )
        }
        migrate(db)

        db.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT sendTransportState, sendFailureCode, sendResultCode, sendRadioErrorCode, " +
                    "sendStateUpdatedAt, status, body FROM `messages` WHERE providerId = 42"
            ).use { rows ->
                assertTrue(rows.next())
                // NULL, not a guessed verdict: an invented "Sent" is the false-success bug this
                // column exists to end, and a historical row genuinely has no app-owned evidence.
                assertNull(rows.getString("sendTransportState"))
                assertNull(rows.getString("sendFailureCode"))
                assertNull(rows.getString("sendResultCode"))
                assertNull(rows.getString("sendRadioErrorCode"))
                assertEquals(0L, rows.getLong("sendStateUpdatedAt"))
                // The provider's own delivery field is untouched — transport and delivery stay apart.
                assertEquals(32, rows.getInt("status"))
                assertEquals("hello", rows.getString("body"))
            }
        }
    }

    @Test
    fun `an existing conversation projection keeps its snippet and gains no ui state`() {
        val db = freshV24()
        db.createStatement().use {
            it.execute(
                "INSERT INTO `conversations` (threadId, normalizedAddress, rawAddress, snippet, " +
                    "lastMessageDate, unreadCount, lastMessageType, pinned, archived) " +
                    "VALUES (100, '+989120000000', '+98 912 000 0000', 'hi', 5000, 2, 1, 1, 0)"
            )
        }
        migrate(db)

        db.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT snippet, lastMessageDate, unreadCount, pinned, archived, lastMessageUiState " +
                    "FROM `conversations` WHERE threadId = 100"
            ).use { rows ->
                assertTrue(rows.next())
                assertEquals("hi", rows.getString("snippet"))
                assertEquals(5000L, rows.getLong("lastMessageDate"))
                assertEquals(2, rows.getInt("unreadCount"))
                // Conversation-owned user state must never be collateral damage of an upgrade.
                assertEquals(1, rows.getInt("pinned"))
                assertEquals(0, rows.getInt("archived"))
                assertNull(rows.getString("lastMessageUiState"))
            }
        }
    }

    @Test
    fun `the migration loses no row on any touched table`() {
        val db = freshV24()
        db.createStatement().use { statement ->
            for (threadId in 1L..25L) {
                statement.execute(
                    "INSERT INTO `conversation_preferences` (threadId, manualUnread, mutedUntil, " +
                        "customNotificationChannel, categoryOverride, spam, spamReportedAt, " +
                        "spamBlockedByReport, updatedAt) VALUES ($threadId, 0, 0, 0, NULL, 0, 0, 0, 0)"
                )
                statement.execute(
                    "INSERT INTO `conversations` (threadId, normalizedAddress, rawAddress, snippet, " +
                        "lastMessageDate, unreadCount, lastMessageType, pinned, archived) " +
                        "VALUES ($threadId, 'a', 'a', 's', 1, 0, 1, 0, 0)"
                )
                statement.execute(
                    "INSERT INTO `messages` (source, providerId, threadId, normalizedAddress, " +
                        "rawAddress, body, date, type, status, dateSent, read, syncState) " +
                        "VALUES ('sms', $threadId, $threadId, 'a', 'a', 'b', 1, 2, 32, 0, 1, 'synced')"
                )
            }
        }
        migrate(db)

        for (table in tables) {
            db.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM `$table`").use { rows ->
                    rows.next()
                    assertEquals("rows lost or duplicated in $table", 25, rows.getInt(1))
                }
            }
        }
    }

    @Test
    fun `the migration is idempotent-safe when re-run after a partial failure`() {
        // Room re-runs a migration after a partially-failed open, so an ALTER that already applied
        // must fail loudly rather than silently corrupt: SQLite raises "duplicate column name", which
        // is the correct outcome for an additive ALTER. This asserts the SQL is not doing something
        // surprising under a second pass.
        val db = freshV24()
        migrate(db)

        val second = runCatching { migrate(db) }

        assertTrue("a re-run must fail rather than half-apply", second.isFailure)
    }
}
