package com.autonomousone.messages

import com.autonomousone.messages.sync.MirrorVerifyCursor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Mission §35: the verification walk's paging query, against real SQLite.
 *
 * The property that matters is a property of the WALK, not of any page: **every row examined exactly
 * once**. A keyset walk has two classic ways to get that wrong and both are invisible in a
 * single-page test — a date-only cursor skips the rest of a date group (silent loss), or it re-reads
 * the group forever (a sweep that never finishes and looks like work). Rows sharing a date are not
 * hypothetical in this table: multi-part SMS and message bursts do it constantly.
 */
class MirrorVerifyPagingSqlTest {

    private val table = "messages"

    /**
     * The SHIPPED predicate and order, taken from the DAO's `pageBefore`.
     *
     * Retyped here because Room runs it, but the shape is asserted against the DAO source below so a
     * change to one without the other fails.
     */
    private val pageSql =
        "SELECT source, providerId, threadId, normalizedAddress, body, date, type, status, read " +
            "FROM `$table` WHERE source = ? " +
            "AND (date < ? OR (date = ? AND providerId < ?)) " +
            "ORDER BY date DESC, providerId DESC LIMIT ?"

    private fun connection(): Connection {
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use {
            it.execute(
                "CREATE TABLE `$table` (`source` TEXT NOT NULL, `providerId` INTEGER NOT NULL, " +
                    "`threadId` INTEGER NOT NULL, `normalizedAddress` TEXT NOT NULL, " +
                    "`body` TEXT NOT NULL, `date` INTEGER NOT NULL, `type` INTEGER NOT NULL, " +
                    "`status` INTEGER NOT NULL, `read` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`source`,`providerId`))"
            )
        }
        return connection
    }

    private fun insert(connection: Connection, source: String, providerId: Long, date: Long) {
        connection.prepareStatement(
            "INSERT INTO `$table` (`source`,`providerId`,`threadId`,`normalizedAddress`,`body`," +
                "`date`,`type`,`status`,`read`) VALUES (?,?,0,'','',?,1,-1,0)"
        ).use {
            it.setString(1, source)
            it.setLong(2, providerId)
            it.setLong(3, date)
            it.execute()
        }
    }

