package com.autonomousone.messages

import com.autonomousone.messages.data.MessagesDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Guards MIGRATION_6_7 (PR-01 gateway durability tables) the way
 * MigrationToV6SqlTest guards v6: against the schema Room actually generates
 * (app/schemas/.../7.json from KSP). Room validates migration results at
 * runtime verbatim, so the pin below compares NORMALIZED shapes:
 *
 *  - tables  → the column-list tail after "CREATE TABLE [IF NOT EXISTS] `name`"
 *  - indexes → "UNIQUE|name|(cols)" with the table name abstracted away
 *              (the JSON uses the `${TABLE_NAME}` placeholder, the migration
 *              uses the real name — both must normalize to the same key).
 */
class GatewaySyncSchemaTest {

    private fun compact(sql: String): String = sql.filterNot { it.isWhitespace() }

    private fun schemaJson(): String {
        val file = File("schemas/com.autonomousone.messages.data.MessagesDatabase/7.json")
        if (!file.exists()) {
            fail("Schema 7.json not found at ${file.absolutePath} — run :app:kspDebugKotlin")
        }
        return file.readText()
    }

    @Test
    fun `migration declares the 6 to 7 boundary`() {
        assertEquals(6, MessagesDatabase.MIGRATION_6_7.startVersion)
        assertEquals(7, MessagesDatabase.MIGRATION_6_7.endVersion)
    }

    @Test
    fun `v7 is additive only - pure CREATE statements, no drops renames or alters`() {
        val statements = MessagesDatabase.UPGRADE_TO_V7_SQL.map(::compact)
        assertTrue(
            "v7 must be additive: CREATE TABLE/INDEX only",
            statements.all {
                it.startsWith("CREATETABLE") || it.startsWith("CREATEINDEX") || it.startsWith("CREATEUNIQUEINDEX")
            }
        )
        assertEquals("5 tables + 7 indexes expected", 12, statements.size)
    }

    /** JSON side: capture every entity's column tail (after the placeholder name). */
    private fun jsonTableTails(json: String): Set<String> =
        Regex("CREATETABLEIFNOTEXISTS`[^`]+`([^\"]+)")
            .findAll(compact(json))
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun `v7 table definitions match the generated schema seven json`() {
        val jsonTails = jsonTableTails(schemaJson())
        assertTrue("schema json must contain table definitions", jsonTails.isNotEmpty())

        val migrationTails = MessagesDatabase.UPGRADE_TO_V7_SQL
            .filter { compact(it).startsWith("CREATETABLE") }
            .map { sql ->
                // CREATE TABLE IF NOT EXISTS `<real name>` → drop prefix + real name
                compact(sql).removePrefix("CREATETABLEIFNOTEXISTS").substringAfter("`", "")
                    .substringAfter("`")
            }
        assertEquals("migration must create exactly 5 tables", 5, migrationTails.size)
        for (tail in migrationTails) {
            assertTrue(
                "migration CREATE TABLE tail not found in 7.json: $tail",
                jsonTails.contains(tail)
            )
        }
    }

    /** Index normalization: "UNIQUE|name|(cols)" — table name abstracted away. */
    private fun indexKey(sql: String): String? {
        val m = Regex("CREATE(UNIQUE)?INDEXIFNOTEXISTS`([^`]+)`ON`[^`]+`\\(([^)]+)\\)").find(compact(sql))
            ?: return null
        val unique = if (m.groupValues[1].isEmpty()) "" else "UNIQUE"
        return "$unique|${m.groupValues[2]}|(${m.groupValues[3]})"
    }

    @Test
    fun `v7 index definitions match the generated schema seven json`() {
        val json = schemaJson()
        val jsonIndexKeys = Regex("CREATE(?:UNIQUE)?INDEXIFNOTEXISTS[^\"']+")
            .findAll(compact(json))
            .mapNotNull { indexKey(it.value) }
            .toSet()
        assertTrue("schema json must contain index definitions", jsonIndexKeys.isNotEmpty())

        val migrationIndexKeys = MessagesDatabase.UPGRADE_TO_V7_SQL
            .filter { compact(it).startsWith("CREATEINDEX") || compact(it).startsWith("CREATEUNIQUEINDEX") }
            .mapNotNull(::indexKey)
        assertEquals("migration must create exactly 7 indexes", 7, migrationIndexKeys.size)
        for (key in migrationIndexKeys) {
            assertTrue(
                "migration CREATE INDEX not found in 7.json: $key",
                jsonIndexKeys.contains(key)
            )
        }
    }

    /** The exactly-once contract lives in the UNIQUE indexes — pin them by name. */
    @Test
    fun `idempotency unique indexes are present in the migration`() {
        val statements = MessagesDatabase.UPGRADE_TO_V7_SQL.map(::compact)
        for (name in listOf(
            "`index_remote_commands_idempotencyKey`",
            "`index_gateway_event_outbox_eventUuid`",
            "`index_remote_conversation_map_threadId`"
        )) {
            assertTrue(
                "expected a CREATE UNIQUE INDEX for $name",
                statements.any { it.startsWith("CREATEUNIQUEINDEX") && it.contains(name) }
            )
        }
        // …and nothing in v7 may be a non-unique UNIQUE-index duplicate
        assertEquals(
            3,
            statements.count { it.startsWith("CREATEUNIQUEINDEX") }
        )
    }
}
