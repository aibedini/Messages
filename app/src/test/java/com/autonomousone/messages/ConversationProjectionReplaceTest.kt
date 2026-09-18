package com.autonomousone.messages

import java.sql.Connection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Last-message fallback after a delete.
 *
 * ConversationDao.upsertPreservingFlags is MONOTONIC by design:
 *     lastMessageDate = MAX(excluded.lastMessageDate, conversations.lastMessageDate)
 * That is correct for the realtime insert fast path (a new message may only
 * advance a conversation) and WRONG for a rebuild: deleting the newest message
 * must roll the conversation BACKWARDS.
 *
 *   projection = C @ 12:00 ; delete C ; remaining newest = B @ 11:00
 *   MAX(11:00, 12:00) keeps 12:00 and the deleted snippet stays on Home.
 *
 * replaceProjectionPreservingFlags (added for every rebuild path) writes the
 * projected fields unconditionally while still never touching the user-owned
 * pinned/archived flags.
 *
 * The two UPDATE statements below are copied verbatim from Daos.kt so this test
 * exercises the shipped SQL, against a real SQLite engine.
 *
 * WRITTEN BUT NOT EXECUTED.
 */
class ConversationProjectionReplaceTest {

    private lateinit var db: Connection

    private val monotonicUpsert = """
        INSERT INTO conversations (
            threadId, normalizedAddress, rawAddress, snippet, lastMessageDate, unreadCount,
            lastMessageType
        )
        VALUES (?, ?, ?, ?, ?, ?, ?)
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
        db.exec(
            "CREATE TABLE IF NOT EXISTS `conversations` (" +
                "`threadId` INTEGER NOT NULL, " +
                "`normalizedAddress` TEXT NOT NULL, " +
                "`rawAddress` TEXT NOT NULL, " +
                "`snippet` TEXT NOT NULL, " +
                "`lastMessageDate` INTEGER NOT NULL, " +
                "`unreadCount` INTEGER NOT NULL, " +
                "`lastMessageType` INTEGER NOT NULL, " +
                "`pinned` INTEGER NOT NULL DEFAULT 0, " +
                "`archived` INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(`threadId`))"
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun write(sql: String, threadId: Long, snippet: String, date: Long, unread: Int = 0) {
        db.prepareStatement(sql).use { st ->
            st.setLong(1, threadId)
            st.setString(2, "+98912")
            st.setString(3, "+98912")
            st.setString(4, snippet)
            st.setLong(5, date)
            st.setInt(6, unread)
            st.setInt(7, 1)
            st.executeUpdate()
        }
    }

    private fun string(column: String): String =
        db.scalarString("SELECT `" + column + "` FROM conversations WHERE threadId = 1") ?: ""

    private fun long(column: String): Long = db.queryLong(
        "SELECT `" + column + "` FROM conversations WHERE threadId = 1"
    )

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
        assertEquals(1L, db.queryLong("SELECT COUNT(*) FROM conversations"))
        assertEquals(10_000L, long("lastMessageDate"))
    }

    @Test
    fun `the authoritative replace can also advance a conversation`() {
        write(authoritativeReplace, 1, "A", 10_000L)
        write(authoritativeReplace, 1, "B", 11_000L)
        assertEquals(11_000L, long("lastMessageDate"))
        assertEquals("B", string("snippet"))
    }
}
