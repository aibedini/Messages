package com.autonomousone.messages

import com.autonomousone.messages.data.COUNT_STARRED_IN_THREAD_SQL
import com.autonomousone.messages.data.DELETE_ORPHANS_SQL
import com.autonomousone.messages.data.DELETE_TRASHED_SNAPSHOT_USER_STATE_SQL
import com.autonomousone.messages.data.MessageCutoff
import com.autonomousone.messages.data.SET_KEEP_FROM_OTP_CLEANUP_SQL
import com.autonomousone.messages.data.SET_STARRED_SQL
import com.autonomousone.messages.data.STARRED_PAGE_IN_THREAD_SQL
import com.autonomousone.messages.data.STARRED_PAGE_SQL
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement

/**
 * The starred-message INVARIANTS of v3.4.0 FEATURE 7, executed as SQL.
 *
 * WHY THIS TEST RUNS SQL INSTEAD OF MOCKING ROOM
 * ----------------------------------------------
 * Every invariant that matters here is a property of the STATEMENT, not of Kotlin
 * code:
 *
 *  1. a star must SURVIVE a provider refresh — i.e. re-upserting the `messages`
 *     row must not clear `message_user_state.starred`;
 *  2. SMS `_id` 100 and MMS `_id` 100 must star INDEPENDENTLY (composite key);
 *  3. star/unstar must patch ONLY its own columns, so the separate
 *     `keepFromOtpCleanup` opt-out (and the star itself) survives either way;
 *  4. user state disappears ONLY through the explicit orphan path — never as a
 *     side effect of a refresh, and never for a row the provider still has;
 *  5. a starred message inside a TRASHED conversation is not in Starred.
 *
 * The tables come from Room's OWN generated `16.json` schema (the same source
 * `MigrationToV16SqlTest` pins the migration against), and the statements come
 * from the production constants the DAO literals are pinned to by
 * `UxSqlLiteralDriftTest` — so the SQL exercised here is the SQL that ships.
 */
class MessageUserStateSqlTest {

    private val schemaDir = "schemas/com.autonomousone.messages.data.MessagesDatabase"

    private fun readSchema(version: Int): JSONObject {
        val file = File("$schemaDir/$version.json")
        if (!file.exists()) {
            fail("Schema $version.json not found at ${file.absolutePath} — run :app:kspDebugKotlin")
        }
        return JSONObject(file.readText())
    }

