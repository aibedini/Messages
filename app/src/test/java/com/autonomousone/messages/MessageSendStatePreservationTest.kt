package com.autonomousone.messages

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * The app-owned send state must SURVIVE a mirror write.
 *
 * ## The bug this pins
 *
 * `messages` is a mirror of the Telephony provider and is rebuilt with a full-column upsert. The
 * durable send verdict is app-owned — the provider has no such column — so every entity the sync path
 * builds carries the Kotlin default (null). A blind upsert therefore ERASES the real outcome of a
 * message each time the row is re-read, and the visible result is a message that showed "Not sent"
 * quietly returning to "Sending…" after a background sync, with nothing in the logs.
 *
 * `MessageSendStatePreserver` fixes it by reading the app-owned columns, upserting, and writing them
 * back. This test executes that exact three-step sequence as SQL against the REAL v25 schema, so the
 * mechanism is pinned rather than assumed. (The upsert statement itself is Room-generated from
 * [com.autonomousone.messages.data.MessageEntity], so it cannot drift from the entity — which is
 * exactly why it is reproduced here rather than asserted.)
 */
class MessageSendStatePreservationTest {

    private val schemaDir = "schemas/com.autonomousone.messages.data.MessagesDatabase"

    private fun schema(version: Int): JSONObject {
        val file = File("$schemaDir/$version.json")
        require(file.exists()) { "Schema $version.json not found — run :app:kspDebugKotlin" }
        return JSONObject(file.readText())
    }

    /** A v25-shaped in-memory database built from Room's OWN generated CREATE statement. */
    private fun database(): Connection {
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        val entities = schema(25).getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            connection.createStatement().use {
                it.execute(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            }
        }
        return connection
    }

