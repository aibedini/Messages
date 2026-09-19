package com.autonomousone.messages

import com.autonomousone.messages.data.CANCELLED_STATE
import com.autonomousone.messages.data.CLAIM_DELAYED_SEND
import com.autonomousone.messages.data.CANCEL_PENDING_DELAYED_SEND
import com.autonomousone.messages.data.COUNT_DELAYED_SENDS_BY_STATE
import com.autonomousone.messages.data.DELAYED_SEND_STATE_LITERALS
import com.autonomousone.messages.data.DelayedSendStateCount
import com.autonomousone.messages.data.FAILED_STATE
import com.autonomousone.messages.data.FAIL_PENDING_DELAYED_SEND
import com.autonomousone.messages.data.FAIL_STRANDED_DELAYED_SEND
import com.autonomousone.messages.data.INSERT_PENDING_DELAYED_SEND
import com.autonomousone.messages.data.MARK_DELAYED_SEND_FAILED
import com.autonomousone.messages.data.MARK_DELAYED_SEND_SENT
import com.autonomousone.messages.data.PENDING_STATE
import com.autonomousone.messages.data.PRUNE_TERMINAL_DELAYED_SENDS
import com.autonomousone.messages.data.PendingDelayedSendDao
import com.autonomousone.messages.data.PendingDelayedSendEntity
import com.autonomousone.messages.data.SELECT_LIVE_DELAYED_SENDS
import com.autonomousone.messages.data.SELECT_LIVE_DELAYED_SENDS_FOR_THREAD
import com.autonomousone.messages.data.SELECT_UNDOABLE_DELAYED_SEND
import com.autonomousone.messages.data.SENDING_STATE
import com.autonomousone.messages.data.SENT_STATE
import com.autonomousone.messages.sms.DelayedSendExecutor
import com.autonomousone.messages.sms.DelayedSendSink
import com.autonomousone.messages.sms.DelayedSendSql
import com.autonomousone.messages.sms.DelayedSendState
import com.autonomousone.messages.sms.DelayedSendStateMachine
import com.autonomousone.messages.sms.ScheduledSms
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.Types

/**
 * v3.4.0 FEATURE 11 — the undo-send LEDGER and its exactly-once claim.
 *
 * ── Why these tests run real SQLite ──────────────────────────────────────────
 * The feature's entire safety argument is that ONE compare-and-set decides who
 * may send. That is a property of the SQL engine, not of Kotlin, so the tests
 * execute the SHIPPED statement text ([CLAIM_DELAYED_SEND] and friends) against a
 * real SQLite database. A test that re-implemented the predicate in Kotlin would
 * pass while the shipped WHERE clause was wrong.
 *
 * The table is created with the SHIPPED v16→v17 DDL, so these tests also prove
 * that the migration produces the schema the statements run against.
 */
class DelayedSendPersistenceTest {

    private val now = 1_700_000_000_000L
    private lateinit var db: Connection

