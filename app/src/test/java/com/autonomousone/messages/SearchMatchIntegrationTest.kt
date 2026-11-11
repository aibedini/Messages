package com.autonomousone.messages

import com.autonomousone.messages.data.FtsQuery
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * The generated MATCH expression against a REAL SQLite FTS4 index.
 *
 * A query builder can look right and still be rejected by SQLite, or match nothing, so the shape
 * assertions in [FtsQueryTest] are paired with these: the same expressions are executed here with
 * the same tokenizer Room's `messages_fts` uses. If the bundled SQLite has no FTS4 module the
 * suite skips rather than failing the build.
 */
class SearchMatchIntegrationTest {

    private fun open(): Connection? = runCatching {
        DriverManager.getConnection("jdbc:sqlite::memory:").also { connection ->
            connection.createStatement().use { it.execute("CREATE VIRTUAL TABLE fts USING fts4(body)") }
        }
    }.getOrNull()

    private fun Connection.index(vararg bodies: String) {
        prepareStatement("INSERT INTO fts(body) VALUES (?)").use { statement ->
            bodies.forEach {
                statement.setString(1, it)
                statement.executeUpdate()
            }
        }
    }

    private fun Connection.count(match: String): Int {
        if (match.isEmpty()) return 0
        prepareStatement("SELECT COUNT(*) FROM fts WHERE fts MATCH ?").use { statement ->
            statement.setString(1, match)
            statement.executeQuery().use { rows ->
                rows.next()
                return rows.getInt(1)
            }
        }
    }

    private fun fixture(): Connection {
        val connection = open()
        assumeTrue("bundled SQLite has no FTS4 module", connection != null)
        return connection!!
    }

    @Test
    fun `a Persian word finds its suffixed forms`() {
        val db = fixture()
        db.index("دخترها آمدند", "دخترونه", "پسرها")
        assertEquals(2, db.count(FtsQuery.build("دختر")))
    }

    @Test
    fun `an Arabic yeh in the message matches a Persian yeh query`() {
        val db = fixture()
        db.index("علي رفت")
        assertEquals(1, db.count(FtsQuery.build("علی")))
    }

    @Test
    fun `Persian digits in the message match a Latin digit query`() {
        val db = fixture()
        db.index("کد ۰۹۱۲۳۴۵۶۷۸")
        assertEquals(1, db.count(FtsQuery.build("0912")))
    }

    @Test
    fun `a ZWNJ-spelled word matches a spaced query`() {
        val db = fixture()
        db.index("بی‌فاصله")
        assertEquals(1, db.count(FtsQuery.build("بی فاصله")))
    }

    @Test
    fun `multi word queries require every token`() {
        val db = fixture()
        db.index("سلام دنیا", "سلام")
        assertEquals(1, db.count(FtsQuery.build("سلام دنیا")))
    }

    @Test
    fun `an unrelated query matches nothing`() {
        val db = fixture()
        db.index("سلام", "دخترها")
        assertEquals(0, db.count(FtsQuery.build("خداحافظ")))
    }

    @Test
    fun `the recall fallback finds a partial phrase`() {
        val db = fixture()
        db.index("سلام دنیا")
        assertEquals(0, db.count(FtsQuery.build("سلام خداحافظ")))
        assertEquals(1, db.count(FtsQuery.buildAny("سلام خداحافظ")))
    }
}
