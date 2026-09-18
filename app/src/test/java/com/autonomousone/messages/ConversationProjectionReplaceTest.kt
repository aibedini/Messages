package com.autonomousone.messages

import java.sql.Connection
import java.sql.SQLException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The conversation projection INSERT contract.
 *
 * Two independent facts are pinned down here, both against a real SQLite
 * engine and the SQL copied verbatim from Daos.kt:
 *
 * 1. NOT NULL WITHOUT DEFAULT. The shipped v13 schema declares
 *        `pinned` INTEGER NOT NULL
 *        `archived` INTEGER NOT NULL
 *    with no SQL default (see 13.json). A projection write that omits them
 *    does not "keep the defaults" — it fails the whole statement with
 *    "NOT NULL constraint failed: conversations.pinned". This test's DDL
 *    therefore must NOT add DEFAULT 0: an earlier revision of this file did,
 *    and that single line hid the bug from the test suite entirely.
 *
 * 2. MONOTONIC vs AUTHORITATIVE. upsertPreservingFlags is MONOTONIC:
 *        lastMessageDate = MAX(excluded.lastMessageDate, conversations.lastMessageDate)
 *    Correct for the realtime insert fast path (a new message may only advance
 *    a conversation) and WRONG for a rebuild: deleting the newest message must
 *    roll the conversation BACKWARDS.
 *
 *      projection = C @ 12:00 ; delete C ; remaining newest = B @ 11:00
 *      MAX(11:00, 12:00) keeps 12:00 and the deleted snippet stays on Home.
 *
 *    replaceProjectionPreservingFlags writes the projected fields
 *    unconditionally while still never touching pinned/archived.
 *
 * Both statements are only ever allowed to set pinned/archived on INSERT; the
 * ON CONFLICT branch never mentions them, so user-owned state (pin, archive)
 * survives every subsequent write — including a brand-new message arriving on
 * a pinned thread and a full rebuild.
 *
 * WRITTEN BUT NOT EXECUTED. Gradle is frozen for this branch.
 */
class ConversationProjectionReplaceTest {

    private lateinit var db: Connection

    private val monotonicUpsert = """
        INSERT INTO conversations (
            threadId, normalizedAddress, rawAddress, snippet, lastMessageDate, unreadCount,
            lastMessageType, pinned, archived
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(threadId) DO UPDATE SET
            normalizedAddress = excluded.normalizedAddress,
            rawAddress = excluded.rawAddress,
            snippet = CASE
                WHEN excluded.lastMessageDate >= conversations.lastMessageDate
                    THEN excluded.snippet
                ELSE conversations.snippet END,
            lastMessageDate = MAX(excluded.lastMessageDate, conversations.lastMessageDate),
            lastMessageType = CASE
                WHEN excluded.lastMessageDate >= conversations.lastMessageDate
                    THEN excluded.lastMessageType
                ELSE conversations.lastMessageType END,
            unreadCount = excluded.unreadCount
    """.trimIndent()

    private val authoritativeReplace = """
        INSERT INTO conversations (
            threadId, normalizedAddress, rawAddress, snippet, lastMessageDate, unreadCount,
            lastMessageType, pinned, archived
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(threadId) DO UPDATE SET
            normalizedAddress = excluded.normalizedAddress,
            rawAddress = excluded.rawAddress,
            snippet = excluded.snippet,
            lastMessageDate = excluded.lastMessageDate,
            lastMessageType = excluded.lastMessageType,
            unreadCount = excluded.unreadCount
    """.trimIndent()

    /**
     * The pre-fix statement, kept ONLY as the negative control. It is what both
     * production queries looked like, and it is unable to insert a row at all.
     */
    private val legacyInsertOmittingFlags = """
        INSERT INTO conversations (
            threadId, normalizedAddress, rawAddress, snippet, lastMessageDate, unreadCount,
            lastMessageType
        )
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(threadId) DO UPDATE SET
            normalizedAddress = excluded.normalizedAddress,
            rawAddress = excluded.rawAddress,
            snippet = excluded.snippet,
            lastMessageDate = excluded.lastMessageDate,
            lastMessageType = excluded.lastMessageType,
            unreadCount = excluded.unreadCount
    """.trimIndent()

