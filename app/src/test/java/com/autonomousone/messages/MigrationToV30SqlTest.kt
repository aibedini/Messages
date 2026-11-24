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
 * Guards MIGRATION_29_30 — the durable per-conversation AI scope for linked devices
 * (LDV-004 / XR-GA-008) — the way the earlier migration tests guard their steps.
 *
 * Two properties matter, and they are different:
 *
 *  1. the migration produces EXACTLY the schema Room expects for v30 (otherwise Room refuses
 *     to open the database at runtime, on a user's phone, after the upgrade);
 *  2. it is purely ADDITIVE — every existing table and row survives untouched, and an
 *     upgrading install starts with ZERO scope rows (no device is silently granted access).
 *
 * Both are asserted against the exported `30.json` schema rather than against a hand-copied
 * DDL string, so a future edit to the entities cannot make this test agree with a stale
 * expectation.
 */
class MigrationToV30SqlTest {

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
        // Collect the names FIRST: reusing one Statement while its ResultSet is open replaces
        // the ResultSet on most JDBC drivers, and the loop would silently see only the first
        // index.
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

    private fun columnNames(connection: Connection, table: String): Set<String> =
        tableInfo(connection, table).map { it.substringBefore('|') }.toSet()

    private fun primaryKeyPositions(connection: Connection, table: String): Map<String, Int> =
        tableInfo(connection, table).mapNotNull { row ->
            val parts = row.split('|')
            val position = parts[4].toInt()
            if (position == 0) null else parts[0] to position
        }.toMap()

    @Test
    fun `this migration owns the current schema version boundary`() {
        assertEquals(29, MessagesDatabase.MIGRATION_29_30.startVersion)
        assertEquals(30, MessagesDatabase.MIGRATION_29_30.endVersion)
        assertEquals(30, MessagesDatabase.CURRENT_SCHEMA_VERSION)
        assertEquals(29, MessagesDatabase.PREVIOUS_SCHEMA_VERSION)
    }

    @Test
    fun `the migration creates exactly the v30 scope schema`() {
        val migrated = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            MessagesDatabase.UPGRADE_TO_V30_SQL.forEach { sql ->
                migrated.createStatement().use { it.execute(sql) }
            }

            val reference = DriverManager.getConnection("jdbc:sqlite::memory:")
            try {
                for (table in listOf(
                    "linked_device_scope_state",
                    "linked_device_conversation_scope"
                )) {
                    createFrom(30, table, reference)
                    assertEquals(
                        "MIGRATION_29_30 must produce exactly the schema Room generates for $table",
                        tableInfo(reference, table),
                        tableInfo(migrated, table)
                    )
                    assertEquals(
                        "the indices of $table must match Room's schema",
                        indexColumns(reference, table),
                        indexColumns(migrated, table)
                    )
                }
            } finally {
                reference.close()
            }
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `the tables have the contract columns and the device lookup index`() {
        val db = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            MessagesDatabase.UPGRADE_TO_V30_SQL.forEach { sql ->
                db.createStatement().use { it.execute(sql) }
            }

            assertEquals(
                setOf("deviceId", "mode", "scopeSequence", "updatedAt"),
                columnNames(db, "linked_device_scope_state")
            )
            assertEquals(
                setOf("deviceId", "conversationId", "enabled", "scopeSequence", "updatedAt"),
                columnNames(db, "linked_device_conversation_scope")
            )

            // The state row is keyed by device, and the conversation row by (device,
            // conversation) in that order: the first key column is what listEnabled filters.
            assertEquals(
                mapOf("deviceId" to 1),
                primaryKeyPositions(db, "linked_device_scope_state")
            )
            assertEquals(
                mapOf("deviceId" to 1, "conversationId" to 2),
                primaryKeyPositions(db, "linked_device_conversation_scope")
            )

            val indexes = indexColumns(db, "linked_device_conversation_scope")
            assertEquals(
                "listEnabled must be an index lookup, not a table scan",
                listOf("deviceId"),
                indexes["index_linked_device_conversation_scope_deviceId"]
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `the migration is additive, existing data survives and no scope is seeded`() {
        val db = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            // A v29 database with real content in the tables that matter for this feature:
            // the AI device's trust row and a v29 suggestion card.
            createFrom(29, "trusted_devices", db)
            createFrom(29, "suggestion_cards", db)
            db.createStatement().use {
                it.execute(
                    "INSERT INTO trusted_devices (" +
                        "deviceId, accountId, displayName, deviceType, origin, " +
                        "signingPublicKey, encryptionPublicKey, capabilitiesJson, historyGrant, " +
                        "certificateJson, certificateSignature, trustSequence, status, " +
                        "approvedAt, expiresAt, revokedAt, createdAt, updatedAt" +
                        ") VALUES (" +
                        "'dev-1', 'acct-1', 'AI agent', 'AI_AGENT', 'https://gmweb.example', " +
                        "'sign', 'enc', '[\"READ_MESSAGES\"]', 'FROM_NOW_ON', " +
                        "'cert', 'cert-sig', 7, 'ACTIVE', 100, 200, NULL, 50, 150)"
                )
                it.execute(
                    "INSERT INTO suggestion_cards (" +
                        "thread_id, for_message_id, payload_ciphertext, expires_at, received_at, " +
                        "generation) VALUES (42, 'msg-1', x'00', 500, 100, 7)"
                )
            }

            MessagesDatabase.UPGRADE_TO_V30_SQL.forEach { sql ->
                db.createStatement().use { it.execute(sql) }
            }

            db.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT deviceId, deviceType, status, trustSequence, encryptionPublicKey " +
                        "FROM trusted_devices WHERE deviceId = 'dev-1'"
                ).use { rows ->
                    assertTrue(rows.next())
                    assertEquals("dev-1", rows.getString("deviceId"))
                    assertEquals("AI_AGENT", rows.getString("deviceType"))
                    assertEquals("ACTIVE", rows.getString("status"))
                    assertEquals(7, rows.getInt("trustSequence"))
                    assertEquals("enc", rows.getString("encryptionPublicKey"))
                    assertTrue("exactly one device row should survive", !rows.next())
                }
                statement.executeQuery(
                    "SELECT payload_ciphertext FROM suggestion_cards WHERE thread_id = 42"
                ).use { rows ->
                    assertTrue(rows.next())
                    assertTrue(rows.getBytes("payload_ciphertext").contentEquals(byteArrayOf(0)))
                    assertTrue("exactly one card row should survive", !rows.next())
                }
            }

            // An upgrading install gets NO scope rows: absence of a state row means "not
            // configured", never "account-wide granted".
            for (table in listOf(
                "linked_device_scope_state",
                "linked_device_conversation_scope"
            )) {
                db.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM `$table`").use { rows ->
                        assertTrue(rows.next())
                        assertEquals("$table must start empty on upgrade", 0, rows.getInt(1))
                    }
                }
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `the v30 tables hold one state row per device and one row per conversation`() {
        val db = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            MessagesDatabase.UPGRADE_TO_V30_SQL.forEach { sql ->
                db.createStatement().use { it.execute(sql) }
            }
            db.createStatement().use { statement ->
                statement.execute(
                    "INSERT INTO linked_device_scope_state " +
                        "(deviceId, mode, scopeSequence, updatedAt) VALUES " +
                        "('dev-1', 'conversation', 9, 1000)"
                )
                statement.execute(
                    "INSERT INTO linked_device_conversation_scope " +
                        "(deviceId, conversationId, enabled, scopeSequence, updatedAt) VALUES " +
                        "('dev-1', 'c-1', 1, 9, 1000), " +
                        "('dev-1', 'c-2', 0, 9, 1000)"
                )
            }

            db.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT conversationId, enabled FROM linked_device_conversation_scope " +
                        "WHERE deviceId = 'dev-1' AND enabled = 1 ORDER BY conversationId"
                ).use { rows ->
                    assertTrue(rows.next())
                    assertEquals("c-1", rows.getString("conversationId"))
                    assertEquals(1, rows.getInt("enabled"))
                    assertTrue("only the enabled conversation should match", !rows.next())
                }
            }

            // The composite key is what makes setConversation idempotent per conversation.
            val duplicate = runCatching {
                db.createStatement().use {
                    it.execute(
                        "INSERT INTO linked_device_conversation_scope " +
                            "(deviceId, conversationId, enabled, scopeSequence, updatedAt) VALUES " +
                            "('dev-1', 'c-1', 1, 10, 1001)"
                    )
                }
            }
            assertTrue("a duplicate (deviceId, conversationId) must be rejected", duplicate.isFailure)
        } finally {
            db.close()
        }
    }
}
