package com.autonomousone.messages

import com.autonomousone.messages.data.COUNT_ACTIVE_UNREAD_SQL
import com.autonomousone.messages.data.DELETE_TRASHED_SNAPSHOT_SQL
import com.autonomousone.messages.data.DELETE_TRASHED_SNAPSHOT_USER_STATE_SQL
import com.autonomousone.messages.data.MessageCutoff
import com.autonomousone.messages.data.NEWEST_ACTIVE_FOR_THREAD_SQL
import com.autonomousone.messages.data.NEWEST_ACTIVE_PER_THREAD_SQL
import com.autonomousone.messages.data.UNREAD_ACTIVE_COUNTS_BY_THREAD_SQL
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * The TRASH SQL contract (v3.4.0 FEATURE 8), executed against a REAL in-memory
 * SQLite database whose schema comes from Room's own generated JSON.
 *
 * The tested text is the text the DAOs run (`data/TrashSql.kt`), so this is not
 * a re-typed copy of the predicates: if the ACTIVE-UI query, the tombstone
 * predicate or the purge range drifts, these tests fail.
 *
 * The invariants:
 *  - the ACTIVE-UI projection hides the deleted snapshot and ROLLS BACK to the
 *    newest visible message (or to nothing at all);
 *  - a genuinely NEW message after the cutoff becomes the projection and
 *    re-creates the conversation, while the old history stays hidden;
 *  - the RAW mirror keeps every row (sync/integrity must still see everything);
 *  - a permanent purge removes ONLY the snapshot — never a newer message, and
 *    never a same-date row from the other source.
 */
class TrashSqlTest {

    private val schemaDir = "schemas/com.autonomousone.messages.data.MessagesDatabase"
    private lateinit var db: Connection

    /** The newest schema Room generated, whatever version the tree is on. */
    private fun schemaFile(): File {
        val dir = File(schemaDir)
        val newest = dir.listFiles { file -> file.name.endsWith(".json") }
            ?.maxByOrNull { it.nameWithoutExtension.toIntOrNull() ?: 0 }
        if (newest == null) {
            fail("No Room schema JSON under ${dir.absolutePath} — run :app:kspDebugKotlin")
        }
        return newest!!
    }

    /** CREATE TABLE (+ indices) exactly as Room generated them. */
    private fun createTables(vararg tables: String) {
        val entities = JSONObject(schemaFile().readText())
            .getJSONObject("database")
            .getJSONArray("entities")
        var created = 0
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            if (table !in tables) continue
            db.createStatement().use {
                it.execute(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            }
            created++
            val indices = entity.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                val index = indices.getJSONObject(j)
                db.createStatement().use {
                    it.execute(index.getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
        }
        assertEquals("all requested tables exist in the generated schema", tables.size, created)
    }

    @Before
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        createTables("messages", "trashed_threads", "message_user_state")
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── Seeding + helpers ──────────────────────────────────────────────────

    private fun message(
        source: String,
        providerId: Long,
        threadId: Long,
        date: Long,
        body: String = "m-$source-$providerId",
        read: Int = 1
    ) {
        db.createStatement().use { statement ->
            statement.execute(
                """
                INSERT INTO `messages`
                    (`source`,`providerId`,`threadId`,`normalizedAddress`,`rawAddress`,
                     `body`,`date`,`type`,`status`,`dateSent`,`read`,`syncState`)
                VALUES
                    ('$source', $providerId, $threadId, '09120000000', '+989120000000',
                     '$body', $date, 1, -1, 0, $read, 'synced')
                """.trimIndent()
            )
        }
    }

    private fun tombstone(
        threadId: Long,
        cutoffDate: Long,
        cutoffSource: String,
        cutoffProviderId: Long
    ) {
        db.createStatement().use { statement ->
            statement.execute(
                """
                INSERT INTO `trashed_threads`
                    (`threadId`,`deletedAt`,`purgeAt`,`cutoffDate`,`cutoffSource`,`cutoffProviderId`)
                VALUES ($threadId, 1000, 2592001000, $cutoffDate, '$cutoffSource', $cutoffProviderId)
                """.trimIndent()
            )
        }
    }

    private fun userState(source: String, providerId: Long, threadId: Long, starred: Int, trashedAt: Long) {
        db.createStatement().use { statement ->
            statement.execute(
                """
                INSERT INTO `message_user_state`
                    (`source`,`providerId`,`threadId`,`starred`,`starredAt`,`trashedAt`,`purgeAt`,
                     `keepFromOtpCleanup`,`updatedAt`)
                VALUES ('$source', $providerId, $threadId, $starred, 0, $trashedAt, 0, 0, 0)
                """.trimIndent()
            )
        }
    }

    /** Runs a pinned constant, substituting its `:threadId` bind parameter. */
    private fun exec(sql: String, threadId: Long = 7L): Int =
        db.createStatement().use { it.executeUpdate(sql.replace(":threadId", threadId.toString())) }

    private fun rows(sql: String, threadId: Long = 7L): List<String> {
        val out = mutableListOf<String>()
        db.createStatement().use { statement ->
            statement.executeQuery(sql.replace(":threadId", threadId.toString())).use { result ->
                while (result.next()) {
                    out += "${result.getString("source")}:${result.getLong("providerId")}"
                }
            }
        }
        return out
    }

    private fun scalar(sql: String): Long =
        db.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                result.next()
                result.getLong(1)
            }
        }