    @Before
    fun setUp() {
        db = rawDb()
        // Byte-for-byte the shipped v13 column list: pinned/archived are
        // NOT NULL with NO default. Do not add DEFAULT here.
        db.exec(
            "CREATE TABLE IF NOT EXISTS `conversations` (" +
                "`threadId` INTEGER NOT NULL, " +
                "`normalizedAddress` TEXT NOT NULL, " +
                "`rawAddress` TEXT NOT NULL, " +
                "`snippet` TEXT NOT NULL, " +
                "`lastMessageDate` INTEGER NOT NULL, " +
                "`unreadCount` INTEGER NOT NULL, " +
                "`lastMessageType` INTEGER NOT NULL, " +
                "`pinned` INTEGER NOT NULL, " +
                "`archived` INTEGER NOT NULL, " +
                "PRIMARY KEY(`threadId`))"
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun write(
        sql: String,
        threadId: Long,
        snippet: String,
        date: Long,
        unread: Int = 0,
        pinned: Int = 0,
        archived: Int = 0
    ) {
        db.prepareStatement(sql).use { st ->
            st.setLong(1, threadId)
            st.setString(2, "+98912")
            st.setString(3, "+98912")
            st.setString(4, snippet)
            st.setLong(5, date)
            st.setInt(6, unread)
            st.setInt(7, 1)
            st.setInt(8, pinned)
            st.setInt(9, archived)
            st.executeUpdate()
        }
    }

    private fun string(column: String): String =
        db.scalarString("SELECT `" + column + "` FROM conversations WHERE threadId = 1") ?: ""

    private fun long(column: String): Long = db.queryLong(
        "SELECT `" + column + "` FROM conversations WHERE threadId = 1"
    )

    private fun rowCount(): Long = db.queryLong("SELECT COUNT(*) FROM conversations")

    @Test
    fun `the monotonic upsert cannot roll a conversation backwards`() {
        write(monotonicUpsert, 1, "C", 12_000L)
        write(monotonicUpsert, 1, "B", 11_000L)

        // Documents the bug this test exists for: the deleted message's snippet
        // and timestamp survive.
        assertEquals(12_000L, long("lastMessageDate"))
        assertEquals("C", string("snippet"))
    }

    @Test
    fun `the authoritative replace rolls the conversation back after a delete`() {
        write(authoritativeReplace, 1, "C", 12_000L)
        write(authoritativeReplace, 1, "B", 11_000L)

        assertEquals("newest remaining message wins", 11_000L, long("lastMessageDate"))
        assertEquals("the deleted snippet is gone", "B", string("snippet"))
    }

    @Test
    fun `the authoritative replace still preserves pinned and archived`() {
        write(authoritativeReplace, 1, "C", 12_000L)
        db.exec("UPDATE conversations SET pinned = 1, archived = 1 WHERE threadId = 1")

        write(authoritativeReplace, 1, "B", 11_000L)

        assertEquals(1L, long("pinned"))
        assertEquals(1L, long("archived"))
        assertEquals(11_000L, long("lastMessageDate"))
    }

    @Test
    fun `the authoritative replace inserts a brand new thread`() {
        write(authoritativeReplace, 1, "A", 10_000L)
        assertEquals(1L, rowCount())
        assertEquals(10_000L, long("lastMessageDate"))
    }

    @Test
    fun `the authoritative replace can also advance a conversation`() {
        write(authoritativeReplace, 1, "A", 10_000L)
        write(authoritativeReplace, 1, "B", 11_000L)
        assertEquals(11_000L, long("lastMessageDate"))
        assertEquals("B", string("snippet"))
    }

    // ── The NOT NULL regression guards ────────────────────────────────────

    @Test
    fun `the shipped schema has no default for pinned and archived`() {
        // If this ever stops holding, the two tests below stop being meaningful.
        val ddl = db.scalarString(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'conversations'"
        ) ?: ""
        assertTrue("pinned must be NOT NULL", ddl.contains("`pinned` INTEGER NOT NULL"))
        assertTrue("archived must be NOT NULL", ddl.contains("`archived` INTEGER NOT NULL"))
        assertTrue("pinned must have no default", !ddl.contains("`pinned` INTEGER NOT NULL DEFAULT"))
        assertTrue("archived must have no default", !ddl.contains("`archived` INTEGER NOT NULL DEFAULT"))
    }

    @Test
    fun `omitting pinned and archived fails to insert a new conversation`() {
        // The production symptom, reproduced at the storage layer: the realtime
        // path could not materialize a brand-new conversation, so an incoming
        // SMS left Home showing the previous state.
        try {
            write(legacyInsertOmittingFlags, 1, "A", 10_000L)
            fail("expected the INSERT to violate NOT NULL")
        } catch (expected: SQLException) {
            val message = expected.message ?: ""
            assertTrue(
                "expected a NOT NULL violation, got: " + message,
                message.contains("NOT NULL constraint failed")
            )
            assertTrue(
                "expected pinned to be named, got: " + message,
                message.contains("pinned")
            )
        }
        assertEquals("no partial row may be written", 0L, rowCount())
    }

    @Test
    fun `a brand new thread is inserted with the caller supplied flags`() {
        // The realtime path resolves flags from the row when it exists and from
        // the pin/archive repositories only on a genuine first insert.
        write(monotonicUpsert, 1, "A", 10_000L, pinned = 1, archived = 1)
        assertEquals(1L, rowCount())
        assertEquals(1L, long("pinned"))
        assertEquals(1L, long("archived"))

        write(authoritativeReplace, 2, "B", 10_000L)
        assertEquals(0L, db.queryLong("SELECT `pinned` FROM conversations WHERE threadId = 2"))
        assertEquals(0L, db.queryLong("SELECT `archived` FROM conversations WHERE threadId = 2"))
    }

    @Test
    fun `an incoming message never clears a pinned or archived conversation`() {
        write(monotonicUpsert, 1, "A", 10_000L, pinned = 1, archived = 1)
        // A later realtime write for the same thread carries the *row's* flags,
        // but even a caller that passed 0/0 must not be able to unset them.
        write(monotonicUpsert, 1, "B", 11_000L, pinned = 0, archived = 0)

        assertEquals(1L, long("pinned"))
        assertEquals(1L, long("archived"))
        assertEquals("B", string("snippet"))
    }

    @Test
    fun `a rebuild never clears a pinned or archived conversation`() {
        write(authoritativeReplace, 1, "A", 10_000L, pinned = 1, archived = 1)
        write(authoritativeReplace, 1, "B", 11_000L, pinned = 0, archived = 0)

        assertEquals(1L, long("pinned"))
        assertEquals(1L, long("archived"))
        assertEquals("B", string("snippet"))
    }

    @Test
    fun `both statements insert a thread that has never been seen before`() {
        write(monotonicUpsert, 1, "A", 10_000L)
        write(authoritativeReplace, 2, "B", 11_000L)
        assertEquals(2L, rowCount())
        assertEquals(10_000L, db.queryLong("SELECT lastMessageDate FROM conversations WHERE threadId = 1"))
        assertEquals(11_000L, db.queryLong("SELECT lastMessageDate FROM conversations WHERE threadId = 2"))
    }
}
