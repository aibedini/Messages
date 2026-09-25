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
 * The upgrade a real device actually performs: **18 → 24**, in one pass (Workstream G).
 *
 * v3.4.8 shipped schema 18. Every version above it (19–24) is in this worktree and has never been
 * released, so the ONLY upgrade path a user can take is 18 → 24 through all six migrations — not
 * one boundary at a time. The existing `MigrationToV19..V24SqlTest` files each prove their own
 * boundary produces the shape Room generates for that step, which is necessary but not sufficient:
 * nothing proved the COMPOSED path preserves data, and nothing checked the specific hazard below.
 *
 * THE HAZARD THIS EXISTS FOR
 *
 * Two dead-letter rescues were fixed earlier in this work because they returned rows to PENDING
 * while leaving `attemptCount` past `MAX_ATTEMPTS` — so the row died again on its first failure with
 * zero retries. A migration is the other place that can resurrect rows, and it runs on every
 * upgrading device exactly once, when nobody is watching. So the invariant is asserted directly:
 *
 *   no row may end up claimable (PENDING / RETRY_WAIT) with an already-exhausted retry budget,
 *   and no dead letter may be silently revived by a migration.
 *
 * Real SQLite, the real released v18 DDL from 18.json, and the real migration statements.
 */
class MigrationV18ToV24UpgradeTest {

    private val dbDir = "schemas/com.autonomousone.messages.data.MessagesDatabase"

    /** `OutboxRetryPolicy.MAX_ATTEMPTS`; a row at or above this is past its budget. */
    private val maxAttempts = 25

    private fun schema(version: Int): JSONObject {
        val file = File("$dbDir/$version.json")
        assertTrue("Schema $version.json not found — run :app:kspDebugKotlin", file.exists())
        return JSONObject(file.readText())
    }

    private fun entities(version: Int) =
        schema(version).getJSONObject("database").getJSONArray("entities")

    private fun tableNames(version: Int): List<String> =
        (0 until entities(version).length()).map { entities(version).getJSONObject(it).getString("tableName") }

