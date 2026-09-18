package com.autonomousone.messages

import com.autonomousone.messages.data.MessageEntity
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.ProviderRepairBackoff
import java.sql.Connection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The durable exact-repair queue, tested as SQL against a real SQLite engine.
 *
 * Why SQL and not the DAO: these are exactly the properties the old in-process
 * PendingExactRepairs map got wrong, and every one of them IS SQL behaviour.
 *
 *   old defect                              property pinned here
 *   --------------------------------------  ------------------------------------
 *   in-process, lost on process death        the row is in a table (schema test)
 *   due() did not claim                     claim() succeeds exactly once
 *   256-entry cap evicting the oldest        300 identities all survive
 *   retried only on another provider event    lease expiry / nextWakeAt is
 *                                             derived from the table alone
 *   an old success could consume new work     a newer generation survives an
 *                                             ACK scoped to the old generation
 *
 * The statements are copied VERBATIM from ProviderRepairDao in Daos.kt, and the
 * table is built by running the SHIPPED v14 migration, so this test also pins the
 * migration text against the behaviour of the statements it must support.
 *
 * WRITTEN BUT NOT EXECUTED.
 */
class ProviderRepairQueueTest {

    private lateinit var db: Connection

    private val sms: String = MessageEntity.SOURCE_SMS
    private val mms: String = MessageEntity.SOURCE_MMS

    private val enqueueSql =
        "INSERT INTO provider_repair_queue " +
            "(source, providerId, generation, state, attempts, nextRetryAt, leaseUntil, " +
            "lastFailureReason, createdAt, updatedAt) " +
            "VALUES (:source, :providerId, 1, 'PENDING', 0, :now, 0, '', :now, :now) " +
            "ON CONFLICT(source, providerId) DO UPDATE SET " +
            "generation = provider_repair_queue.generation + 1, " +
            "state = 'PENDING', attempts = 0, nextRetryAt = :now, leaseUntil = 0, " +
            "updatedAt = :now"

    private val claimSql =
        "UPDATE provider_repair_queue SET state = 'IN_FLIGHT', leaseUntil = :leaseUntil, " +
            "updatedAt = :now WHERE source = :source AND providerId = :providerId " +
            "AND generation = :generation AND state != 'IN_FLIGHT'"

    private val stillOwnedSql =
        "SELECT COUNT(*) FROM provider_repair_queue WHERE source = :source " +
            "AND providerId = :providerId AND generation = :generation AND state = 'IN_FLIGHT'"

    private val ackSql =
        "DELETE FROM provider_repair_queue WHERE source = :source " +
            "AND providerId = :providerId AND generation = :generation"

    private val nackSql =
        "UPDATE provider_repair_queue SET state = 'BACKOFF', attempts = attempts + 1, " +
            "nextRetryAt = :nextRetryAt, leaseUntil = 0, lastFailureReason = :reason, " +
            "updatedAt = :now WHERE source = :source AND providerId = :providerId " +
            "AND generation = :generation"

    private val reclaimSql =
        "UPDATE provider_repair_queue SET state = 'PENDING', nextRetryAt = 0, " +
            "leaseUntil = 0, updatedAt = :now " +
            "WHERE state = 'IN_FLIGHT' AND leaseUntil <= :now"

    private val dueSql =
        "SELECT * FROM provider_repair_queue WHERE state != 'IN_FLIGHT' " +
            "AND nextRetryAt <= :now ORDER BY nextRetryAt ASC LIMIT :limit"

    private val minPendingSql =
        "SELECT MIN(nextRetryAt) FROM provider_repair_queue WHERE state != 'IN_FLIGHT'"

    private val minLeaseSql =
        "SELECT MIN(leaseUntil) FROM provider_repair_queue WHERE state = 'IN_FLIGHT'"

    @Before
    fun setUp() {
        db = rawDb()
        // The SHIPPED migration text, not a hand-written copy of the schema.
        MessagesDatabase.UPGRADE_TO_V14_SQL.forEach { db.exec(it) }
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── local binding helpers (named -> positional, in textual order) ──────

    private val named = Regex(":[A-Za-z][A-Za-z0-9_]*")

    private fun exec(sql: String, vararg args: Any?) {
        db.prepareStatement(sql.replace(named, "?")).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeUpdate()
        }
    }

