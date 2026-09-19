package com.autonomousone.messages

import com.autonomousone.messages.data.MessageAssetBackfillRow
import com.autonomousone.messages.data.MessageAssetKeys
import com.autonomousone.messages.data.MessageAssetKind
import com.autonomousone.messages.data.MessageAssetSql
import com.autonomousone.messages.data.MessageEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * The `message_assets` SQL contract, executed as REAL SQL against a REAL SQLite
 * database whose shape comes from the generated Room schema.
 *
 * The DAO annotations carry these statements as INLINE LITERALS (Room/KSP rejects
 * a string template as an annotation argument — see [MessageAssetSql]), and
 * [MessageAssetSqlDaoLiteralDriftTest] asserts each literal against its constant
 * here, character for character. Together with this harness that means the
 * statement executed on a real SQLite engine, the statement Room runs on the
 * device and the statement pinned here cannot drift apart. That matters because
 * the invariants the brief pins are SQL facts:
 *
 *  - re-indexing is an UPSERT (one row per deterministic `assetKey`);
 *  - two IDENTICAL urls in two DIFFERENT messages both survive;
 *  - deleting a message removes exactly ITS assets (composite identity);
 *  - paging is newest-first and BOUNDED;
 *  - the history sweep's keyset covers every message exactly once and terminates.
 */
class MessageAssetSqlTest {

    // ── Harness ─────────────────────────────────────────────────────────────

    private val schemaDir = "schemas/com.autonomousone.messages.data.MessagesDatabase"

    /**
     * Highest generated schema that contains `message_assets` — the version that
     * defines the table, whatever the current database version is.
     */
    private fun schemaFile(): File {
        val dir = File(schemaDir)
        if (!dir.isDirectory) fail("Schema dir not found at ${dir.absolutePath} — run :app:kspDebugKotlin")
        val candidates = dir.listFiles { file: File -> file.name.endsWith(".json") }
            ?.sortedByDescending { it.name.removeSuffix(".json").toIntOrNull() ?: -1 }
            ?: emptyList()
        for (file in candidates) {
            val entities = JSONObject(file.readText())
                .getJSONObject("database")
                .getJSONArray("entities")
            for (i in 0 until entities.length()) {
                if (entities.getJSONObject(i).getString("tableName") == "message_assets") return file
            }
        }
        fail("No generated schema declares message_assets")
        error("unreachable")
    }