    private fun count(table: String): Long = scalar("SELECT COUNT(*) FROM `$table`")

    /** The ACTIVE-UI window of one thread — what the conversation actually shows. */
    private fun activeWindow(threadId: Long, limit: Int = 50): List<String> {
        val sql = "SELECT m.* FROM messages m WHERE m.threadId = :threadId AND " +
            MessageCutoff.ACTIVE_MESSAGE_FILTER_SQL +
            " ORDER BY m.date DESC, m.source DESC, m.providerId DESC LIMIT $limit"
        return rows(sql, threadId)
    }

    // ── ACTIVE UI ──────────────────────────────────────────────────────────

    @Test
    fun `a trashed conversation disappears from the active projection`() {
        message("sms", 100, 7, 1_000)
        message("sms", 101, 7, 2_000)
        tombstone(7, cutoffDate = 2_000, cutoffSource = "sms", cutoffProviderId = 101)

        assertEquals(
            "no ACTIVE message is left, so the conversation must leave Home",
            emptyList<String>(),
            rows(NEWEST_ACTIVE_FOR_THREAD_SQL)
        )
        assertEquals(emptyList<String>(), activeWindow(7))
        assertEquals("the RAW mirror keeps every row (sync/integrity need it)", 2, count("messages"))
    }

    @Test
    fun `a new message after the cutoff becomes the projection and re-creates the conversation`() {
        message("sms", 100, 7, 1_000)
        message("sms", 101, 7, 2_000)
        tombstone(7, cutoffDate = 2_000, cutoffSource = "sms", cutoffProviderId = 101)
        message("sms", 102, 7, 3_000, body = "brand new")

        assertEquals(
            listOf("sms:102"),
            rows(NEWEST_ACTIVE_FOR_THREAD_SQL)
        )
        assertEquals(
            "the whole deleted history stays hidden while the new message shows",
            listOf("sms:102"),
            activeWindow(7)
        )
    }

    @Test
    fun `a newer message that is individually trashed is not the projection either`() {
        message("sms", 100, 7, 1_000)
        message("sms", 101, 7, 3_000)
        tombstone(7, cutoffDate = 2_000, cutoffSource = "sms", cutoffProviderId = 100)
        userState("sms", 101, 7, starred = 0, trashedAt = 5_000)

        assertEquals(
            "the tombstone hides 100 and the trashed state hides 101",
            emptyList<String>(),
            rows(NEWEST_ACTIVE_FOR_THREAD_SQL)
        )
    }

