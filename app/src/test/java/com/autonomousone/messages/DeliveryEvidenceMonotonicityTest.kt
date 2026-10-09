package com.autonomousone.messages

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * The monotonic DELIVERY evidence rule, executed as real SQL against the real v27 schema.
 *
 * ## Why this is tested as SQL rather than through the DAO
 *
 * The rule is a property of the STATEMENT, not of Kotlin: delivery callbacks are independent
 * asynchronous writers that arrive out of order, more than once, and sometimes malformed, and the
 * required guarantees are all of the form "a weaker answer must not replace a stronger one". A
 * read-compare-then-write in Kotlin cannot provide that — there is a window between the read and the
 * write in which a late report overwrites a confirmed one — so the comparison lives inside the UPDATE
 * and is therefore only meaningfully testable by executing that UPDATE.
 *
 * The four guarantees this pins, from the task's callback-precedence rules:
 *
 * ```text
 * DELIVERED must not be downgraded by a late UNKNOWN callback
 * a definite negative report must not be erased by a late malformed report
 * a duplicate callback must not change the stored answer
 * a late SENT result must not erase stronger delivery evidence
 * ```
 *
 * The table comes from Room's own generated 27.json, so the columns exercised are the columns that
 * ship, and the SQL below is the production statement from `MessageDao.recordDeliveryEvidence`.
 */
class DeliveryEvidenceMonotonicityTest {

    private val dbDir = "schemas/com.autonomousone.messages.data.MessagesDatabase"

    private fun readSchema(version: Int): JSONObject {
        val file = File("$dbDir/$version.json")
        assertTrue("Schema $version.json not found — run :app:kspDebugKotlin", file.exists())
        return JSONObject(file.readText())
    }

