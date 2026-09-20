package com.autonomousone.messages.data

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * v17 → v18 BEHAVIOUR (v3.5.0 Phase 3), executed as real SQLite.
 *
 * The contract test next door pins the migration TEXT; this one proves what that text
 * DOES: a v17 database with real user state survives the upgrade row for row, the two
 * new tables arrive empty, and the one foreign key behaves (CASCADE removes memberships
 * with their category, and nothing else is touched).
 *
 * It deliberately populates every user-state table the feature could plausibly damage,
 * because "the database opened" is not evidence that nothing was lost.
 */
class UserCategoryMigrationBehaviourTest {

    private val schemaDir = "schemas/com.autonomousone.messages.data.MessagesDatabase"
    private lateinit var connection: Connection

    // ── Fixture ─────────────────────────────────────────────────────────────

    private fun v17Schema(): JSONObject =
        JSONObject(File("$schemaDir/17.json").readText()).getJSONObject("database")

    /** Creates the REAL v17 tables (from 17.json), so the fixture is not a guess. */
    private fun createV17(target: Connection) {
        val entities = v17Schema().getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            val sql = entity.getString("createSql").replace("\${TABLE_NAME}", table)
            // The FTS virtual table and its shadows are not needed for this test.
            if (!sql.startsWith("CREATE TABLE IF NOT EXISTS")) continue
            target.createStatement().use { it.execute(sql) }
            val indices = entity.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                target.createStatement().use {
                    it.execute(
                        indices.getJSONObject(j).getString("createSql")
                            .replace("\${TABLE_NAME}", table)
                    )
                }
            }
        }
    }

    private fun execOn(target: Connection, sql: String): Int =
        target.createStatement().use { it.executeUpdate(sql) }

    private fun scalarOn(target: Connection, sql: String): Long =
        target.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                assertTrue("query returned no row: $sql", rows.next())
                rows.getLong(1)
            }
        }

    private fun textOn(target: Connection, sql: String): String? =
        target.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                if (!rows.next()) null else rows.getString(1)
            }
        }

    /** Bound to the current test's [connection], for use inside test bodies. */
    private fun exec(sql: String): Int = execOn(connection, sql)

    private fun scalar(sql: String): Long = scalarOn(connection, sql)

    private fun text(sql: String): String? = textOn(connection, sql)

    private fun seedV17State(target: Connection) {
        execOn(
            target,
            "INSERT INTO `conversations` (`threadId`,`normalizedAddress`,`rawAddress`,`snippet`," +
                "`lastMessageDate`,`unreadCount`,`lastMessageType`,`pinned`,`archived`) " +
                "VALUES (7,'+989121234567','+98 912 123 4567','hello',1000,2,1,0,0)"
        )
        execOn(
            target,
            "INSERT INTO `messages` (`source`,`providerId`,`threadId`,`normalizedAddress`," +
                "`rawAddress`,`body`,`date`,`type`,`status`,`dateSent`,`read`,`syncState`) " +
                "VALUES ('sms',100,7,'+989121234567','+98 912 123 4567','hello',1000,1,-1,0,0,'synced')"
        )
        execOn(
            target,
            "INSERT INTO `conversation_preferences` (`threadId`,`manualUnread`,`mutedUntil`," +
                "`customNotificationChannel`,`categoryOverride`,`spam`,`spamReportedAt`," +
                "`spamBlockedByReport`,`updatedAt`) " +
                "VALUES (7,1,999,1,'TRANSACTION',0,0,0,50)"
        )
        execOn(
            target,
            "INSERT INTO `message_user_state` (`source`,`providerId`,`threadId`,`starred`," +
                "`starredAt`,`trashedAt`,`purgeAt`,`keepFromOtpCleanup`,`updatedAt`) " +
                "VALUES ('sms',100,7,1,60,0,0,1,60)"
        )
        execOn(
            target,
            "INSERT INTO `message_classification` (`source`,`providerId`,`threadId`,`category`," +
                "`confidence`,`isOtp`,`otpDeleteEligibleAt`,`classifiedAt`) " +
                "VALUES ('sms',100,7,'OTP',0.9,1,500,70)"
        )
        execOn(
            target,
            "INSERT INTO `conversation_classification` (`threadId`,`category`,`confidence`,`updatedAt`) " +
                "VALUES (7,'OTP',0.9,70)"
        )
        execOn(
            target,
            "INSERT INTO `trashed_threads` (`threadId`,`deletedAt`,`purgeAt`,`cutoffDate`," +
                "`cutoffSource`,`cutoffProviderId`) VALUES (8,80,90,1000,'sms',5)"
        )
        execOn(
            target,
            "INSERT INTO `pending_delayed_sends` (`intentId`,`body`,`phoneToken`,`threadId`," +
                "`state`,`dueAt`,`createdAt`,`claimedAt`,`sentRowId`,`attempts`,`failureCode`) " +
                "VALUES ('dly_1','body','tok',7,'PENDING',1000,900,0,0,0,NULL)"
        )
    }

    @Before
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        createV17(connection)
        seedV17State(connection)
    }

    @After
    fun tearDown() {
        if (::connection.isInitialized) connection.close()
    }

    private fun migrateToV18() {
        MessagesDatabase.UPGRADE_TO_V18_SQL.forEach { statement ->
            connection.createStatement().use { it.execute(statement) }
        }
    }

    // ── Preservation ────────────────────────────────────────────────────────

    @Test
    fun `every v17 row survives the upgrade`() {
        migrateToV18()

        // The durable mirror and its projection.
        assertEquals(1L, scalar("SELECT COUNT(*) FROM messages"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM conversations"))
        assertEquals("hello", text("SELECT body FROM messages WHERE providerId = 100"))
        assertEquals(2L, scalar("SELECT unreadCount FROM conversations WHERE threadId = 7"))

        // User state, exactly as written.
        assertEquals(
            1L,
            scalar("SELECT manualUnread FROM conversation_preferences WHERE threadId = 7")
        )
        assertEquals(
            999L,
            scalar("SELECT mutedUntil FROM conversation_preferences WHERE threadId = 7")
        )
        assertEquals(
            "TRANSACTION",
            text("SELECT categoryOverride FROM conversation_preferences WHERE threadId = 7")
        )
        assertEquals(1L, scalar("SELECT starred FROM message_user_state WHERE providerId = 100"))
        assertEquals(
            1L,
            scalar("SELECT keepFromOtpCleanup FROM message_user_state WHERE providerId = 100")
        )

        // Classification, trash and the send ledger.
        assertEquals(1L, scalar("SELECT isOtp FROM message_classification WHERE providerId = 100"))
        assertEquals(
            "OTP",
            text("SELECT category FROM conversation_classification WHERE threadId = 7")
        )
        assertEquals(1L, scalar("SELECT COUNT(*) FROM trashed_threads"))
        assertEquals(
            "PENDING",
            text("SELECT state FROM pending_delayed_sends WHERE intentId = 'dly_1'")
        )
    }

    @Test
    fun `the upgrade invents no categories and no memberships`() {
        migrateToV18()

        assertEquals(0L, scalar("SELECT COUNT(*) FROM user_categories"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM user_category_assignments"))
    }

    // ── The foreign key ─────────────────────────────────────────────────────

    @Test
    fun `the assignment foreign key cascades from the category`() {
        migrateToV18()

        val fk = ArrayList<Triple<String, String, String>>()
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA foreign_key_list(`user_category_assignments`)")
                .use { rows ->
                    while (rows.next()) {
                        fk += Triple(
                            rows.getString("table"),
                            rows.getString("from"),
                            rows.getString("on_delete")
                        )
                    }
                }
        }
        assertEquals(listOf(Triple("user_categories", "categoryId", "CASCADE")), fk)

        // Functional proof: deleting the category takes its memberships with it.
        exec("PRAGMA foreign_keys = ON")
        exec(
            "INSERT INTO `user_categories` (`categoryId`,`name`,`normalizedName`," +
                "`sortOrder`,`createdAt`,`updatedAt`) VALUES ('c1','VPN','vpn',0,1,1)"
        )
        exec(
            "INSERT INTO `user_category_assignments` " +
                "(`categoryId`,`scopeType`,`scopeKey`,`createdAt`) " +
                "VALUES ('c1','ADDRESS','+989121234567',1)"
        )
        exec(
            "INSERT INTO `user_category_assignments` " +
                "(`categoryId`,`scopeType`,`scopeKey`,`createdAt`) " +
                "VALUES ('c1','THREAD','500',1)"
        )
        assertEquals(2L, scalar("SELECT COUNT(*) FROM user_category_assignments"))

        exec("DELETE FROM `user_categories` WHERE `categoryId` = 'c1'")

        assertEquals(
            "memberships must not outlive their category",
            0L,
            scalar("SELECT COUNT(*) FROM user_category_assignments")
        )
    }

    // ── Scope semantics stored in the database ──────────────────────────────

    @Test
    fun `a thread membership is valid with no conversation row for it`() {
        migrateToV18()

        exec(
            "INSERT INTO `user_categories` (`categoryId`,`name`,`normalizedName`," +
                "`sortOrder`,`createdAt`,`updatedAt`) VALUES ('c1','VPN','vpn',0,1,1)"
        )
        // Thread 999999999 has no `conversations` row; the membership must still be
        // accepted, because category membership is user-owned durable state that must
        // not be destroyed by projection churn.
        exec(
            "INSERT INTO `user_category_assignments` " +
                "(`categoryId`,`scopeType`,`scopeKey`,`createdAt`) " +
                "VALUES ('c1','THREAD','999999999',1)"
        )

        assertEquals(1L, scalar("SELECT COUNT(*) FROM user_category_assignments"))
    }

    @Test
    fun `an existing v17 thread id is not part of an address membership`() {
        migrateToV18()
        exec(
            "INSERT INTO `user_categories` (`categoryId`,`name`,`normalizedName`," +
                "`sortOrder`,`createdAt`,`updatedAt`) VALUES ('c1','VPN','vpn',0,1,1)"
        )
        exec("INSERT INTO `user_category_assignments` VALUES ('c1','ADDRESS','+989121234567',1)")

        val key = text("SELECT scopeKey FROM user_category_assignments WHERE categoryId = 'c1'")
        assertNotNull(key)
        assertEquals(
            "an ADDRESS membership stores the stable phone identity, never the thread id",
            "+989121234567",
            key
        )
    }

    // ── Membership shape ────────────────────────────────────────────────────

    @Test
    fun `the membership triple makes re-assigning idempotent`() {
        migrateToV18()
        exec(
            "INSERT INTO `user_categories` (`categoryId`,`name`,`normalizedName`," +
                "`sortOrder`,`createdAt`,`updatedAt`) VALUES ('c1','VPN','vpn',0,1,1)"
        )
        val insert =
            "INSERT OR IGNORE INTO `user_category_assignments` " +
                "(`categoryId`,`scopeType`,`scopeKey`,`createdAt`) " +
                "VALUES ('c1','ADDRESS','+989121234567',1)"

        exec(insert)
        exec(insert)

        assertEquals(1L, scalar("SELECT COUNT(*) FROM user_category_assignments"))
    }

    @Test
    fun `one scope may hold many categories and one category many scopes`() {
        migrateToV18()
        exec(
            "INSERT INTO `user_categories` (`categoryId`,`name`,`normalizedName`," +
                "`sortOrder`,`createdAt`,`updatedAt`) VALUES ('c1','VPN','vpn',0,1,1)"
        )
        exec(
            "INSERT INTO `user_categories` (`categoryId`,`name`,`normalizedName`," +
                "`sortOrder`,`createdAt`,`updatedAt`) VALUES ('c2','مهم','مهم',1,1,1)"
        )

        // Same scope, two categories (many-to-many).
        exec("INSERT INTO `user_category_assignments` VALUES ('c1','ADDRESS','+989121234567',1)")
        exec("INSERT INTO `user_category_assignments` VALUES ('c2','ADDRESS','+989121234567',1)")
        // One category, three different scope shapes.
        exec("INSERT INTO `user_category_assignments` VALUES ('c1','ADDRESS','sender:bank',1)")
        exec("INSERT INTO `user_category_assignments` VALUES ('c1','THREAD','500',1)")

        assertEquals(4L, scalar("SELECT COUNT(*) FROM user_category_assignments"))
        assertEquals(
            2L,
            scalar("SELECT COUNT(*) FROM user_category_assignments WHERE scopeKey = '+989121234567'")
        )
        assertEquals(
            3L,
            scalar("SELECT COUNT(*) FROM user_category_assignments WHERE categoryId = 'c1'")
        )
    }

    @Test
    fun `the unique normalized name is enforced by the database`() {
        migrateToV18()
        exec(
            "INSERT INTO `user_categories` (`categoryId`,`name`,`normalizedName`," +
                "`sortOrder`,`createdAt`,`updatedAt`) VALUES ('c1','VPN','vpn',0,1,1)"
        )

        var rejected = false
        try {
            exec(
                "INSERT INTO `user_categories` (`categoryId`,`name`,`normalizedName`," +
                    "`sortOrder`,`createdAt`,`updatedAt`) VALUES ('c2','vpn','vpn',1,1,1)"
            )
        } catch (_: Exception) {
            rejected = true
        }

        assertTrue("a duplicate normalizedName must be rejected by the UNIQUE index", rejected)
        assertEquals(1L, scalar("SELECT COUNT(*) FROM user_categories"))
    }
}
