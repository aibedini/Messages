package com.autonomousone.messages

import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.ProviderRepairIntent
import java.sql.Connection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Persisted repair INTENT and the absence-maturity policy.
 *
 * The queue is identity-keyed, but identity alone does not say what an absence
 * MEANS. A mature exact observer identity may prove a delete; a row that was just
 * created (EXPECT_EXISTS) or a status callback (REFRESH_STATUS) must never delete
 * on absence, because the row is expected to exist and is often not query-visible
 * yet. Getting this wrong simply moves the visibility race one layer deeper:
 * enqueue -> generic repair -> first strict Success(null) -> Delete.
 *
 * The table is built by running the SHIPPED v14 migration and then the SHIPPED v15
 * migration, and the statements under test are copied VERBATIM from
 * ProviderRepairDao, so this pins the migration, the schema and the semantics
 * against a real SQLite engine.
 *
 * WRITTEN BUT NOT EXECUTED.
 */
class RepairIntentTest {

    private lateinit var db: Connection

    private val enqueueSql =
        "INSERT INTO provider_repair_queue " +
            "(source, providerId, generation, state, attempts, nextRetryAt, leaseUntil, " +
            "lastFailureReason, createdAt, updatedAt, intent, intentSince) " +
            "VALUES (:source, :providerId, 1, 'PENDING', 0, :now, 0, '', :now, :now, " +
            ":intent, :now) " +
            "ON CONFLICT(source, providerId) DO UPDATE SET " +
            "generation = provider_repair_queue.generation + 1, " +
            "state = 'PENDING', attempts = 0, nextRetryAt = :now, leaseUntil = 0, " +
            "updatedAt = :now, intent = :intent, intentSince = :now"

    private val rearmSql =
        "UPDATE provider_repair_queue SET generation = generation + 1, " +
            "intent = :intent, intentSince = :now, state = 'PENDING', attempts = 0, " +
            "nextRetryAt = :now, leaseUntil = 0, updatedAt = :now " +
            "WHERE source = :source AND providerId = :providerId AND generation = :generation"

    private val ackSql =
        "DELETE FROM provider_repair_queue WHERE source = :source " +
            "AND providerId = :providerId AND generation = :generation"

    private val named = Regex(":[A-Za-z][A-Za-z0-9_]*")

    @Before
    fun setUp() {
        db = rawDb()
        // SHIPPED migrations, in order: v14 creates the queue, v15 adds intent.
        MessagesDatabase.UPGRADE_TO_V14_SQL.forEach { db.exec(it) }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun exec(sql: String, vararg args: Any?) {
        db.prepareStatement(sql.replace(named, "?")).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeUpdate()
        }
    }