    /** Every column of the messages table, in declaration order — what a full-column upsert writes. */
    private fun messageColumns(): List<String> {
        val entities = schema(25).getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            if (entity.getString("tableName") != "messages") continue
            val fields = entity.getJSONArray("fields")
            return (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }
        }
        throw AssertionError("messages is not declared in 25.json")
    }

    /** Inserts one mirror row, leaving the app-owned send columns NULL as the sync path does. */
    private fun insertMirrorRow(connection: Connection, providerId: Long, state: String?) {
        connection.createStatement().use { statement ->
            statement.execute(
                "INSERT INTO `messages` (source, providerId, threadId, normalizedAddress, " +
                    "rawAddress, body, date, type, status, dateSent, read, syncState, " +
                    "sendTransportState, sendFailureCode, sendResultCode, sendRadioErrorCode, " +
                    "sendStateUpdatedAt) VALUES ('sms', $providerId, 100, 'a', 'a', 'hello', 1, 2, 32, " +
                    "0, 1, 'synced', ${state?.let { "'$it'" } ?: "NULL"}, NULL, NULL, NULL, 0)"
            )
        }
    }

    /** The exact REPLACE the sync engine performs, built from the entity's own column list. */
    private fun mirrorUpsert(connection: Connection, providerId: Long, body: String, state: String?, failureCode: String?) {
        val columns = messageColumns()
        val values = columns.joinToString(", ") { column ->
            when (column) {
                "source" -> "'sms'"
                "providerId" -> providerId.toString()
                "threadId" -> "100"
                "normalizedAddress" -> "'a'"
                "rawAddress" -> "'a'"
                "body" -> "'$body'"
                "date" -> "1"
                "type" -> "2"
                "status" -> "32"
                "dateSent" -> "0"
                "read" -> "1"
                "syncState" -> "'synced'"
                "sendTransportState" -> state?.let { "'$it'" } ?: "NULL"
                "sendFailureCode" -> failureCode?.let { "'$it'" } ?: "NULL"
                "sendResultCode" -> "NULL"
                "sendRadioErrorCode" -> "NULL"
                "sendStateUpdatedAt" -> "0"
                else -> "NULL"
            }
        }
        connection.createStatement().use { statement ->
            statement.execute(
                "INSERT OR REPLACE INTO `messages` (${columns.joinToString(", ") { "`$it`" }}) " +
                    "VALUES ($values)"
            )
        }
    }

    private fun stateOf(connection: Connection, providerId: Long): Pair<String?, String?> =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT sendTransportState, sendFailureCode FROM `messages` WHERE providerId = $providerId"
            ).use { rows ->
                rows.next()
                (rows.getString("sendTransportState")) to (rows.getString("sendFailureCode"))
            }
        }

    // ── the defect ───────────────────────────────────────────────────────────

    @Test
    fun `a blind mirror upsert erases the app-owned send verdict`() {
        // This is the behaviour WITHOUT the preserver — the reason it exists. Asserted so the guard
        // below cannot be silently satisfied by a change that removes the need for it.
        val db = database()
        insertMirrorRow(db, 1L, state = "NOT_SENT")
        assertEquals("NOT_SENT", stateOf(db, 1L).first)

        mirrorUpsert(db, 1L, body = "hello again", state = null, failureCode = null)

        assertNull("the defect: a default-carrying upsert wiped the verdict", stateOf(db, 1L).first)
    }

    // ── the fix ──────────────────────────────────────────────────────────────

    @Test
    fun `read-upsert-write-back preserves the app-owned send verdict`() {
        val db = database()
        insertMirrorRow(db, 1L, state = "NOT_SENT")
        db.createStatement().use {
            it.execute("UPDATE `messages` SET sendFailureCode = 'NO_SERVICE' WHERE providerId = 1")
        }

        // Step 1: read the app-owned columns (MessageDao.sendStateOf).
        val before = stateOf(db, 1L)
        // Step 2: the sync engine's full-column upsert, carrying Kotlin defaults.
        mirrorUpsert(db, 1L, body = "provider body", state = null, failureCode = null)
        // Step 3: write them back (MessageDao.restoreSendState).
        db.createStatement().use {
            it.execute(
                "UPDATE `messages` SET sendTransportState = '${before.first}', " +
                    "sendFailureCode = '${before.second}' WHERE source = 'sms' AND providerId = 1"
            )
        }

        assertEquals(
            "a re-synced row must keep telling the user what actually happened",
            "NOT_SENT" to "NO_SERVICE",
            stateOf(db, 1L)
        )
    }

    @Test
    fun `preservation works for every verdict the transport can record`() {
        for (verdict in listOf(
            "SENT_PENDING", "SENT_CONFIRMED", "SENT_AMBIGUOUS", "NOT_SENT", "UNKNOWN"
        )) {
            val db = database()
            insertMirrorRow(db, 1L, state = verdict)

            val before = stateOf(db, 1L)
            mirrorUpsert(db, 1L, body = "b", state = null, failureCode = null)
            db.createStatement().use {
                it.execute(
                    "UPDATE `messages` SET sendTransportState = '${before.first}' WHERE providerId = 1"
                )
            }

            assertEquals("$verdict did not survive the mirror write", verdict, stateOf(db, 1L).first)
        }
    }

    @Test
    fun `an ambiguous verdict is preserved, never downgraded to a success`() {
        // The mission §2 case: a GENERIC_FAILURE verdict has no other durable home, so losing it in a
        // mirror write is losing the user's only warning that a retry could double-send.
        val db = database()
        insertMirrorRow(db, 1L, state = "SENT_AMBIGUOUS")
        db.createStatement().use {
            it.execute("UPDATE `messages` SET sendFailureCode = 'CARRIER_FAILURE_UNKNOWN' WHERE providerId = 1")
        }

        val before = stateOf(db, 1L)
        mirrorUpsert(db, 1L, body = "b", state = null, failureCode = null)
        db.createStatement().use {
            it.execute(
                "UPDATE `messages` SET sendTransportState = '${before.first}', " +
                    "sendFailureCode = '${before.second}' WHERE providerId = 1"
            )
        }

        assertEquals("SENT_AMBIGUOUS" to "CARRIER_FAILURE_UNKNOWN", stateOf(db, 1L))
    }

    @Test
    fun `a brand-new row inserted by the mirror keeps its default state`() {
        // The other half of the contract: an INSERT must not invent a verdict. A message the app has
        // not sent through this path has NO app-owned state, and NULL is exactly the right answer
        // because the presentation layer falls back to the provider status for it.
        val db = database()

        mirrorUpsert(db, 77L, body = "incoming", state = null, failureCode = null)

        assertNull(stateOf(db, 77L).first)
    }

    @Test
    fun `preservation does not resurrect a verdict onto a different message`() {
        // The read is keyed by (source, providerId); a write-back that lost the key would smear one
        // message's outcome onto every other row, which is worse than losing it.
        val db = database()
        insertMirrorRow(db, 1L, state = "NOT_SENT")
        insertMirrorRow(db, 2L, state = null)

        val before = stateOf(db, 1L)
        mirrorUpsert(db, 1L, body = "a", state = null, failureCode = null)
        mirrorUpsert(db, 2L, body = "b", state = null, failureCode = null)
        db.createStatement().use {
            it.execute(
                "UPDATE `messages` SET sendTransportState = '${before.first}' " +
                    "WHERE source = 'sms' AND providerId = 1"
            )
        }

        assertEquals("NOT_SENT", stateOf(db, 1L).first)
        assertNull("the other message must be untouched", stateOf(db, 2L).first)
    }
}
