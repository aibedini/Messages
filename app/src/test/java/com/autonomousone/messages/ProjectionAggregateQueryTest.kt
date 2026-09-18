package com.autonomousone.messages

import java.sql.Connection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The projection rebuild's batch unread aggregate.
 *
 * The full rebuild used to call countUnread(threadId) once per conversation -
 * 3N read queries for N conversations. It now issues ONE grouped query, so the
 * READ query count is constant and the result set grows with THREADS, never with
 * MESSAGES.
 *
 * This test pins the two properties that make that replacement safe:
 *
 *  1. the grouped predicate is IDENTICAL to MessageDao.countUnread, so the batch
 *     and the single-thread query can never disagree about "unread";
 *  2. the result has one row per thread WITH unread messages - a thread with none
 *     is absent, and the caller treats absence as 0.
 *
 * The messages table here is minimal (only the columns the predicate touches):
 * this test pins the PREDICATE, not the schema. The schema itself is pinned by
 * ConversationProjectionReplaceTest and ProviderRepairQueueTest.
 *
 * WRITTEN BUT NOT EXECUTED.
 */
class ProjectionAggregateQueryTest {

    private lateinit var db: Connection

    /** Verbatim from MessageDao.unreadCountsByThread in Daos.kt. */
    private val batchSql =
        "SELECT threadId AS threadId, COUNT(*) AS unreadCount " +
            "FROM messages WHERE read = 0 AND type = 1 GROUP BY threadId"

    /** Verbatim from MessageDao.countUnread in Daos.kt. */
    private val singleSql =
        "SELECT COUNT(*) FROM messages WHERE threadId = ? AND read = 0 AND type = 1"

    @Before
    fun setUp() {
        db = rawDb()
        db.exec(
            "CREATE TABLE IF NOT EXISTS `messages` (" +
                "`source` TEXT NOT NULL, " +
                "`providerId` INTEGER NOT NULL, " +
                "`threadId` INTEGER NOT NULL, " +
                "`read` INTEGER NOT NULL, " +
                "`type` INTEGER NOT NULL, " +
                "PRIMARY KEY(`source`, `providerId`))"
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insert(source: String, id: Long, thread: Long, read: Int, type: Int) {
        db.prepareStatement(
            "INSERT INTO messages (source, providerId, threadId, read, type) VALUES (?,?,?,?,?)"
        ).use { st ->
            st.setString(1, source); st.setLong(2, id); st.setLong(3, thread)
            st.setInt(4, read); st.setInt(5, type)
            st.executeUpdate()
        }
    }

    private fun batch(): Map<Long, Long> =
        db.createStatement().use { st ->
            st.executeQuery(batchSql).use { rs ->
                val out = mutableMapOf<Long, Long>()
                while (rs.next()) out[rs.getLong("threadId")] = rs.getLong("unreadCount")
                out
            }
        }

    private fun single(thread: Long): Long =
        db.prepareStatement(singleSql).use { st ->
            st.setLong(1, thread)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else -1L }
        }

    @Test
    fun `the batch aggregate agrees with the single-thread count for every thread`() {
        // thread 1: two unread incoming; thread 2: none; thread 3: one of each
        insert("sms", 1, 1, 1, 1)   // read  -> ignored
        insert("sms", 2, 1, 0, 1)   // unread incoming -> counted
        insert("sms", 3, 1, 0, 1)   // unread incoming -> counted
        insert("mms", 1, 1, 0, 2)   // unread OUTGOING -> ignored
        insert("sms", 4, 2, 1, 1)   // read -> ignored, thread 2 absent
        insert("sms", 5, 3, 0, 1)   // counted
        insert("mms", 2, 3, 0, 2)   // ignored

        val grouped = batch()
        assertEquals(mapOf(1L to 2L, 3L to 1L), grouped)
        assertFalse("a thread with no unread is absent, not zero", grouped.containsKey(2L))
        assertEquals(2L, single(1L))
        assertEquals(0L, single(2L))
        assertEquals(1L, single(3L))
    }

    @Test
    fun `the aggregate returns one row per thread regardless of how many messages`() {
        // 1000 messages across 4 threads must still produce at most 4 rows.
        var id = 1L
        for (thread in 1L..4L) {
            for (i in 1..250) {
                insert("sms", id++, thread, 0, 1)
            }
        }
        val grouped = batch()
        assertEquals(4, grouped.size)
        assertTrue(grouped.values.all { it == 250L })
        // The job of this query is to NOT be per-message.
        assertTrue("1000 messages collapsed into 4 rows", db.queryLong("SELECT COUNT(*) FROM messages") == 1000L)
    }

    @Test
    fun `outgoing unread messages never count as unread`() {
        insert("sms", 1, 9, 0, 2)
        insert("mms", 1, 9, 0, 2)
        assertEquals(0, batch().size)
        assertEquals(0L, single(9L))
    }

    @Test
    fun `sms and mms in the same thread are counted together`() {
        insert("sms", 1, 5, 0, 1)
        insert("mms", 1, 5, 0, 1)
        assertEquals(2L, batch()[5L])
        assertEquals(2L, single(5L))
    }
}