    /** executeUpdate result: the number of rows affected. */
    private fun exec2(sql: String, vararg args: Any?): Long =
        db.prepareStatement(sql.replace(named, "?")).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeUpdate().toLong()
        }

    private fun one(sql: String, vararg args: Any?): Long =
        db.prepareStatement(sql.replace(named, "?")).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else -1L }
        }

    private fun string(sql: String, vararg args: Any?): String? =
        db.prepareStatement(sql.replace(named, "?")).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    /** Mirrors ProviderRepairQueue.enqueue. */
    private fun enqueue(source: String, id: Long, intent: String, now: Long) =
        exec(enqueueSql, source, id, now, now, now, intent, now)

    private fun intentOf(source: String, id: Long): String? =
        string("SELECT intent FROM provider_repair_queue WHERE source = ? AND providerId = ?", source, id)

    private fun generationOf(source: String, id: Long): Long =
        one("SELECT generation FROM provider_repair_queue WHERE source = ? AND providerId = ?", source, id)

    private fun count(): Long = one("SELECT COUNT(*) FROM provider_repair_queue")

    // ── migration safety ───────────────────────────────────────────────────

    @Test
    fun `the v15 migration gives pre-existing rows the NON-DESTRUCTIVE intent`() {
        // A row written by v14 has NO recorded semantic origin. It must never
        // become delete-capable by accident.
        db.exec(
            "INSERT INTO provider_repair_queue (source, providerId, generation, state, " +
                "attempts, nextRetryAt, leaseUntil, lastFailureReason, createdAt, updatedAt) " +
                "VALUES ('sms', 42, 1, 'PENDING', 0, 100, 0, '', 100, 700)"
        )
        MessagesDatabase.UPGRADE_TO_V15_SQL.forEach { db.exec(it) }

        assertEquals(ProviderRepairIntent.EXPECT_EXISTS.name, intentOf("sms", 42L))
        assertEquals("intentSince is backfilled from updatedAt", 700L,
            one("SELECT intentSince FROM provider_repair_queue WHERE providerId = 42"))
        assertFalse(
            "a migrated row must not be delete-capable",
            ProviderRepairIntent.from(intentOf("sms", 42L)).absenceCanProveDelete
        )
    }

    @Test
    fun `the v15 migration is idempotent for rows it has already seen`() {
        MessagesDatabase.UPGRADE_TO_V15_SQL.forEach { db.exec(it) }
        enqueue("sms", 5L, ProviderRepairIntent.RECONCILE_EXACT.name, 1_000L)
        // Re-running the backfill UPDATE must not overwrite a real intentSince.
        db.exec(
            "UPDATE provider_repair_queue SET intentSince = updatedAt WHERE intentSince = 0"
        )
        assertEquals(1_000L,
            one("SELECT intentSince FROM provider_repair_queue WHERE providerId = 5"))
        assertEquals(ProviderRepairIntent.RECONCILE_EXACT.name, intentOf("sms", 5L))
    }

    // ── enqueue records intent; a newer intent always wins ─────────────────

    @Test
    fun `enqueue records the caller's intent`() {
        MessagesDatabase.UPGRADE_TO_V15_SQL.forEach { db.exec(it) }
        enqueue("sms", 7L, ProviderRepairIntent.EXPECT_EXISTS.name, 1_000L)
        assertEquals("EXPECT_EXISTS", intentOf("sms", 7L))
        assertEquals(1L, generationOf("sms", 7L))
    }

    @Test
    fun `a newer intent supersedes the older one and bumps the generation`() {
        MessagesDatabase.UPGRADE_TO_V15_SQL.forEach { db.exec(it) }
        enqueue("sms", 7L, ProviderRepairIntent.EXPECT_EXISTS.name, 1_000L)
        // A mature observer event for the same identity is delete-capable.
        enqueue("sms", 7L, ProviderRepairIntent.RECONCILE_EXACT.name, 2_000L)

        assertEquals(2L, generationOf("sms", 7L))
        assertEquals("RECONCILE_EXACT", intentOf("sms", 7L))
        assertEquals(2_000L,
            one("SELECT intentSince FROM provider_repair_queue WHERE providerId = 7"))
    }

    // ── maturity: absence never deletes from a non-delete-capable intent ───

    @Test
    fun `rearm converts a matured absence into a fresh delete-candidate generation`() {
        MessagesDatabase.UPGRADE_TO_V15_SQL.forEach { db.exec(it) }
        enqueue("sms", 7L, ProviderRepairIntent.EXPECT_EXISTS.name, 1_000L)

        assertEquals(1L, exec2(
            rearmSql,
            ProviderRepairIntent.VERIFY_DELETE_CANDIDATE.name, 1_000L, 1_000L, 1_000L,
            "sms", 7L, 1L
        ))
        assertEquals("VERIFY_DELETE_CANDIDATE", intentOf("sms", 7L))
        assertEquals("the delete candidate is its OWN generation", 2L, generationOf("sms", 7L))
        assertEquals("backoff and attempts reset for the new generation", 0L,
            one("SELECT attempts FROM provider_repair_queue WHERE providerId = 7"))
    }

    @Test
    fun `an ack for the old generation cannot remove the re-armed row`() {
        MessagesDatabase.UPGRADE_TO_V15_SQL.forEach { db.exec(it) }
        enqueue("sms", 7L, ProviderRepairIntent.EXPECT_EXISTS.name, 1_000L)
        exec2(
            rearmSql,
            ProviderRepairIntent.VERIFY_DELETE_CANDIDATE.name, 1_000L, 1_000L, 1_000L,
            "sms", 7L, 1L
        )

        // The worker that finished the OLD generation acks generation 1.
        assertEquals(0L, exec2(ackSql, "sms", 7L, 1L))
        assertEquals("the newer generation survives", 1L, count())
        assertEquals("VERIFY_DELETE_CANDIDATE", intentOf("sms", 7L))
    }

    @Test
    fun `a stale delete-capable worker cannot delete after a newer EXPECT_EXISTS`() {
        MessagesDatabase.UPGRADE_TO_V15_SQL.forEach { db.exec(it) }
        enqueue("sms", 7L, ProviderRepairIntent.VERIFY_DELETE_CANDIDATE.name, 1_000L)
        // The row reappears: a new EXPECT_EXISTS intent replaces the candidate.
        enqueue("sms", 7L, ProviderRepairIntent.EXPECT_EXISTS.name, 2_000L)
        assertEquals(2L, generationOf("sms", 7L))

        // The old delete-capable worker's ACK is generation-scoped and loses.
        assertEquals(0L, exec2(ackSql, "sms", 7L, 1L))
        assertEquals(1L, count())
        assertEquals("EXPECT_EXISTS", intentOf("sms", 7L))
        assertFalse(ProviderRepairIntent.from(intentOf("sms", 7L)).absenceCanProveDelete)
    }

    // ── the policy itself (pure) ───────────────────────────────────────────

    @Test
    fun `only a mature exact identity or a delete candidate may prove a delete`() {
        assertTrue(ProviderRepairIntent.RECONCILE_EXACT.absenceCanProveDelete)
        assertTrue(ProviderRepairIntent.VERIFY_DELETE_CANDIDATE.absenceCanProveDelete)
        assertFalse("a just-created row is expected to exist",
            ProviderRepairIntent.EXPECT_EXISTS.absenceCanProveDelete)
        assertFalse("a status callback must never delete a known message",
            ProviderRepairIntent.REFRESH_STATUS.absenceCanProveDelete)
    }

    @Test
    fun `an unknown or missing intent falls back to the non-destructive one`() {
        assertEquals(ProviderRepairIntent.EXPECT_EXISTS, ProviderRepairIntent.from(null))
        assertEquals(ProviderRepairIntent.EXPECT_EXISTS, ProviderRepairIntent.from(""))
        assertEquals(ProviderRepairIntent.EXPECT_EXISTS, ProviderRepairIntent.from("NONSENSE"))
        assertEquals(ProviderRepairIntent.RECONCILE_EXACT,
            ProviderRepairIntent.from("RECONCILE_EXACT"))
    }

    @Test
    fun `the visibility grace is a real interval, not a single arbitrary delay`() {
        assertTrue(com.autonomousone.messages.data.ProviderRepairQueue.VISIBILITY_GRACE_MS >= 5_000L)
        assertTrue(com.autonomousone.messages.data.ProviderRepairQueue.ABSENCE_MIN_ATTEMPTS >= 2)
    }
}