    /** A v16-shaped in-memory database: the REAL generated CREATE statements. */
    private fun database(): Connection {
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        val entities = readSchema(16).getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            val tableSql = entity.getString("createSql").replace("\${TABLE_NAME}", table)
            // The FTS virtual table and its shadow tables are irrelevant to user
            // state and are not created by sqlite-jdbc here.
            if (!tableSql.startsWith("CREATE TABLE IF NOT EXISTS")) continue
            connection.createStatement().use { it.execute(tableSql) }
            val indices = entity.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                val indexSql = indices.getJSONObject(j)
                    .getString("createSql")
                    .replace("\${TABLE_NAME}", table)
                connection.createStatement().use { it.execute(indexSql) }
            }
        }
        return connection
    }

    // ── Statement helpers ───────────────────────────────────────────────────

    /** Replaces `:name` placeholders with `?` in first-appearance order. */
    private fun bind(
        connection: Connection,
        sql: String,
        vararg params: Any
    ): PreparedStatement {
        val names = Regex(":(\\w+)").findAll(sql).map { it.groupValues[1] }.distinct().toList()
        check(names.size == params.size) {
            "statement needs ${names.size} parameters ($names) but got ${params.size}"
        }
        val positional = sql.replace(Regex(":(\\w+)")) { "?" }
        val statement = connection.prepareStatement(positional)
        params.forEachIndexed { index, value ->
            when (value) {
                is Long -> statement.setLong(index + 1, value)
                is Int -> statement.setInt(index + 1, value)
                is Boolean -> statement.setInt(index + 1, if (value) 1 else 0)
                else -> statement.setString(index + 1, value.toString())
            }
        }
        return statement
    }

    private fun Connection.exec(sql: String, vararg params: Any): Int =
        bind(this, sql, *params).use { it.executeUpdate() }

    private fun Connection.scalar(sql: String, vararg params: Any): Long =
        bind(this, sql, *params).use { statement ->
            statement.executeQuery().use { rows ->
                assertTrue("query returned no row: $sql", rows.next())
                rows.getLong(1)
            }
        }

    private fun Connection.rows(sql: String, vararg params: Any): List<String> =
        bind(this, sql, *params).use { statement ->
            statement.executeQuery().use { rows ->
                val out = mutableListOf<String>()
                while (rows.next()) out.add("${rows.getString("source")}:${rows.getLong("providerId")}")
                out
            }
        }

    private fun Connection.insertMessage(
        source: String,
        providerId: Long,
        threadId: Long = 7L,
        body: String = "hello",
        date: Long = 1_700_000_000_000L
    ) {
        exec(
            """
            INSERT INTO `messages`
                (`source`,`providerId`,`threadId`,`normalizedAddress`,`rawAddress`,
                 `body`,`date`,`type`,`status`,`dateSent`,`read`,`syncState`)
            VALUES (?, ?, ?, '09120000000', '+989120000000', ?, ?, 1, -1, 0, 1, 'synced')
            """.trimIndent(),
            source, providerId, threadId, body, date
        )
    }

    private fun Connection.insertPreference(threadId: Long) {
        exec(
            """
            INSERT INTO `conversation_preferences`
                (`threadId`,`manualUnread`,`mutedUntil`,`customNotificationChannel`,
                 `categoryOverride`,`spam`,`spamReportedAt`,`spamBlockedByReport`,`updatedAt`)
            VALUES (?, 0, 0, 0, NULL, 0, 0, 0, 0)
            """.trimIndent(),
            threadId
        )
    }

    // ── 1. Star / unstar persistence ────────────────────────────────────────

    @Test
    fun `star and unstar persist through the shipped statement`() {
        val db = database()
        try {
            db.insertMessage("sms", 100L)

            db.exec(SET_STARRED_SQL, "sms", 100L, 7L, true, 42L, 42L)
            assertEquals(1L, db.scalar("SELECT COUNT(*) FROM message_user_state WHERE starred = 1"))

            val starredAt = db.scalar(
                "SELECT starredAt FROM message_user_state WHERE source = 'sms' AND providerId = 100"
            )
            assertEquals(42L, starredAt)

            db.exec(SET_STARRED_SQL, "sms", 100L, 7L, false, 0L, 99L)
            assertEquals(0L, db.scalar("SELECT COUNT(*) FROM message_user_state WHERE starred = 1"))
            assertEquals(
                "unstar clears starredAt as well",
                0L,
                db.scalar("SELECT starredAt FROM message_user_state WHERE source = 'sms' AND providerId = 100")
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `unstar patches only its own columns`() {
        val db = database()
        try {
            db.insertMessage("sms", 100L)
            // The user ALSO decided this OTP must never be auto-cleaned.
            db.exec(SET_KEEP_FROM_OTP_CLEANUP_SQL, "sms", 100L, 7L, true, 1L)
            db.exec(SET_STARRED_SQL, "sms", 100L, 7L, true, 2L, 2L)

            db.exec(SET_STARRED_SQL, "sms", 100L, 7L, false, 0L, 3L)

            assertEquals(
                "an unstar must never clear the OTP-cleanup opt-out",
                1L,
                db.scalar(
                    "SELECT keepFromOtpCleanup FROM message_user_state " +
                        "WHERE source = 'sms' AND providerId = 100"
                )
            )
            assertEquals(0L, db.scalar("SELECT trashedAt FROM message_user_state WHERE providerId = 100"))
            assertEquals(0L, db.scalar("SELECT purgeAt FROM message_user_state WHERE providerId = 100"))
        } finally {
            db.close()
        }
    }

    // ── 2. SMS 100 vs MMS 100 ───────────────────────────────────────────────

    @Test
    fun `sms 100 and mms 100 star independently`() {
        val db = database()
        try {
            db.insertMessage("sms", 100L, date = 1_000L)
            db.insertMessage("mms", 100L, date = 2_000L)

            db.exec(SET_STARRED_SQL, "sms", 100L, 7L, true, 10L, 10L)

            assertEquals(1L, db.scalar("SELECT COUNT(*) FROM message_user_state"))
            assertEquals(
                1L,
                db.scalar("SELECT COUNT(*) FROM message_user_state WHERE source = 'sms' AND starred = 1")
            )
            assertEquals(
                0L,
                db.scalar("SELECT COUNT(*) FROM message_user_state WHERE source = 'mms'")
            )

            db.exec(SET_STARRED_SQL, "mms", 100L, 7L, true, 11L, 11L)
            assertEquals(2L, db.scalar("SELECT COUNT(*) FROM message_user_state WHERE starred = 1"))

            // Unstarring the SMS must not touch the MMS row with the same id.
            db.exec(SET_STARRED_SQL, "sms", 100L, 7L, false, 0L, 12L)
            assertEquals(1L, db.scalar("SELECT COUNT(*) FROM message_user_state WHERE starred = 1"))
            assertEquals(
                1L,
                db.scalar("SELECT starred FROM message_user_state WHERE source = 'mms' AND providerId = 100")
            )
        } finally {
            db.close()
        }
    }

    // ── 3. Provider refresh ─────────────────────────────────────────────────

    @Test
    fun `a star survives a provider refresh that re-upserts the message`() {
        val db = database()
        try {
            db.insertMessage("sms", 100L, body = "first")
            db.exec(SET_STARRED_SQL, "sms", 100L, 7L, true, 42L, 42L)

            // A refresh that UPDATES the row in place (the common path).
            db.exec("UPDATE `messages` SET `body` = 'edited' WHERE source = 'sms' AND providerId = 100")

            // A refresh that DELETES and re-inserts the row (the repair path that
            // would have destroyed a flag stored on MessageEntity itself).
            db.exec("DELETE FROM `messages` WHERE source = 'sms' AND providerId = 100")
            db.insertMessage("sms", 100L, body = "re-inserted")

            assertEquals(
                "user-owned metadata must never be cleared by provider sync",
                1L,
                db.scalar("SELECT starred FROM message_user_state WHERE source = 'sms' AND providerId = 100")
            )
            assertEquals(42L, db.scalar("SELECT starredAt FROM message_user_state WHERE providerId = 100"))
        } finally {
            db.close()
        }
    }

    // ── 4. Orphan cleanup is explicit ───────────────────────────────────────

    @Test
    fun `deleteOrphans removes only identities the provider no longer has`() {
        val db = database()
        try {
            db.insertMessage("sms", 100L)
            db.insertMessage("mms", 100L)
            db.exec(SET_STARRED_SQL, "sms", 100L, 7L, true, 1L, 1L)
            db.exec(SET_STARRED_SQL, "mms", 100L, 7L, true, 1L, 1L)

            // The provider PROVABLY lost the MMS row.
            db.exec("DELETE FROM `messages` WHERE source = 'mms' AND providerId = 100")

            val removed = db.exec(DELETE_ORPHANS_SQL)
            assertEquals(1, removed)
            assertEquals(1L, db.scalar("SELECT COUNT(*) FROM message_user_state"))
            assertEquals(
                "the surviving row keeps its star",
                1L,
                db.scalar("SELECT starred FROM message_user_state WHERE source = 'sms'")
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `a plain refresh never runs the orphan path`() {
        val db = database()
        try {
            db.insertMessage("sms", 100L)
            db.exec(SET_STARRED_SQL, "sms", 100L, 7L, true, 1L, 1L)

            // Re-upserting the SAME identity is not a proven absence.
            db.insertMessage("sms", 100L, body = "updated")
            assertEquals(0, db.exec(DELETE_ORPHANS_SQL))
            assertEquals(1L, db.scalar("SELECT COUNT(*) FROM message_user_state"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `a purged trash snapshot takes its user state and only its own`() {
        val db = database()
        try {
            // Two messages in the snapshot, one NEWER than the cutoff.
            db.insertMessage("sms", 1L, threadId = 7L, date = 1_000L)
            db.insertMessage("sms", 2L, threadId = 7L, date = 2_000L)
            db.insertMessage("sms", 3L, threadId = 7L, date = 3_000L)
            db.exec(SET_STARRED_SQL, "sms", 1L, 7L, true, 1L, 1L)
            db.exec(SET_STARRED_SQL, "sms", 2L, 7L, true, 1L, 1L)
            db.exec(SET_STARRED_SQL, "sms", 3L, 7L, true, 1L, 1L)

            // Conversation trashed with the cutoff at message 2.
            db.exec(
                """
                INSERT INTO trashed_threads
                    (threadId, deletedAt, purgeAt, cutoffDate, cutoffSource, cutoffProviderId)
                VALUES (7, 10, 20, 2000, 'sms', 2)
                """.trimIndent()
            )

            // The purge flow runs this BEFORE the messages rows are removed.
            val removed = db.exec(DELETE_TRASHED_SNAPSHOT_USER_STATE_SQL, 7L)
            assertEquals(2, removed)
            assertEquals(
                "a message NEWER than the cutoff keeps its star",
                1L,
                db.scalar(
                    "SELECT COUNT(*) FROM message_user_state WHERE providerId = 3 AND starred = 1"
                )
            )
            assertEquals(1L, db.scalar("SELECT COUNT(*) FROM message_user_state"))

            // The explicit orphan path remains the ONLY other cleanup, and it is
            // not what removed the snapshot state above.
            assertEquals(0, db.exec(DELETE_ORPHANS_SQL))
            assertEquals(1L, db.scalar("SELECT COUNT(*) FROM message_user_state"))
        } finally {
            db.close()
        }
    }

    // ── 5. The starred readers ──────────────────────────────────────────────
    @Test
    fun `starredPage is newest first and excludes a trashed row`() {
        val db = database()
        try {
            db.insertMessage("sms", 1L, date = 1_000L)
            db.insertMessage("sms", 2L, date = 2_000L)
            db.insertMessage("sms", 3L, date = 3_000L)
            db.exec(SET_STARRED_SQL, "sms", 1L, 7L, true, 1L, 1L)
            db.exec(SET_STARRED_SQL, "sms", 2L, 7L, true, 1L, 1L)
            db.exec(SET_STARRED_SQL, "sms", 3L, 7L, true, 1L, 1L)

            // The user individually trashed message 3.
            db.exec(
                """
                INSERT INTO message_user_state
                    (source, providerId, threadId, starred, starredAt, trashedAt, purgeAt,
                     keepFromOtpCleanup, updatedAt)
                VALUES ('sms', 3, 7, 1, 1, 5000, 0, 0, 5000)
                """.trimIndent()
            )

            val ordered = db.rows(STARRED_PAGE_SQL, 10, 0)
            assertEquals(listOf("sms:2", "sms:1"), ordered)
        } finally {
            db.close()
        }
    }

    @Test
    fun `starredPage excludes rows hidden by a conversation tombstone`() {
        val db = database()
        try {
            db.insertMessage("sms", 1L, threadId = 7L, date = 1_000L)
            db.insertMessage("sms", 2L, threadId = 8L, date = 2_000L)
            db.exec(SET_STARRED_SQL, "sms", 1L, 7L, true, 1L, 1L)
            db.exec(SET_STARRED_SQL, "sms", 2L, 8L, true, 1L, 1L)

            // Conversation 7 was moved to trash; its snapshot cutoff is row 1.
            db.exec(
                """
                INSERT INTO trashed_threads
                    (threadId, deletedAt, purgeAt, cutoffDate, cutoffSource, cutoffProviderId)
                VALUES (7, 10, 20, 1000, 'sms', 1)
                """.trimIndent()
            )

            assertEquals(listOf("sms:2"), db.rows(STARRED_PAGE_SQL, 10, 0))
            // LIMIT/OFFSET paging still walks the visible set only.
            assertTrue(db.rows(STARRED_PAGE_SQL, 10, 1).isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun `starredPageInThread is pinned to one conversation`() {
        val db = database()
        try {
            db.insertMessage("sms", 1L, threadId = 7L, date = 1_000L)
            db.insertMessage("sms", 2L, threadId = 8L, date = 2_000L)
            db.exec(SET_STARRED_SQL, "sms", 1L, 7L, true, 1L, 1L)
            db.exec(SET_STARRED_SQL, "sms", 2L, 8L, true, 1L, 1L)

            assertEquals(listOf("sms:1"), db.rows(STARRED_PAGE_IN_THREAD_SQL, 7L, 10, 0))
            assertEquals(listOf("sms:2"), db.rows(STARRED_PAGE_IN_THREAD_SQL, 8L, 10, 0))
        } finally {
            db.close()
        }
    }

    @Test
    fun `a starred OTP is exempt from cleanup eligibility`() {
        val db = database()
        try {
            db.insertMessage("sms", 100L)
            db.exec(SET_STARRED_SQL, "sms", 100L, 7L, true, 1L, 1L)

            // The cleanup candidate query the OTP worker uses: an eligible OTP that
            // is neither starred nor explicitly kept.
            val candidateSql =
                "SELECT c.source FROM message_classification c " +
                    "WHERE c.otpDeleteEligibleAt > 0 AND c.otpDeleteEligibleAt <= :now " +
                    "AND NOT EXISTS (" +
                    "SELECT 1 FROM message_user_state us " +
                    "WHERE us.source = c.source AND us.providerId = c.providerId " +
                    "AND (us.starred = 1 OR us.keepFromOtpCleanup = 1))"

            db.exec(
                """
                INSERT INTO message_classification
                    (source, providerId, threadId, category, confidence, isOtp,
                     otpDeleteEligibleAt, classifiedAt)
                VALUES ('sms', 100, 7, 'OTP', 0.9, 1, 500, 100)
                """.trimIndent()
            )

            assertEquals(
                "a starred OTP must never be a cleanup candidate",
                0L,
                db.scalar(candidateSql, 1_000L)
            )

            // Unstarring it makes it eligible again — the star IS the protection.
            db.exec(SET_STARRED_SQL, "sms", 100L, 7L, false, 0L, 2L)
            assertEquals(1L, db.scalar(candidateSql, 1_000L))
        } finally {
            db.close()
        }
    }

    // ── 6. Counts and the shared ACTIVE-UI predicate ────────────────────────

    @Test
    fun `count starred in thread counts only that thread`() {
        val db = database()
        try {
            db.insertMessage("sms", 1L, threadId = 7L)
            db.insertMessage("sms", 2L, threadId = 7L)
            db.insertMessage("sms", 3L, threadId = 8L)
            db.exec(SET_STARRED_SQL, "sms", 1L, 7L, true, 1L, 1L)
            db.exec(SET_STARRED_SQL, "sms", 2L, 7L, true, 1L, 1L)
            db.exec(SET_STARRED_SQL, "sms", 3L, 8L, true, 1L, 1L)

            assertEquals(2L, db.scalar(COUNT_STARRED_IN_THREAD_SQL, 7L))
            assertEquals(1L, db.scalar(COUNT_STARRED_IN_THREAD_SQL, 8L))
            assertEquals(0L, db.scalar(COUNT_STARRED_IN_THREAD_SQL, 9L))
        } finally {
            db.close()
        }
    }

    @Test
    fun `both starred statements carry the shared ACTIVE-UI predicate`() {
        // The predicate is never re-typed: it comes from MessageCutoff, and the DAO
        // literals expand the very same string (pinned by UxSqlLiteralDriftTest).
        assertTrue(STARRED_PAGE_SQL.contains(MessageCutoff.NOT_INDIVIDUALLY_TRASHED_SQL))
        assertTrue(STARRED_PAGE_SQL.contains(MessageCutoff.NOT_HIDDEN_BY_TOMBSTONE_SQL))
        assertTrue(STARRED_PAGE_IN_THREAD_SQL.contains(MessageCutoff.ACTIVE_MESSAGE_FILTER_SQL))
        assertTrue(STARRED_PAGE_SQL.contains("ORDER BY m.date DESC, m.source DESC, m.providerId DESC"))
        assertTrue(STARRED_PAGE_IN_THREAD_SQL.contains("us.threadId = :threadId"))
    }

    @Test
    fun `the user-state tables exist in the generated v16 schema`() {
        val entities = readSchema(16).getJSONObject("database").getJSONArray("entities")
        val tables = (0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }
        assertTrue(tables.contains("message_user_state"))
        assertTrue(tables.contains("conversation_preferences"))
        assertTrue(tables.contains("trashed_threads"))
        assertTrue(tables.contains("message_classification"))
        assertFalse("star state must not live on the synced messages row", tables.contains("starred_messages"))
    }

    @Test
    fun `a stale preference row can never be read as a star`() {
        val db = database()
        try {
            db.insertMessage("sms", 100L)
            db.insertPreference(7L)
            assertEquals(0L, db.scalar(COUNT_STARRED_IN_THREAD_SQL, 7L))
            assertTrue(db.rows(STARRED_PAGE_SQL, 10, 0).isEmpty())
        } finally {
            db.close()
        }
    }
}
