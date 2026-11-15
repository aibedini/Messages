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
 * Guards MIGRATION_28_29 — `suggestion_cards` (XR-GA-017) — the way the earlier migration tests guard
 * their steps.
 *
 * Two properties matter, and they are different:
 *
 *  1. the migration produces EXACTLY the schema Room expects for v29 (otherwise Room refuses to open
 *     the database at runtime, on a user's phone, after the upgrade);
 *  2. it is purely ADDITIVE — every existing table and row survives untouched.
 *
 * Both are asserted against the exported `29.json` schema rather than against a hand-copied DDL string,
 * so a future edit to the entity cannot make this test agree with a stale expectation.
 */
class MigrationToV29SqlTest {

    private val schemaDirs = listOf(
        "app/schemas/com.autonomousone.messages.data.MessagesDatabase",
        "schemas/com.autonomousone.messages.data.MessagesDatabase"
    )

    private fun schemaDir(): File =
        schemaDirs.map(::File).firstOrNull { it.isDirectory }
            ?: error("schema directory not found; looked in $schemaDirs")

    private fun entityOf(version: Int, table: String): JSONObject {
        val file = File(schemaDir(), "$version.json")
        assertTrue("$version.json not found — run :app:kspDebugKotlin", file.isFile)
        val entities = JSONObject(file.readText())
            .getJSONObject("database").getJSONArray("entities")
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

    private fun indexColumns(connection: Connection, table: String): Map<String, List<String>> {
        // Collect the names FIRST: reusing one Statement while its ResultSet is open replaces the
        // ResultSet on most JDBC drivers, and the loop would silently see only the first index.
        val names = buildList {
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA index_list(`$table`)").use { rows ->
                    while (rows.next()) add(rows.getString("name"))
                }
            }
        }
        return names.associateWith { name ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA index_info(`$name`)").use { info ->
                    buildList {
                        while (info.next()) add(info.getString("name"))
                    }
                }
            }
        }
    }

    @Test
    fun `this migration owns the current schema version boundary`() {
        assertEquals(28, MessagesDatabase.MIGRATION_28_29.startVersion)
        assertEquals(29, MessagesDatabase.MIGRATION_28_29.endVersion)
        assertEquals(29, MessagesDatabase.CURRENT_SCHEMA_VERSION)
        assertEquals(28, MessagesDatabase.PREVIOUS_SCHEMA_VERSION)
    }

    @Test
    fun `the migration creates exactly the v29 suggestion_cards schema`() {
        val migrated = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            MessagesDatabase.UPGRADE_TO_V29_SQL.forEach { sql ->
                migrated.createStatement().use { it.execute(sql) }
            }

            val reference = DriverManager.getConnection("jdbc:sqlite::memory:")
            try {
                createFrom(29, "suggestion_cards", reference)
                assertEquals(
                    tableInfo(reference, "suggestion_cards"),
                    tableInfo(migrated, "suggestion_cards")
                )
                assertEquals(
                    indexColumns(reference, "suggestion_cards"),
                    indexColumns(migrated, "suggestion_cards")
                )
            } finally {
                reference.close()
            }
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `the card table has the contract columns and the thread index`() {
        val db = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            MessagesDatabase.UPGRADE_TO_V29_SQL.forEach { sql ->
                db.createStatement().use { it.execute(sql) }
            }

            val columns = tableInfo(db, "suggestion_cards")
                .map { it.substringBefore('|') }
                .toSet()
            assertEquals(
                setOf(
                    "thread_id",
                    "for_message_id",
                    "payload_ciphertext",
                    "expires_at",
                    "received_at",
                    "generation"
                ),
                columns
            )

            val indexes = indexColumns(db, "suggestion_cards")
            assertEquals(
                "the conversation read is an index lookup, not a table scan",
                listOf("thread_id"),
                indexes["index_suggestion_cards_thread_id"]
            )
            assertEquals(
                "the expiry sweep must not scan the table",
                listOf("expires_at"),
                indexes["index_suggestion_cards_expires_at"]
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `the migration is additive and every existing message survives`() {
        val db = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            // A v28 database with real content in the table that matters most.
            createFrom(28, "messages", db)
            db.createStatement().use {
                it.execute(
                    "INSERT INTO messages (" +
                        "source, providerId, threadId, normalizedAddress, rawAddress, body, date, " +
                        "type, status, dateSent, read, syncState" +
                        ") VALUES ('sms', 7, 42, '+98912', '+98912', 'body', 1000, 1, -1, 0, 1, 'synced')"
                )
            }

            MessagesDatabase.UPGRADE_TO_V29_SQL.forEach { sql ->
                db.createStatement().use { it.execute(sql) }
            }

            db.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT body, read, threadId FROM messages WHERE source = 'sms' AND providerId = 7"
                ).use { rows ->
                    assertTrue(rows.next())
                    assertEquals("body", rows.getString("body"))
                    assertEquals(1, rows.getInt("read"))
                    assertEquals(42L, rows.getLong("threadId"))
                }
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `an expired card is removable by the cleanup predicate the dao uses`() {
        val db = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            MessagesDatabase.UPGRADE_TO_V29_SQL.forEach { sql ->
                db.createStatement().use { it.execute(sql) }
            }
            db.createStatement().use { statement ->
                statement.execute(
                    "INSERT INTO suggestion_cards (thread_id, for_message_id, payload_ciphertext, " +
                        "expires_at, received_at, generation) VALUES " +
                        "(1, 'a', x'00', 500, 100, 7), " +
                        "(2, 'b', x'00', 5_000, 100, 7)"
                )
            }

            // Mirrors SuggestionCardDao.deleteExpired: expired rows only, and never a row whose
            // expiry is 0 ("unset", which the executor refuses to write but older data could hold).
            db.createStatement().use {
                it.execute("DELETE FROM suggestion_cards WHERE expires_at > 0 AND expires_at <= 1000")
            }

            db.createStatement().use { statement ->
                statement.executeQuery("SELECT thread_id FROM suggestion_cards ORDER BY thread_id")
                    .use { rows ->
                        assertTrue(rows.next())
                        assertEquals(2L, rows.getLong("thread_id"))
                        assertTrue("exactly one row should survive", !rows.next())
                    }
            }
        } finally {
            db.close()
        }
    }
}