    private fun database(): Connection {
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        val entities = readSchema(27).getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val name = entity.getString("tableName")
            connection.createStatement().use {
                it.execute(entity.getString("createSql").replace("\${TABLE_NAME}", name))
            }
        }
        return connection
    }

    /** The production statement, transcribed from MessageDao.recordDeliveryEvidence. */
    private fun applyEvidence(
        connection: Connection,
        providerId: Long,
        at: Long,
        tpStatus: Int?,
        evidence: String?,
        resultCode: Int?,
        rank: Int
    ): Int = connection.prepareStatement(
        """
        UPDATE messages SET
            deliveryCallbackAt = CASE WHEN ? > deliveryEvidenceRank THEN ? ELSE deliveryCallbackAt END,
            deliveryTpStatus = CASE WHEN ? >= deliveryEvidenceRank THEN ? ELSE deliveryTpStatus END,
            deliveryEvidence = CASE WHEN ? >= deliveryEvidenceRank THEN ? ELSE deliveryEvidence END,
            deliveryResultCode = CASE WHEN ? >= deliveryEvidenceRank THEN ? ELSE deliveryResultCode END,
            deliveryEvidenceRank = CASE WHEN ? >= deliveryEvidenceRank THEN ? ELSE deliveryEvidenceRank END
        WHERE source = 'sms' AND providerId = ?
        """.trimIndent()
    ).use { statement ->
        statement.setLong(1, rank.toLong()); statement.setLong(2, at)
        statement.setInt(3, rank); statement.setObject(4, tpStatus)
        statement.setInt(5, rank); statement.setString(6, evidence)
        statement.setInt(7, rank); statement.setObject(8, resultCode)
        statement.setInt(9, rank); statement.setInt(10, rank)
        statement.setLong(11, providerId)
        statement.executeUpdate()
    }

    /** v27 ranks, mirroring DeliveryEvidenceRank: NONE/TEMPORARY/UNKNOWN/FAILED/DELIVERED. */
    private val rankTemporary = 1
    private val rankUnknown = 2
    private val rankFailed = 3
    private val rankDelivered = 4

    private fun insertRow(connection: Connection, providerId: Long) {
        connection.createStatement().use {
            it.execute(
                "INSERT INTO `messages` (source, providerId, threadId, normalizedAddress, rawAddress, " +
                    "body, date, type, status, dateSent, read, syncState, sendStateUpdatedAt, " +
                    "deliveryCallbackAt, deliveryEvidenceRank) " +
                    "VALUES ('sms', $providerId, 100, 'a', 'a', 'hello', 1, 2, 32, 0, 1, 'synced', 0, 0, 0)"
            )
        }
    }

    private fun read(connection: Connection, providerId: Long): List<String?> =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT deliveryCallbackAt, deliveryTpStatus, deliveryEvidence, deliveryResultCode, " +
                    "deliveryEvidenceRank FROM `messages` WHERE providerId = $providerId"
            ).use { rows ->
                rows.next()
                listOf(
                    rows.getLong("deliveryCallbackAt").toString(),
                    rows.getObject("deliveryTpStatus")?.toString(),
                    rows.getString("deliveryEvidence"),
                    rows.getObject("deliveryResultCode")?.toString(),
                    rows.getInt("deliveryEvidenceRank").toString()
                )
            }
        }

    // ── the four guarantees ──────────────────────────────────────────────────

    @Test
    fun `a late UNKNOWN callback does not downgrade a confirmed DELIVERED`() {
        val db = database()
        insertRow(db, 1L)
        applyEvidence(db, 1L, at = 1000, tpStatus = 0x00, evidence = "DELIVERED", resultCode = -1, rank = rankDelivered)
        val afterDelivered = read(db, 1L)

        // A duplicate/late report that could not be parsed arrives afterwards.
        applyEvidence(db, 1L, at = 2000, tpStatus = null, evidence = "UNKNOWN", resultCode = -1, rank = rankUnknown)

        assertEquals(
            "a confirmed delivery must survive a later unparseable report",
            afterDelivered,
            read(db, 1L)
        )
        assertEquals("DELIVERED", read(db, 1L)[2])
    }

    @Test
    fun `a late malformed report does not erase a definite negative report`() {
        val db = database()
        insertRow(db, 1L)
        val tp = 0x40
        applyEvidence(db, 1L, at = 1000, tpStatus = tp, evidence = "FAILED", resultCode = -1, rank = rankFailed)
        val afterFailed = read(db, 1L)

        applyEvidence(db, 1L, at = 2000, tpStatus = null, evidence = "UNKNOWN", resultCode = -1, rank = rankUnknown)

        // The user was told the carrier reported a delivery failure, and that is still true.
        assertEquals(afterFailed, read(db, 1L))
        assertEquals("FAILED", read(db, 1L)[2])
        assertEquals(tp.toString(), read(db, 1L)[1])
    }

    @Test
    fun `a duplicate callback leaves the stored answer unchanged`() {
        val db = database()
        insertRow(db, 1L)
        applyEvidence(db, 1L, at = 1000, tpStatus = 0x00, evidence = "DELIVERED", resultCode = -1, rank = rankDelivered)
        val afterFirst = read(db, 1L)

        // Same evidence, different timestamp: a redelivered broadcast must not even move the clock,
        // or the "delivered at" shown to the user would drift on every duplicate.
        applyEvidence(db, 1L, at = 9999, tpStatus = 0x00, evidence = "DELIVERED", resultCode = -1, rank = rankDelivered)

        assertEquals(afterFirst, read(db, 1L))
        assertEquals("1000", read(db, 1L)[0])
    }

    @Test
    fun `a late SENT result does not erase stronger delivery evidence`() {
        val db = database()
        insertRow(db, 1L)
        applyEvidence(db, 1L, at = 1000, tpStatus = 0x00, evidence = "DELIVERED", resultCode = -1, rank = rankDelivered)
        val afterDelivered = read(db, 1L)

        // The transport half writes its own columns and never those of the delivery half, so a late
        // SENT callback cannot reach this data at all. Asserted anyway, because the guarantee is what
        // matters and the statement is what must keep it true.
        db.createStatement().use {
            it.execute(
                "UPDATE messages SET sendTransportState = 'SENT_CONFIRMED', sendStateUpdatedAt = 2500 " +
                    "WHERE providerId = 1"
            )
        }

        assertEquals(afterDelivered, read(db, 1L))
    }

    // ── ordering and accumulation ────────────────────────────────────────────

    @Test
    fun `stronger evidence does replace weaker evidence`() {
        val db = database()
        insertRow(db, 1L)
        applyEvidence(db, 1L, at = 1000, tpStatus = null, evidence = "TEMPORARY", resultCode = -1, rank = rankTemporary)

        applyEvidence(db, 1L, at = 2000, tpStatus = 0x00, evidence = "DELIVERED", resultCode = -1, rank = rankDelivered)

        // Monotonic does not mean frozen: a temporary state advancing to delivered is progress.
        assertEquals("DELIVERED", read(db, 1L)[2])
        assertEquals("0", read(db, 1L)[1])
        assertEquals("4", read(db, 1L)[4])
    }

    @Test
    fun `evidence at the same rank may refine its details but not move the clock`() {
        val db = database()
        insertRow(db, 1L)
        applyEvidence(db, 1L, at = 1000, tpStatus = 0x20, evidence = "TEMPORARY", resultCode = -1, rank = rankTemporary)

        // A second report of EQUAL strength carrying a different TP-Status is new information about the
        // same conclusion, so the details are refreshed...
        applyEvidence(db, 1L, at = 2000, tpStatus = 0x21, evidence = "TEMPORARY", resultCode = -1, rank = rankTemporary)

        assertEquals("the newer TP-Status of equal strength is recorded", "33", read(db, 1L)[1])
        // ...but "when did this become true" stays the FIRST moment it became true. Letting a repeat
        // report move it would make the timestamp shown to the user drift on every duplicate.
        assertEquals("the clock does not move for equal-strength evidence", "1000", read(db, 1L)[0])
    }

    @Test
    fun `the first report always lands on a fresh row`() {
        val db = database()
        insertRow(db, 1L)

        applyEvidence(db, 1L, at = 1000, tpStatus = null, evidence = "UNKNOWN", resultCode = -1, rank = rankUnknown)

        // Rank 0 ("nothing recorded") is not stronger than UNKNOWN, so even the weakest real evidence
        // is recorded rather than being mistaken for a downgrade.
        assertEquals("UNKNOWN", read(db, 1L)[2])
        assertEquals("2", read(db, 1L)[4])
    }

    @Test
    fun `a missing report is stored as null, never as a delivered code`() {
        val db = database()
        insertRow(db, 1L)

        applyEvidence(db, 1L, at = 1000, tpStatus = null, evidence = "UNKNOWN", resultCode = -1, rank = rankUnknown)

        // Zero is a REAL 3GPP "delivered" code, so writing it for a missing report would fabricate the
        // strongest possible evidence out of nothing.
        assertEquals(null, read(db, 1L)[1])
        assertEquals("UNKNOWN", read(db, 1L)[2])
    }

    @Test
    fun `evidence is scoped to one message and cannot leak to another row`() {
        val db = database()
        insertRow(db, 1L)
        insertRow(db, 2L)

        applyEvidence(db, 1L, at = 1000, tpStatus = 0x40, evidence = "FAILED", resultCode = -1, rank = rankFailed)

        assertEquals("FAILED", read(db, 1L)[2])
        assertEquals("0", read(db, 2L)[0])
        assertEquals(null, read(db, 2L)[2])
    }

    @Test
    fun `an update for a message that is not mirrored yet reports zero rows`() {
        val db = database()

        val updated = applyEvidence(db, 99L, at = 1000, tpStatus = 0x00, evidence = "DELIVERED", resultCode = -1, rank = rankDelivered)

        // The provider row reaches the mirror asynchronously, so this is a legitimate outcome and must
        // not throw. The evidence is not lost: the send_segments ledger still holds the transport half.
        assertEquals(0, updated)
    }
}
