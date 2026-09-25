package com.autonomousone.messages

import com.autonomousone.messages.data.MessageAssetSql
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * The count behind the MMS attachment gap diagnostic (mission §52, WS-I prerequisite 1).
 *
 * WHY THIS EXISTS SEPARATELY. The report's rendering of the gap is covered by
 * `GatewayDiagnosticReportTest`, but the *collector* that fills it reads `message_assets` through
 * Room, which cannot run in JVM — so the query itself would otherwise be verified only by the fact
 * that it compiled. This executes the shipped statement against real SQLite, which is the part that
 * can actually be wrong: a count that included SMS rows would overstate the gap, and a count that
 * matched nothing would hide it behind a healthy-looking zero.
 */
class MmsAttachmentGapSqlTest {

    private val dbDir = "schemas/com.autonomousone.messages.data.MessagesDatabase"

    private fun connection(): Connection {
        val file = File("build/mmsgap/mmsgap.db")
        file.parentFile?.mkdirs()
        file.delete()
        val connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        val schema = JSONObject(File("$dbDir/24.json").readText())
        val entities = schema.getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            if (entity.getString("tableName") != "message_assets") continue
            connection.createStatement().use {
                it.execute(
                    entity.getString("createSql").replace("\${TABLE_NAME}", "message_assets")
                )
            }
        }
        return connection
    }

    private fun insert(connection: Connection, key: String, source: String, kind: String) {
        connection.prepareStatement(
            "INSERT INTO message_assets (assetKey, source, providerId, threadId, kind, value," +
                " mimeType, displayName, date) VALUES (?,?,?,?,?,?,?,?,?)"
        ).use { statement ->
            statement.setString(1, key)
            statement.setString(2, source)
            statement.setLong(3, 1)
            statement.setLong(4, 1)
            statement.setString(5, kind)
            statement.setString(6, "content://mms/part/1")
            statement.setString(7, "image/jpeg")
            statement.setString(8, "photo.jpg")
            statement.setLong(9, 1)
            statement.execute()
        }
    }

    private fun countFor(connection: Connection, source: String): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                MessageAssetSql.COUNT_FOR_SOURCE_SQL.replace(":source", "'$source'")
            ).use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    @Test
    fun `the shipped count reports only the source it was asked about`() {
        val connection = connection()
        try {
            insert(connection, "m1", "mms", "MEDIA")
            insert(connection, "m2", "mms", "FILE")
            insert(connection, "s1", "sms", "MEDIA")

            assertEquals("both MMS attachments counted", 2, countFor(connection, "mms"))
            assertEquals("an SMS asset must never inflate the MMS gap", 1, countFor(connection, "sms"))
        } finally {
            connection.close()
        }
    }

    @Test
    fun `an empty table counts zero rather than failing`() {
        // Zero is a legitimate count here — the report's job is to say the replication path is
        // missing either way (see GatewayDiagnosticReportTest), so this only pins that the statement
        // is total.
        val connection = connection()
        try {
            assertEquals(0, countFor(connection, "mms"))
        } finally {
            connection.close()
        }
    }

    @Test
    fun `the shipped statement is the one the DAO uses`() {
        // Ties the tested predicate to the shipped one: if the DAO stopped referencing the constant,
        // every test above would keep passing while production ran something else.
        val dao = listOf(
            File("src/main/java/com/autonomousone/messages/data/UxDaos.kt"),
            File("app/src/main/java/com/autonomousone/messages/data/UxDaos.kt")
        ).first { it.isFile }.readText()
        assertTrue(
            "MessageAssetDao.countForSource must execute the shared constant",
            dao.contains("@Query(MessageAssetSql.COUNT_FOR_SOURCE_SQL)")
        )
    }
}