    @Test
    fun `the projection rolls back to the newest ACTIVE message when the newest is deleted`() {
        message("sms", 200, 8, 1_000)
        message("sms", 201, 8, 2_000)
        userState("sms", 201, 8, starred = 0, trashedAt = 5_000)

        assertEquals(
            "Home must move BACKWARDS to the surviving visible message",
            listOf("sms:200"),
            rows(NEWEST_ACTIVE_FOR_THREAD_SQL, threadId = 8)
        )
        assertEquals(listOf("sms:200"), activeWindow(8))
    }

    @Test
    fun `the unread count only counts messages the user can see`() {
        message("sms", 100, 7, 1_000, read = 0)
        message("sms", 101, 7, 2_000, read = 0)
        tombstone(7, cutoffDate = 2_000, cutoffSource = "sms", cutoffProviderId = 101)
        message("sms", 102, 7, 3_000, read = 0)

        db.createStatement().use { statement ->
            statement.executeQuery(COUNT_ACTIVE_UNREAD_SQL.replace(":threadId", "7")).use { result ->
                result.next()
                assertEquals("only the message after the cutoff is visible → 1 unread", 1, result.getInt(1))
            }
        }
    }

    @Test
    fun `the recovery rebuild lists only threads with an active message`() {
        message("sms", 100, 7, 1_000)
        message("sms", 101, 7, 2_000)
        tombstone(7, cutoffDate = 2_000, cutoffSource = "sms", cutoffProviderId = 101)
        message("sms", 102, 7, 3_000, read = 0)
        message("sms", 300, 9, 1_000)
        tombstone(9, cutoffDate = 1_000, cutoffSource = "sms", cutoffProviderId = 300)
        message("sms", 400, 10, 1_000)

        val newest = rows(NEWEST_ACTIVE_PER_THREAD_SQL)
        assertTrue("the revived conversation lists its new message", "sms:102" in newest)
        assertTrue("an untouched conversation still lists", "sms:400" in newest)
        assertTrue(
            "a fully trashed conversation must NOT be projected (or sync would resurrect it)",
            newest.none { it == "sms:300" }
        )

        val unread = db.createStatement().use { statement ->
            val out = mutableListOf<String>()
            statement.executeQuery(UNREAD_ACTIVE_COUNTS_BY_THREAD_SQL).use { result ->
                while (result.next()) out += "${result.getLong("threadId")}=${result.getInt("unreadCount")}"
            }
            out
        }
        assertEquals(listOf("7=1"), unread)
    }

    // ── Purge range ────────────────────────────────────────────────────────

    @Test
    fun `a purge removes exactly the snapshot and never a newer message`() {
        message("sms", 100, 7, 1_000)
        message("sms", 101, 7, 2_000)
        message("sms", 102, 7, 3_000, body = "arrived after the delete")
        message("mms", 200, 7, 2_000, body = "same millisecond, other source")
        tombstone(7, cutoffDate = 2_000, cutoffSource = "sms", cutoffProviderId = 101)

        val deleted = exec(DELETE_TRASHED_SNAPSHOT_SQL, threadId = 7)

        assertEquals("the snapshot was 100 and 101", 2, deleted)
        assertEquals(
            listOf("sms:102", "mms:200"),
            rows("SELECT * FROM messages WHERE threadId = :threadId ORDER BY date DESC, source DESC, providerId DESC")
        )
    }

