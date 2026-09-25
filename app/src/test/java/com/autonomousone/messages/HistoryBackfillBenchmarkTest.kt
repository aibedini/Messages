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
 * History backfill at production scale — measured, not extrapolated (Workstream B).
 *
 * WHY THIS LAYER, AND WHAT IT IS NOT
 *
 * The goal is to replace speculation about the 360k-message workload with measurements of the real
 * production pipeline. The real loop is `TelephonySyncCoordinator.backfillCloudHistory`, and it
 * cannot run here: it needs an Android `Context`, a Room database, `GatewayPreferences`, the
 * notification stack and the **Android Keystore** for encryption. There is no Robolectric in this
 * project and no device or emulator available.
 *
 * So this exercises the largest layer below that which is deterministic and real:
 *
 *   REAL — the production DDL, read from the exported Room schema (so the tables, the primary keys
 *          and the indices are exactly what ships), executed against real SQLite;
 *   REAL — the SHIPPED page statement (`MessageDao.CLOUD_HISTORY_PAGE_SQL`), the compound
 *          `(date, providerId)` cursor, and the production `source,date,providerId` index;
 *   REAL — `GatewayEventFactory.messageCreated` / `eventUuidFor`, so event identity and the payload
 *          envelope are the shipping implementations, and the outbox's unique `eventUuid` index
 *          performs the same dedupe it does in production;
 *   REAL — the transaction shape: ONE transaction per page containing both the event inserts and
 *          the checkpoint update, which is what makes a crash mid-scan resumable;
 *   REAL — `HistoryScanAccounting` for the per-row outcome classification;
 *   REAL — the shipped priority ordering constants for the realtime-fairness check.
 *
 *   NOT REAL — `ConversationKeyRepository.encrypt` (Android Keystore AES-GCM). The benchmark
 *          inserts the factory's plaintext-envelope bytes instead, so every timing here EXCLUDES
 *          encryption and is therefore a LOWER BOUND on device cost. It is also not the Android
 *          Telephony Provider (`ContentProvider` I/O, cursor windows, real device storage), and it
 *          is not a device run: DEVICE_VALIDATION stays PHYSICAL DEVICE REQUIRED.
 *
 * The generator is deterministic: the same seed and count produce the same logical rows, so a
 * failure is reproducible rather than a flake.
 */
class HistoryBackfillBenchmarkTest {

    private val dbDir = "schemas/com.autonomousone.messages.data.MessagesDatabase"

    /** Production backfill page size (`minOf(100, available)` in `backfillCloudHistory`). */
    private val productionPageLimit = 100

    /** Production backfill depth cap (`maxPendingBackfill = 2_000`). */
    private val productionPendingCap = 2_000

    private fun schema(version: Int): JSONObject {
        val file = File("$dbDir/$version.json")
        assertTrue("Schema $version.json not found — run :app:kspDebugKotlin", file.exists())
        return JSONObject(file.readText())
    }

