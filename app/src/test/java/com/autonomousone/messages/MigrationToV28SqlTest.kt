package com.autonomousone.messages

import com.autonomousone.messages.data.MessagesDatabase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Guards MIGRATION_27_28 (read-state provenance) the way MigrationToV25SqlTest guards v25.
 *
 * The migration is additive: an existing row must keep every column and gain
 * `readStateChangedAt=0` / `readStateCause=null`, which means "provenance unknown" — never an
 * invented cause.
 */
class MigrationToV28SqlTest {

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

    @Test
    fun `this migration owns the current schema version boundary`() {
        assertEquals(27, MessagesDatabase.MIGRATION_27_28.startVersion)
        assertEquals(28, MessagesDatabase.MIGRATION_27_28.endVersion)
        assertEquals(28, MessagesDatabase.CURRENT_SCHEMA_VERSION)
        assertEquals(27, MessagesDatabase.PREVIOUS_SCHEMA_VERSION)
    }

    @Test
    fun `v27 messages migrates to exactly the v28 schema`() {
        val migrated = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            createFrom(27, "messages", migrated)
            MessagesDatabase.UPGRADE_TO_V28_SQL.forEach { sql ->
                migrated.createStatement().use { it.execute(sql) }
            }

            val reference = DriverManager.getConnection("jdbc:sqlite::memory:")
            try {
                createFrom(28, "messages", reference)
                assertEquals(tableInfo(reference, "messages"), tableInfo(migrated, "messages"))
            } finally {
                reference.close()
            }
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `existing rows keep their data and gain unknown provenance`() {
        val db = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            createFrom(27, "messages", db)
            db.createStatement().use {
                it.execute(
                    "INSERT INTO messages (" +
                        "source, providerId, threadId, normalizedAddress, rawAddress, body, date, " +
                        "type, status, dateSent, read, syncState" +
                        ") VALUES ('sms', 7, 42, '+98912', '+98912', 'body', 1000, 2, 0, 0, 1, 'synced')"
                )
            }
            MessagesDatabase.UPGRADE_TO_V28_SQL.forEach { sql ->
                db.createStatement().use { it.execute(sql) }
            }

            db.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT body, read, readStateChangedAt, readStateCause FROM messages " +
                        "WHERE source = 'sms' AND providerId = 7"
                ).use { rows ->
                    assertTrue(rows.next())
                    assertEquals("body", rows.getString("body"))
                    assertEquals(1, rows.getInt("read"))
                    assertEquals(0L, rows.getLong("readStateChangedAt"))
                    assertNull(rows.getString("readStateCause"))
                }
            }
        } finally {
            db.close()
        }
    }
}
