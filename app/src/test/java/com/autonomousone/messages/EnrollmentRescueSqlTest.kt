package com.autonomousone.messages

import com.autonomousone.messages.data.GatewayEventOutboxEntity
import com.autonomousone.messages.sync.OutboxRetryPolicy
import com.autonomousone.messages.sync.SyncErrorCode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * The post-enrollment dead-letter rescue (PR-11) — narrowed, and given a retry budget that works.
 *
 * Two defects motivate this test, and both were invisible because the statement was never
 * executed by anything but Room:
 *
 *  1. **Scope.** The predicate was a bare `WHERE state = 'DEAD_LETTER'`, so every successful
 *     enrollment resurrected EVERY dead letter ever recorded — including 400/422 contract
 *     failures the server had permanently judged, whose verdict a fresh credential cannot change.
 *     Since a 401 now re-enrolls the device, that ran on every auth-recovery cycle. The doomed
 *     rows are re-claimed into batches that are bounded at 100 events / 512 KB, so each one
 *     displaces a live event, and the reset wiped `lastErrorCode`, `failureHttpStatus`,
 *     `deadLetteredAt` and `lastErrorMessageSafe` — erasing the answer to "why is this stuck?".
 *
 *  2. **Budget.** `EventUploader` checks `exhausted(attemptCount)` BEFORE the increment, so a dead
 *     letter sits at 26 or more. The old query deliberately left `attemptCount` alone, so a
 *     rescued row was already past `MAX_ATTEMPTS` and its next failure — any failure, including a
 *     transient one — dead-lettered it again with zero retries. For its own stated case the rescue
 *     was a no-op.
 *
 * What must stay true is asserted from both ends: the set of codes is pinned as an explicit
 * literal (so a stray addition is a failure), and every code in the shipped list is then actually
 * fed through the real statement (so a query that stopped using the constant is a failure too).
 */
class EnrollmentRescueSqlTest {

    private val dbDir = "schemas/com.autonomousone.messages.data.MessagesDatabase"

    /** The codes the rescue must repair: each one names a credential or an identity. */
    private val expectedCodes = setOf(
        "AUTH_REQUIRED",
        "AUTH_EXPIRED",
        "IDENTITY_NOT_REGISTERED",
        "CRYPTO_KEY_UNAVAILABLE"
    )

    private fun schema(version: Int): JSONObject {
        val file = File("$dbDir/$version.json")
        assertTrue("Schema $version.json not found — run :app:kspDebugKotlin", file.exists())
        return JSONObject(file.readText())
    }

    /** Room's own CREATE TABLE for one entity, with ${TABLE_NAME} resolved. */
    private fun createTable(connection: Connection, table: String) {
        val entities = schema(19).getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            if (entity.getString("tableName") != table) continue
            connection.createStatement().use {
                it.execute(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            }
            return
        }
        throw AssertionError("$table is not declared in 19.json")
    }

    private fun connection(): Connection =
        DriverManager.getConnection("jdbc:sqlite::memory:").apply {
            createTable(this, "gateway_event_outbox")
        }

    /**
     * One outbox row in a state we choose.
     *
     * [attemptCount] defaults to a realistic dead letter: `exhausted()` is evaluated before the
     * increment, so the smallest row that can be dead-lettered by exhaustion has already been
     * written to 26.
     */
    private fun insert(
        connection: Connection,
        uuid: String,
        state: String = GatewayEventOutboxEntity.STATE_DEAD_LETTER,
        attemptCount: Int = 26,
        nextAttemptAt: Long = 0,
        errorCode: String? = null,
        httpStatus: Int? = null,
        failureCategory: String? = null,
        errorMessage: String? = null,
        deadLetteredAt: Long? = null,
        appVersion: String? = "3.4.8",
        leaseId: String? = null,
        cryptoVersion: Int = 3
    ) {
        connection.prepareStatement(
            """
            INSERT INTO `gateway_event_outbox`
                (`eventUuid`,`eventType`,`aggregateId`,`messageId`,`revision`,`sortKey`,
                 `priority`,`historySource`,`historyGeneration`,`historyOrdinal`,`historyDate`,
                 `historyProviderId`,`sequenceLocal`,`ciphertext`,`encoding`,`schemaVersion`,
                 `cryptoVersion`,`createdAt`,`attemptCount`,`nextAttemptAt`,`state`,
                 `serverSequence`,`ackedAt`,`failureCategory`,`failureHttpStatus`,`deadLetteredAt`,
                 `failureAppVersion`,`lastErrorCode`,`lastErrorMessageSafe`,`leaseId`)
            VALUES (?, 'MESSAGE_CREATED','agg','msg',1,0,'REALTIME','',0,0,0,0,0,X'01',
                    'envelope.v3',1,?,0,?,?,?,0,0,?,?,?,?,?,?,?)
            """.trimIndent()
        ).use { statement ->
            // Parameter order MUST follow the VALUES order. A missing setNull here shifts every
            // later binding by one, and the test then asserts against the wrong column while
            // still passing its shape checks — the shift that made this test lie once already.
            // The existing assertions on `lastErrorCode` are what prove this numbering.
            statement.setString(1, uuid)
            statement.setInt(2, cryptoVersion)
            statement.setInt(3, attemptCount)
            statement.setLong(4, nextAttemptAt)
            statement.setString(5, state)
            statement.setString(6, failureCategory)
            if (httpStatus == null) statement.setNull(7, java.sql.Types.INTEGER)
            else statement.setInt(7, httpStatus)
            if (deadLetteredAt == null) statement.setNull(8, java.sql.Types.INTEGER)
            else statement.setLong(8, deadLetteredAt)
            statement.setString(9, appVersion)
            statement.setString(10, errorCode)
            statement.setString(11, errorMessage)
            statement.setString(12, leaseId)
            statement.execute()
        }
    }

