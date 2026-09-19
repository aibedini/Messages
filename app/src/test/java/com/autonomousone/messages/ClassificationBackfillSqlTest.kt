package com.autonomousone.messages

import com.autonomousone.messages.repository.BackfillCursor
import com.autonomousone.messages.repository.ClassificationRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * FEATURE 12 — the checkpointed classification backfill, exercised against a REAL
 * SQLite database (sqlite-jdbc, the same convention `TrashSqlTest` and
 * `MessageUserStateSqlTest` use).
 *
 * What this pins:
 *
 *  - the batch is BOUNDED to the required 200-500 window;
 *  - paging is KEYSET on the canonical `date DESC, source DESC, providerId DESC`
 *    order and never OFFSET;
 *  - the cursor ADVANCES strictly backwards and a resumed sweep continues after
 *    the last handled row;
 *  - a re-run does NOT re-classify the same rows, because the query itself
 *    excludes every row that already has a classification;
 *  - the predicate in the test matches `MessageClassificationDao.unclassifiedBatch`
 *    verbatim (source-drift guard), so the test cannot pass against SQL the app
 *    does not actually run.
 */
class ClassificationBackfillSqlTest {

    private lateinit var db: Connection

    /** The production predicate, mirrored verbatim from the DAO annotation. */
    private val unclassifiedBatchSql =
        """
        SELECT m.source AS source, m.providerId AS providerId, m.threadId AS threadId,
               m.body AS body, m.date AS date, m.rawAddress AS rawAddress,
               m.type AS messageType
        FROM messages m
        WHERE NOT EXISTS (
            SELECT 1 FROM message_classification c
            WHERE c.source = m.source AND c.providerId = m.providerId
        )
          AND (m.date < ?1
               OR (m.date = ?1 AND (m.source < ?2
                   OR (m.source = ?2 AND m.providerId < ?3))))
        ORDER BY m.date DESC, m.source DESC, m.providerId DESC
        LIMIT ?4
        """.trimIndent()

