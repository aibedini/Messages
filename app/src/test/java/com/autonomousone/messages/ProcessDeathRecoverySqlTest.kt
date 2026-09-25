package com.autonomousone.messages

import com.autonomousone.messages.data.GatewayEventFactory
import com.autonomousone.messages.data.GatewayEventOutboxEntity
import com.autonomousone.messages.data.MessageDao
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * The four process-death windows that CAN be made deterministic, plus the one that cannot
 * (Workstream E).
 *
 * WHY A CRASH IS SIMULATED BY CLOSING A CONNECTION WITHOUT COMMITTING
 *
 * "Process death" between a read and a commit is, at the storage layer, exactly a transaction that
 * never commits: SQLite rolls the journal back and the database behaves as though the work never
 * began. That is the property the whole resumable design rests on, and it can be tested for real
 * rather than argued about — so this test opens a real database, performs the production page work
 * inside a transaction, and then abandons the connection mid-transaction.
 *
 * What it proves: a kill at a page boundary loses at most one page of WORK, never a checkpoint that
 * claims work which was not durably written, and never a row's identity (the unique `eventUuid`
 * makes re-doing a page idempotent rather than duplicating).
 *
 * What it does NOT prove: that Android actually kills the process at that moment, or that Room's
 * own transaction wrapper behaves identically to raw JDBC. Those are device/runtime facts.
 */
class ProcessDeathRecoverySqlTest {

    private val dbDir = "schemas/com.autonomousone.messages.data.MessagesDatabase"
    private val generation = 1L
    private val leaseTimeoutMs = 60_000L

    private fun schema(version: Int): JSONObject {
        val file = File("$dbDir/$version.json")
        assertTrue("Schema $version.json not found — run :app:kspDebugKotlin", file.exists())
        return JSONObject(file.readText())
    }