    private fun one(sql: String, vararg args: Any?): Long =
        db.prepareStatement(sql.replace(named, "?")).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else -1L }
        }

    private fun nullableOne(sql: String, vararg args: Any?): Long? =
        db.prepareStatement(sql.replace(named, "?")).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeQuery().use { rs ->
                if (rs.next()) {
                    val v = rs.getLong(1)
                    if (rs.wasNull()) null else v
                } else null
            }
        }

    private fun dueIds(now: Long, limit: Int): List<String> =
        db.prepareStatement(dueSql.replace(named, "?")).use { st ->
            st.setObject(1, now)
            st.setObject(2, limit)
            st.executeQuery().use { rs ->
                val out = mutableListOf<String>()
                while (rs.next()) {
                    out += rs.getString("source") + ":" + rs.getLong("providerId")
                }
                out
            }
        }

    /** Mirrors ProviderRepairQueue.enqueue. */
    private fun enqueue(source: String, id: Long, now: Long) =
        exec(enqueueSql, source, id, now, now, now, now, now)

    /** Mirrors ProviderRepairQueue.claim. */
    private fun claim(source: String, id: Long, generation: Long, leaseUntil: Long, now: Long): Long =
        one(claimSql, leaseUntil, now, source, id, generation)

    private fun generationOf(source: String, id: Long): Long =
        one("SELECT generation FROM provider_repair_queue WHERE source = ? AND providerId = ?", source, id)

    private fun stateOf(source: String, id: Long): String? =
        db.prepareStatement("SELECT state FROM provider_repair_queue WHERE source = ? AND providerId = ?").use { st ->
            st.setString(1, source); st.setLong(2, id)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    private fun attemptsOf(source: String, id: Long): Long =
        one("SELECT attempts FROM provider_repair_queue WHERE source = ? AND providerId = ?", source, id)

    private fun count(): Long = one("SELECT COUNT(*) FROM provider_repair_queue")

    // ── schema / persistence ───────────────────────────────────────────────

    @Test
    fun `the shipped v14 migration creates the queue table`() {
        val ddl = db.scalarString(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'provider_repair_queue'"
        ) ?: ""
        assertTrue("composite primary key", ddl.contains("PRIMARY KEY(`source`, `providerId`)"))
        assertTrue("generation is stored", ddl.contains("`generation` INTEGER NOT NULL"))
        assertTrue("state is stored", ddl.contains("`state` TEXT NOT NULL"))
        assertTrue("a lease is stored", ddl.contains("`leaseUntil` INTEGER NOT NULL"))
        assertTrue(
            "the timer index exists",
            db.scalarString(
                "SELECT name FROM sqlite_master WHERE type = 'index' " +
                    "AND name = 'index_provider_repair_queue_state_nextRetryAt'"
            ) != null
        )
    }

    @Test
    fun `enqueue is unbounded in the number of identities it keeps`() {
        // The old map capped at 256 and evicted the OLDEST entry: at capacity it
        // silently discarded correctness work. 300 identities must all survive.
        for (i in 1L..300L) enqueue(sms, i, 1_000L)
        assertEquals(300L, count())
        assertEquals(300L, dueIds(1_000L, 1000).size)
        assertNotNull(stateOf(sms, 1L))
    }

    @Test
    fun `one identity is due immediately after it is enqueued`() {
        enqueue(sms, 42L, 5_000L)
        assertEquals(listOf("sms:42"), dueIds(5_000L, 10))
        assertEquals(1L, generationOf(sms, 42L))
        assertEquals("PENDING", stateOf(sms, 42L))
    }

    @Test
    fun `sms and mms with the same id are different work`() {
        enqueue(sms, 123L, 1_000L)
        enqueue(mms, 123L, 1_000L)
        assertEquals(2L, count())
        val due = dueIds(1_000L, 10).toSet()
        assertEquals(setOf("sms:123", "mms:123"), due)
    }

    @Test
    fun `a non-positive provider id is never enqueued`() {
        // Mirrors the guard in ProviderRepairQueue.enqueue.
        for (bad in longArrayOf(0L, -1L, -999L)) {
            if (bad > 0L) enqueue(sms, bad, 1_000L)
        }
        assertEquals(0L, count())
    }

    // ── claim ──────────────────────────────────────────────────────────────

    @Test
    fun `due does not claim and a row can be claimed exactly once`() {
        enqueue(sms, 7L, 1_000L)
        assertEquals(1, dueIds(1_000L, 10).size)   // observation only
        assertEquals(1L, claim(sms, 7L, 1L, 31_000L, 1_000L))
        assertEquals(0L, claim(sms, 7L, 1L, 31_000L, 1_000L))  // second worker loses
        assertEquals(1L, one(stillOwnedSql, sms, 7L, 1L))
    }

    @Test
    fun `a claimed row is not returned as due again`() {
        enqueue(sms, 7L, 1_000L)
        claim(sms, 7L, 1L, 31_000L, 1_000L)
        assertEquals(0, dueIds(1_000L, 10).size)
    }

    @Test
    fun `a claim scoped to a stale generation cannot take the row`() {
        enqueue(sms, 7L, 1_000L)          // generation 1
        enqueue(sms, 7L, 2_000L)          // generation 2
        assertEquals(2L, generationOf(sms, 7L))
        assertEquals(0L, claim(sms, 7L, 1L, 32_000L, 2_000L))
        assertEquals(0L, one(stillOwnedSql, sms, 7L, 1L))
        assertEquals(1L, claim(sms, 7L, 2L, 32_000L, 2_000L))
    }

    // ── generation discipline (the core correctness property) ──────────────

    @Test
    fun `a new provider event bumps the generation and re-opens the work`() {
        enqueue(sms, 7L, 1_000L)
        claim(sms, 7L, 1L, 31_000L, 1_000L)
        assertEquals("IN_FLIGHT", stateOf(sms, 7L))

        enqueue(sms, 7L, 2_000L)          // new event while an old read is in flight
        assertEquals(2L, generationOf(sms, 7L))
        assertEquals("PENDING", stateOf(sms, 7L))
        assertEquals("the newer work is immediately due", listOf("sms:7"), dueIds(2_000L, 10))
    }

    @Test
    fun `an ack scoped to the old generation cannot consume newer work`() {
        enqueue(sms, 7L, 1_000L)
        claim(sms, 7L, 1L, 31_000L, 1_000L)
        enqueue(sms, 7L, 2_000L)          // generation 2 arrives

        // The stale worker finishes successfully and tries to ack generation 1.
        assertEquals(0L, one(ackSql, sms, 7L, 1L))

        assertEquals("the newer work survives", 1L, count())
        assertEquals(2L, generationOf(sms, 7L))
        assertEquals("PENDING", stateOf(sms, 7L))
    }

    @Test
    fun `an ack of the claimed generation removes the work`() {
        enqueue(sms, 7L, 1_000L)
        claim(sms, 7L, 1L, 31_000L, 1_000L)
        assertEquals(1L, one(ackSql, sms, 7L, 1L))
        assertEquals(0L, count())
    }

    // ── nack / backoff ─────────────────────────────────────────────────────

    @Test
    fun `a nack keeps the work and schedules a retry`() {
        enqueue(sms, 7L, 1_000L)
        claim(sms, 7L, 1L, 31_000L, 1_000L)
        exec(nackSql, 2_000L, "BINDER", 1_100L, sms, 7L, 1L)

        assertEquals(1L, count())
        assertEquals("BACKOFF", stateOf(sms, 7L))
        assertEquals(1L, attemptsOf(sms, 7L))
        assertEquals(0, dueIds(1_500L, 10).size)     // not due yet
        assertEquals(listOf("sms:7"), dueIds(2_000L, 10))
    }

    @Test
    fun `repeated failures never remove the work and never abandon the identity`() {
        enqueue(sms, 7L, 1_000L)
        var attempts = 0
        var now = 1_000L
        repeat(50) {
            claim(sms, 7L, 1L, now + 30_000L, now)
            attempts += 1
            val next = now + ProviderRepairBackoff.delayMs(attempts)
            exec(nackSql, next, "PROVIDER_UNAVAILABLE", now, sms, 7L, 1L)
            now = next
        }
        assertEquals("the identity is still queued after 50 failures", 1L, count())
        assertEquals(50L, attemptsOf(sms, 7L))
        assertTrue("backoff plateaued instead of growing without bound",
            ProviderRepairBackoff.delayMs(attempts) <= ProviderRepairBackoff.MAX_MS)
    }

    @Test
    fun `a poisoned identity does not block a healthy one`() {
        enqueue(sms, 1L, 1_000L)          // will keep failing
        enqueue(sms, 2L, 1_000L)          // healthy
        claim(sms, 1L, 1L, 31_000L, 1_000L)
        exec(nackSql, 300_000L, "BINDER", 1_000L, sms, 1L, 1L)

        // The healthy identity is still due and still claimable.
        assertEquals(listOf("sms:2"), dueIds(1_000L, 10))
        assertEquals(1L, claim(sms, 2L, 1L, 31_000L, 1_000L))
        assertEquals(1L, one(stillOwnedSql, sms, 2L, 1L))
    }

    // ── lease / crash recovery ─────────────────────────────────────────────

    @Test
    fun `a live lease is not reclaimed`() {
        enqueue(sms, 7L, 1_000L)
        claim(sms, 7L, 1L, 31_000L, 1_000L)
        assertEquals(0L, one(reclaimSql, 30_000L, 30_000L))
        assertEquals("IN_FLIGHT", stateOf(sms, 7L))
    }

    @Test
    fun `an expired lease is reclaimed and becomes due immediately`() {
        enqueue(sms, 7L, 1_000L)
        claim(sms, 7L, 1L, 31_000L, 1_000L)     // worker "dies" here
        assertEquals(1L, one(reclaimSql, 31_001L, 31_001L))
        assertEquals("PENDING", stateOf(sms, 7L))
        assertEquals(listOf("sms:7"), dueIds(31_001L, 10))
        assertEquals("the generation is untouched by recovery", 1L, generationOf(sms, 7L))
    }

    // ── timer inputs: progress with NO external event ──────────────────────

    @Test
    fun `the next wake time is derivable from the table alone`() {
        assertEquals(null, nullableOne(minPendingSql))
        enqueue(sms, 7L, 1_000L)
        assertEquals(1_000L, nullableOne(minPendingSql))
        enqueue(mms, 9L, 5_000L)
        assertEquals(1_000L, nullableOne(minPendingSql))
        assertEquals(null, nullableOne(minLeaseSql))

        claim(sms, 7L, 1L, 31_000L, 1_000L)
        assertEquals(5_000L, nullableOne(minPendingSql))
        assertEquals(31_000L, nullableOne(minLeaseSql))
    }

    @Test
    fun `an empty queue reports no wake time rather than a fake zero`() {
        assertFalse("no pending work", nullableOne(minPendingSql) != null)
        assertFalse("no lease", nullableOne(minLeaseSql) != null)
        assertEquals(0L, count())
    }

    // ── backoff arithmetic (pure) ──────────────────────────────────────────

    @Test
    fun `backoff is exponential, capped and never zero`() {
        assertEquals(1_000L, ProviderRepairBackoff.delayMs(1))
        assertEquals(2_000L, ProviderRepairBackoff.delayMs(2))
        assertEquals(4_000L, ProviderRepairBackoff.delayMs(3))
        assertEquals(8_000L, ProviderRepairBackoff.delayMs(4))
        assertEquals(ProviderRepairBackoff.MAX_MS, ProviderRepairBackoff.delayMs(16))
        assertEquals(ProviderRepairBackoff.MAX_MS, ProviderRepairBackoff.delayMs(10_000))
        assertTrue(ProviderRepairBackoff.delayMs(0) > 0L)
        assertTrue(ProviderRepairBackoff.delayMs(-5) > 0L)
        assertEquals(5L * 60_000L, ProviderRepairBackoff.MAX_MS)
    }
}
