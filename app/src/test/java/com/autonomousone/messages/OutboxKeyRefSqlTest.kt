package com.autonomousone.messages

import com.autonomousone.messages.data.GatewayEventOutboxEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Mission §42: the rotation query, against real SQLite.
 *
 * `keyRef` is only worth populating if a rotation can ask "which not-yet-uploaded events are under the
 * key I am retiring?" and get an exact answer. The query has a hazard worth a test of its own: a key id
 * sits INSIDE a `live|history` value, so the obvious `LIKE :keyId || '%'` also matches a *different*
 * key whose id merely starts the same way. The failure would be silent — a rotation would re-encrypt
 * or deliberately skip rows it never claimed to touch — so the predicates are matched with explicit
 * delimiters and pinned here.
 *
 * Run against sqlite-jdbc rather than through Room because the thing under test IS the SQL.
 */
class OutboxKeyRefSqlTest {

    private val table = "gateway_event_outbox"

    /**
     * The SHIPPED predicate, taken from the entity rather than retyped — so the tested SQL and the
     * query Room runs cannot drift apart.
     */
    private val underKeySql =
        "SELECT COUNT(*) FROM `$table` WHERE state IN ('PENDING','RETRY_WAIT','SENDING') AND " +
            GatewayEventOutboxEntity.KEY_REF_MATCH_SQL

    private fun connection(): Connection {
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use {
            it.execute(
                "CREATE TABLE `$table` (`eventUuid` TEXT NOT NULL PRIMARY KEY, `state` TEXT NOT NULL, " +
                    "`keyRef` TEXT)"
            )
        }
        return connection
    }

    private fun insert(connection: Connection, uuid: String, state: String, keyRef: String?) {
        connection.prepareStatement(
            "INSERT INTO `$table` (`eventUuid`,`state`,`keyRef`) VALUES (?,?,?)"
        ).use {
            it.setString(1, uuid)
            it.setString(2, state)
            it.setString(3, keyRef)
            it.execute()
        }
    }

    /**
     * Bind the shipped predicate's named parameters as positional ones.
     *
     * JDBC has no named parameters, so `:keyId` is rewritten to `?` — textually, which is faithful
     * because the name appears only as a parameter. The count is derived rather than assumed so a
     * predicate that gains a parameter fails here instead of silently binding the wrong number.
     */
    private fun countUnderKey(connection: Connection, keyId: String): Int {
        val occurrences = Regex(":keyId").findAll(underKeySql).count()
        assertTrue("the predicate must bind :keyId at least once", occurrences > 0)
        val sql = underKeySql.replace(":keyId", "?")
        return connection.prepareStatement(sql).use {
            for (index in 1..occurrences) it.setString(index, keyId)
            it.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    // ── The counts ───────────────────────────────────────────────────────────

    @Test
    fun `aKeyIsFoundInEitherPosition`() {
        connection().use { connection ->
            insert(connection, "a", "PENDING", "live-1")
            insert(connection, "b", "PENDING", "live-1|hist-1")
            insert(connection, "c", "RETRY_WAIT", "live-2|hist-1")
            insert(connection, "d", "PENDING", "live-2|hist-2")

            assertEquals("live-1 must be found as a single key and as the live half", 2, countUnderKey(connection, "live-1"))
            assertEquals("hist-1 must be found in the history half", 2, countUnderKey(connection, "hist-1"))
            assertEquals("hist-2 must be found only where it appears", 1, countUnderKey(connection, "hist-2"))
            assertEquals("an unknown key matches nothing", 0, countUnderKey(connection, "live-9"))
        }
    }

    @Test
    fun `aKeyIdThatIsAPrefixOfAnotherDoesNotMatchIt`() {
        // The hazard. `LIKE 'live-1%'` would count `live-10`; the delimiter form must not. Two UUIDs
        // sharing a prefix is unlikely, and an invisible over-count is exactly why this is tested
        // rather than reasoned about.
        connection().use { connection ->
            insert(connection, "short", "PENDING", "live-1")
            insert(connection, "long", "PENDING", "live-10")
            insert(connection, "longer", "PENDING", "live-10|hist-10")
            insert(connection, "suffixed", "PENDING", "live-1x|hist-1")

            assertEquals(1, countUnderKey(connection, "live-1"))
            assertEquals(2, countUnderKey(connection, "live-10"))
            assertEquals("hist-1 must not swallow hist-10", 1, countUnderKey(connection, "hist-1"))
            assertEquals("and live-1 must not swallow live-1x", 1, countUnderKey(connection, "live-1"))
        }
    }

    @Test
    fun `onlyOutstandingRowsAreCounted`() {
        // The query exists to answer "what could still be re-encrypted?". An ACKED row is on the
        // server already and a DEAD_LETTER row is visibly parked, so neither is part of a rotation
        // decision — counting them would overstate the work and hide that nothing is left.
        connection().use { connection ->
            insert(connection, "pending", "PENDING", "live-1")
            insert(connection, "retry", "RETRY_WAIT", "live-1")
            insert(connection, "sending", "SENDING", "live-1")
            insert(connection, "acked", "ACKED", "live-1")
            insert(connection, "dead", "DEAD_LETTER", "live-1")

            assertEquals(3, countUnderKey(connection, "live-1"))
        }
    }

    @Test
    fun `rowsWithNoKeyReferenceAreNeitherMatchedNorHidden`() {
        // A null keyRef is a real state — a signed key grant, or a row written before the column was
        // populated — and it must not be counted under some key. The grouped query keeps it visible as
        // its own NULL group so the per-key counts cannot be mistaken for the whole population.
        connection().use { connection ->
            insert(connection, "grant", "PENDING", null)
            insert(connection, "legacy", "PENDING", "")
            insert(connection, "content", "PENDING", "live-1")

            assertEquals(1, countUnderKey(connection, "live-1"))
            assertEquals("the empty string is not a key id", 0, countUnderKey(connection, ""))
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT COUNT(*) FROM `$table` WHERE state IN ('PENDING','RETRY_WAIT','SENDING') " +
                        "AND keyRef IS NULL"
                ).use { rows ->
                    rows.next()
                    assertEquals(1, rows.getInt(1))
                }
            }
        }
    }

    @Test
    fun `theOutstandingStatesMatchTheCodeConstant`() {
        // The query hardcodes the state list in this test; the DAO interpolates the shipped constant.
        // If the constant changes, this test must be updated with it — deliberately, and visibly.
        val states = GatewayEventOutboxEntity.OUTSTANDING_STATES_SQL
        listOf("PENDING", "RETRY_WAIT", "SENDING").forEach {
            assertTrue("$it must be outstanding", states.contains("'$it'"))
        }
        assertTrue("ACKED is not outstanding", !states.contains("'ACKED'"))
    }
}