    /** A fresh file-backed database with Room's own DDL for the tables these windows touch. */
    private fun open(name: String, fresh: Boolean = true): Connection {
        val file = File("build/procdeath/$name.db")
        file.parentFile?.mkdirs()
        if (fresh) file.delete()
        val connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        if (fresh) {
            val entities = schema(24).getJSONObject("database").getJSONArray("entities")
            val wanted = setOf("messages", "gateway_event_outbox", "cloud_history_checkpoint")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                if (table !in wanted) continue
                connection.createStatement().use {
                    it.execute(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                }
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    connection.createStatement().use {
                        it.execute(
                            indices.getJSONObject(j).getString("createSql")
                                .replace("\${TABLE_NAME}", table)
                        )
                    }
                }
            }
        }
        return connection
    }

    private fun seedMessages(connection: Connection, count: Int) {
        connection.autoCommit = false
        connection.prepareStatement(
            "INSERT INTO messages (source, providerId, threadId, normalizedAddress, rawAddress," +
                " body, date, type, status, dateSent, read, syncState) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"
        ).use { statement ->
            for (n in 0 until count) {
                val providerId = n + 1L
                statement.setString(1, "sms")
                statement.setLong(2, providerId)
                statement.setLong(3, 1)
                statement.setString(4, "+1555000")
                statement.setString(5, "+1555000")
                statement.setString(6, "body $providerId")
                statement.setLong(7, 1_800_000_000_000L + n * 1_000L)
                statement.setInt(8, 1)
                statement.setInt(9, 0)
                statement.setLong(10, 0)
                statement.setInt(11, 0)
                statement.setString(12, "SYNCED")
                statement.execute()
            }
        }
        connection.commit()
        connection.autoCommit = true
    }

    private fun readCursor(connection: Connection): Pair<Long, Long>? =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT producerCursorDate, producerCursorProviderId FROM cloud_history_checkpoint " +
                    "WHERE source = 'sms'"
            ).use { rows -> if (rows.next()) rows.getLong(1) to rows.getLong(2) else null }
        }

    private fun writeCursor(connection: Connection, date: Long, providerId: Long, exhausted: Boolean) {
        connection.prepareStatement(
            "INSERT OR REPLACE INTO cloud_history_checkpoint (source, generation," +
                " producerCursorDate, producerCursorProviderId, nextOrdinal, ackedContiguousOrdinal," +
                " ackedCursorDate, ackedCursorProviderId, sourceExhausted, updatedAt)" +
                " VALUES ('sms',?,?,?,?,?,?,?,?,?)"
        ).use { statement ->
            statement.setLong(1, generation)
            statement.setLong(2, date)
            statement.setLong(3, providerId)
            statement.setLong(4, 1)
            statement.setLong(5, 0)
            statement.setLong(6, Long.MAX_VALUE)
            statement.setLong(7, Long.MAX_VALUE)
            statement.setInt(8, if (exhausted) 1 else 0)
            statement.setLong(9, 0)
            statement.execute()
        }
    }

    private fun outboxCount(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM gateway_event_outbox").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    private fun distinctUuids(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT COUNT(DISTINCT eventUuid) FROM gateway_event_outbox"
            ).use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    /**
     * ONE production page: the shipped read SQL, the real event identity, the outbox insert, and the
     * checkpoint update — inside a single transaction, exactly as `backfillCloudHistory` does it.
     * The caller decides whether the transaction is committed, which is what makes this usable as a
     * crash simulation.
     */
    private fun page(connection: Connection, limit: Int): Int {
        val pageSql = MessageDao.CLOUD_HISTORY_PAGE_SQL
            .replace(":source", "'sms'")
            .replace(":beforeDate", "?")
            .replace(":beforeId", "?")
            .replace(":limit", "?")
        assertEquals("the shipped page statement has 4 bound parameters", 4, pageSql.count { it == '?' })

        val cursor = readCursor(connection)
        val beforeDate = cursor?.first ?: Long.MAX_VALUE
        val beforeId = cursor?.second ?: Long.MAX_VALUE

        val rows = connection.prepareStatement(pageSql).use { statement ->
            statement.setLong(1, beforeDate)
            statement.setLong(2, beforeDate)
            statement.setLong(3, beforeId)
            statement.setInt(4, limit)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(result.getLong("providerId") to result.getLong("date"))
                    }
                }
            }
        }

        rows.forEach { (providerId, date) ->
            val event = GatewayEventFactory.messageCreated(
                source = "sms",
                providerId = providerId,
                conversationId = "conv-1",
                direction = "incoming",
                body = "body $providerId",
                dateMs = date,
                status = 0,
                revision = 1,
                priority = GatewayEventOutboxEntity.PRIORITY_BACKFILL
            ).copy(
                historySource = "sms",
                historyGeneration = generation,
                historyOrdinal = providerId,
                historyDate = date,
                historyProviderId = providerId
            )
            connection.prepareStatement(
                "INSERT OR IGNORE INTO gateway_event_outbox (eventUuid, eventType, aggregateId," +
                    " messageId, revision, sortKey, priority, historySource, historyGeneration," +
                    " historyOrdinal, historyDate, historyProviderId, sequenceLocal, ciphertext," +
                    " encoding, schemaVersion, cryptoVersion, createdAt, attemptCount, nextAttemptAt," +
                    " state, serverSequence, ackedAt) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            ).use { statement ->
                statement.setString(1, event.eventUuid)
                statement.setString(2, event.eventType)
                statement.setString(3, event.aggregateId)
                statement.setString(4, event.messageId)
                statement.setLong(5, event.revision)
                statement.setLong(6, event.sortKey)
                statement.setString(7, event.priority)
                statement.setString(8, event.historySource)
                statement.setLong(9, event.historyGeneration)
                statement.setLong(10, event.historyOrdinal)
                statement.setLong(11, event.historyDate)
                statement.setLong(12, event.historyProviderId)
                statement.setLong(13, event.sequenceLocal)
                statement.setBytes(14, event.ciphertext)
                statement.setString(15, event.encoding)
                statement.setInt(16, event.schemaVersion)
                statement.setInt(17, event.cryptoVersion)
                statement.setLong(18, event.createdAt)
                statement.setInt(19, event.attemptCount)
                statement.setLong(20, event.nextAttemptAt)
                statement.setString(21, event.state)
                statement.setLong(22, event.serverSequence)
                statement.setLong(23, event.ackedAt)
                statement.executeUpdate()
            }
        }

        if (rows.isNotEmpty()) {
            val last = rows.last()
            writeCursor(connection, last.second, last.first, rows.size < limit)
        } else {
            writeCursor(connection, Long.MAX_VALUE, Long.MAX_VALUE, true)
        }
        return rows.size
    }

    /** Drain to exhaustion with commit, as a healthy run does. */
    private fun drain(connection: Connection, limit: Int = 100): Int {
        var total = 0
        while (true) {
            connection.autoCommit = false
            val count = page(connection, limit)
            connection.commit()
            connection.autoCommit = true
            total += count
            if (count < limit) break
        }
        return total
    }

    // ── Window 1: dies after the page is read, before the transaction commits ──

    @Test
    fun `a kill before the page commits leaves no events and does not advance the checkpoint`() {
        val connection = open("window1")
        try {
            seedMessages(connection, 250)

            // The production page work, abandoned mid-transaction: this is the crash.
            connection.autoCommit = false
            assertEquals(100, page(connection, 100))
            connection.close() // ← the process "dies" here: no commit, no checkpoint

            val reopened = open("window1", fresh = false)
            try {
                assertEquals(
                    "an uncommitted page must leave no events behind",
                    0,
                    outboxCount(reopened)
                )
                assertEquals(
                    "the checkpoint must NOT advance past work that was never committed",
                    null,
                    readCursor(reopened)
                )
                assertEquals("nothing should have been written", 0, distinctUuids(reopened))
            } finally {
                reopened.close()
            }
        } finally {
            runCatching { connection.close() }
        }
    }

    @Test
    fun `a kill before the commit still completes exactly once on the next run`() {
        val connection = open("window1b")
        try {
            seedMessages(connection, 250)
            connection.autoCommit = false
            page(connection, 100)
            connection.close()
        } finally {
            runCatching { connection.close() }
        }

        val reopened = open("window1b", fresh = false)
        try {
            val total = drain(reopened)
            assertEquals("every message is processed exactly once", 250, total)
            assertEquals("no duplicate rows", 250, outboxCount(reopened))
            assertEquals("no duplicate identities", 250, distinctUuids(reopened))
        } finally {
            reopened.close()
        }
    }

    // ── Window 2: dies after events + checkpoint committed ───────────────────

    @Test
    fun `a kill after the commit resumes after the committed checkpoint without redoing the page`() {
        val connection = open("window2")
        try {
            seedMessages(connection, 250)
            connection.autoCommit = false
            page(connection, 100)
            connection.commit() // committed: the page is durable
            connection.close()
        } finally {
            runCatching { connection.close() }
        }

        val reopened = open("window2", fresh = false)
        try {
            assertEquals("the committed page is durable", 100, outboxCount(reopened))
            assertTrue("the checkpoint advanced", readCursor(reopened) != null)

            val secondRun = drain(reopened)
            assertEquals("only the remaining 150 rows are read", 150, secondRun)
            assertEquals("still exactly one row per message", 250, outboxCount(reopened))
            assertEquals(250, distinctUuids(reopened))

            // And the checkpoint really did position the resume: no provider id was read twice.
            val ordinals = reopened.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT COUNT(*), COUNT(DISTINCT historyOrdinal) FROM gateway_event_outbox"
                ).use { rows ->
                    rows.next()
                    rows.getInt(1) to rows.getInt(2)
                }
            }
            assertEquals("every event got its own ordinal", ordinals.first, ordinals.second)
        } finally {
            reopened.close()
        }
    }

    // ── Window 3: dies with rows IN_FLIGHT ──────────────────────────────────

    @Test
    fun `an expired lease is recovered while a live lease is never stolen`() {
        val connection = open("window3")
        try {
            seedMessages(connection, 3)
            drain(connection)
            val now = 1_800_000_000_000L

            // Two claimed rows: one abandoned long ago, one claimed just now by a live uploader.
            connection.createStatement().use {
                it.executeUpdate(
                    "UPDATE gateway_event_outbox SET state='SENDING', leaseId='dead-lease'," +
                        " inFlightSince=${now - leaseTimeoutMs - 1} WHERE id=1"
                )
                it.executeUpdate(
                    "UPDATE gateway_event_outbox SET state='SENDING', leaseId='live-lease'," +
                        " inFlightSince=$now WHERE id=2"
                )
            }

            val recovered = connection.createStatement().use {
                it.executeUpdate(
                    GatewayEventOutboxEntity.RECOVER_STALE_LEASES_SQL
                        .replace(":staleBefore", (now - leaseTimeoutMs).toString())
                )
            }
            assertEquals("only the abandoned lease is reclaimed", 1, recovered)

            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT state, leaseId, inFlightSince, attemptCount FROM gateway_event_outbox WHERE id=1"
                ).use { rows ->
                    rows.next()
                    assertEquals("PENDING", rows.getString(1))
                    assertEquals(null, rows.getString(2))
                    assertEquals(null, rows.getString(3))
                    assertEquals(
                        "being reclaimed is not a failed attempt",
                        0,
                        rows.getInt(4)
                    )
                }
            }
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT state, leaseId FROM gateway_event_outbox WHERE id=2"
                ).use { rows ->
                    rows.next()
                    assertEquals("a live uploader keeps its row", "SENDING", rows.getString(1))
                    assertEquals("live-lease", rows.getString(2))
                }
            }
        } finally {
            connection.close()
        }
    }

    @Test
    fun `a pre-lease SENDING row with no timestamp is still recovered`() {
        // A row left SENDING by a build that predates `inFlightSince` has nothing to age out, so an
        // age-only predicate would strand it forever.
        val connection = open("window3b")
        try {
            seedMessages(connection, 1)
            drain(connection)
            connection.createStatement().use {
                it.executeUpdate(
                    "UPDATE gateway_event_outbox SET state='SENDING', leaseId='old', inFlightSince=NULL"
                )
            }
            val recovered = connection.createStatement().use {
                it.executeUpdate(
                    GatewayEventOutboxEntity.RECOVER_STALE_LEASES_SQL
                        .replace(":staleBefore", "0")
                )
            }
            assertEquals("a lease with no timestamp must still be reclaimable", 1, recovered)
        } finally {
            connection.close()
        }
    }

    // ── Window 4: the server accepted it, the response never arrived ─────────

    @Test
    fun `a lost response converges on ACKED exactly once`() {
        val connection = open("window4")
        try {
            seedMessages(connection, 1)
            drain(connection)
            val now = 1_800_000_000_000L

            // Claim, upload, and die before the ACK is written: the row is SENDING with no lease
            // timestamp (the crash) while the server already has the event.
            connection.createStatement().use {
                it.executeUpdate(
                    "UPDATE gateway_event_outbox SET state='SENDING', leaseId='lost', inFlightSince=NULL"
                )
            }
            connection.createStatement().use {
                it.executeUpdate(
                    GatewayEventOutboxEntity.RECOVER_STALE_LEASES_SQL
                        .replace(":staleBefore", now.toString())
                )
            }
            assertEquals(
                "the row is retryable again, not lost",
                "PENDING",
                connection.createStatement().use { s ->
                    s.executeQuery("SELECT state FROM gateway_event_outbox").use { r ->
                        r.next(); r.getString(1)
                    }
                }
            )

            // Re-claim and re-send. The server answers DUPLICATE, which the uploader treats as an
            // acknowledgement (EventUploader: "ACCEPTED and DUPLICATE both mean the server durably
            // has the event").
            connection.createStatement().use {
                it.executeUpdate("UPDATE gateway_event_outbox SET state='SENDING', leaseId='second'")
            }
            // The SHIPPED acknowledgement statement, with its named parameters bound.
            fun ack(serverSequence: Long): Int = connection.createStatement().use {
                it.executeUpdate(
                    GatewayEventOutboxEntity.MARK_ACKED_SQL
                        .replace(":serverSequence", serverSequence.toString())
                        .replace(":ackedAt", now.toString())
                        .replace(":httpStatus", "200")
                        .replace(
                            ":eventUuid",
                            "(SELECT eventUuid FROM gateway_event_outbox LIMIT 1)"
                        )
                )
            }
            assertEquals("the duplicate answer acknowledges the row", 1, ack(77))

            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT state, serverSequence, attemptCount FROM gateway_event_outbox"
                ).use { rows ->
                    rows.next()
                    assertEquals("ACKED", rows.getString(1))
                    assertEquals(77L, rows.getLong(2))
                    assertEquals(
                        "a duplicate is not a failed attempt",
                        0,
                        rows.getInt(3)
                    )
                }
            }

            // Idempotence: the ACK statement only moves a row that is IN_FLIGHT, so a replayed
            // acknowledgement cannot rewrite an acknowledged row or double-count it.
            val replay = ack(serverSequence = 999)
            assertEquals("a replayed ACK must be a no-op", 0, replay)
            val serverSequence = scalar(connection, "SELECT serverSequence FROM gateway_event_outbox")
            assertEquals("the sequence must not be rewritten by a replay", "77", serverSequence)
            assertEquals("still exactly one row for the message", 1, outboxCount(connection))
        } finally {
            connection.close()
        }
    }

    @Test
    fun `a re-read page cannot duplicate an already-queued event`() {
        // The reason a kill at a page boundary is safe: identity is derived from the provider row,
        // so the same page read twice produces the same eventUuid and the unique index refuses the
        // second insert. Without this, resuming would duplicate every re-read message.
        val connection = open("window4b")
        try {
            seedMessages(connection, 100)
            drain(connection)
            assertEquals(100, outboxCount(connection))

            // Force a rewind of the producer cursor, as a recovery pass may do, and re-read.
            connection.createStatement().use {
                it.executeUpdate("DELETE FROM cloud_history_checkpoint")
            }
            val total = drain(connection)
            assertEquals("the rows are re-offered", 100, total)
            assertEquals("but nothing is duplicated", 100, outboxCount(connection))
            assertEquals(100, distinctUuids(connection))
        } finally {
            connection.close()
        }
    }

    private fun scalar(connection: Connection, sql: String): String? =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows -> if (rows.next()) rows.getString(1) else null }
        }
}