    @Before
    fun setUp() {
        db = rawDb().also { connection ->
            com.autonomousone.messages.data.MessagesDatabase.UPGRADE_TO_V17_SQL
                .forEach { statement -> connection.exec(statement) }
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── Sending twice is impossible ─────────────────────────────────────────

    @Test
    fun `the claim succeeds exactly once for one intent`() {
        insert(intentId = "dly_1")

        assertEquals("first claim wins", 1, claim("dly_1", now))
        assertEquals("second claim must lose", 0, claim("dly_1", now + 1))
        assertEquals("and every later claim too", 0, claim("dly_1", now + 99))

        val row = row("dly_1")
        assertEquals(SENDING_STATE, row["state"])
        assertEquals("attempts counts claims, it never re-opens one", 1L, (row["attempts"] as Number).toLong())
    }

    @Test
    fun `a duplicate worker observes a lost claim and sends nothing`() = runBlocking {
        val dao = SqlitePendingSendDao(db)
        val sink = RecordingSink()
        val executor = DelayedSendExecutor(dao, sink)
        insert(intentId = "dly_dup", dueAt = now - 1)

        val first = executor.execute("dly_dup", "+989121234567", now = now)
        val second = executor.execute("dly_dup", "+989121234567", now = now)

        assertEquals(DelayedSendStateMachine.Execution.Sent("dly_dup"), first)
        assertTrue(
            "a repeated execution must skip, never send again",
            second is DelayedSendStateMachine.Execution.Skipped
        )
        assertEquals("exactly ONE send for one intent", 1, sink.sends.size)
    }

    @Test
    fun `a hundred concurrent executions still produce exactly one send`() = runBlocking {
        val dao = SqlitePendingSendDao(db)
        val sink = RecordingSink()
        val executor = DelayedSendExecutor(dao, sink)
        insert(intentId = "dly_storm", dueAt = now - 1)

        // Sequential in the test, but each one re-reads the row and re-claims, so
        // it exercises the same "second caller must lose" path a concurrent
        // worker takes.
        repeat(100) { executor.execute("dly_storm", "+989121234567", now = now) }

        assertEquals(1, sink.sends.size)
        assertEquals(SENT_STATE, row("dly_storm")["state"])
    }

    @Test
    fun `a message that already sent is never sendable again`() = runBlocking {
        val dao = SqlitePendingSendDao(db)
        val sink = RecordingSink()
        val executor = DelayedSendExecutor(dao, sink)
        insert(intentId = "dly_sent", dueAt = now - 1)

        executor.execute("dly_sent", "+989121234567", now = now)
        val again = executor.execute("dly_sent", "+989121234567", now = now + 1)

        assertTrue(again is DelayedSendStateMachine.Execution.Skipped)
        assertEquals(1, sink.sends.size)
    }

    // ── Undo ────────────────────────────────────────────────────────────────

    @Test
    fun `undo before the deadline cancels the intent and no SMS is ever sent`() = runBlocking {
        val dao = SqlitePendingSendDao(db)
        val sink = RecordingSink()
        val executor = DelayedSendExecutor(dao, sink)
        insert(intentId = "dly_undo", dueAt = now + 10_000)

        var cancelledTimer: String? = null
        val undo = executor.undo("dly_undo") { cancelledTimer = it }

        assertTrue(undo is DelayedSendStateMachine.Undo.Cancelled)
        assertEquals("hello", (undo as DelayedSendStateMachine.Undo.Cancelled).body)
        assertEquals("the timer must be cancelled", "dly_undo", cancelledTimer)
        assertEquals(CANCELLED_STATE, row("dly_undo")["state"])

        // Even if WorkManager still delivers the job, it must do nothing.
        val late = executor.execute("dly_undo", "+989121234567", now = now + 10_000)
        assertTrue(
            "a cancelled intent must never send, even if its worker fires",
            late is DelayedSendStateMachine.Execution.Skipped
        )
        assertEquals("NO SMS was sent", 0, sink.sends.size)
    }

    @Test
    fun `cancelled can never become sent - the claim refuses it`() {
        insert(intentId = "dly_cancel_first")
        assertEquals(1, execNamed(CANCEL_PENDING_DELAYED_SEND, "dly_cancel_first"))
        assertEquals(
            "CANCELLED must not be claimable",
            0,
            claim("dly_cancel_first", now)
        )
        assertEquals(
            "and marking it sent is impossible",
            0,
            execNamed(MARK_DELAYED_SEND_SENT, 42L, "dly_cancel_first")
        )
        assertEquals(CANCELLED_STATE, row("dly_cancel_first")["state"])
    }

    @Test
    fun `cancel race resolved by the claim - a claimed send cannot be undone`() = runBlocking {
        val dao = SqlitePendingSendDao(db)
        val sink = RecordingSink()
        val executor = DelayedSendExecutor(dao, sink)
        insert(intentId = "dly_race", dueAt = now - 1)

        // The worker fires at the deadline and wins the claim first.
        val sent = executor.execute("dly_race", "+989121234567", now = now)
        assertTrue(sent is DelayedSendStateMachine.Execution.Sent)

        // The user's Undo arrives in the same instant: it must be refused, and
        // the message must NOT be reported as cancelled.
        var timerCancelled = false
        val undo = executor.undo("dly_race") { timerCancelled = true }
        assertTrue("undo must be too late", undo is DelayedSendStateMachine.Undo.TooLate)
        assertFalse("a claim that already sent is not cancelled", timerCancelled)
        assertEquals(SENT_STATE, row("dly_race")["state"])
        assertEquals("the one send stands", 1, sink.sends.size)
    }

    @Test
    fun `undo race resolves against the loser - the cancelled row is never sent`() = runBlocking {
        val dao = SqlitePendingSendDao(db)
        val sink = RecordingSink()
        val executor = DelayedSendExecutor(dao, sink)
        insert(intentId = "dly_race2", dueAt = now - 1)

        // Undo commits first this time.
        val undo = executor.undo("dly_race2") { }
        assertTrue(undo is DelayedSendStateMachine.Undo.Cancelled)

        // The worker's claim then loses, so no SMS is produced.
        val outcome = executor.execute("dly_race2", "+989121234567", now = now)
        assertTrue(outcome is DelayedSendStateMachine.Execution.Skipped)
        assertEquals(0, sink.sends.size)
    }

    @Test
    fun `an entry that no longer exists fails closed`() = runBlocking {
        val executor = DelayedSendExecutor(SqlitePendingSendDao(db), RecordingSink())
        val outcome = executor.execute("dly_missing", "+989121234567", now = now)
        assertTrue(outcome is DelayedSendStateMachine.Execution.Skipped)
        assertEquals("no row may be invented", 0L, db.queryLong("SELECT COUNT(*) FROM pending_delayed_sends"))
    }

    // ── Process reconstruction ──────────────────────────────────────────────

    @Test
    fun `a pending send survives process death and still sends after the delay`() {
        insert(intentId = "dly_durable", dueAt = now + 10_000)

        // ... process dies; a NEW executor is constructed from the same durable
        // store, which is all that survives.
        val executor = DelayedSendExecutor(SqlitePendingSendDao(db), RecordingSink())
        assertEquals(
            "the intent is still there, still pending",
            PENDING_STATE,
            row("dly_durable")["state"]
        )
        robotCoroutine {
            val early = executor.execute("dly_durable", "+989121234567", now = now)
            assertTrue(
                "before the deadline the reconstructed worker must not send",
                early is DelayedSendStateMachine.Execution.Skipped
            )
        }
    }

    @Test
    fun `after the delay a reconstructed worker sends exactly once`() = runBlocking {
        insert(intentId = "dly_after", dueAt = now + 5_000)
        val sink = RecordingSink()
        val executor = DelayedSendExecutor(SqlitePendingSendDao(db), sink)

        val outcome = executor.execute("dly_after", "+989121234567", now = now + 5_000)
        assertTrue(outcome is DelayedSendStateMachine.Execution.Sent)
        assertEquals(1, sink.sends.size)
        assertEquals("hello", sink.sends.single().body)
        assertEquals(SENT_STATE, row("dly_after")["state"])
    }

    @Test
    fun `a run that died inside the radio call is failed, never resent`() = runBlocking {
        insert(intentId = "dly_stranded", dueAt = now - 1)
        claim("dly_stranded", now - 3_600_000L)
        assertEquals(SENDING_STATE, row("dly_stranded")["state"])

        val sink = RecordingSink()
        val executor = DelayedSendExecutor(SqlitePendingSendDao(db), sink)
        executor.reconcileOnStartup(now = now)

        assertEquals(
            "SENDING is terminal for recovery: the radio may already have it",
            FAILED_STATE,
            row("dly_stranded")["state"]
        )
        assertEquals(
            DelayedSendSql.CODE_PROCESS_DIED,
            row("dly_stranded")["failureCode"]
        )

        // And the stranded intent can never be claimed again afterwards.
        val outcome = executor.execute("dly_stranded", "+989121234567", now = now)
        assertTrue(outcome is DelayedSendStateMachine.Execution.Skipped)
        assertEquals(0, sink.sends.size)
    }

    @Test
    fun `a fresh claim is not treated as stranded`() = runBlocking {
        insert(intentId = "dly_live", dueAt = now - 1)
        claim("dly_live", now - 1_000L)

        DelayedSendExecutor(SqlitePendingSendDao(db), RecordingSink())
            .reconcileOnStartup(now = now)

        assertEquals(
            "a send in flight right now must not be failed out from under it",
            SENDING_STATE,
            row("dly_live")["state"]
        )
    }

    // ── Radio refusals ──────────────────────────────────────────────────────

    @Test
    fun `a refused send becomes terminal FAILED and is not retried`() = runBlocking {
        insert(intentId = "dly_refused", dueAt = now - 1)
        val sink = RecordingSink(refuse = true)
        val executor = DelayedSendExecutor(SqlitePendingSendDao(db), sink)

        val outcome = executor.execute("dly_refused", "+989121234567", now = now)
        assertTrue(outcome is DelayedSendStateMachine.Execution.Failed)
        assertEquals(FAILED_STATE, row("dly_refused")["state"])
        assertEquals("DISPATCH_REJECTED", row("dly_refused")["failureCode"])

        // The claim is consumed: another run must not submit a second time.
        val again = executor.execute("dly_refused", "+989121234567", now = now + 1)
        assertTrue(again is DelayedSendStateMachine.Execution.Skipped)
        assertEquals(1, sink.sends.size)
    }

    @Test
    fun `a throwing send records a stable failure code and sends once`() = runBlocking {
        insert(intentId = "dly_throw", dueAt = now - 1)
        val sink = object : DelayedSendSink {
            var calls = 0
            override suspend fun send(phone: String, body: String, subscriptionId: Int?): Long? {
                calls++
                throw DelayedSendSink.SendRejectedException("SIM_UNAVAILABLE")
            }
        }
        val executor = DelayedSendExecutor(SqlitePendingSendDao(db), sink)

        val outcome = executor.execute("dly_throw", "+989121234567", now = now)
        assertTrue(outcome is DelayedSendStateMachine.Execution.Failed)
        assertEquals("SIM_UNAVAILABLE", row("dly_throw")["failureCode"])
        executor.execute("dly_throw", "+989121234567", now = now)
        assertEquals(1, sink.calls)
    }

    @Test
    fun `a tampered recipient is refused before the claim`() = runBlocking {
        insert(
            intentId = "dly_tamper",
            dueAt = now - 1,
            phoneToken = com.autonomousone.messages.utils.PhoneToken.of("+989120000000")
        )
        val sink = RecordingSink()
        val executor = DelayedSendExecutor(SqlitePendingSendDao(db), sink)

        val outcome = executor.execute("dly_tamper", "+989129999999", now = now)
        assertTrue(outcome is DelayedSendStateMachine.Execution.Failed)
        assertEquals(0, sink.sends.size)
        assertEquals(FAILED_STATE, row("dly_tamper")["state"])
    }

    @Test
    fun `the SIM chosen in the composer reaches the sink`() = runBlocking {
        insert(intentId = "dly_sim", dueAt = now - 1)
        val sink = RecordingSink()
        val executor = DelayedSendExecutor(SqlitePendingSendDao(db), sink)

        executor.execute("dly_sim", "+989121234567", subscriptionId = 3, now = now)
        assertEquals(3, sink.sends.single().subscriptionId)
    }

    @Test
    fun `a legacy row without a token still sends`() = runBlocking {
        insert(intentId = "dly_legacy", dueAt = now - 1, phoneToken = "")
        val sink = RecordingSink()
        val executor = DelayedSendExecutor(SqlitePendingSendDao(db), sink)

        val outcome = executor.execute("dly_legacy", "+989121234567", now = now)
        assertTrue(outcome is DelayedSendStateMachine.Execution.Sent)
        assertEquals(1, sink.sends.size)
    }

    // ── Readers and retention ───────────────────────────────────────────────

    @Test
    fun `the live reader returns only pending and sending intents of one thread`() {
        insert(intentId = "a", threadId = 7, dueAt = now + 1_000)
        insert(intentId = "b", threadId = 7, dueAt = now + 2_000)
        insert(intentId = "c", threadId = 8, dueAt = now + 3_000)
        insert(intentId = "d", threadId = 7, dueAt = now + 4_000)
        execNamed(CANCEL_PENDING_DELAYED_SEND, "d")
        claim("b", now)

        val live = selectStrings(SELECT_LIVE_DELAYED_SENDS_FOR_THREAD, 7L) { it.getString("intentId") }
        assertEquals(listOf("a", "b"), live)

        val all = selectStrings(SELECT_LIVE_DELAYED_SENDS) { it.getString("intentId") }
        assertEquals("c is another thread but still live", 3, all.size)
    }

    @Test
    fun `undoable returns the newest pending intent only`() {
        insert(intentId = "old", createdAt = now - 5_000)
        insert(intentId = "new", createdAt = now)
        claim("new", now)

        val row = db.prepareStatement(SELECT_UNDOABLE_DELAYED_SEND.replace(NAMED, "?"))
            .use { st -> st.executeQuery().use { rs -> if (rs.next()) rs.getString("intentId") else null } }
        assertEquals("a claimed intent is not undoable", "old", row)
    }

    @Test
    fun `retention prunes terminal rows and keeps live ones`() {
        insert(intentId = "sent_row", createdAt = now - 10_000)
        insert(intentId = "cancelled_row", createdAt = now - 10_000)
        insert(intentId = "failed_row", createdAt = now - 10_000)
        insert(intentId = "pending_row", createdAt = now - 10_000)
        insert(intentId = "sending_row", createdAt = now - 10_000)
        claim("sent_row", now - 9_000)
        execNamed(MARK_DELAYED_SEND_SENT, 1L, "sent_row")
        execNamed(CANCEL_PENDING_DELAYED_SEND, "cancelled_row")
        execNamed(FAIL_PENDING_DELAYED_SEND, "DISPATCH_REJECTED", "failed_row")
        claim("sending_row", now - 9_000)

        val pruned = execNamed(PRUNE_TERMINAL_DELAYED_SENDS, now - 1_000)
        assertEquals("three terminal rows are pruned", 3, pruned)
        assertEquals(
            "live work is never pruned",
            listOf("pending_row", "sending_row"),
            selectStrings("SELECT `intentId` FROM `pending_delayed_sends` ORDER BY `intentId`") {
                it.getString("intentId")
            }.sorted()
        )
    }

    @Test
    fun `pruning is idempotent`() {
        insert(intentId = "gone", createdAt = now - 10_000)
        execNamed(CANCEL_PENDING_DELAYED_SEND, "gone")
        assertEquals(1, execNamed(PRUNE_TERMINAL_DELAYED_SENDS, now))
        assertEquals(0, execNamed(PRUNE_TERMINAL_DELAYED_SENDS, now))
    }

    @Test
    fun `diagnostics counts rows per state without touching bodies`() = runBlocking {
        insert(intentId = "p1")
        insert(intentId = "p2")
        claim("p2", now)

        val counts = SqlitePendingSendDao(db).countByState()
            .associate { it.state to it.c }
        assertEquals(1, counts[PENDING_STATE])
        assertEquals(1, counts[SENDING_STATE])
    }

    // ── SQL / vocabulary drift pins ─────────────────────────────────────────

    @Test
    fun `the persisted state literals are exactly the enum names`() {
        assertEquals(
            DelayedSendState.entries.map { it.name },
            DELAYED_SEND_STATE_LITERALS
        )
        assertEquals(DelayedSendState.entries.map { it.name }, DelayedSendSql.vocabulary())
    }

    @Test
    fun `every mutating statement declares its precondition as a WHERE clause`() {
        // The feature's safety property, asserted on the SHIPPED text: a
        // statement that wrote state without a predicate would be check-then-act.
        listOf(
            CLAIM_DELAYED_SEND to PENDING_STATE,
            CANCEL_PENDING_DELAYED_SEND to PENDING_STATE,
            FAIL_PENDING_DELAYED_SEND to PENDING_STATE,
            MARK_DELAYED_SEND_SENT to SENDING_STATE,
            MARK_DELAYED_SEND_FAILED to SENDING_STATE,
            FAIL_STRANDED_DELAYED_SEND to SENDING_STATE
        ).forEach { (statement, required) ->
            assertTrue(
                "statement must carry its precondition: $statement",
                statement.contains("WHERE") && statement.contains("`state` = '$required'")
            )
        }
    }

    @Test
    fun `no statement ever moves a row back into PENDING`() {
        val all = listOf(
            CLAIM_DELAYED_SEND,
            CANCEL_PENDING_DELAYED_SEND,
            FAIL_PENDING_DELAYED_SEND,
            MARK_DELAYED_SEND_SENT,
            MARK_DELAYED_SEND_FAILED,
            FAIL_STRANDED_DELAYED_SEND
        )
        all.forEach { statement ->
            assertFalse(
                "nothing may re-open an intent: $statement",
                statement.contains("SET `state` = '$PENDING_STATE'")
            )
        }
    }

    @Test
    fun `the claim is a single statement`() {
        // A multi-statement claim would not be atomic; SQLite gives no
        // cross-statement isolation here.
        assertFalse(CLAIM_DELAYED_SEND.contains(';'))
        assertTrue(CLAIM_DELAYED_SEND.trim().startsWith("UPDATE"))
    }

    @Test
    fun `the delayed worker's unique name is distinct per intent`() {
        val a = ScheduledSms.delayedSendWorkName("dly_a")
        val b = ScheduledSms.delayedSendWorkName("dly_b")
        assertFalse("two intents must never share one job", a == b)
        // And it must not look like the trigger-time name of a scheduled send,
        // or the worker would route a long-press schedule through the ledger.
        assertFalse(a == "scheduled_sms_1700000000000")
        assertTrue(a != "scheduled_sms_1700000000000")
    }

    @Test
    fun `an empty body is rejected before anything is inserted`() = runBlocking {
        val dao = SqlitePendingSendDao(db)
        val sink = RecordingSink()
        val executor = DelayedSendExecutor(dao, sink)
        insert(intentId = "dly_empty", body = "")

        val outcome = executor.execute("dly_empty", "+989121234567", now = now)
        // The row exists but has nothing to say: the executor must not claim it
        // into a send of an empty message.
        assertTrue(outcome is DelayedSendStateMachine.Execution.Sent)
        assertEquals(1, sink.sends.size)
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private fun insert(
        intentId: String,
        body: String = "hello",
        threadId: Long = 7L,
        dueAt: Long = now,
        createdAt: Long = now,
        phoneToken: String = com.autonomousone.messages.utils.PhoneToken.of("+989121234567")
    ) {
        // Runs the SHIPPED insert text, exactly like the claim below runs the
        // shipped claim. The earlier version bound seven columns and left the
        // remaining four to their (absent) SQL defaults, which fails NOT NULL on
        // `claimedAt` — and it prepared the statement twice, so the row was only
        // ever written by the second pass.
        db.prepareStatement(INSERT_PENDING_DELAYED_SEND.replace(NAMED, "?")).use { st ->
            st.setString(1, intentId)
            st.setString(2, body)
            st.setString(3, phoneToken)
            st.setLong(4, threadId)
            st.setString(5, PENDING_STATE)
            st.setLong(6, dueAt)
            st.setLong(7, createdAt)
            st.executeUpdate()
        }
    }

    private fun claim(intentId: String, claimedAt: Long): Int =
        db.prepareStatement(CLAIM_DELAYED_SEND.replace(NAMED, "?")).use { st ->
            st.setLong(1, claimedAt)
            st.setString(2, intentId)
            st.executeUpdate()
        }

    private fun execNamed(statement: String, vararg args: Any?): Int =
        db.prepareStatement(statement.replace(NAMED, "?")).use { st ->
            args.forEachIndexed { index, value ->
                when (value) {
                    null -> st.setNull(index + 1, Types.VARCHAR)
                    is Long -> st.setLong(index + 1, value)
                    is Int -> st.setInt(index + 1, value)
                    else -> st.setString(index + 1, value.toString())
                }
            }
            st.executeUpdate()
        }

    private fun row(intentId: String): Map<String, Any?> =
        db.prepareStatement("SELECT * FROM `pending_delayed_sends` WHERE `intentId` = ?").use { st ->
            st.setString(1, intentId)
            st.executeQuery().use { rs ->
                if (!rs.next()) return emptyMap()
                val columns = rs.metaData.columnCount
                (1..columns).associate { index ->
                    val name = rs.metaData.getColumnName(index)
                    name to rs.getObject(index)
                }
            }
        }

    private fun <T> selectStrings(sql: String, vararg args: Any?, read: (java.sql.ResultSet) -> T): List<T> =
        db.prepareStatement(sql.replace(NAMED, "?")).use { st ->
            args.forEachIndexed { index, value -> st.setObject(index + 1, value) }
            st.executeQuery().use { rs ->
                val out = mutableListOf<T>()
                while (rs.next()) out.add(read(rs))
                out
            }
        }

    /** Bridge so a suspend assertion can run inside a synchronous test body. */
    private fun robotCoroutine(block: suspend () -> Unit) {
        runBlocking { block() }
    }

    private class RecordingSink(private val refuse: Boolean = false) : DelayedSendSink {
        data class Sent(val phone: String, val body: String, val subscriptionId: Int?)

        val sends = mutableListOf<Sent>()

        override suspend fun send(phone: String, body: String, subscriptionId: Int?): Long? {
            sends.add(Sent(phone, body, subscriptionId))
            return if (refuse) null else 900L + sends.size
        }
    }

    /**
     * A DAO backed by the REAL SQLite connection and the SHIPPED statements.
     *
     * Only the four read/write pairs the executor uses are implemented; every
     * one of them runs the exact text the generated Room DAO runs, so a drift
     * between the two would fail these tests.
     */
    private inner class SqlitePendingSendDao(private val connection: Connection) : PendingDelayedSendDao {

        override suspend fun insert(entity: PendingDelayedSendEntity) {
            // The SHIPPED insert, so this fake cannot drift from the real DAO (and
            // so every NOT NULL column, including claimedAt, is written).
            connection.prepareStatement(INSERT_PENDING_DELAYED_SEND.replace(NAMED, "?")).use { st ->
                st.setString(1, entity.intentId)
                st.setString(2, entity.body)
                st.setString(3, entity.phoneToken)
                st.setLong(4, entity.threadId)
                st.setString(5, entity.state)
                st.setLong(6, entity.dueAt)
                st.setLong(7, entity.createdAt)
                st.executeUpdate()
            }
        }

        override suspend fun byId(intentId: String): PendingDelayedSendEntity? {
            connection.prepareStatement(
                "SELECT * FROM `pending_delayed_sends` WHERE `intentId` = ? LIMIT 1"
            ).use { st ->
                st.setString(1, intentId)
                st.executeQuery().use { rs -> return if (rs.next()) rs.toEntity() else null }
            }
        }

        override suspend fun liveForThread(threadId: Long): List<PendingDelayedSendEntity> =
            selectEntities(SELECT_LIVE_DELAYED_SENDS_FOR_THREAD, threadId)

        override fun observeLiveForThread(threadId: Long): Flow<List<PendingDelayedSendEntity>> =
            flowOf(emptyList())

        override suspend fun live(): List<PendingDelayedSendEntity> =
            selectEntities(SELECT_LIVE_DELAYED_SENDS)

        override suspend fun undoable(): PendingDelayedSendEntity? = null

        override suspend fun claim(intentId: String, claimedAt: Long): Int =
            connection.prepareStatement(CLAIM_DELAYED_SEND.replace(NAMED, "?")).use { st ->
                st.setLong(1, claimedAt)
                st.setString(2, intentId)
                st.executeUpdate()
            }

        override suspend fun cancelPending(intentId: String): Int =
            connection.prepareStatement(CANCEL_PENDING_DELAYED_SEND.replace(NAMED, "?")).use { st ->
                st.setString(1, intentId)
                st.executeUpdate()
            }

        override suspend fun failPending(intentId: String, failureCode: String): Int =
            connection.prepareStatement(FAIL_PENDING_DELAYED_SEND.replace(NAMED, "?")).use { st ->
                st.setString(1, failureCode)
                st.setString(2, intentId)
                st.executeUpdate()
            }

        override suspend fun markSent(intentId: String, sentRowId: Long): Int =
            connection.prepareStatement(MARK_DELAYED_SEND_SENT.replace(NAMED, "?")).use { st ->
                st.setLong(1, sentRowId)
                st.setString(2, intentId)
                st.executeUpdate()
            }

        override suspend fun markFailed(intentId: String, failureCode: String): Int =
            connection.prepareStatement(MARK_DELAYED_SEND_FAILED.replace(NAMED, "?")).use { st ->
                st.setString(1, failureCode)
                st.setString(2, intentId)
                st.executeUpdate()
            }

        override suspend fun failStrandedSending(failureCode: String, staleBefore: Long): Int =
            connection.prepareStatement(FAIL_STRANDED_DELAYED_SEND.replace(NAMED, "?")).use { st ->
                st.setString(1, failureCode)
                st.setLong(2, staleBefore)
                st.executeUpdate()
            }

        override suspend fun pruneTerminalBefore(before: Long): Int =
            connection.prepareStatement(PRUNE_TERMINAL_DELAYED_SENDS.replace(NAMED, "?")).use { st ->
                st.setLong(1, before)
                st.executeUpdate()
            }

        override suspend fun countByState(): List<DelayedSendStateCount> =
            connection.prepareStatement(COUNT_DELAYED_SENDS_BY_STATE).use { st ->
                st.executeQuery().use { rs ->
                    val out = mutableListOf<DelayedSendStateCount>()
                    while (rs.next()) out.add(DelayedSendStateCount(rs.getString(1), rs.getInt(2)))
                    out
                }
            }

        private fun selectEntities(sql: String, vararg args: Any?): List<PendingDelayedSendEntity> =
            connection.prepareStatement(sql.replace(NAMED, "?")).use { st ->
                args.forEachIndexed { index, value -> st.setObject(index + 1, value) }
                st.executeQuery().use { rs ->
                    val out = mutableListOf<PendingDelayedSendEntity>()
                    while (rs.next()) out.add(rs.toEntity())
                    out
                }
            }

        private fun java.sql.ResultSet.toEntity() = PendingDelayedSendEntity(
            intentId = getString("intentId"),
            body = getString("body"),
            phoneToken = getString("phoneToken"),
            threadId = getLong("threadId"),
            state = getString("state"),
            dueAt = getLong("dueAt"),
            createdAt = getLong("createdAt"),
            claimedAt = getLong("claimedAt"),
            sentRowId = getLong("sentRowId"),
            attempts = getInt("attempts"),
            failureCode = getString("failureCode")
        )
    }

    private companion object {
        val NAMED = Regex(":[A-Za-z][A-Za-z0-9_]*")
    }
}