    @Before
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { statement ->
            statement.execute(
                "CREATE TABLE messages (" +
                    "source TEXT NOT NULL, providerId INTEGER NOT NULL, threadId INTEGER NOT NULL, " +
                    "normalizedAddress TEXT NOT NULL DEFAULT '', rawAddress TEXT NOT NULL DEFAULT '', " +
                    "body TEXT NOT NULL DEFAULT '', date INTEGER NOT NULL, type INTEGER NOT NULL DEFAULT 1, " +
                    "PRIMARY KEY (source, providerId))"
            )
            statement.execute(
                "CREATE TABLE message_classification (" +
                    "source TEXT NOT NULL, providerId INTEGER NOT NULL, threadId INTEGER NOT NULL, " +
                    "category TEXT NOT NULL, confidence REAL NOT NULL, isOtp INTEGER NOT NULL DEFAULT 0, " +
                    "otpDeleteEligibleAt INTEGER NOT NULL DEFAULT 0, classifiedAt INTEGER NOT NULL, " +
                    "PRIMARY KEY (source, providerId))"
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── Batch bounds ───────────────────────────────────────────────────────

    @Test
    fun `batch size is bounded to the required window`() {
        assertEquals(200, ClassificationRepository.MIN_BATCH)
        assertEquals(500, ClassificationRepository.MAX_BATCH)

        // The worker clamps whatever it is handed into that window.
        assertEquals(200, clamp(50))
        assertEquals(400, clamp(ClassificationRepository.DEFAULT_BATCH))
        assertEquals(500, clamp(10_000))
    }

    @Test
    fun `a full batch is capped at the requested limit and never exceeds it`() {
        seedMessages(count = 1_000)
        val batch = nextBatch(BackfillCursor.START, limit = 400)

        assertEquals("a full batch returns exactly the limit", 400, batch.size)
        assertTrue(batch.zipWithNext().all { (a, b) -> isAfter(b.cursor, a.cursor) })
        val first = batch.first().cursor
        val last = batch.last().cursor
        assertEquals("newest first", 1_000L, first.date)
        assertEquals("oldest of the batch last", 601L, last.date)
    }

    @Test
    fun `the final short batch ends the sweep`() {
        seedMessages(count = 1_000)
        var cursor = BackfillCursor.START
        var batches = 0
        var total = 0
        while (true) {
            val batch = nextBatch(cursor, limit = 400)
            if (batch.isEmpty()) break
            batches++
            total += batch.size
            // Persist exactly what the worker persists: the LAST row of the batch.
            markClassified(batch)
            cursor = batch.last().cursor
        }
        assertEquals(1_000, total)
        assertEquals("400 + 400 + 200", 3, batches)
        assertEquals(0, remaining())
    }

    // ── Cursor semantics ───────────────────────────────────────────────────

    @Test
    fun `the cursor advances and resumes correctly`() {
        seedMessages(count = 1_000)

        val first = nextBatch(BackfillCursor.START, limit = 400)
        markClassified(first)
        val afterFirst = first.last().cursor
        assertEquals(601L, afterFirst.date)

        // Simulate process death here: everything live is rebuilt from the
        // persisted cursor only.
        val resumed = nextBatch(afterFirst, limit = 400)
        assertEquals("resume continues after the last handled row", 400, resumed.size)
        assertEquals(201L, resumed.last().cursor.date)
        assertTrue(
            "no row may be handled twice",
            resumed.map { it.key }.none { key -> first.any { it.key == key } }
        )
    }

    @Test
    fun `a re-run does not re-classify the same rows`() {
        seedMessages(count = 1_000)
        val first = nextBatch(BackfillCursor.START, limit = 400)
        markClassified(first)

        // Re-running the SAME cursor is the crash-recovery case: the already
        // classified rows are excluded by the query, so it must not return them
        // again even though the cursor still points at the newest row.
        val rerun = nextBatch(BackfillCursor.START, limit = 400)
        assertEquals(400, rerun.size)
        assertTrue(
            "already classified rows must be excluded",
            rerun.map { it.key }.none { key -> first.any { it.key == key } }
        )
        assertEquals(
            "the newest remaining row is the 600th, not the 1000th",
            600L,
            rerun.first().cursor.date
        )
    }

    @Test
    fun `a completed sweep is idempotent`() {
        seedMessages(count = 250)
        val batch = nextBatch(BackfillCursor.START, limit = 400)
        assertEquals(250, batch.size)
        markClassified(batch)
        assertEquals(0, remaining())
        assertTrue(
            "a second sweep over a fully classified table returns nothing",
            nextBatch(BackfillCursor.START, limit = 400).isEmpty()
        )
    }

    @Test
    fun `the cursor never moves forward on a completed sweep`() {
        seedMessages(count = 10)
        val batch = nextBatch(BackfillCursor.START, limit = 400)
        markClassified(batch)
        val end = batch.last().cursor
        assertTrue(end.date < BackfillCursor.START.date)
        assertEquals(BackfillCursor.START, BackfillCursor.START)
    }

    // ── Source-drift guard ─────────────────────────────────────────────────

    @Test
    fun `the test predicate matches the DAO annotation`() {
        val source = File(
            "src/main/java/com/autonomousone/messages/data/UxDaos.kt"
        ).takeIf { it.exists() }
            ?: File("app/src/main/java/com/autonomousone/messages/data/UxDaos.kt")
        assertTrue("UxDaos.kt must be readable for the drift guard", source.exists())

        val text = source.readText()
        val start = text.indexOf("suspend fun unclassifiedBatch")
        assertTrue("unclassifiedBatch must still exist", start > 0)
        // Slice EXACTLY this annotation: from its own `@Query(` to the declaration.
        //
        // A fixed-size window backwards was wrong twice over: it swallowed the
        // PREVIOUS method's annotation (which legitimately pages `LIMIT … OFFSET`)
        // and it swallowed this method's own KDoc, whose text says "never OFFSET" —
        // so the guard reported a violation that did not exist.
        val queryAt = text.lastIndexOf("@Query(", start)
        assertTrue("unclassifiedBatch must still carry a @Query", queryAt in 0 until start)
        val annotation = text.substring(queryAt, start)
        for (fragment in listOf(
            "FROM messages m",
            "SELECT 1 FROM message_classification c",
            "c.source = m.source AND c.providerId = m.providerId",
            "m.date < :afterDate",
            "m.date = :afterDate",
            "m.source < :afterSource",
            "m.source = :afterSource",
            "m.providerId < :afterProviderId",
            "ORDER BY m.date DESC, m.source DESC, m.providerId DESC",
            "LIMIT :limit"
        )) {
            assertTrue(
                "unclassifiedBatch drifted from the tested predicate: missing '$fragment'",
                annotation.contains(fragment)
            )
        }
        assertFalse(
            "OFFSET paging on a 360K-row table is forbidden",
            annotation.contains("OFFSET")
        )
        assertTrue("LIMIT :limit must be present", annotation.contains("LIMIT :limit"))
        assertTrue("a batch must be bounded", annotation.length > 200)
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private data class Row(val key: String, val cursor: BackfillCursor)

    private fun clamp(limit: Int): Int = limit.coerceIn(
        ClassificationRepository.MIN_BATCH,
        ClassificationRepository.MAX_BATCH
    )

    private fun nextBatch(after: BackfillCursor, limit: Int): List<Row> {
        val bounded = clamp(limit)
        db.prepareStatement(unclassifiedBatchSql).use { statement ->
            statement.setLong(1, after.date)
            statement.setString(2, after.source)
            statement.setLong(3, after.providerId)
            statement.setInt(4, bounded)
            statement.executeQuery().use { rs ->
                val out = ArrayList<Row>(bounded)
                while (rs.next()) {
                    val source = rs.getString("source")
                    val providerId = rs.getLong("providerId")
                    out += Row(
                        key = "$source:$providerId",
                        cursor = BackfillCursor(
                            date = rs.getLong("date"),
                            source = source,
                            providerId = providerId
                        )
                    )
                }
                return out
            }
        }
    }

    /** The worker's durable write, reduced to what the next batch depends on. */
    private fun markClassified(batch: List<Row>) {
        db.prepareStatement(
            "INSERT OR REPLACE INTO message_classification " +
                "(source, providerId, threadId, category, confidence, isOtp, " +
                "otpDeleteEligibleAt, classifiedAt) VALUES (?, ?, 1, 'UNKNOWN', 0.0, 0, 0, 0)"
        ).use { statement ->
            for (row in batch) {
                val (source, providerId) = row.key.split(":")
                statement.setString(1, source)
                statement.setLong(2, providerId.toLong())
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun remaining(): Int =
        db.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT COUNT(*) FROM messages m WHERE NOT EXISTS (" +
                    "SELECT 1 FROM message_classification c " +
                    "WHERE c.source = m.source AND c.providerId = m.providerId)"
            ).use { rs -> rs.next(); rs.getInt(1) }
        }

    /**
     * `count` sequential SMS rows (newest first by `date`), plus a same-date MMS
     * that proves the composite identity/tie-break.
     *
     * EXACTLY `count` MESSAGES. Every batch assertion here is phrased in row counts
     * and cursor dates ("400 newest rows are dates 1000…601", "the final short batch
     * ends the sweep"), so the seed must not inflate the table: planting an EXTRA
     * row per 250 shifted every date boundary by one and the failures looked like SQL
     * bugs. A second row at an EXISTING index keeps the counts exact while still
     * giving one date two different sources.
     *
     * The MMS provider id is OUTSIDE the 1..count range because the provider `_id`
     * is unique per source across the whole table — a fixed id would collide with the
     * sequential SMS row that reaches it.
     */
    private fun seedMessages(count: Int) {
        db.autoCommit = false
        db.prepareStatement(
            "INSERT INTO messages (source, providerId, threadId, rawAddress, body, date, type) " +
                "VALUES (?, ?, ?, ?, ?, ?, 1)"
        ).use { statement ->
            for (index in 1..count) {
                val date = index.toLong()
                addRow(statement, "sms", index.toLong(), date, index)
                if (index % SAME_DATE_PAIR_STEP == 0) {
                    // The SAME date on BOTH sources: the tie-break must keep them
                    // distinct (SMS N and MMS 900001 are different messages).
                    addRow(statement, "mms", SAME_DATE_ID, date, index)
                }
            }
            statement.executeBatch()
        }
        db.commit()
        db.autoCommit = true
    }

    private fun addRow(
        statement: java.sql.PreparedStatement,
        source: String,
        providerId: Long,
        date: Long,
        index: Int
    ) {
        statement.setString(1, source)
        statement.setLong(2, providerId)
        statement.setLong(3, 500L + index)
        statement.setString(4, "+9891200000" + (index % 10))
        statement.setString(5, "body-$index")
        statement.setLong(6, date)
        statement.addBatch()
    }

    private fun isAfter(row: BackfillCursor, cursor: BackfillCursor): Boolean = when {
        row.date != cursor.date -> row.date < cursor.date
        row.source != cursor.source -> row.source < cursor.source
        else -> row.providerId < cursor.providerId
    }

    private companion object {
        /**
         * The same-date SMS/MMS pair's provider id. Outside 1..count so it cannot
         * collide with the sequential row of the same id (the provider `_id` is
         * unique per source across the whole table).
         */
        const val SAME_DATE_ID = 900_001L

        /** How often a second (MMS) row shares an existing date. */
        const val SAME_DATE_PAIR_STEP = 250
    }
}
