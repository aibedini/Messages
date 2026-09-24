package com.autonomousone.messages

import com.autonomousone.messages.data.GatewayEventOutboxEntity
import com.autonomousone.messages.sync.HistoryAckCandidate
import com.autonomousone.messages.sync.HistoryAckFrontier
import com.autonomousone.messages.sync.HistoryAckWalk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * The history walk's QUERY, against real SQLite, plus the rule applied to what it returns.
 *
 * The rule alone is not enough. `historyAfter` must return rows in ascending ordinal order and honour
 * the resumable bound, because the walk stops at the first non-contiguous row — an unordered read would
 * stop somewhere arbitrary and freeze the frontier, which is the failure this whole subsystem has
 * already produced three times. The SQL is taken from the shipped constant so this test cannot keep
 * passing while the DAO changes underneath it.
 *
 * This is the JVM replacement for coverage that previously existed **only** in an instrumented test, so
 * it never ran in this environment.
 */
class HistoryAckWatermarkSqlTest {

    private val table = "gateway_event_outbox"

    private val afterSql = GatewayEventOutboxEntity.HISTORY_AFTER_SQL
        // JDBC has no named parameters; the names appear only as parameters, so the rewrite is faithful.
        .replace(":source", "?")
        .replace(":generation", "?")
        .replace(":afterOrdinal", "?")
        .replace(":limit", "?")

    private fun connection(): Connection {
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use {
            it.execute(
                "CREATE TABLE `$table` (`id` INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "`eventUuid` TEXT NOT NULL, `state` TEXT NOT NULL, " +
                    "`historySource` TEXT, `historyGeneration` INTEGER, `historyOrdinal` INTEGER, " +
                    "`historyDate` INTEGER, `historyProviderId` INTEGER)"
            )
        }
        return connection
    }

    private fun produce(
        connection: Connection,
        ordinal: Long,
        state: String,
        source: String = "sms",
        generation: Long = 4,
        date: Long = ordinal * 100,
        providerId: Long = ordinal,
    ) {
        connection.prepareStatement(
            "INSERT INTO `$table` (`eventUuid`,`state`,`historySource`,`historyGeneration`," +
                "`historyOrdinal`,`historyDate`,`historyProviderId`) VALUES (?,?,?,?,?,?,?)"
        ).use {
            it.setString(1, "$source-$ordinal")
            it.setString(2, state)
            it.setString(3, source)
            it.setLong(4, generation)
            it.setLong(5, ordinal)
            it.setLong(6, date)
            it.setLong(7, providerId)
            it.execute()
        }
    }