    /**
     * Runs the REAL shipped statement. It has no bound parameters, so nothing is substituted here —
     * the tested predicate IS the one Room executes.
     */
    private fun rescue(connection: Connection): Int =
        connection.createStatement().use {
            it.executeUpdate(GatewayEventOutboxEntity.RESCUE_ENROLLMENT_SQL)
        }

    private fun state(connection: Connection, uuid: String): String? =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT state FROM gateway_event_outbox WHERE eventUuid = '$uuid'"
            ).use { rows -> if (rows.next()) rows.getString(1) else null }
        }

    private fun attemptCount(connection: Connection, uuid: String): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT attemptCount FROM gateway_event_outbox WHERE eventUuid = '$uuid'"
            ).use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    /** The row's own explanation of itself, read back as one tuple. */
    private fun reason(connection: Connection, uuid: String): List<String?> =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT failureCategory, failureHttpStatus, deadLetteredAt, failureAppVersion, " +
                    "lastErrorCode, lastErrorMessageSafe, leaseId, inFlightSince " +
                    "FROM gateway_event_outbox WHERE eventUuid = '$uuid'"
            ).use { rows ->
                rows.next()
                (1..8).map { rows.getString(it) }
            }
        }

    // ── The code list ────────────────────────────────────────────────────────

    @Test
    fun `the rescue code list is exactly the codes a fresh credential repairs`() {
        val codes = GatewayEventOutboxEntity.RESCUE_ENROLLMENT_CODES_SQL
            .split(",")
            .map { it.trim().trim('\'') }
            .toSet()

        assertEquals(
            "the rescue scope is a decision, not an accident — changing it must be deliberate",
            expectedCodes,
            codes
        )

        // Every entry has to be a real code, or the comparison in SQL silently matches nothing.
        codes.forEach { SyncErrorCode.valueOf(it) }

        // The two classes that must never be here: a withdrawn authorization, and a verdict the
        // server has already reached about the EVENT rather than about our credential.
        assertFalse("a revoked device must not be resurrected", codes.contains("DEVICE_REVOKED"))
        assertFalse(codes.contains("INVALID_EVENT_SCHEMA"))
        assertFalse(codes.contains("PAYLOAD_TOO_LARGE"))
        assertFalse(codes.contains("SERVER_4XX"))
    }

    // ── Scope: what is rescued ───────────────────────────────────────────────

    @Test
    fun `every enrollment-recoverable code is rescued by the shipped statement`() {
        val codes = GatewayEventOutboxEntity.RESCUE_ENROLLMENT_CODES_SQL
            .split(",")
            .map { it.trim().trim('\'') }

        val connection = connection()
        try {
            codes.forEachIndexed { index, code ->
                insert(
                    connection,
                    uuid = "rescued-$index",
                    errorCode = code,
                    httpStatus = if (code == "DEVICE_REVOKED") 403 else 401,
                    failureCategory = "HTTP_AUTH",
                    errorMessage = "server said no",
                    deadLetteredAt = 1_700_000_000_000L,
                    leaseId = "stale-lease"
                )
            }

            assertEquals("every listed code must be rescued", codes.size, rescue(connection))

            codes.forEachIndexed { index, code ->
                val uuid = "rescued-$index"
                assertEquals("$code → PENDING", "PENDING", state(connection, uuid))
                assertEquals(
                    "$code must get its retry budget back",
                    0,
                    attemptCount(connection, uuid)
                )
                // The reason columns describe the failure we have just decided to retry under a
                // working credential, so they are cleared — including the stale lease.
                assertTrue(
                    "$code: reason must be cleared, was ${reason(connection, uuid)}",
                    reason(connection, uuid).all { it == null }
                )
            }
        } finally {
            connection.close()
        }
    }

    @Test
    fun `legacy rows with no error code are recognised by status and category`() {
        val connection = connection()
        try {
            // Dead-lettered by a build that predates lastErrorCode.
            insert(connection, "legacy-401", httpStatus = 401, failureCategory = "HTTP_AUTH")
            insert(connection, "legacy-tls", failureCategory = "TLS")

            assertEquals(2, rescue(connection))

            assertEquals("PENDING", state(connection, "legacy-401"))
            assertEquals("PENDING", state(connection, "legacy-tls"))
        } finally {
            connection.close()
        }
    }

    @Test
    fun `a permanent contract failure keeps its dead letter and its reason`() {
        val connection = connection()
        try {
            insert(
                connection,
                "rejected",
                errorCode = "INVALID_EVENT_SCHEMA",
                httpStatus = 422,
                failureCategory = "VALIDATION_FAILED",
                errorMessage = "field ciphertext rejected",
                deadLetteredAt = 1_700_000_000_000L
            )

            assertEquals("nothing may be rescued here", 0, rescue(connection))

            assertEquals("DEAD_LETTER", state(connection, "rejected"))
            val reason = reason(connection, "rejected")
            assertEquals("VALIDATION_FAILED", reason[0])
            assertEquals("422", reason[1])
            assertEquals("1700000000000", reason[2])
            assertEquals("INVALID_EVENT_SCHEMA", reason[4])
            assertEquals("field ciphertext rejected", reason[5])
        } finally {
            connection.close()
        }
    }

    @Test
    fun `a revoked device is never resurrected`() {
        val connection = connection()
        try {
            insert(
                connection,
                "revoked",
                errorCode = "DEVICE_REVOKED",
                httpStatus = 403,
                failureCategory = "HTTP_FORBIDDEN",
                errorMessage = "device revoked",
                deadLetteredAt = 1_700_000_000_000L
            )

            assertEquals(0, rescue(connection))

            assertEquals("DEAD_LETTER", state(connection, "revoked"))
            assertEquals("DEVICE_REVOKED", reason(connection, "revoked")[4])
        } finally {
            connection.close()
        }
    }

    @Test
    fun `a legacy contract failure is not rescued`() {
        val connection = connection()
        try {
            insert(connection, "legacy-422", httpStatus = 422, failureCategory = "HTTP_BAD_REQUEST")
            insert(connection, "legacy-500", httpStatus = 500, failureCategory = "HTTP_SERVER")

            assertEquals("only auth-shaped legacy rows qualify", 0, rescue(connection))

            assertEquals("DEAD_LETTER", state(connection, "legacy-422"))
            assertEquals("DEAD_LETTER", state(connection, "legacy-500"))
        } finally {
            connection.close()
        }
    }

    // ── Budget: what a rescued row can do next ───────────────────────────────

    @Test
    fun `a rescued row is no longer over budget`() {
        val connection = connection()
        try {
            insert(connection, "auth", attemptCount = 26, errorCode = "AUTH_REQUIRED", httpStatus = 401)

            // The premise of the defect: this row is already past the cap, so the uploader would
            // dead-letter it on its very next failure.
            assertTrue(OutboxRetryPolicy.exhausted(26))

            rescue(connection)

            val restored = attemptCount(connection, "auth")
            assertEquals(0, restored)
            assertFalse(
                "a rescued row must be able to retry — otherwise the rescue is a no-op",
                OutboxRetryPolicy.exhausted(restored)
            )
        } finally {
            connection.close()
        }
    }

    @Test
    fun `a rescued row is immediately claimable rather than waiting out a stale backoff`() {
        val connection = connection()
        try {
            insert(
                connection,
                "backed-off",
                nextAttemptAt = 9_000_000_000_000L,
                errorCode = "AUTH_REQUIRED",
                httpStatus = 401
            )

            rescue(connection)

            val nextAttemptAt = connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT nextAttemptAt FROM gateway_event_outbox WHERE eventUuid = 'backed-off'"
                ).use { rows ->
                    rows.next()
                    rows.getLong(1)
                }
            }
            assertEquals(0L, nextAttemptAt)
        } finally {
            connection.close()
        }
    }

    // ── Boundaries ───────────────────────────────────────────────────────────

    @Test
    fun `rows that are not dead letters are untouched`() {
        val connection = connection()
        try {
            insert(connection, "pending", state = "PENDING", errorCode = "AUTH_REQUIRED")
            insert(connection, "sending", state = "SENDING", attemptCount = 3, leaseId = "live-lease")
            insert(connection, "acked", state = "ACKED", attemptCount = 1, errorCode = "AUTH_REQUIRED")

            assertEquals(0, rescue(connection))

            assertEquals("PENDING", state(connection, "pending"))
            assertEquals("SENDING", state(connection, "sending"))
            assertEquals("ACKED", state(connection, "acked"))
            assertEquals("a live lease must survive the rescue", "live-lease", reason(connection, "sending")[6])
            assertEquals("a live attempt count must survive", 3, attemptCount(connection, "sending"))
            assertEquals("an ACKed row's history must survive", 1, attemptCount(connection, "acked"))
            assertEquals(
                "an ACKed row keeps its recorded code",
                "AUTH_REQUIRED",
                reason(connection, "acked")[4]
            )
        } finally {
            connection.close()
        }
    }

    @Test
    fun `the returned count is exactly the number of rows rescued`() {
        val connection = connection()
        try {
            insert(connection, "a", errorCode = "AUTH_REQUIRED", httpStatus = 401)
            insert(connection, "b", errorCode = "IDENTITY_NOT_REGISTERED")
            insert(connection, "c", errorCode = "INVALID_EVENT_SCHEMA", httpStatus = 422)

            assertEquals(2, rescue(connection))
            // Re-running must be a no-op: the rescued rows are no longer dead letters.
            assertEquals(0, rescue(connection))
        } finally {
            connection.close()
        }
    }

    @Test
    fun `an empty dead-letter cohort rescues nothing`() {
        val connection = connection()
        try {
            assertEquals(0, rescue(connection))
        } finally {
            connection.close()
        }
    }

    // ── The sibling rescue: the one-time v3 rollout cohort ───────────────────
    //
    // Same table, same uploader interaction, same defect: `attemptCount` was left alone here too,
    // so the rescued rows were already past `MAX_ATTEMPTS` and died again on their first failure.
    // This one is worse than wasteful, because it consumes `cryptoV3DeadLettersRecovered` — a
    // one-time flag — so a no-op rescue can never be attempted again.

    private fun rescueCrypto(connection: Connection, minCryptoVersion: Int): Int =
        connection.createStatement().use {
            it.executeUpdate(
                GatewayEventOutboxEntity.RESET_CRYPTO_DEAD_LETTER_SQL
                    .replace(":minCryptoVersion", minCryptoVersion.toString())
            )
        }

    @Test
    fun `the crypto rollout rescue restores the retry budget`() {
        val connection = connection()
        try {
            insert(connection, "v3", attemptCount = 26, cryptoVersion = 3)
            insert(connection, "v2", attemptCount = 31, cryptoVersion = 2)

            assertTrue(OutboxRetryPolicy.exhausted(26))

            assertEquals(2, rescueCrypto(connection, minCryptoVersion = 2))

            listOf("v3", "v2").forEach { uuid ->
                assertEquals("$uuid → PENDING", "PENDING", state(connection, uuid))
                assertEquals(
                    "$uuid must be able to retry after a one-time rescue, or the rescue is wasted",
                    0,
                    attemptCount(connection, uuid)
                )
                assertTrue(
                    "$uuid: reason must be cleared, was ${reason(connection, uuid)}",
                    reason(connection, uuid).all { it == null }
                )
            }
        } finally {
            connection.close()
        }
    }

    @Test
    fun `the crypto rollout rescue only touches the cohort it names`() {
        val connection = connection()
        try {
            insert(connection, "v3", cryptoVersion = 3)
            insert(connection, "v1", cryptoVersion = 1)
            insert(connection, "v0", cryptoVersion = 0)

            assertEquals(1, rescueCrypto(connection, minCryptoVersion = 2))

            assertEquals("PENDING", state(connection, "v3"))
            // An older protocol's dead letters are NOT part of the rollout cohort: rescuing them
            // would re-emit a payload the current server contract does not accept.
            assertEquals("DEAD_LETTER", state(connection, "v1"))
            assertEquals("DEAD_LETTER", state(connection, "v0"))
        } finally {
            connection.close()
        }
    }

    @Test
    fun `the two rescues are independent`() {
        val connection = connection()
        try {
            // An auth failure on an old protocol version: the enrollment rescue owns it, because a
            // credential repair is what it needs, not a contract upgrade.
            insert(connection, "old-auth", errorCode = "AUTH_REQUIRED", httpStatus = 401, cryptoVersion = 1)
            // A v3 row rejected under the pre-contract server: the rollout rescue owns it.
            insert(connection, "v3-rejected", errorCode = "INVALID_EVENT_SCHEMA", cryptoVersion = 3)

            assertEquals(1, rescue(connection))
            assertEquals("PENDING", state(connection, "old-auth"))
            assertEquals("DEAD_LETTER", state(connection, "v3-rejected"))

            assertEquals(1, rescueCrypto(connection, minCryptoVersion = 2))
            assertEquals("PENDING", state(connection, "v3-rejected"))
        } finally {
            connection.close()
        }
    }
}