    @Test
    fun `a purge with an mms cutoff keeps a same-second sms and newer mms rows`() {
        message("mms", 100, 7, 1_000)
        message("mms", 199, 7, 2_000)
        message("mms", 200, 7, 2_000)
        message("mms", 201, 7, 2_000)
        message("sms", 150, 7, 2_000)
        message("sms", 100, 7, 1_000)
        tombstone(7, cutoffDate = 2_000, cutoffSource = "mms", cutoffProviderId = 200)

        val deleted = exec(DELETE_TRASHED_SNAPSHOT_SQL, threadId = 7)

        assertEquals("mms 100, 199, the cutoff row 200 and sms 100 go; nothing else", 4, deleted)
        assertEquals(
            // Canonical order is date DESC, source DESC, providerId DESC — and
            // 'sms' > 'mms' lexicographically, so the surviving SMS sorts FIRST at
            // an equal timestamp. (The live-database test above pins the same rule.)
            listOf("sms:150", "mms:201"),
            rows("SELECT * FROM messages WHERE threadId = :threadId ORDER BY date DESC, source DESC, providerId DESC")
        )
    }

    @Test
    fun `a purge never leaves the thread`() {
        // NOTE ON IDENTITY: `messages` is keyed (source, providerId) — the SAME
        // provider id cannot exist twice in the table, in any two threads, because
        // the provider's _id is unique per provider table. Two threads are therefore
        // seeded with DIFFERENT ids (this used to insert sms 100 twice, which the
        // primary key correctly rejects before the assertion under test could run).
        message("sms", 100, 7, 1_000)
        message("sms", 101, 8, 1_000)
        tombstone(7, cutoffDate = 1_000, cutoffSource = "sms", cutoffProviderId = 100)

        assertEquals(1, exec(DELETE_TRASHED_SNAPSHOT_SQL, threadId = 7))
        assertEquals("another thread's messages survive", 1, count("messages"))
    }

    @Test
    fun `a purge fails closed when the tombstone is gone`() {
        message("sms", 100, 7, 1_000)
        // No tombstone row: the statement's JOIN finds nothing, so a purge can
        // never delete by accident (the caller deletes the tombstone only after).
        assertEquals(0, exec(DELETE_TRASHED_SNAPSHOT_SQL, threadId = 7))
        assertEquals(1, count("messages"))
    }

    // ── User state cleanup ─────────────────────────────────────────────────

    @Test
    fun `purging a snapshot removes only that snapshot's user state`() {
        message("sms", 100, 7, 1_000)
        message("sms", 101, 7, 2_000)
        message("sms", 102, 7, 3_000)
        userState("sms", 100, 7, starred = 1, trashedAt = 0)
        userState("sms", 101, 7, starred = 0, trashedAt = 5_000)
        userState("sms", 102, 7, starred = 1, trashedAt = 0)
        tombstone(7, cutoffDate = 2_000, cutoffSource = "sms", cutoffProviderId = 101)

        // Order matters: the user-state statement joins `messages`, so it must run
        // BEFORE the message rows are removed.
        val userStateDeleted = exec(DELETE_TRASHED_SNAPSHOT_USER_STATE_SQL, threadId = 7)
        exec(DELETE_TRASHED_SNAPSHOT_SQL, threadId = 7)

        assertEquals("the snapshot's two rows of user state are cleaned", 2, userStateDeleted)
        assertEquals(
            "a NEWER message keeps its star",
            1,
            scalar("SELECT COUNT(*) FROM message_user_state WHERE providerId = 102 AND starred = 1")
        )
        assertEquals(1, count("message_user_state"))
    }

    @Test
    fun `user state cleanup after the message rows are gone removes nothing`() {
        message("sms", 100, 7, 1_000)
        userState("sms", 100, 7, starred = 1, trashedAt = 0)
        tombstone(7, cutoffDate = 1_000, cutoffSource = "sms", cutoffProviderId = 100)

        exec(DELETE_TRASHED_SNAPSHOT_SQL, threadId = 7)
        // Documented ordering: this is the WRONG order on purpose.
        assertEquals(0, exec(DELETE_TRASHED_SNAPSHOT_USER_STATE_SQL, threadId = 7))
        assertEquals(
            "the orphan row is left for the explicit orphan cleanup, never silently reused",
            1,
            count("message_user_state")
        )
    }
}