    private fun read(connection: Connection, source: String, afterOrdinal: Long, limit: Int = 100) =
        connection.prepareStatement(afterSql).use {
            it.setString(1, source)
            it.setLong(2, 4)
            it.setLong(3, afterOrdinal)
            it.setInt(4, limit)
            it.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            HistoryAckCandidate(
                                ordinal = rows.getLong("historyOrdinal"),
                                state = rows.getString("state"),
                                date = rows.getLong("historyDate"),
                                providerId = rows.getLong("historyProviderId"),
                            )
                        )
                    }
                }
            }
        }

    /** The repository's loop: read from the frontier, ask the rule, repeat. */
    private fun walk(connection: Connection, source: String, from: HistoryAckFrontier): HistoryAckFrontier? {
        var frontier = from
        var advanced: HistoryAckFrontier? = null
        var guard = 0
        while (true) {
            val rows = read(connection, source, frontier.ordinal)
            val next = HistoryAckWalk.advance(frontier, rows) ?: break
            frontier = next
            advanced = next
            check(++guard < 100) { "the walk did not terminate" }
        }
        return advanced
    }

    @Test
    fun `theQueryReturnsRowsInAscendingOrdinalOrder`() {
        // The walk's stopping rule depends on this ordering entirely.
        connection().use { connection ->
            listOf(5L, 1L, 3L, 2L, 4L).forEach { produce(connection, it, "ACKED") }

            assertEquals(
                listOf(1L, 2L, 3L, 4L, 5L),
                read(connection, "sms", afterOrdinal = 0).map { it.ordinal }
            )
        }
    }

    @Test
    fun `theBoundIsExclusiveSoTheWalkResumesWithoutReReading`() {
        connection().use { connection ->
            listOf(1L, 2L, 3L).forEach { produce(connection, it, "ACKED") }

            assertEquals(listOf(2L, 3L), read(connection, "sms", afterOrdinal = 1).map { it.ordinal })
        }
    }

    @Test
    fun `anotherSourceOrGenerationIsNeverMixedIn`() {
        // Ordinals are per (source, generation). Mixing them would make the walk see a gap that is
        // really a different key space, and freeze one source because of the other's numbering.
        connection().use { connection ->
            produce(connection, 1, "ACKED", source = "sms")
            produce(connection, 2, "ACKED", source = "sms")
            produce(connection, 1, "ACKED", source = "mms")
            produce(connection, 2, "ACKED", source = "sms", generation = 5)

            assertEquals(listOf(1L, 2L), read(connection, "sms", 0).map { it.ordinal })
            assertEquals(listOf(1L), read(connection, "mms", 0).map { it.ordinal })
        }
    }

    @Test
    fun `aGapFreezesTheFrontierAndClosingItResumesToTheEnd`() {
        // The whole trap, end to end over real SQL: produce five rows, ack 1, 2, 4 and 5, walk to 2;
        // then ack 3 and walk again — it must reach 5 without anything else changing.
        connection().use { connection ->
            (1L..5L).forEach { produce(connection, it, "PENDING") }
            connection.createStatement().use {
                it.execute("UPDATE `$table` SET state = 'ACKED' WHERE historyOrdinal IN (1, 2, 4, 5)")
            }

            val stuck = walk(connection, "sms", HistoryAckFrontier.initial())!!
            assertEquals("the frontier stops at the gap", 2, stuck.ordinal)

            connection.createStatement().use {
                it.execute("UPDATE `$table` SET state = 'ACKED' WHERE historyOrdinal = 3")
            }
            val resumed = walk(connection, "sms", HistoryAckFrontier(2, 200, 2))!!
            assertEquals("and closing the gap resumes to the end", 5, resumed.ordinal)
            assertEquals(500, resumed.date)
        }
    }

    @Test
    fun `aDeadLetterStallsTheWalkInSqlExactlyAsTheRuleSays`() {
        connection().use { connection ->
            produce(connection, 1, "ACKED")
            produce(connection, 2, "DEAD_LETTER")
            produce(connection, 3, "ACKED")

            val frontier = walk(connection, "sms", HistoryAckFrontier.initial())!!
            assertEquals(1, frontier.ordinal)
        }
    }

    @Test
    fun `aRowThatWasReStampedOutOfOrderStallsRatherThanBeingSkipped`() {
        // Re-stamping is how an ordinal gets reused — and the reason the frontier freezes rather than
        // stepping over a hole. Here ordinal 3 is missing entirely and 4 exists; the walk must stop at 2.
        connection().use { connection ->
            listOf(1L, 2L, 4L).forEach { produce(connection, it, "ACKED") }

            assertEquals(2, walk(connection, "sms", HistoryAckFrontier.initial())!!.ordinal)
        }
    }

    @Test
    fun `aFinishedSourceWalksToItsLastOrdinalAndNoFurther`() {
        connection().use { connection ->
            (1L..3L).forEach { produce(connection, it, "ACKED") }

            val frontier = walk(connection, "sms", HistoryAckFrontier.initial())!!
            assertEquals(3, frontier.ordinal)
            assertNull(
                "nothing after the last ordinal",
                walk(connection, "sms", frontier)
            )
        }
    }

    @Test
    fun `theShippedQueryKeepsItsOrderingAndItsBound`() {
        // A source-level check on the shipped text itself. The SQL above is built from the same
        // constant, so this only fails if the constant loses a clause — which is exactly the change
        // that would silently stop the frontier from advancing.
        assertTrue(
            "the walk needs ordinal order",
            afterSql.contains("ORDER BY") || GatewayEventOutboxEntity.HISTORY_AFTER_SQL.contains("ORDER BY historyOrdinal")
        )
        assertTrue(
            "the walk needs an exclusive, resumable bound",
            GatewayEventOutboxEntity.HISTORY_AFTER_SQL.contains("historyOrdinal > :afterOrdinal")
        )
        assertTrue(
            "and it must stay scoped to one source and generation",
            GatewayEventOutboxEntity.HISTORY_AFTER_SQL.contains("historySource = :source") &&
                GatewayEventOutboxEntity.HISTORY_AFTER_SQL.contains("historyGeneration = :generation")
        )
    }
}