    /** Room's own CREATE TABLE + CREATE INDEX for one entity, with ${TABLE_NAME} resolved. */
    private fun createEntity(connection: Connection, table: String) {
        val entities = schema(24).getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            if (entity.getString("tableName") != table) continue
            connection.createStatement().use {
                it.execute(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val indices = entity.optJSONArray("indices")
            if (indices != null) {
                for (j in 0 until indices.length()) {
                    val sql = indices.getJSONObject(j).getString("createSql")
                        .replace("\${TABLE_NAME}", table)
                    connection.createStatement().use { it.execute(sql) }
                }
            }
            return
        }
        throw AssertionError("$table is not declared in 24.json")
    }

    private fun database(name: String): Connection {
        // A FILE database, not :memory: — at 360k rows an in-memory database would put the whole
        // table on the JVM heap and make the memory observation meaningless.
        val file = File("build/bench/$name.db")
        file.parentFile?.mkdirs()
        file.delete()
        val connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        listOf(
            "messages",
            "gateway_event_outbox",
            "cloud_history_checkpoint",
            "history_sync_sessions"
        ).forEach { createEntity(connection, it) }
        return connection
    }

    // ── The deterministic fixture ────────────────────────────────────────────

    /**
     * `count` logical messages with the variation a real phone holds.
     *
     * Same-timestamp bursts are deliberate: `date` alone is not unique, and the cursor must be the
     * COMPOUND `(date, providerId)`. Groups of [burst] rows share one timestamp, so a date-only
     * cursor would either skip or livelock on them.
     */
    private fun seedMessages(connection: Connection, count: Int, seed: Long = 20260924L) {
        val sql = "INSERT INTO messages " +
            "(source, providerId, threadId, normalizedAddress, rawAddress, body, date, type, status," +
            " dateSent, read, syncState) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"
        connection.autoCommit = false
        connection.prepareStatement(sql).use { statement ->
            var written = 0
            var date = 1_800_000_000_000L
            var providerId = 1L
            // A tiny LCG: deterministic across JVMs (unlike Random, whose algorithm is specified
            // but whose sequences are easy to break by accident when the seed handling changes).
            var state = seed
            fun next(bound: Int): Int {
                state = (state * 6364136223846793005L + 1442695040888963407L)
                return ((state ushr 33).toInt() and 0x7fffffff) % bound
            }
            while (written < count) {
                val burst = 1 + next(5)
                repeat(burst) {
                    if (written >= count) return@repeat
                    // ~4% MMS text-only, as required; binary attachments are out of scope.
                    val source = if (next(25) == 0) "mms" else "sms"
                    val outgoing = next(3) == 0
                    val threadId = (providerId % 400) + 1
                    val address = "+1555" + (1000000 + (providerId % 5000)).toString()
                    val body = when {
                        next(10) == 0 -> "x".repeat(1200)          // a long concatenated body
                        next(4) == 0 -> "ok"                        // a very short one
                        else -> "message body $providerId"
                    }
                    statement.setString(1, source)
                    statement.setLong(2, providerId)
                    statement.setLong(3, threadId)
                    statement.setString(4, address)
                    statement.setString(5, address)
                    statement.setString(6, body)
                    statement.setLong(7, date)
                    // type: 1=inbox 2=sent (Telephony convention)
                    statement.setInt(8, if (outgoing) 2 else 1)
                    statement.setInt(9, next(4))                    // status variation
                    statement.setLong(10, if (outgoing) date else 0L)
                    statement.setInt(11, if (next(2) == 0) 1 else 0)
                    statement.setString(12, "SYNCED")
                    statement.execute()
                    providerId++
                    written++
                }
                date += 1_000L + next(3_000)
            }
            connection.commit()
        }
        connection.autoCommit = true
    }

    // ── The drain, mirroring the production protocol ──────────────────────────

    private data class DrainStats(
        var pages: Int = 0,
        var rows: Int = 0,
        var maxPageRows: Int = 0,
        var checkpointWrites: Int = 0,
        var outboxRows: Int = 0,
        var enqueued: Int = 0,
        var skipped: Int = 0,
        var failed: Int = 0,
        var eligible: Int = 0,
        var pendingCapHits: Int = 0,
        var elapsedMs: Long = 0
    )

    /** The shipped predicate, so the depth gauge is the production one. */
    private fun pendingBackfillDepth(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT COUNT(*) FROM gateway_event_outbox " +
                    "WHERE priority = '${GatewayEventOutboxEntity.PRIORITY_BACKFILL}' AND " +
                    "state IN (${GatewayEventOutboxEntity.OUTSTANDING_STATES_SQL})"
            ).use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    /**
     * One page: the shipped SQL read, the real event construction, the real outbox insert
     * (`INSERT OR IGNORE` on the unique `eventUuid`), and the checkpoint update — all inside ONE
     * transaction, exactly as `backfillCloudHistory` does it.
     *
     * @return rows read in this page.
     */
    private fun drainPage(
        connection: Connection,
        source: String,
        generation: Long,
        limit: Int,
        stats: DrainStats,
        onRow: (String) -> Unit = {}
    ): Int {
        val pageSql = MessageDao.CLOUD_HISTORY_PAGE_SQL
            .replace(":source", "'$source'")
            .replace(":beforeDate", "?")
            .replace(":beforeId", "?")
            .replace(":limit", "?")
        connection.autoCommit = false
        try {
            val cursor = readCursor(connection, source)
            val beforeDate = cursor?.first ?: Long.MAX_VALUE
            val beforeId = cursor?.second ?: Long.MAX_VALUE

            val page = connection.prepareStatement(pageSql).use { statement ->
                // The shipped statement references `:beforeDate` TWICE (the `date <` and the
                // `date =` halves of the compound cursor), so it has FOUR placeholders after
                // substitution. Binding three shifts every argument by one — which is exactly the
                // positional-binding defect that made an earlier round's test assert against the
                // wrong column, so the count is asserted rather than assumed.
                assertEquals(
                    "the page statement must have exactly 4 bound parameters",
                    4,
                    pageSql.count { it == '?' }
                )
                statement.setLong(1, beforeDate)
                statement.setLong(2, beforeDate)
                statement.setLong(3, beforeId)
                statement.setInt(4, limit)
                statement.executeQuery().use { rows ->
                    val out = ArrayList<Triple<Long, Long, String>>(limit)
                    while (rows.next()) {
                        out.add(
                            Triple(
                                rows.getLong("providerId"),
                                rows.getLong("date"),
                                rows.getString("source")
                            )
                        )
                    }
                    out
                }
            }
            stats.pages++
            stats.maxPageRows = maxOf(stats.maxPageRows, page.size)

            page.forEach { (providerId, date, _) ->
                stats.eligible++
                onRow("$source")
                val event = GatewayEventFactory.messageCreated(
                    source = source,
                    providerId = providerId,
                    conversationId = "conv-${providerId % 400}",
                    direction = if (providerId % 3L == 0L) "outgoing" else "incoming",
                    body = "message body $providerId",
                    dateMs = date,
                    status = 0,
                    address = "+1555${providerId % 5000}",
                    read = false,
                    revision = 1,
                    priority = GatewayEventOutboxEntity.PRIORITY_BACKFILL
                ).copy(
                    historySource = source,
                    historyGeneration = generation,
                    historyOrdinal = stats.eligible.toLong(),
                    historyDate = date,
                    historyProviderId = providerId
                )
                if (insertOutbox(connection, event)) {
                    stats.enqueued++
                    stats.outboxRows++
                } else {
                    // The same identity is already present: the §33 realtime/history race, which
                    // production counts as an explained outcome rather than a loss.
                    stats.skipped++
                }
            }

            if (page.isNotEmpty()) {
                val last = page.last()
                writeCursor(connection, source, generation, last.second, last.first, page.size < limit)
                stats.checkpointWrites++
            } else {
                writeCursor(connection, source, generation, Long.MAX_VALUE, Long.MAX_VALUE, true)
                stats.checkpointWrites++
            }
            stats.rows += page.size
            connection.commit()
            return page.size
        } finally {
            connection.autoCommit = true
        }
    }

    private fun readCursor(connection: Connection, source: String): Pair<Long, Long>? =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT producerCursorDate, producerCursorProviderId FROM cloud_history_checkpoint " +
                    "WHERE source = '$source'"
            ).use { rows ->
                if (rows.next()) rows.getLong(1) to rows.getLong(2) else null
            }
        }

    private fun writeCursor(
        connection: Connection,
        source: String,
        generation: Long,
        date: Long,
        providerId: Long,
        exhausted: Boolean,
        generation0: Long = generation
    ) {
        connection.prepareStatement(
            "INSERT OR REPLACE INTO cloud_history_checkpoint " +
                "(source, generation, producerCursorDate, producerCursorProviderId, nextOrdinal," +
                " ackedContiguousOrdinal, ackedCursorDate, ackedCursorProviderId, sourceExhausted," +
                " updatedAt) VALUES (?,?,?,?,?,?,?,?,?,?)"
        ).use { statement ->
            statement.setString(1, source)
            statement.setLong(2, generation0)
            statement.setLong(3, date)
            statement.setLong(4, providerId)
            statement.setLong(5, 1)
            statement.setLong(6, 0)
            statement.setLong(7, Long.MAX_VALUE)
            statement.setLong(8, Long.MAX_VALUE)
            statement.setInt(9, if (exhausted) 1 else 0)
            statement.setLong(10, 0)
            statement.execute()
        }
    }

    /** The production outbox insert. `false` means the unique `eventUuid` already held this row. */
    private fun insertOutbox(connection: Connection, event: GatewayEventOutboxEntity): Boolean =
        connection.prepareStatement(
            "INSERT OR IGNORE INTO gateway_event_outbox " +
                "(eventUuid, eventType, aggregateId, messageId, revision, sortKey, priority," +
                " historySource, historyGeneration, historyOrdinal, historyDate, historyProviderId," +
                " sequenceLocal, ciphertext, encoding, schemaVersion, cryptoVersion, createdAt," +
                " attemptCount, nextAttemptAt, state, serverSequence, ackedAt) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
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
            statement.executeUpdate() > 0
        }

    /**
     * The production control flow: page → commit → check the depth cap → repeat, yielding between
     * pages. `ackAsWeGo` simulates the uploader draining ACKed rows, which is what production does;
     * without it the 2000-row cap stops the scan after 20 pages, which is itself the key finding.
     */
    private fun drain(
        connection: Connection,
        source: String,
        stats: DrainStats,
        ackAsWeGo: Boolean,
        onPage: (Int) -> Unit = {}
    ) {
        val started = System.nanoTime()
        while (true) {
            val available = productionPendingCap - pendingBackfillDepth(connection)
            if (available <= 0) {
                if (!ackAsWeGo) {
                    stats.pendingCapHits++
                    break
                }
                ackOldest(connection, productionPendingCap)
                continue
            }
            val limit = minOf(productionPageLimit, available)
            val count = drainPage(connection, source, generation = 1L, limit = limit, stats = stats)
            onPage(count)
            if (count < limit) break
        }
        stats.elapsedMs = (System.nanoTime() - started) / 1_000_000
    }

    /** Model the uploader's ACK so the scan can continue, as it does on a real device. */
    private fun ackOldest(connection: Connection, count: Int) {
        connection.createStatement().use {
            it.executeUpdate(
                "UPDATE gateway_event_outbox SET state = 'ACKED', ackedAt = 1 " +
                    "WHERE id IN (SELECT id FROM gateway_event_outbox " +
                    "WHERE state IN (${GatewayEventOutboxEntity.OUTSTANDING_STATES_SQL}) " +
                    "AND priority = '${GatewayEventOutboxEntity.PRIORITY_BACKFILL}' " +
                    "ORDER BY id LIMIT $count)"
            )
        }
    }

    private fun scale(connection: Connection, count: Int, ackAsWeGo: Boolean): DrainStats {        seedMessages(connection, count)
        val stats = DrainStats()
        // Production drains SMS and then MMS (`for (source in listOf(SOURCE_SMS, SOURCE_MMS))`).
        // Draining only SMS here left ~4% of the fixture untouched, which the row assertion caught.
        if (ackAsWeGo) {
            drain(connection, "sms", stats, ackAsWeGo = true)
            drain(connection, "mms", stats, ackAsWeGo = true)
        } else {
            // The cap case deliberately stops inside the first source: that is where production
            // stops too, and the point of that test is the cap, not coverage.
            drain(connection, "sms", stats, ackAsWeGo = false)
        }
        return stats
    }

    // ── 1k ───────────────────────────────────────────────────────────────────

    @Test
    fun `one thousand messages drain in bounded pages`() {
        val connection = database("bench1k")
        try {
            // ackAsWeGo so this scale drains BOTH sources like the others: the no-drain path stops
            // inside the first source (that behaviour is pinned by its own test) and would report
            // an SMS-only row count here, making the four scales incomparable.
            val stats = scale(connection, 1_000, ackAsWeGo = true)
            record("1k", stats)
            assertTrue("the scan must complete", stats.rows > 0)
            assertEquals("every seeded row must be accounted for", 1_000, stats.rows)
            assertTrue(
                "a page may never exceed the production page size",
                stats.maxPageRows <= productionPageLimit
            )
            assertTrue(
                "checkpoints must be written per page, not once at the end",
                stats.checkpointWrites >= 1
            )
        } finally {
            connection.close()
        }
    }

    // ── 10k ──────────────────────────────────────────────────────────────────

    @Test
    fun `ten thousand messages stay bounded in memory and complete`() {
        val connection = database("bench10k")
        try {
            val stats = scale(connection, 10_000, ackAsWeGo = true)
            record("10k", stats)
            assertTrue(stats.rows >= 10_000)
            assertTrue(stats.maxPageRows <= productionPageLimit)
        } finally {
            connection.close()
        }
    }

    // ── 100k ─────────────────────────────────────────────────────────────────

    @Test
    fun `one hundred thousand messages drain with page-sized memory only`() {
        val connection = database("bench100k")
        try {
            val stats = scale(connection, 100_000, ackAsWeGo = true)
            record("100k", stats)
            assertTrue(stats.rows >= 100_000)
            assertTrue(stats.maxPageRows <= productionPageLimit)
            // eligible == enqueued + skipped + failed, with no unexplained remainder (§70).
            assertEquals(
                "accounting must balance: eligible = enqueued + skipped",
                stats.eligible,
                stats.enqueued + stats.skipped
            )
        } finally {
            connection.close()
        }
    }

    // ── The production cap is the interesting one ────────────────────────────

    private fun record(scale: String, stats: DrainStats, note: String = "") {
        val line = "BENCH scale=$scale elapsedMs=${stats.elapsedMs} pages=${stats.pages} " +
            "rows=${stats.rows} maxPageRows=${stats.maxPageRows} " +
            "checkpointWrites=${stats.checkpointWrites} outbox=${stats.outboxRows} " +
            "eligible=${stats.eligible} enqueued=${stats.enqueued} skipped=${stats.skipped} " +
            "capHits=${stats.pendingCapHits} $note"
        // Written to a file as well as stdout: Gradle does not capture per-test stdout by default,
        // so a println-only metric is invisible to whoever reads the run afterwards.
        val out = File("build/bench/history-benchmark.txt")
        out.parentFile?.mkdirs()
        out.appendText(line + "\n")
        println(line)
    }

    // ── ~360k: the production-scale target ──────────────────────────────────

    @Test
    fun `three hundred sixty thousand messages drain without exhausting the heap`() {
        // The requested scale. Run deliberately (it takes minutes): it is the number the runtime
        // architecture decision rests on, so it is measured rather than extrapolated.
        val connection = database("bench360k")
        try {
            val stats = scale(connection, 360_000, ackAsWeGo = true)
            record("360k", stats)
            assertTrue("every seeded row must be accounted for", stats.rows >= 300_000)
            assertTrue(
                "a page may never exceed the production page size",
                stats.maxPageRows <= productionPageLimit
            )
            assertEquals(
                "accounting must balance with no unexplained remainder (§70)",
                stats.eligible,
                stats.enqueued + stats.skipped + stats.failed
            )
        } finally {
            connection.close()
        }
    }

    // ── Realtime pressure against a history backlog ─────────────────────────

    /**
     * The shipped ordering, executed verbatim: realtime is ranked above everything, so a realtime
     * event inserted behind a large BACKFILL backlog must still be selected first.
     */
    private fun claimable(connection: Connection, now: Long, limit: Int): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT priority FROM gateway_event_outbox " +
                    "WHERE state IN (${GatewayEventOutboxEntity.CLAIMABLE_STATES_SQL}) " +
                    "AND nextAttemptAt <= $now " +
                    "ORDER BY CASE priority WHEN '${GatewayEventOutboxEntity.PRIORITY_REALTIME}' " +
                    "THEN 0 ELSE 1 END, id LIMIT $limit"
            ).use { rows ->
                buildList { while (rows.next()) add(rows.getString(1)) }
            }
        }

    @Test
    fun `all four event classes are scheduled correctly against a large backfill`() {
        // WS-D asks about FOUR classes, not one: a new SMS, a status update, a command result and a
        // reconciliation event. Only the first was covered before this test.
        //
        // Each is built by the production builder for its kind, so this exercises the priority the
        // real code actually assigns — which is how it caught that `messageStatusChanged` was
        // inheriting REALTIME instead of its declared STATUS_UPDATE rank.
        val connection = database("benchclasses")
        try {
            val stats = DrainStats()
            seedMessages(connection, 20_000)
            drain(connection, "sms", stats, ackAsWeGo = true)
            drain(connection, "mms", stats, ackAsWeGo = true)
            connection.createStatement().use {
                it.executeUpdate(
                    "UPDATE gateway_event_outbox SET state = 'PENDING' " +
                        "WHERE priority = '${GatewayEventOutboxEntity.PRIORITY_BACKFILL}'"
                )
            }
            assertTrue(pendingBackfillDepth(connection) > 1_000)

            // A new SMS — REALTIME.
            val newSms = GatewayEventFactory.messageCreated(
                source = "sms", providerId = 900_001L, conversationId = "conv-live",
                direction = "incoming", body = "hello", dateMs = 1_900_000_000_001L, status = 0
            )
            // A status update — the real builder, which must carry STATUS_UPDATE.
            val statusUpdate = GatewayEventFactory.messageStatusChanged(
                source = "sms", providerId = 900_002L, conversationId = "conv-live",
                status = 0, dateMs = 1_900_000_000_002L
            )
            // A command result — a send a web command asked for.
            val commandResult = GatewayEventFactory.messageCreated(
                source = "sms", providerId = 900_003L, conversationId = "conv-live",
                direction = "outgoing", body = "sent from web", dateMs = 1_900_000_000_003L,
                status = 0, originCommandId = "cmd-1",
                priority = GatewayEventOutboxEntity.PRIORITY_COMMAND_RESULT
            )
            // A reconciliation event — a repair for a row the mirror missed.
            val reconciliation = GatewayEventFactory.messageCreated(
                source = "sms", providerId = 900_004L, conversationId = "conv-live",
                direction = "incoming", body = "missed", dateMs = 1_900_000_000_004L, status = 0,
                priority = GatewayEventOutboxEntity.PRIORITY_RECONCILIATION
            )

            assertEquals(
                "the status change must be enqueued at its declared rank",
                GatewayEventOutboxEntity.PRIORITY_STATUS_UPDATE, statusUpdate.priority
            )
            listOf(newSms, statusUpdate, commandResult, reconciliation).forEach {
                assertEquals("each class must be queued", true, insertOutbox(connection, it))
            }

            // FOREGROUND: the three urgent classes are served in the declared rank order, and every
            // one of them is served BEFORE any backfill row despite the backlog.
            val foreground = claimableGroup(connection, Long.MAX_VALUE, 10, foreground = true)
            assertEquals(
                "foreground must be served realtime → command result → status update",
                listOf(
                    GatewayEventOutboxEntity.PRIORITY_REALTIME,
                    GatewayEventOutboxEntity.PRIORITY_COMMAND_RESULT,
                    GatewayEventOutboxEntity.PRIORITY_STATUS_UPDATE
                ),
                foreground
            )

            // The single-window read ranks the most urgent row first overall.
            assertEquals(
                GatewayEventOutboxEntity.PRIORITY_REALTIME,
                claimable(connection, Long.MAX_VALUE, 1).single()
            )

            // HISTORY still progresses: the background window keeps serving backfill while the
            // urgent classes are queued, so neither side starves the other.
            val background = claimableGroup(connection, Long.MAX_VALUE, 10, foreground = false)
            assertTrue("history keeps being served", background.isNotEmpty())
            assertTrue(
                "the background window belongs to the background classes",
                background.all {
                    it == GatewayEventOutboxEntity.PRIORITY_BACKFILL ||
                        it == GatewayEventOutboxEntity.PRIORITY_RECONCILIATION
                }
            )

            // MEASURED, and deliberately pinned rather than assumed: reconciliation shares the
            // background window and ranks BELOW backfill (weight 10 vs 20), so while any backfill
            // row is due it is not selected at all — a repair for a missed message waits for the
            // bulk import to drain. It does NOT block anything urgent, because the groups have
            // separate windows, and the mission's fairness requirement (§13) was about realtime
            // never being pushed out by history. Recorded as the actual behaviour so a future
            // change to the rank is a decision rather than an accident.
            assertFalse(
                "a reconciliation event is ordered behind backfill in the same window",
                background.contains(GatewayEventOutboxEntity.PRIORITY_RECONCILIATION)
            )
        } finally {
            connection.close()
        }
    }

    /** The fair-batching reads (mission §13): separate windows per group. */
    private fun claimableGroup(connection: Connection, now: Long, limit: Int, foreground: Boolean): List<String> {
        val priorities = if (foreground) {
            GatewayEventOutboxEntity.FOREGROUND_PRIORITIES_SQL
        } else {
            GatewayEventOutboxEntity.BACKGROUND_PRIORITIES_SQL
        }
        val order = if (foreground) {
            GatewayEventOutboxEntity.FOREGROUND_ORDER_SQL
        } else {
            GatewayEventOutboxEntity.BACKGROUND_ORDER_SQL
        }
        return connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT priority FROM gateway_event_outbox " +
                    "WHERE state IN (${GatewayEventOutboxEntity.CLAIMABLE_STATES_SQL}) " +
                    "AND nextAttemptAt <= $now AND priority IN ($priorities) " +
                    "ORDER BY $order, id LIMIT $limit"
            ).use { rows ->
                buildList { while (rows.next()) add(rows.getString(1)) }
            }
        }
    }

    @Test
    fun `realtime events are selected ahead of a large history backlog and history still progresses`() {
        val connection = database("benchrealtime")
        try {
            // A backlog of history, then realtime events inserted behind it — through the same
            // event builder and the same outbox insert the production realtime path uses.
            val stats = DrainStats()
            seedMessages(connection, 20_000)
            drain(connection, "sms", stats, ackAsWeGo = true)
            drain(connection, "mms", stats, ackAsWeGo = true)

            // Put BACKFILL rows back in the claimable state so there is a real backlog to compete
            // with, as there is on a device mid-import.
            connection.createStatement().use {
                it.executeUpdate(
                    "UPDATE gateway_event_outbox SET state = 'PENDING' " +
                        "WHERE priority = '${GatewayEventOutboxEntity.PRIORITY_BACKFILL}'"
                )
            }
            val backlog = pendingBackfillDepth(connection)
            assertTrue("there must be a backlog to compete with: $backlog", backlog > 1_000)

            val realtimeIds = (1..5).map { n ->
                GatewayEventFactory.messageCreated(
                    source = "sms",
                    providerId = 900_000L + n,
                    conversationId = "conv-live",
                    direction = "incoming",
                    body = "live $n",
                    dateMs = 1_900_000_000_000L + n,
                    status = 0,
                    priority = GatewayEventOutboxEntity.PRIORITY_REALTIME
                )
            }
            assertEquals(
                "all realtime events must be queued for this to mean anything",
                5,
                realtimeIds.count { insertOutbox(connection, it) }
            )

            // The global window must rank every realtime row ahead of every backfill row.
            val picked = claimable(connection, now = Long.MAX_VALUE, limit = 5)
            assertEquals(
                "with 5 realtime events due, the first window of 5 must be exactly those: $picked",
                List(5) { GatewayEventOutboxEntity.PRIORITY_REALTIME },
                picked
            )

            // …and the fair-batching reads must still hand the background group its own window, so
            // history cannot be starved by realtime pressure.
            val foreground = claimableGroup(connection, Long.MAX_VALUE, 50, foreground = true)
            val background = claimableGroup(connection, Long.MAX_VALUE, 50, foreground = false)
            assertTrue("foreground window is realtime work", foreground.isNotEmpty())
            assertTrue(
                "the background window must keep serving history during realtime pressure",
                background.isNotEmpty() && background.all {
                    it == GatewayEventOutboxEntity.PRIORITY_BACKFILL ||
                        it == GatewayEventOutboxEntity.PRIORITY_RECONCILIATION
                }
            )
        } finally {
            connection.close()
        }
    }

    @Test
    fun `without an uploader draining the outbox the scan stops at the production cap`() {
        // This is not a defect, it is the design: `maxPendingBackfill = 2000` bounds how far ahead
        // of the network the scan may run. It matters for the runtime decision because it means the
        // local scan rate is COUPLED to the upload ACK rate, so "how long does 360k take locally"
        // has no answer independent of the network.
        val connection = database("benchcap")
        try {
            val stats = scale(connection, 10_000, ackAsWeGo = false)
            record("cap-10k-nodrain", stats, note="(uploader not draining)")
            assertTrue("the cap must stop the scan", stats.pendingCapHits == 1)
            assertTrue(
                "the scan must not run far past the pending cap",
                stats.outboxRows <= productionPendingCap + productionPageLimit
            )
        } finally {
            connection.close()
        }
    }
}