    /** Build a database with Room's own DDL (tables + indices) for one schema version. */
    private fun build(version: Int, name: String): Connection {
        val file = File("build/migration/$name.db")
        file.parentFile?.mkdirs()
        file.delete()
        val connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        val list = entities(version)
        for (i in 0 until list.length()) {
            val entity = list.getJSONObject(i)
            val table = entity.getString("tableName")
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
        return connection
    }

    /** The six migrations a v3.4.8 install must pass through, in order. */
    private fun migrateToV24(connection: Connection) {
        val steps = listOf(
            19 to MessagesDatabase.UPGRADE_TO_V19_SQL,
            20 to MessagesDatabase.UPGRADE_TO_V20_SQL,
            21 to MessagesDatabase.UPGRADE_TO_V21_SQL,
            22 to MessagesDatabase.UPGRADE_TO_V22_SQL,
            23 to MessagesDatabase.UPGRADE_TO_V23_SQL,
            24 to MessagesDatabase.UPGRADE_TO_V24_SQL
        )
        connection.autoCommit = false
        try {
            steps.forEach { (version, sql) ->
                sql.forEach { statement ->
                    connection.createStatement().use { it.execute(statement) }
                }
                connection.commit()
                assertTrue("migration to v$version must have run statements", sql.isNotEmpty())
            }
        } finally {
            connection.autoCommit = true
        }
    }

    /**
     * The durable state a real phone carries across the upgrade: everything the mission names —
     * pending outbox rows, retry counters (including an exhausted one), dead letters, the history
     * checkpoint, command state, crypto state and identity.
     */
    private fun seedV18(connection: Connection) {
        connection.createStatement().use { it.execute(OUTBOX_SEED) }
        connection.createStatement().use { it.execute(CHECKPOINT_SEED) }
        connection.createStatement().use { it.execute(COMMAND_SEED) }
        connection.createStatement().use { it.execute(KEY_EPOCH_SEED) }
        connection.createStatement().use { it.execute(DEVICE_SEED) }
    }

    private val OUTBOX_SEED = """
        INSERT INTO `gateway_event_outbox`
            (`eventUuid`,`eventType`,`aggregateId`,`messageId`,`revision`,`sortKey`,`priority`,
             `historySource`,`historyGeneration`,`historyOrdinal`,`historyDate`,`historyProviderId`,
             `sequenceLocal`,`ciphertext`,`encoding`,`schemaVersion`,`cryptoVersion`,`createdAt`,
             `attemptCount`,`nextAttemptAt`,`state`,`serverSequence`,`ackedAt`,
             `failureCategory`,`failureHttpStatus`,`lastAttemptAt`,`deadLetteredAt`,`failureAppVersion`)
        VALUES
            -- a healthy pending history event
            ('ev-pending','MESSAGE_CREATED','t','m',1,1,'BACKFILL','sms',1,10,100,10,1,
             X'01','envelope.v3',1,3,1,2,0,'PENDING',0,0,NULL,NULL,NULL,NULL,NULL),
            -- one mid-retry: the counter must survive
            ('ev-retry','MESSAGE_CREATED','t','m',1,2,'BACKFILL','sms',1,11,110,11,2,
             X'02','envelope.v3',1,3,1,5,0,'RETRY_WAIT',0,0,'HTTP_SERVER',503,9,NULL,NULL),
            -- one IN_FLIGHT under a lease: the lease must survive so recovery can age it out
            ('ev-inflight','MESSAGE_CREATED','t','m',1,3,'BACKFILL','sms',1,12,120,12,3,
             X'03','envelope.v3',1,3,1,4,0,'SENDING',0,0,NULL,NULL,10,NULL,NULL),
            -- an EXHAUSTED retry budget, parked as a dead letter with its reason
            ('ev-dead','MESSAGE_CREATED','t','m',1,4,'BACKFILL','sms',1,13,130,13,4,
             X'04','envelope.v3',1,3,1,26,0,'DEAD_LETTER',0,0,'VALIDATION_FAILED',422,20,20,'3.4.8'),
            -- acknowledged history behind the watermark
            ('ev-acked','MESSAGE_CREATED','t','m',1,5,'BACKFILL','sms',1,9,90,9,5,
             X'05','envelope.v3',1,3,1,1,0,'ACKED',42,30,NULL,NULL,NULL,NULL,NULL)
    """.trimIndent()

    private val CHECKPOINT_SEED = """
        INSERT INTO `cloud_history_checkpoint`
            (`source`,`generation`,`producerCursorDate`,`producerCursorProviderId`,`nextOrdinal`,
             `ackedContiguousOrdinal`,`ackedCursorDate`,`ackedCursorProviderId`,`sourceExhausted`,
             `updatedAt`)
        VALUES ('sms',1,130,13,14,9,90,9,0,111), ('mms',1,55,5,6,5,50,5,1,222)
    """.trimIndent()

    private val COMMAND_SEED = """
        INSERT INTO `remote_commands`
            (`commandId`,`type`,`ciphertext`,`encoding`,`schemaVersion`,`cryptoVersion`,`signature`,
             `senderDeviceId`,`issuedAt`,`receivedAt`,`expiresAt`,`nonce`,`idempotencyKey`,`state`)
        VALUES ('cmd-1','SEND_SMS',X'07','envelope.v1',1,1,X'08','dev-9',1,2,9999999999999,
                'nonce-1','idem-1','CLAIMED')
    """.trimIndent()

    private val KEY_EPOCH_SEED = """
        INSERT INTO `conversation_key_epochs`
            (`id`,`epochId`,`conversationId`,`generation`,`historyFloor`,`category`,`wrappedKey`,
             `createdAt`)
        VALUES (1,'epoch-1','conv-1',3,0,'READ_AUTH_CODES',X'09',7)
    """.trimIndent()

    private val DEVICE_SEED = """
        INSERT INTO `trusted_devices`
            (`deviceId`,`accountId`,`displayName`,`deviceType`,`origin`,`signingPublicKey`,
             `encryptionPublicKey`,`capabilitiesJson`,`historyGrant`,`certificateJson`,
             `certificateSignature`,`trustSequence`,`status`,`approvedAt`,`expiresAt`,`revokedAt`,
             `createdAt`,`updatedAt`)
        VALUES ('dev-9','acct-1','Web','WEB','gmweb','spk','epk','[]','FULL_HISTORY','cert','sig',
                7,'ACTIVE',1,0,0,1,1)
    """.trimIndent()

    private fun scalar(connection: Connection, sql: String): String? =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows -> if (rows.next()) rows.getString(1) else null }
        }

    private fun count(connection: Connection, table: String): Int =
        scalar(connection, "SELECT COUNT(*) FROM `$table`")?.toInt() ?: -1

    private fun tableExists(connection: Connection, table: String): Boolean =
        scalar(
            connection,
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='$table'"
        )?.toInt() == 1

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

    // ── The composed path ────────────────────────────────────────────────────

    @Test
    fun `a v18 install upgrades to v24 with every table shaped as Room expects`() {
        val connection = build(18, "v18-shape")
        val expected = build(24, "v24-shape")
        try {
            seedV18(connection)
            migrateToV24(connection)

            val expectedTables = tableNames(24)
            val actualTables = expectedTables.filter { tableExists(connection, it) }
            // Every v24 table must exist after the chain, including the four that v18 never had.
            assertEquals(
                "the composed migration must create every v24 table",
                expectedTables.sorted(),
                actualTables.sorted()
            )

            expectedTables.forEach { table ->
                assertEquals(
                    "`$table` must have exactly the shape Room generates for v24 after 18→24",
                    tableInfo(expected, table),
                    tableInfo(connection, table)
                )
            }
        } finally {
            connection.close()
            expected.close()
        }
    }

    @Test
    fun `no row is lost and no durable state is rewritten by the upgrade`() {
        val connection = build(18, "v18-preserve")
        try {
            seedV18(connection)
            val outboxBefore = count(connection, "gateway_event_outbox")
            val checkpointBefore = count(connection, "cloud_history_checkpoint")
            val commandsBefore = count(connection, "remote_commands")
            val epochsBefore = count(connection, "conversation_key_epochs")
            val devicesBefore = count(connection, "trusted_devices")

            migrateToV24(connection)

            assertEquals("outbox rows", outboxBefore, count(connection, "gateway_event_outbox"))
            assertEquals("checkpoints", checkpointBefore, count(connection, "cloud_history_checkpoint"))
            assertEquals("commands", commandsBefore, count(connection, "remote_commands"))
            assertEquals("key epochs", epochsBefore, count(connection, "conversation_key_epochs"))
            assertEquals("trusted devices", devicesBefore, count(connection, "trusted_devices"))

            // Retry counters and states survive verbatim.
            assertEquals("2", scalar(connection, "SELECT attemptCount FROM gateway_event_outbox WHERE eventUuid='ev-pending'"))
            assertEquals("5", scalar(connection, "SELECT attemptCount FROM gateway_event_outbox WHERE eventUuid='ev-retry'"))
            assertEquals("RETRY_WAIT", scalar(connection, "SELECT state FROM gateway_event_outbox WHERE eventUuid='ev-retry'"))
            assertEquals("SENDING", scalar(connection, "SELECT state FROM gateway_event_outbox WHERE eventUuid='ev-inflight'"))
            assertEquals("ACKED", scalar(connection, "SELECT state FROM gateway_event_outbox WHERE eventUuid='ev-acked'"))
            assertEquals("42", scalar(connection, "SELECT serverSequence FROM gateway_event_outbox WHERE eventUuid='ev-acked'"))

            // History watermarks survive: losing these would restart history from zero.
            assertEquals("9", scalar(connection, "SELECT ackedContiguousOrdinal FROM cloud_history_checkpoint WHERE source='sms'"))
            assertEquals("14", scalar(connection, "SELECT nextOrdinal FROM cloud_history_checkpoint WHERE source='sms'"))
            assertEquals("1", scalar(connection, "SELECT sourceExhausted FROM cloud_history_checkpoint WHERE source='mms'"))

            // Command, crypto and identity state survive.
            assertEquals("CLAIMED", scalar(connection, "SELECT state FROM remote_commands WHERE commandId='cmd-1'"))
            assertEquals("3", scalar(connection, "SELECT generation FROM conversation_key_epochs WHERE id=1"))
            assertEquals("FULL_HISTORY", scalar(connection, "SELECT historyGrant FROM trusted_devices WHERE deviceId='dev-9'"))
            assertEquals("ACTIVE", scalar(connection, "SELECT status FROM trusted_devices WHERE deviceId='dev-9'"))
        } finally {
            connection.close()
        }
    }

    @Test
    fun `no migration revives a dead letter or leaves a row claimable with an exhausted budget`() {
        val connection = build(18, "v18-retry")
        try {
            seedV18(connection)
            migrateToV24(connection)

            // The dead letter is still dead, still at its own attempt count, still explaining itself.
            assertEquals("DEAD_LETTER", scalar(connection, "SELECT state FROM gateway_event_outbox WHERE eventUuid='ev-dead'"))
            assertEquals("26", scalar(connection, "SELECT attemptCount FROM gateway_event_outbox WHERE eventUuid='ev-dead'"))
            assertEquals("VALIDATION_FAILED", scalar(connection, "SELECT failureCategory FROM gateway_event_outbox WHERE eventUuid='ev-dead'"))
            assertEquals("422", scalar(connection, "SELECT failureHttpStatus FROM gateway_event_outbox WHERE eventUuid='ev-dead'"))
            assertEquals("20", scalar(connection, "SELECT deadLetteredAt FROM gateway_event_outbox WHERE eventUuid='ev-dead'"))

            // THE INVARIANT: nothing may be claimable while already past its retry budget. This is
            // the migration-shaped version of the rescue defect — a row returned to service with no
            // attempts left dies again on its first failure, silently.
            val stranded = scalar(
                connection,
                "SELECT COUNT(*) FROM gateway_event_outbox " +
                    "WHERE state IN ('PENDING','RETRY_WAIT') AND attemptCount >= $maxAttempts"
            )
            assertEquals(
                "no migration may leave a row claimable with an exhausted retry budget",
                "0",
                stranded
            )

            // And no migration may invent work: PENDING means it was PENDING before.
            val pendingNow = scalar(
                connection,
                "SELECT GROUP_CONCAT(eventUuid) FROM gateway_event_outbox WHERE state='PENDING'"
            )
            assertEquals("only the row that was already pending may be pending", "ev-pending", pendingNow)
        } finally {
            connection.close()
        }
    }

    @Test
    fun `the four accounting tables arrive empty because the upgrade proves nothing`() {
        // history_sync_sessions, control_plane_auth_state, mirror_verify_state and pending_inbound_sms
        // describe work this install has never done. A seeded row would claim a verification (or a
        // held inbound message) that never happened, so an upgrading install must get zero rows.
        val connection = build(18, "v18-empty")
        try {
            seedV18(connection)
            migrateToV24(connection)

            listOf(
                "history_sync_sessions",
                "control_plane_auth_state",
                "mirror_verify_state",
                "pending_inbound_sms"
            ).forEach { table ->
                assertEquals(
                    "`$table` must exist and be empty on an upgraded install",
                    0,
                    count(connection, table)
                )
            }
        } finally {
            connection.close()
        }
    }
}
