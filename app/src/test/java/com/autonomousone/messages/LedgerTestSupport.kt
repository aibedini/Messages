package com.autonomousone.messages

import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.SegmentCallbackState
import com.autonomousone.messages.data.SendSegmentSql
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types

/**
 * Real in-memory SQLite for the send_segments ledger tests.
 *
 * The ledger contract — immutable submittedAt, targeted callback UPDATE,
 * convergent handling of the two racing writers, UTC-day counting — IS SQL
 * behaviour, so it is tested as SQL against a real engine, using the very
 * statements that ship ([SendSegmentSql]), not a mock.
 *
 * The table is built by running the SHIPPED v12 migration over the SHIPPED v11
 * schema, so these tests also prove the migration produces the schema the ledger
 * statements run against.
 */

/** The shipped v4 -> v5 send_segments DDL (the pre-v12 shape). */
internal const val V11_SEND_SEGMENTS_DDL =
    "CREATE TABLE IF NOT EXISTS `send_segments` (`rowId` INTEGER NOT NULL, " +
        "`partIndex` INTEGER NOT NULL, `partCount` INTEGER NOT NULL, " +
        "`sentAt` INTEGER NOT NULL, `subscriptionId` INTEGER NOT NULL, " +
        "`success` INTEGER NOT NULL, PRIMARY KEY(`rowId`, `partIndex`))"

/** Mirrors the INSERT Room generates for SendSegmentEntity (@Insert IGNORE). */
internal const val INSERT_SEGMENT =
    "INSERT OR IGNORE INTO `send_segments` (`rowId`,`partIndex`," +
        "`partCount`,`submittedAt`,`subscriptionId`,`callbackAt`," +
        "`callbackResult`,`callbackState`,`callbackFailureCode`) " +
        "VALUES (?,?,?,?,?,?,?,?,?)"

private val NAMED_PARAM = Regex(":[A-Za-z][A-Za-z0-9_]*")

/** Opens an empty database without touching the schema. */
internal fun rawDb(path: String = ":memory:"): Connection =
    DriverManager.getConnection("jdbc:sqlite:" + path)

/** Creates the pre-v12 table and upgrades it with the shipped migration. */
internal fun Connection.migrateV11toV12(): Connection {
    exec(V11_SEND_SEGMENTS_DDL)
    MessagesDatabase.UPGRADE_TO_V12_SQL.forEach { exec(it) }
    MessagesDatabase.UPGRADE_TO_V13_SQL.forEach { exec(it) }
    return this
}

/** An empty ledger table in its post-migration (v12) shape. */
internal fun ledgerDb(path: String = ":memory:"): Connection = rawDb(path).migrateV11toV12()

internal fun Connection.exec(sql: String) {
    createStatement().use { it.executeUpdate(sql) }
}

/** Binds the shared SQL's named parameters positionally, in order of appearance. */
private fun Connection.prepared(sql: String, vararg args: Any?) =
    prepareStatement(sql.replace(NAMED_PARAM, "?")).use { st ->
        args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
        st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
    }

internal fun Connection.queryLong(sql: String, vararg args: Any?): Long = prepared(sql, *args)

internal fun Connection.execNamed(sql: String, vararg args: Any?) {
    prepareStatement(sql.replace(NAMED_PARAM, "?")).use { st ->
        args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
        st.executeUpdate()
    }
}

internal fun Connection.scalarString(sql: String): String? =
    createStatement().use { st ->
        st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null }
    }

/** The native submission write path (SmsSender.recordSegmentSubmissions). */
internal fun Connection.submitSegment(
    rowId: Long,
    partIndex: Int,
    partCount: Int,
    submittedAt: Long,
    subId: Int = 7
) {
    val inserted = prepareStatement(INSERT_SEGMENT).use { st ->
        st.setLong(1, rowId); st.setInt(2, partIndex); st.setInt(3, partCount)
        st.setLong(4, submittedAt); st.setInt(5, subId)
        st.setNull(6, Types.INTEGER); st.setNull(7, Types.INTEGER); st.setString(8, "PENDING")
        st.setNull(9, Types.VARCHAR)
        st.executeUpdate()
    }
    if (inserted == 0) {
        // A callback reached the ledger first: complete the submission fact.
        execNamed(SendSegmentSql.FILL_SUBMITTED_AT, submittedAt, rowId, partIndex)
    }
}

/** The modem callback write path (SmsStatusReceiver). */
internal fun Connection.applyCallback(
    rowId: Long,
    partIndex: Int,
    partCount: Int,
    callbackAt: Long,
    result: Int,
    state: SegmentCallbackState,
    subId: Int = 7,
    failureCode: String? = null
) {
    val updated = prepareStatement(SendSegmentSql.APPLY_CALLBACK.replace(NAMED_PARAM, "?")).use { st ->
        st.setLong(1, callbackAt); st.setInt(2, result); st.setString(3, state.name)
        st.setString(4, failureCode); st.setLong(5, rowId); st.setInt(6, partIndex)
        st.executeUpdate()
    }
    if (updated == 0) {
        prepareStatement(INSERT_SEGMENT).use { st ->
            st.setLong(1, rowId); st.setInt(2, partIndex); st.setInt(3, partCount)
            st.setNull(4, Types.INTEGER); st.setInt(5, subId)
            st.setLong(6, callbackAt); st.setInt(7, result); st.setString(8, state.name)
            st.setString(9, failureCode)
            st.executeUpdate()
        }
        execNamed(
            SendSegmentSql.APPLY_CALLBACK,
            callbackAt, result, state.name, failureCode, rowId, partIndex
        )
    }
}

internal fun Connection.submittedBetween(start: Long, end: Long): Long =
    queryLong(SendSegmentSql.COUNT_SUBMITTED_BETWEEN, start, end)

internal fun Connection.submittedBySubscription(start: Long, end: Long): Map<Int, Long> =
    prepareStatement(SendSegmentSql.SUBMITTED_BY_SUBSCRIPTION.replace(NAMED_PARAM, "?")).use { st ->
        st.setObject(1, start); st.setObject(2, end)
        st.executeQuery().use { rs ->
            val out = mutableMapOf<Int, Long>()
            while (rs.next()) out[rs.getInt(1)] = rs.getLong(2)
            out
        }
    }