    /** Every real table + index of the app's Room schema. */
    private fun newDb(): Connection {
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        val entities = JSONObject(schemaFile().readText())
            .getJSONObject("database")
            .getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            connection.createStatement().use {
                it.execute(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val indices = entity.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                val index = indices.getJSONObject(j)
                connection.createStatement().use {
                    it.execute(index.getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
        }
        return connection
    }

    /** Binds `:name` placeholders with literals so a real @Query can be executed. */
    private fun bind(sql: String, vararg params: Pair<String, Any?>): String {
        var out = sql
        params.forEach { (name, value) ->
            val literal = when (value) {
                null -> "NULL"
                is String -> "'" + value.replace("'", "''") + "'"
                is Boolean -> if (value) "1" else "0"
                else -> value.toString()
            }
            out = out.replace(":$name", literal)
        }
        return out
    }

    private fun Connection.execute(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.scalar(sql: String): Long = createStatement().use { statement ->
        statement.executeQuery(sql).use { rs ->
            rs.next()
            rs.getLong(1)
        }
    }

    private fun Connection.insertMessage(
        source: String,
        providerId: Long,
        threadId: Long = 7L,
        body: String = "",
        date: Long = 1_700_000_000_000L
    ) = execute(
        """
        INSERT INTO `messages`
            (`source`,`providerId`,`threadId`,`normalizedAddress`,`rawAddress`,
             `body`,`date`,`type`,`status`,`dateSent`,`read`,`syncState`)
        VALUES ('$source', $providerId, $threadId, '09120000000', '+989120000000',
                '${body.replace("'", "''")}', $date, 1, -1, 0, 1, 'synced')
        """.trimIndent()
    )

    private fun Connection.insertAsset(
        assetKey: String,
        source: String,
        providerId: Long,
        threadId: Long = 7L,
        kind: MessageAssetKind,
        value: String,
        mimeType: String = "",
        displayName: String = "",
        date: Long = 1_700_000_000_000L
    ) = execute(
        """
        INSERT INTO `message_assets`
            (`assetKey`,`source`,`providerId`,`threadId`,`kind`,`value`,
             `mimeType`,`displayName`,`date`)
        VALUES ('$assetKey', '$source', $providerId, $threadId, '${kind.name}',
                '${value.replace("'", "''")}', '$mimeType',
                '${displayName.replace("'", "''")}', $date)
        """.trimIndent()
    )

    /**
     * The statement Room generates for `@Upsert` on a single-primary-key entity.
     * Used to prove that a deterministic `assetKey` makes re-index idempotent.
     */
    private fun Connection.upsertAsset(
        assetKey: String,
        source: String,
        providerId: Long,
        threadId: Long = 7L,
        kind: MessageAssetKind,
        value: String,
        mimeType: String = "",
        displayName: String = "",
        date: Long = 1_700_000_000_000L
    ) = execute(
        """
        INSERT INTO `message_assets`
            (`assetKey`,`source`,`providerId`,`threadId`,`kind`,`value`,
             `mimeType`,`displayName`,`date`)
        VALUES ('$assetKey', '$source', $providerId, $threadId, '${kind.name}',
                '${value.replace("'", "''")}', '$mimeType',
                '${displayName.replace("'", "''")}', $date)
        ON CONFLICT(`assetKey`) DO UPDATE SET
            `source`=excluded.`source`, `providerId`=excluded.`providerId`,
            `threadId`=excluded.`threadId`, `kind`=excluded.`kind`,
            `value`=excluded.`value`, `mimeType`=excluded.`mimeType`,
            `displayName`=excluded.`displayName`, `date`=excluded.`date`
        """.trimIndent()
    )

    private fun assetKey(source: String, providerId: Long, kind: MessageAssetKind, value: String) =
        MessageAssetKeys.of(source, providerId, kind, value)

    private val sms = MessageEntity.SOURCE_SMS
    private val mms = MessageEntity.SOURCE_MMS

    // ── Re-index is an UPSERT ───────────────────────────────────────────────

    @Test
    fun `re-indexing the same part upserts instead of duplicating`() {
        val db = newDb()
        try {
            val key = assetKey(mms, 10L, MessageAssetKind.MEDIA, "content://mms/part/3")
            db.upsertAsset(key, mms, 10L, 7L, MessageAssetKind.MEDIA, "content://mms/part/3", displayName = "first.jpg")
            db.upsertAsset(key, mms, 10L, 7L, MessageAssetKind.MEDIA, "content://mms/part/3", displayName = "second.jpg")
            assertEquals(1L, db.scalar("SELECT COUNT(*) FROM message_assets"))
            assertEquals(
                "second.jpg",
                db.createStatement().use { s ->
                    s.executeQuery("SELECT displayName FROM message_assets").use { rs -> rs.next(); rs.getString(1) }
                }
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `two identical urls in two different messages both survive`() {
        val db = newDb()
        try {
            val url = "https://example.com/deal"
            val first = assetKey(sms, 500L, MessageAssetKind.LINK, url)
            val second = assetKey(sms, 501L, MessageAssetKind.LINK, url)
            assertTrue("identity must differ per message", first != second)
            db.upsertAsset(first, sms, 500L, kind = MessageAssetKind.LINK, value = url)
            db.upsertAsset(second, sms, 501L, kind = MessageAssetKind.LINK, value = url)
            assertEquals(2L, db.scalar("SELECT COUNT(*) FROM message_assets"))
            // Re-indexing both messages must not add a third row.
            db.upsertAsset(first, sms, 500L, kind = MessageAssetKind.LINK, value = url)
            db.upsertAsset(second, sms, 501L, kind = MessageAssetKind.LINK, value = url)
            assertEquals(2L, db.scalar("SELECT COUNT(*) FROM message_assets"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `the same url twice inside one message is one row`() {
        val db = newDb()
        try {
            val key = assetKey(sms, 5L, MessageAssetKind.LINK, "https://example.com/a")
            db.upsertAsset(key, sms, 5L, kind = MessageAssetKind.LINK, value = "https://example.com/a")
            db.upsertAsset(key, sms, 5L, kind = MessageAssetKind.LINK, value = "https://example.com/a")
            assertEquals(1L, db.scalar("SELECT COUNT(*) FROM message_assets"))
        } finally {
            db.close()
        }
    }

    // ── Deletes ─────────────────────────────────────────────────────────────

    @Test
    fun `deleting a message removes exactly its own assets`() {
        val db = newDb()
        try {
            val url = "https://example.com/deal"
            db.upsertAsset(assetKey(sms, 100L, MessageAssetKind.LINK, url), sms, 100L, kind = MessageAssetKind.LINK, value = url)
            db.upsertAsset(assetKey(mms, 100L, MessageAssetKind.LINK, url), mms, 100L, kind = MessageAssetKind.LINK, value = url)
            db.upsertAsset(assetKey(mms, 100L, MessageAssetKind.FILE, "content://mms/part/9"), mms, 100L, kind = MessageAssetKind.FILE, value = "content://mms/part/9")

            db.execute(bind(MessageAssetSql.DELETE_FOR_MESSAGE_SQL, "source" to sms, "providerId" to 100L))

            assertEquals("SMS 100 must not remove MMS 100", 2L, db.scalar("SELECT COUNT(*) FROM message_assets"))
            // The deleted message's OWN asset is gone...
            assertEquals(
                "the deleted message's own asset must be removed",
                0L,
                db.scalar("SELECT COUNT(*) FROM message_assets WHERE source = 'sms' AND providerId = 100")
            )
            // ...and the same numeric id under the OTHER source is untouched. The
            // delete is keyed on (source, providerId), never on the id alone.
            assertEquals(
                2L,
                db.scalar("SELECT COUNT(*) FROM message_assets WHERE source = 'mms' AND providerId = 100")
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `a kind-scoped delete drops a stale link and keeps attachments`() {
        val db = newDb()
        try {
            db.upsertAsset(assetKey(sms, 1L, MessageAssetKind.LINK, "https://old.example.com"), sms, 1L, kind = MessageAssetKind.LINK, value = "https://old.example.com")
            db.upsertAsset(assetKey(mms, 1L, MessageAssetKind.LINK, "https://old.example.com"), mms, 1L, kind = MessageAssetKind.LINK, value = "https://old.example.com")
            db.upsertAsset(assetKey(mms, 1L, MessageAssetKind.MEDIA, "content://mms/part/1"), mms, 1L, kind = MessageAssetKind.MEDIA, value = "content://mms/part/1")

            db.execute(
                bind(
                    MessageAssetSql.DELETE_FOR_MESSAGE_KIND_SQL,
                    "source" to mms,
                    "providerId" to 1L,
                    "kind" to MessageAssetKind.LINK.name
                )
            )

            assertEquals(2L, db.scalar("SELECT COUNT(*) FROM message_assets"))
            assertEquals(0L, db.scalar("SELECT COUNT(*) FROM message_assets WHERE kind = 'LINK' AND source = 'mms'"))
            assertEquals("the SMS double must be untouched", 1L, db.scalar("SELECT COUNT(*) FROM message_assets WHERE kind = 'LINK'"))
            assertEquals(1L, db.scalar("SELECT COUNT(*) FROM message_assets WHERE kind = 'MEDIA'"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `orphan cleanup removes only assets whose message is gone`() {
        val db = newDb()
        try {
            db.insertMessage(sms, 1L)
            db.upsertAsset(assetKey(sms, 1L, MessageAssetKind.LINK, "https://kept.example.com"), sms, 1L, kind = MessageAssetKind.LINK, value = "https://kept.example.com")
            db.upsertAsset(assetKey(sms, 2L, MessageAssetKind.LINK, "https://orphan.example.com"), sms, 2L, kind = MessageAssetKind.LINK, value = "https://orphan.example.com")

            val removed = db.createStatement().use { statement ->
                statement.executeUpdate(MessageAssetSql.DELETE_ORPHANS_SQL)
            }

            assertEquals(1, removed)
            assertEquals(1L, db.scalar("SELECT COUNT(*) FROM message_assets"))
            assertEquals(1L, db.scalar("SELECT COUNT(*) FROM message_assets WHERE providerId = 1"))
        } finally {
            db.close()
        }
    }

    // ── Paging ──────────────────────────────────────────────────────────────

    private fun seedMedia(db: Connection, count: Int) {
        for (i in 1..count) {
            db.upsertAsset(
                assetKey(mms, i.toLong(), MessageAssetKind.MEDIA, "content://mms/part/$i"),
                mms,
                i.toLong(),
                kind = MessageAssetKind.MEDIA,
                value = "content://mms/part/$i",
                date = i * 1_000L
            )
        }
    }

    private fun Connection.pageMedia(threadId: Long, limit: Int, offset: Int): List<Long> =
        createStatement().use { statement ->
            statement.executeQuery(
                bind(
                    MessageAssetSql.PAGE_BY_KIND_SQL,
                    "threadId" to threadId,
                    "kind" to MessageAssetKind.MEDIA.name,
                    "limit" to limit,
                    "offset" to offset
                )
            ).use { rs ->
                val dates = mutableListOf<Long>()
                while (rs.next()) dates += rs.getLong("date")
                dates
            }
        }

    @Test
    fun `paging is newest first and bounded by the limit`() {
        val db = newDb()
        try {
            seedMedia(db, 5)
            assertEquals(listOf(5_000L, 4_000L), db.pageMedia(7L, 2, 0))
            assertEquals(listOf(3_000L, 2_000L), db.pageMedia(7L, 2, 2))
            assertEquals(listOf(1_000L), db.pageMedia(7L, 2, 4))
            assertEquals(emptyList<Long>(), db.pageMedia(7L, 2, 6))
        } finally {
            db.close()
        }
    }

    @Test
    fun `paging never leaks rows from another conversation or kind`() {
        val db = newDb()
        try {
            seedMedia(db, 3)
            db.upsertAsset(assetKey(mms, 99L, MessageAssetKind.MEDIA, "content://mms/part/99"), mms, 99L, threadId = 8L, kind = MessageAssetKind.MEDIA, value = "content://mms/part/99", date = 9_000L)
            db.upsertAsset(assetKey(mms, 98L, MessageAssetKind.FILE, "content://mms/part/98"), mms, 98L, kind = MessageAssetKind.FILE, value = "content://mms/part/98", date = 9_500L)
            assertEquals(listOf(3_000L, 2_000L, 1_000L), db.pageMedia(7L, 10, 0))
        } finally {
            db.close()
        }
    }

    @Test
    fun `countByKind counts exactly that tab`() {
        val db = newDb()
        try {
            seedMedia(db, 2)
            db.upsertAsset(assetKey(mms, 90L, MessageAssetKind.FILE, "content://mms/part/90"), mms, 90L, kind = MessageAssetKind.FILE, value = "content://mms/part/90")
            db.upsertAsset(assetKey(sms, 91L, MessageAssetKind.LINK, "https://a.com"), sms, 91L, kind = MessageAssetKind.LINK, value = "https://a.com")
            assertEquals(
                2L,
                db.scalar(
                    bind(
                        MessageAssetSql.COUNT_BY_KIND_SQL,
                        "threadId" to 7L,
                        "kind" to MessageAssetKind.MEDIA.name
                    )
                )
            )
            assertEquals(
                1L,
                db.scalar(
                    bind(
                        MessageAssetSql.COUNT_BY_KIND_SQL,
                        "threadId" to 7L,
                        "kind" to MessageAssetKind.FILE.name
                    )
                )
            )
            assertEquals(
                1L,
                db.scalar(
                    bind(
                        MessageAssetSql.COUNT_BY_KIND_SQL,
                        "threadId" to 7L,
                        "kind" to MessageAssetKind.LINK.name
                    )
                )
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `the links page joins the source body in one query`() {
        val db = newDb()
        try {
            val url = "https://example.com/deal"
            db.insertMessage(sms, 1L, body = "check https://example.com/deal now")
            db.upsertAsset(assetKey(sms, 1L, MessageAssetKind.LINK, url), sms, 1L, kind = MessageAssetKind.LINK, value = url, displayName = "example.com")

            db.createStatement().use { statement ->
                statement.executeQuery(
                    bind(
                        MessageAssetSql.PAGE_LINKS_WITH_BODY_SQL,
                        "threadId" to 7L,
                        "kind" to MessageAssetKind.LINK.name,
                        "limit" to 10,
                        "offset" to 0
                    )
                ).use { rs ->
                    assertTrue(rs.next())
                    assertEquals(url, rs.getString("value"))
                    assertEquals("example.com", rs.getString("displayName"))
                    assertEquals("check https://example.com/deal now", rs.getString("body"))
                    assertTrue("one row only", !rs.next())
                }
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `an orphaned link row still pages with a null body`() {
        val db = newDb()
        try {
            val url = "https://example.com/deal"
            db.upsertAsset(assetKey(sms, 1L, MessageAssetKind.LINK, url), sms, 1L, kind = MessageAssetKind.LINK, value = url)
            db.createStatement().use { statement ->
                statement.executeQuery(
                    bind(
                        MessageAssetSql.PAGE_LINKS_WITH_BODY_SQL,
                        "threadId" to 7L,
                        "kind" to MessageAssetKind.LINK.name,
                        "limit" to 10,
                        "offset" to 0
                    )
                ).use { rs ->
                    assertTrue(rs.next())
                    assertNull(rs.getString("body"))
                }
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `forMessage is scoped to the composite identity`() {
        val db = newDb()
        try {
            db.upsertAsset(assetKey(sms, 100L, MessageAssetKind.LINK, "https://a.com"), sms, 100L, kind = MessageAssetKind.LINK, value = "https://a.com")
            db.upsertAsset(assetKey(mms, 100L, MessageAssetKind.LINK, "https://b.com"), mms, 100L, kind = MessageAssetKind.LINK, value = "https://b.com")
            db.createStatement().use { statement ->
                statement.executeQuery(
                    bind(MessageAssetSql.FOR_MESSAGE_SQL, "source" to sms, "providerId" to 100L)
                ).use { rs ->
                    assertTrue(rs.next())
                    assertEquals("https://a.com", rs.getString("value"))
                    assertTrue(!rs.next())
                }
            }
        } finally {
            db.close()
        }
    }

    // ── Backfill keyset ─────────────────────────────────────────────────────

    private fun Connection.backfillPage(limit: Int, date: Long, source: String, providerId: Long) =
        createStatement().use { statement ->
            statement.executeQuery(
                bind(
                    MessageAssetSql.BACKFILL_BATCH_SQL,
                    "afterDate" to date,
                    "afterSource" to source,
                    "afterProviderId" to providerId,
                    "limit" to limit
                )
            ).use { rs ->
                val rows = mutableListOf<MessageAssetBackfillRow>()
                while (rs.next()) {
                    rows += MessageAssetBackfillRow(
                        source = rs.getString("source"),
                        providerId = rs.getLong("providerId"),
                        threadId = rs.getLong("threadId"),
                        body = rs.getString("body"),
                        date = rs.getLong("date")
                    )
                }
                rows
            }
        }

    @Test
    fun `the keyset sweep covers every message exactly once and terminates`() {
        val db = newDb()
        try {
            // Same date on purpose: the source/providerId tie-break must separate
            // these rows or the sweep would loop forever on the tie.
            db.insertMessage(sms, 1L, date = 1_000L)
            db.insertMessage(mms, 1L, date = 1_000L)
            db.insertMessage(sms, 2L, date = 1_000L)
            db.insertMessage(sms, 3L, date = 900L)
            db.insertMessage(mms, 7L, date = 900L)
            db.insertMessage(sms, 4L, date = 800L)
            db.insertMessage(sms, 5L, date = 800L)

            // A message with NO assets must still be covered (that is why the
            // sweep walks `messages` rather than "messages without assets").
            val seen = mutableListOf<String>()
            var cursorDate = Long.MAX_VALUE
            var cursorSource = "\uFFFF"
            var cursorProviderId = Long.MAX_VALUE
            var guard = 0
            while (guard++ < 20) {
                val page = db.backfillPage(3, cursorDate, cursorSource, cursorProviderId)
                if (page.isEmpty()) break
                page.forEach { seen += "${it.source}:${it.providerId}" }
                val last = page.last()
                cursorDate = last.date
                cursorSource = last.source
                cursorProviderId = last.providerId
            }

            assertEquals("every message covered exactly once", 7, seen.size)
            assertEquals(seen.size, seen.toSet().size)
            assertEquals(
                listOf("sms:2", "sms:1", "mms:1", "sms:3", "mms:7", "sms:5", "sms:4"),
                seen
            )

            // And the sweep really terminates: one more page is empty.
            assertTrue(db.backfillPage(3, cursorDate, cursorSource, cursorProviderId).isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun `the keyset sweep never re-reads a row with a full page`() {
        val db = newDb()
        try {
            for (i in 1L..6L) db.insertMessage(sms, i, date = 10_000L - i)
            val first = db.backfillPage(3, Long.MAX_VALUE, "\uFFFF", Long.MAX_VALUE)
            assertEquals(3, first.size)
            val second = db.backfillPage(
                3, first.last().date, first.last().source, first.last().providerId
            )
            val firstIds = first.map { it.providerId }.toSet()
            assertTrue(second.none { it.providerId in firstIds })
        } finally {
            db.close()
        }
    }

    @Test
    fun `the backfill batch carries exactly what the indexer needs`() {
        val db = newDb()
        try {
            db.insertMessage(sms, 12L, threadId = 44L, body = "hi https://a.com", date = 555L)
            val page = db.backfillPage(10, Long.MAX_VALUE, "\uFFFF", Long.MAX_VALUE)
            val row = page.single()
            assertEquals(sms, row.source)
            assertEquals(12L, row.providerId)
            assertEquals(44L, row.threadId)
            assertEquals("hi https://a.com", row.body)
            assertEquals(555L, row.date)
            assertNotNull(row)
        } finally {
            db.close()
        }
    }

    @Test
    fun `every tested statement is the one the DAO uses`() {
        // A typo'd copy in this test would silently pass against SQL the app never
        // runs; pin the placeholders instead.
        assertTrue(MessageAssetSql.PAGE_BY_KIND_SQL.contains(":threadId"))
        assertTrue(MessageAssetSql.PAGE_BY_KIND_SQL.contains(":limit"))
        assertTrue(MessageAssetSql.PAGE_BY_KIND_SQL.contains("LIMIT :limit OFFSET :offset"))
        assertTrue(MessageAssetSql.COUNT_BY_KIND_SQL.contains(":kind"))
        assertTrue(MessageAssetSql.FOR_MESSAGE_SQL.contains(":providerId"))
        assertTrue(MessageAssetSql.DELETE_FOR_MESSAGE_SQL.startsWith("DELETE FROM message_assets"))
        assertTrue(MessageAssetSql.DELETE_FOR_MESSAGE_KIND_SQL.contains("AND kind = :kind"))
        assertTrue(MessageAssetSql.DELETE_ORPHANS_SQL.contains("NOT EXISTS"))
        assertTrue(MessageAssetSql.BACKFILL_BATCH_SQL.contains("LIMIT :limit"))
    }
}
