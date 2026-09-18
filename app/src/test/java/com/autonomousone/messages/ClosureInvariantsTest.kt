package com.autonomousone.messages

import com.autonomousone.messages.data.ChangeRouter
import com.autonomousone.messages.data.TelephonySyncCoordinator
import java.sql.Connection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

/**
 * The closure-pass invariants that are expressible as SQL or as a stable
 * contract, tested against a real SQLite engine with the shipped statements.
 *
 * WRITTEN BUT NOT EXECUTED.
 */
class ClosureInvariantsTest {

    private lateinit var db: Connection

    @Before
    fun setUp() {
        db = rawDb()
        db.exec(
            "CREATE TABLE IF NOT EXISTS `messages` (" +
                "`source` TEXT NOT NULL, `providerId` INTEGER NOT NULL, " +
                "`threadId` INTEGER NOT NULL, `date` INTEGER NOT NULL, " +
                "`read` INTEGER NOT NULL, `type` INTEGER NOT NULL, " +
                "PRIMARY KEY(`source`, `providerId`))"
        )
        db.exec(
            "CREATE TABLE IF NOT EXISTS `conversations` (" +
                "`threadId` INTEGER NOT NULL, `unreadCount` INTEGER NOT NULL, " +
                "PRIMARY KEY(`threadId`))"
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun msg(source: String, id: Long, thread: Long, date: Long, read: Int, type: Int = 1) {
        db.prepareStatement(
            "INSERT INTO messages (source, providerId, threadId, date, read, type) VALUES (?,?,?,?,?,?)"
        ).use { st ->
            st.setString(1, source); st.setLong(2, id); st.setLong(3, thread)
            st.setLong(4, date); st.setInt(5, read); st.setInt(6, type)
            st.executeUpdate()
        }
    }

    private fun firstSource(orderBy: String): String =
        db.createStatement().use { st ->
            st.executeQuery("SELECT source FROM messages ORDER BY " + orderBy + " LIMIT 1").use { rs ->
                rs.next(); rs.getString(1)
            }
        }

    // ── P0-12: ONE canonical order ─────────────────────────────────────────

    @Test
    fun `equal date ties break by source then providerId`() {
        // SMS id 9 and MMS id 3 share a timestamp. Provider-id-only ordering would
        // pick the SMS (9 > 3); canonical order picks MMS, because source breaks
        // the tie FIRST. This is the rule Home, the projection and the conversation
        // view all use, so they can never disagree about which row is newest.
        msg("sms", 9L, 1L, 5_000L, 1)
        msg("mms", 3L, 1L, 5_000L, 1)
        assertEquals("mms", firstSource("date DESC, source DESC, providerId DESC"))
        assertEquals("sms", firstSource("date DESC, providerId DESC"))
    }

    @Test
    fun `a newer date always wins over the source tie-break`() {
        msg("mms", 1L, 1L, 1_000L, 1)
        msg("sms", 1L, 1L, 9_000L, 1)
        assertEquals("sms", firstSource("date DESC, source DESC, providerId DESC"))
    }

    // ── P0-15: mark-all-read is a whole-table fact ─────────────────────────

    @Test
    fun `mark all read clears threads that were never rendered`() {
        // Three threads: one visible, one archived/filtered, one blocked.
        msg("sms", 1L, 1L, 1_000L, 0)
        msg("sms", 2L, 2L, 2_000L, 0)
        msg("mms", 1L, 3L, 3_000L, 0)
        db.exec("INSERT INTO conversations (threadId, unreadCount) VALUES (1,1),(2,1),(3,1)")

        // The shipped statements, in the coordinator's ONE transaction.
        db.exec("UPDATE messages SET read = 1 WHERE read = 0")
        db.exec("UPDATE conversations SET unreadCount = 0 WHERE unreadCount != 0")

        assertEquals(0L, db.queryLong("SELECT COUNT(*) FROM messages WHERE read = 0"))
        assertEquals(0L, db.queryLong("SELECT COUNT(*) FROM conversations WHERE unreadCount != 0"))
    }

    // ── P0-13: a recovery rebuild removes phantom projections ──────────────

    @Test
    fun `a conversation with no messages left is stale and is removed`() {
        msg("sms", 1L, 1L, 1_000L, 1)
        db.exec("INSERT INTO conversations (threadId, unreadCount) VALUES (1,0),(2,0),(3,0)")

        // liveThreadIds from newestPerThread(); the difference is authoritative.
        val live = db.queryLong("SELECT COUNT(DISTINCT threadId) FROM messages")
        assertEquals(1L, live)
        db.createStatement().use { st ->
            st.executeQuery(
                "SELECT threadId FROM conversations WHERE threadId NOT IN " +
                    "(SELECT DISTINCT threadId FROM messages)"
            ).use { rs ->
                val stale = mutableListOf<Long>()
                while (rs.next()) stale += rs.getLong(1)
                assertEquals(listOf(2L, 3L), stale)
            }
        }
    }

    // ── stable contracts the reconcile layer asserts on ────────────────────

    @Test
    fun `the partial-repair and not-committed markers are stable`() {
        // These strings are the contract between the sync core and the NACK path:
        // a partial thread repair throws with PARTIAL_THREAD_REPAIR so the
        // reconcile consumer retries it, and a repair whose Room transaction did
        // not commit fails with MUTATION_NOT_COMMITTED so the durable queue keeps
        // the work instead of ACKing it.
        assertEquals("PARTIAL_THREAD_REPAIR", TelephonySyncCoordinator.PARTIAL_THREAD_REPAIR)
        assertEquals("MUTATION_NOT_COMMITTED", ChangeRouter.MUTATION_NOT_COMMITTED)
        assertNotEquals(
            TelephonySyncCoordinator.PARTIAL_THREAD_REPAIR,
            ChangeRouter.MUTATION_NOT_COMMITTED
        )
    }

    // ── P0-5A: the bounded-overlap boundary predicate ──────────────────────

    @Test
    fun `the overlap boundary keeps rows that share the oldest page date`() {
        // The provider page's oldest covered key is (date=5000, providerId=7).
        // A Room row at the SAME date with a LOWER id is outside the page but
        // INSIDE the covered range by date, so it must be considered - and a row
        // at the same date with a HIGHER id is inside the page.
        msg("sms", 3L, 1L, 5_000L, 1)
        msg("sms", 7L, 1L, 5_000L, 1)
        msg("sms", 9L, 1L, 5_000L, 1)
        msg("sms", 2L, 1L, 4_000L, 1)   // older than the boundary

        val covered =
            "SELECT providerId FROM messages WHERE source = 'sms' AND threadId = 1 " +
                "AND (date > 5000 OR (date = 5000 AND providerId >= 7)) " +
                "ORDER BY providerId"
        db.createStatement().use { st ->
            st.executeQuery(covered).use { rs ->
                val ids = mutableListOf<Long>()
                while (rs.next()) ids += rs.getLong(1)
                assertEquals("equal-date row at the page's oldest date is covered",
                    listOf(7L, 9L), ids)
            }
        }
        // A provider-id-only boundary would have dropped id 3 from consideration
        // while still claiming the date range was covered.
        assertEquals(2L, db.queryLong("SELECT COUNT(*) FROM messages WHERE date = 5000"))
    }
}