    private fun page(connection: Connection, source: String, cursor: MirrorVerifyCursor, limit: Int) =
        connection.prepareStatement(pageSql).use {
            it.setString(1, source)
            it.setLong(2, cursor.date)
            it.setLong(3, cursor.date)
            it.setLong(4, cursor.providerId)
            it.setInt(5, limit)
            it.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(rows.getLong("date") to rows.getLong("providerId"))
                }
            }
        }

    /** Walk one source to completion exactly the way the coordinator does, at a given page size. */
    private fun walk(connection: Connection, source: String, limit: Int): List<Pair<Long, Long>> {
        val seen = mutableListOf<Pair<Long, Long>>()
        var cursor = MirrorVerifyCursor.START
        var guard = 0
        while (true) {
            val page = page(connection, source, cursor, limit)
            seen += page
            if (page.size < limit) break
            val last = page.last()
            cursor = MirrorVerifyCursor(date = last.first, providerId = last.second)
            check(++guard < 1000) { "the walk did not terminate" }
        }
        return seen
    }

    // ── The walk's two properties ────────────────────────────────────────────

    @Test
    fun `everyRowIsExaminedExactlyOnceAcrossPages`() {
        connection().use { connection ->
            // Deliberately many rows sharing a date, so a date-only cursor misbehaves.
            for (providerId in 1L..20L) insert(connection, "sms", providerId, date = 1000L - providerId)
            for (providerId in 21L..40L) insert(connection, "sms", providerId, date = 100L)

            val seen = walk(connection, "sms", limit = 7)

            assertEquals("the walk must cover the table once", 40, seen.size)
            assertEquals("and examine nothing twice", 40, seen.toSet().size)
            assertEquals(
                "newest first",
                seen.first(),
                999L to 1L
            )
        }
    }

    @Test
    fun `aPageBoundaryInsideADateGroupLosesNothing`() {
        // The failure this exists to prevent. Four rows share date 100 and the page size is 2, so the
        // boundary falls INSIDE the group: a cursor of `date < 100` would skip rows 2 and 1 entirely,
        // and a cursor that did not advance at all would re-read them forever.
        connection().use { connection ->
            insert(connection, "sms", 4L, 100)
            insert(connection, "sms", 3L, 100)
            insert(connection, "sms", 2L, 100)
            insert(connection, "sms", 1L, 100)

            val seen = walk(connection, "sms", limit = 2)

            assertEquals(listOf(100L to 4L, 100L to 3L, 100L to 2L, 100L to 1L), seen)
            assertEquals(4, seen.toSet().size)
        }
    }

    @Test
    fun `aCursorThatAdvancesByDateAloneWouldSkipTheGroup`() {
        // The negative control, run so the guard above is not vacuous: this is what the naive cursor
        // produces on the same data. If this ever stops losing rows, the composite predicate has
        // stopped being necessary and someone should say why.
        connection().use { connection ->
            insert(connection, "sms", 4L, 100)
            insert(connection, "sms", 3L, 100)
            insert(connection, "sms", 2L, 100)
            insert(connection, "sms", 1L, 100)

            val firstPage = page(connection, "sms", MirrorVerifyCursor.START, limit = 2)
            assertEquals(listOf(100L to 4L, 100L to 3L), firstPage)
            val naive = connection.prepareStatement(
                "SELECT COUNT(*) FROM `$table` WHERE source = 'sms' AND date < ?"
            ).use {
                it.setLong(1, 100)
                it.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
            }

            assertEquals("a date-only cursor sees nothing older, and skips 2 and 1", 0, naive)
        }
    }

    @Test
    fun `theWalkIsPerSourceAndNeverCrossesThem`() {
        // One cursor cannot serve two key spaces, which is why the progress row is keyed by source.
        connection().use { connection ->
            insert(connection, "sms", 1L, 500)
            insert(connection, "mms", 1L, 500)
            insert(connection, "sms", 2L, 400)
            insert(connection, "mms", 2L, 400)

            val sms = walk(connection, "sms", limit = 1)
            val mms = walk(connection, "mms", limit = 1)

            assertEquals(listOf(500L to 1L, 400L to 2L), sms)
            assertEquals(listOf(500L to 1L, 400L to 2L), mms)
        }
    }

    @Test
    fun `aRowAddedWithAnOlderDateIsStillWalked`() {
        // The reason this walk uses a keyset cursor while the recent-window check deliberately uses a
        // TIME window: the history backfill writes OLD messages long after they were sent, so a
        // backfilled row can appear behind the cursor. It is not lost — the sweep simply reaches it on
        // the next pass, because a completed sweep can be restarted while a window that has already
        // moved on never looks back.
        connection().use { connection ->
            insert(connection, "sms", 1L, 500)
            val seen = walk(connection, "sms", limit = 10)
            assertEquals(listOf(500L to 1L), seen)

            insert(connection, "sms", 2L, 100)
            val second = walk(connection, "sms", limit = 10)

            assertTrue("a newer pass sees it", second.contains(100L to 2L))
        }
    }

    @Test
    fun `theShippedQueryKeepsTheCompositePredicate`() {
        // A source-level check on the DAO itself: the SQL above is retyped, so if the shipped query
        // were simplified to `date < :beforeDate` the tests here would keep passing while the app
        // silently skipped rows.
        val dao = java.io.File("src/main/java/com/autonomousone/messages/data/MirrorReconcileDao.kt")
            .let { if (it.isFile) it else java.io.File("app/src/main/java/com/autonomousone/messages/data/MirrorReconcileDao.kt") }
        assertTrue("MirrorReconcileDao.kt not found", dao.isFile)
        val text = dao.readText()

        assertTrue(
            "the walk must compare the composite key, not just the date",
            text.contains("date = :beforeDate AND providerId < :beforeId")
        )
        assertTrue(
            "and it must order by the same pair so the cursor is well defined",
            text.contains("ORDER BY date DESC, providerId DESC")
        )
    }
}
