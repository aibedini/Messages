package com.autonomousone.messages

import com.autonomousone.messages.data.GatewayEventOutboxEntity
import com.autonomousone.messages.repository.GatewaySyncRepository
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * ACKED retention (mission §19) — and specifically the hazard that makes it dangerous.
 *
 * `advanceHistoryAckWatermarks` walks ACKED history rows forward from `ackedContiguousOrdinal` and
 * stops at the first gap. If retention deleted a history row BEYOND that watermark, the walk would
 * break immediately and the watermark could never advance again: history would report
 * CATCHING_UP forever while every event was in fact acknowledged. So the predicate must refuse any
 * history row the watermark has not already passed.
 *
 * This runs the REAL `PURGE_ACKED_SQL` (the same constant the DAO's @Query uses) against a real
 * SQLite database, because the rule is a WHERE clause and reading it is not the same as testing it.
 */
class OutboxRetentionSqlTest {

    private val dbDir = "schemas/com.autonomousone.messages.data.MessagesDatabase"
    private val now = 1_800_000_000_000L
    private val retention = GatewaySyncRepository.Policy.ACKED_RETENTION_MS

    private fun schema(version: Int): JSONObject {
        val file = File("$dbDir/$version.json")
        assertTrue("Schema $version.json not found — run :app:kspDebugKotlin", file.exists())
        return JSONObject(file.readText())
    }

    /** Room's own CREATE TABLE for one entity, with ${TABLE_NAME} resolved. */
    private fun createTable(connection: Connection, table: String) {
        val entities = schema(19).getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            if (entity.getString("tableName") != table) continue
            connection.createStatement().use {
                it.execute(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            }
            return
        }
        throw AssertionError("$table is not declared in 19.json")
    }

    private fun connection(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:").apply {
        createTable(this, "gateway_event_outbox")
        createTable(this, "cloud_history_checkpoint")
    }

    /**
     * One outbox row. Only the columns the predicate and the assertions care about are varied.
     */
    private fun insert(
        connection: Connection,
        uuid: String,
        state: String,
        ackedAt: Long,
        historyGeneration: Long = 0,
        historyOrdinal: Long = 0,
        historySource: String = ""
    ) {
        connection.prepareStatement(
            """
            INSERT INTO `gateway_event_outbox`
                (`eventUuid`,`eventType`,`aggregateId`,`messageId`,`revision`,`sortKey`,
                 `priority`,`historySource`,`historyGeneration`,`historyOrdinal`,`historyDate`,
                 `historyProviderId`,`sequenceLocal`,`ciphertext`,`encoding`,`schemaVersion`,
                 `cryptoVersion`,`createdAt`,`attemptCount`,`nextAttemptAt`,`state`,
                 `serverSequence`,`ackedAt`)
            VALUES (?, 'MESSAGE_CREATED','agg','msg',1,0,'REALTIME',?,?,?,0,0,0,X'01',
                    'envelope.v3',1,3,0,0,0,?,0,?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, uuid)
            statement.setString(2, historySource)
            statement.setLong(3, historyGeneration)
            statement.setLong(4, historyOrdinal)
            statement.setString(5, state)
            statement.setLong(6, ackedAt)
            statement.execute()
        }
    }

    /** The sms cursor is at ordinal 5: rows 1..5 are behind it, 6+ are not. */
    private fun seedCheckpoint(connection: Connection) {
        connection.createStatement().use {
            it.execute(
                """
                INSERT INTO `cloud_history_checkpoint`
                    (`source`,`generation`,`producerCursorDate`,`producerCursorProviderId`,
                     `nextOrdinal`,`ackedContiguousOrdinal`,`ackedCursorDate`,`ackedCursorProviderId`,
                     `sourceExhausted`,`updatedAt`)
                VALUES ('sms',4,0,0,11,5,0,0,0,0)
                """.trimIndent()
            )
        }
    }

    private fun purge(connection: Connection, olderThan: Long = now - retention): Int =
        connection.createStatement().use {
            // The DAO's @Query uses Room's named-parameter syntax; JDBC takes literals.
            it.executeUpdate(
                GatewayEventOutboxEntity.PURGE_ACKED_SQL.replace(":olderThan", olderThan.toString())
            )
        }

    private fun remaining(connection: Connection): Set<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT eventUuid FROM gateway_event_outbox").use { rows ->
                buildSet { while (rows.next()) add(rows.getString(1)) }
            }
        }

    // ── The predicate ────────────────────────────────────────────────────────

    @Test
    fun `anAcknowledgedRealtimeEventPastRetentionIsRemoved`() {
        val connection = connection()
        try {
            insert(connection, "old", "ACKED", now - retention - 1)
            assertEquals(1, purge(connection))
            assertTrue(remaining(connection).isEmpty())
        } finally {
            connection.close()
        }
    }

    @Test
    fun `anAcknowledgedEventInsideRetentionIsKept`() {
        val connection = connection()
        try {
            insert(connection, "recent", "ACKED", now - retention + 60_000L)
            assertEquals(0, purge(connection))
            assertEquals(setOf("recent"), remaining(connection))
        } finally {
            connection.close()
        }
    }

    @Test
    fun `aHistoryRowBehindTheWatermarkIsRemoved`() {
        val connection = connection()
        try {
            seedCheckpoint(connection)
            insert(
                connection, "behind", "ACKED", now - retention - 1,
                historyGeneration = 4, historyOrdinal = 3, historySource = "sms"
            )
            assertEquals(1, purge(connection))
            assertTrue(remaining(connection).isEmpty())
        } finally {
            connection.close()
        }
    }

    @Test
    fun `aHistoryRowBeyondTheWatermarkIsKeptEvenThoughItIsOld`() {
        // THE hazard. Deleting this would make the watermark walk stop at this gap forever.
        val connection = connection()
        try {
            seedCheckpoint(connection)
            insert(
                connection, "ahead", "ACKED", now - retention - 1,
                historyGeneration = 4, historyOrdinal = 7, historySource = "sms"
            )
            assertEquals(0, purge(connection))
            assertEquals(setOf("ahead"), remaining(connection))
        } finally {
            connection.close()
        }
    }

    @Test
    fun `aCrossedDeadLetterNoLongerPinsEveryLaterAcknowledgedRow`() {
        // FIXED (round 47) — this test previously pinned the defect it now guards against.
        //
        // `HistoryAckWalk` used to stall on a DEAD_LETTER, so `ackedContiguousOrdinal` froze at the
        // position before it forever. Since this predicate only removes a history row at or below the
        // watermark, EVERY acknowledged row produced afterwards became permanently undeletable: one
        // permanently failed event switched retention off for the rest of the history.
        //
        // A dead position is now terminal for the frontier (it will never change without human
        // action, so waiting for it is waiting forever), so the watermark moves past it and the
        // acknowledged rows behind it become removable again. The dead letter itself is NOT removed:
        // this predicate only matches ACKED, and the failure record must survive.
        val connection = connection()
        try {
            connection.createStatement().use {
                it.execute(
                    """
                    INSERT INTO `cloud_history_checkpoint`
                        (`source`,`generation`,`producerCursorDate`,`producerCursorProviderId`,
                         `nextOrdinal`,`ackedContiguousOrdinal`,`ackedCursorDate`,`ackedCursorProviderId`,
                         `sourceExhausted`,`updatedAt`)
                    VALUES ('sms',4,0,0,4004,4003,0,0,1,0)
                    """.trimIndent()
                )
            }
            // The one permanent failure, now behind the watermark rather than holding it back.
            insert(
                connection, "blocked", "DEAD_LETTER", now - retention - 1,
                historyGeneration = 4, historyOrdinal = 3, historySource = "sms"
            )
            // 4,000 acknowledged rows above it, every one older than retention.
            connection.autoCommit = false
            connection.prepareStatement(
                """
                INSERT INTO `gateway_event_outbox`
                    (`eventUuid`,`eventType`,`aggregateId`,`messageId`,`revision`,`sortKey`,
                     `priority`,`historySource`,`historyGeneration`,`historyOrdinal`,`historyDate`,
                     `historyProviderId`,`sequenceLocal`,`ciphertext`,`encoding`,`schemaVersion`,
                     `cryptoVersion`,`createdAt`,`attemptCount`,`nextAttemptAt`,`state`,
                     `serverSequence`,`ackedAt`)
                VALUES (?,'MESSAGE_CREATED','agg','msg',1,0,'BACKFILL','sms',4,?,0,0,0,X'01',
                        'envelope.v3',1,3,0,0,0,'ACKED',0,?)
                """.trimIndent()
            ).use { statement ->
                for (ordinal in 4L..4003L) {
                    statement.setString(1, "acked-$ordinal")
                    statement.setLong(2, ordinal)
                    statement.setLong(3, now - retention - 1)
                    statement.execute()
                }
            }
            connection.commit()
            connection.autoCommit = true

            assertEquals("every acknowledged row behind the watermark is removable", 4_000, purge(connection))
            assertEquals(
                "the permanent failure is retained as the record of what happened",
                setOf("blocked"),
                remaining(connection)
            )
        } finally {
            connection.close()
        }
    }

    @Test
    fun `aHistoryRowAtExactlyTheWatermarkIsRemovable`() {
        val connection = connection()
        try {
            seedCheckpoint(connection)
            insert(
                connection, "boundary", "ACKED", now - retention - 1,
                historyGeneration = 4, historyOrdinal = 5, historySource = "sms"
            )
            assertEquals(1, purge(connection))
        } finally {
            connection.close()
        }
    }

    @Test
    fun `aHistoryRowWithNoCheckpointForItsGenerationIsKept`() {
        // A generation the server has no acked progress for has no watermark to be behind.
        val connection = connection()
        try {
            seedCheckpoint(connection)
            insert(
                connection, "othergen", "ACKED", now - retention - 1,
                historyGeneration = 9, historyOrdinal = 1, historySource = "sms"
            )
            assertEquals(0, purge(connection))
            assertEquals(setOf("othergen"), remaining(connection))
        } finally {
            connection.close()
        }
    }

    @Test
    fun `aHistoryRowForAnotherSourceIsKept`() {
        val connection = connection()
        try {
            seedCheckpoint(connection)
            insert(
                connection, "mms", "ACKED", now - retention - 1,
                historyGeneration = 4, historyOrdinal = 1, historySource = "mms"
            )
            assertEquals(0, purge(connection))
        } finally {
            connection.close()
        }
    }

    // ── Only ACKED, ever ─────────────────────────────────────────────────────

    @Test
    fun `noNonAcknowledgedRowIsEverRemoved`() {
        val connection = connection()
        try {
            seedCheckpoint(connection)
            val old = now - retention - 1
            insert(connection, "pending", GatewayEventOutboxEntity.STATE_PENDING, 0)
            insert(connection, "sending", GatewayEventOutboxEntity.STATE_SENDING, 0)
            insert(connection, "retry", GatewayEventOutboxEntity.STATE_RETRY_WAIT, 0)
            // DEAD_LETTER is the record that something failed; it is never cleanup's business.
            insert(connection, "dead", GatewayEventOutboxEntity.STATE_DEAD_LETTER, old)
            // An ACKED row from before ackedAt existed cannot have its age established.
            insert(connection, "noackedat", "ACKED", 0)

            assertEquals(0, purge(connection))
            assertEquals(
                setOf("pending", "sending", "retry", "dead", "noackedat"),
                remaining(connection)
            )
        } finally {
            connection.close()
        }
    }

    // ── The window ───────────────────────────────────────────────────────────

    @Test
    fun `theRetentionWindowIsFortyEightHours`() {
        assertEquals(48 * 60 * 60_000L, GatewaySyncRepository.Policy.ACKED_RETENTION_MS)
        assertEquals(172_800_000L, GatewaySyncRepository.Policy.ACKED_RETENTION_MS)
    }

    @Test
    fun `aMixedPopulationRemovesExactlyTheSafeRows`() {
        val connection = connection()
        try {
            seedCheckpoint(connection)
            val old = now - retention - 1
            val recent = now - 60_000L
            insert(connection, "rt-old", "ACKED", old)
            insert(connection, "rt-recent", "ACKED", recent)
            insert(connection, "hist-behind", "ACKED", old, 4, 2, "sms")
            insert(connection, "hist-ahead", "ACKED", old, 4, 8, "sms")
            insert(connection, "dead", "DEAD_LETTER", old)

            assertEquals(2, purge(connection))
            assertEquals(setOf("rt-recent", "hist-ahead", "dead"), remaining(connection))
        } finally {
            connection.close()
        }
    }

    @Test
    fun `purgingTwiceRemovesNothingTheSecondTime`() {
        val connection = connection()
        try {
            seedCheckpoint(connection)
            insert(connection, "rt-old", "ACKED", now - retention - 1)
            assertEquals(1, purge(connection))
            assertEquals(0, purge(connection))
        } finally {
            connection.close()
        }
    }
}
