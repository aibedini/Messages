package com.autonomousone.messages

import com.autonomousone.messages.data.GatewayEventFactory
import com.autonomousone.messages.data.GatewayEventOutboxEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Identity convergence between the realtime and history paths (mission §33).
 *
 * Before this, one logical message had two possible deterministic identities — realtime produced
 * `evt:v3:MESSAGE_CREATED:…` and a history sweep produced `evt:replica-v4:…` — so the outbox's
 * unique index could not dedupe across them and the §33 race could put the same message in the
 * outbox twice with one shared `payload.messageId`
 * (`docs/gateway-replication-audit.md`, Blocker 7).
 */
class EventIdentityConvergenceTest {

    private val dbDir = "schemas/com.autonomousone.messages.data.MessagesDatabase"

    private fun created(priority: String) = GatewayEventFactory.messageCreated(
        source = "sms",
        providerId = 4242,
        conversationId = "conv",
        direction = "in",
        body = "hello",
        dateMs = 1_700_000_000_000L,
        status = 0,
        priority = priority
    )

    // ── The identity is now the same ─────────────────────────────────────────

    @Test
    fun `realtimeAndHistoryAgreeOnTheEventIdentity`() {
        val realtime = created(GatewayEventOutboxEntity.PRIORITY_REALTIME)
        val history = created(GatewayEventOutboxEntity.PRIORITY_BACKFILL)

        assertEquals(
            "one message must have one eventUuid whichever path found it",
            realtime.eventUuid,
            history.eventUuid
        )
        assertEquals(realtime.messageId, history.messageId)
        assertEquals(
            GatewayEventFactory.eventUuidFor(
                GatewayEventFactory.Types.MESSAGE_CREATED, "sms", 4242, 1_700_000_000_000L
            ),
            realtime.eventUuid
        )
    }

    @Test
    fun `theOldHistoryNamespaceIsGone`() {
        val history = created(GatewayEventOutboxEntity.PRIORITY_BACKFILL)
        val legacy = java.util.UUID.nameUUIDFromBytes(
            "evt:replica-v4:sms:4242:1700000000000".toByteArray()
        ).toString()

        assertNotEquals("the replica-v4 namespace must no longer be used", legacy, history.eventUuid)
    }

    @Test
    fun `onlyThePriorityAndSourceStillDifferBetweenTheTwoPaths`() {
        // Identity converges; the operational metadata that legitimately differs does not.
        val realtime = created(GatewayEventOutboxEntity.PRIORITY_REALTIME)
        val history = created(GatewayEventOutboxEntity.PRIORITY_BACKFILL)

        assertEquals(GatewayEventOutboxEntity.PRIORITY_REALTIME, realtime.priority)
        assertEquals(GatewayEventOutboxEntity.PRIORITY_BACKFILL, history.priority)
        assertEquals(GatewayEventOutboxEntity.SOURCE_REALTIME, realtime.source)
        assertEquals(GatewayEventOutboxEntity.SOURCE_HISTORY, history.source)
    }

    @Test
    fun `distinctMessagesStillGetDistinctIdentities`() {
        val a = GatewayEventFactory.messageCreated(
            source = "sms", providerId = 1, conversationId = "c",
            direction = "in", body = "b", dateMs = 10, status = 0
        )
        val b = GatewayEventFactory.messageCreated(
            source = "sms", providerId = 2, conversationId = "c",
            direction = "in", body = "b", dateMs = 10, status = 0
        )
        assertNotEquals(a.eventUuid, b.eventUuid)
    }

    // ── The stamp guard: ordinals are never rewritten ────────────────────────

    private fun connection(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:").apply {
        val entities = JSONObject(
            File("$dbDir/19.json").readText()
        ).getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            if (entity.getString("tableName") != "gateway_event_outbox") continue
            createStatement().use {
                it.execute(entity.getString("createSql").replace("\${TABLE_NAME}", "gateway_event_outbox"))
            }
            break
        }
    }

    private fun insert(connection: Connection, uuid: String, generation: Long, ordinal: Long) {
        connection.prepareStatement(
            """
            INSERT INTO `gateway_event_outbox`
                (`eventUuid`,`eventType`,`aggregateId`,`messageId`,`revision`,`sortKey`,
                 `priority`,`historySource`,`historyGeneration`,`historyOrdinal`,`historyDate`,
                 `historyProviderId`,`sequenceLocal`,`ciphertext`,`encoding`,`schemaVersion`,
                 `cryptoVersion`,`createdAt`,`attemptCount`,`nextAttemptAt`,`state`,
                 `serverSequence`,`ackedAt`)
            VALUES (?, 'MESSAGE_CREATED','agg','msg',1,0,'REALTIME','',?,?,0,0,0,X'01',
                    'envelope.v3',1,3,0,0,0,'PENDING',0,0)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, uuid)
            statement.setLong(2, generation)
            statement.setLong(3, ordinal)
            statement.execute()
        }
    }

    /** Executes the DAO's own statement, with Room's named parameters replaced by literals. */
    private fun stamp(
        connection: Connection,
        uuid: String,
        generation: Long,
        ordinal: Long
    ): Int = connection.createStatement().use {
        it.executeUpdate(
            GatewayEventOutboxEntity.STAMP_HISTORY_SQL
                .replace(":source", "'sms'")
                .replace(":generation", generation.toString())
                .replace(":ordinal", ordinal.toString())
                .replace(":date", "1")
                .replace(":providerId", "1")
                .replace(":eventUuid", "'$uuid'")
        )
    }

    private fun ordinals(connection: Connection, uuid: String): Pair<Long, Long> =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT historyGeneration, historyOrdinal FROM gateway_event_outbox WHERE eventUuid = '$uuid'"
            ).use { rows ->
                rows.next()
                rows.getLong(1) to rows.getLong(2)
            }
        }

    @Test
    fun `anUnstampedRowIsStamped`() {
        val connection = connection()
        try {
            insert(connection, "e1", generation = 0, ordinal = 0)
            assertEquals(1, stamp(connection, "e1", generation = 4, ordinal = 7))
            assertEquals(4L to 7L, ordinals(connection, "e1"))
        } finally {
            connection.close()
        }
    }

    @Test
    fun `anAlreadyStampedRowIsNeverRewritten`() {
        // The invariant that keeps the ack watermark walk from stalling: a row keeps the ordinal it
        // was first given, so the ordinal sequence never develops a hole.
        val connection = connection()
        try {
            insert(connection, "e1", generation = 4, ordinal = 7)
            assertEquals(0, stamp(connection, "e1", generation = 4, ordinal = 99))
            assertEquals("the original ordinal must survive", 4L to 7L, ordinals(connection, "e1"))
        } finally {
            connection.close()
        }
    }

    @Test
    fun `stampingAnotherRowLeavesTheFirstAlone`() {
        val connection = connection()
        try {
            insert(connection, "e1", generation = 4, ordinal = 1)
            insert(connection, "e2", generation = 0, ordinal = 0)

            assertEquals(1, stamp(connection, "e2", generation = 4, ordinal = 2))
            assertEquals(4L to 1L, ordinals(connection, "e1"))
            assertEquals(4L to 2L, ordinals(connection, "e2"))
        } finally {
            connection.close()
        }
    }

    @Test
    fun `stampingAnUnknownRowChangesNothing`() {
        val connection = connection()
        try {
            assertTrue(0 == stamp(connection, "absent", generation = 4, ordinal = 1))
        } finally {
            connection.close()
        }
    }
}
