package com.autonomousone.messages

import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.data.SendSegmentSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationToV12SqlTest {

    @Test
    fun v12RebuildsTheLedgerWithoutErasingIt() {
        val sql = MessagesDatabase.UPGRADE_TO_V12_SQL
        assertEquals(5, sql.size)
        assertTrue(
            "legacy rows are copied over, with submittedAt mapped from sentAt",
            sql.any { it.startsWith("INSERT INTO") && it.contains("sentAt") && it.contains("CASE WHEN") }
        )
        assertEquals(1, sql.count { it.startsWith("DROP TABLE") })
        assertTrue("only the emptied old table is dropped", sql.none { it.contains("DELETE FROM") })
        assertTrue(sql.any { it.contains("index_send_segments_submittedAt") })
    }

    @Test
    fun legacyRowsKeepTheirCountsAndTheirDay() {
        val db = rawDb()
        try {
            db.exec(V11_SEND_SEGMENTS_DDL)
            db.exec(
                "INSERT INTO `send_segments` (`rowId`,`partIndex`," +
                    "`partCount`,`sentAt`,`subscriptionId`,`success`) " +
                    "VALUES (42,0,2,1700000000000,7,1)"
            )
            db.exec(
                "INSERT INTO `send_segments` (`rowId`,`partIndex`," +
                    "`partCount`,`sentAt`,`subscriptionId`,`success`) " +
                    "VALUES (42,1,2,1700000000000,7,0)"
            )

            MessagesDatabase.UPGRADE_TO_V12_SQL.forEach { db.exec(it) }

            assertEquals(
                "both legacy segments survive and are still counted",
                2L,
                db.submittedBetween(1699999999999L, 1700000000001L)
            )
            assertEquals(
                1700000000000L,
                db.queryLong("SELECT `submittedAt` FROM `send_segments` WHERE `partIndex` = 0")
            )
            assertEquals(
                "CONFIRMED",
                db.scalarString("SELECT `callbackState` FROM `send_segments` WHERE `partIndex` = 0")
            )
            assertEquals(
                "FAILED",
                db.scalarString("SELECT `callbackState` FROM `send_segments` WHERE `partIndex` = 1")
            )
            assertEquals(2L, db.queryLong("SELECT COUNT(*) FROM `send_segments`"))
        } finally {
            db.close()
        }
    }

    @Test
    fun theCounterQueryReadsOnlyTheImmutableColumn() {
        assertTrue(
            "the submitted counter must never be filtered by the mutable callback state",
            !SendSegmentSql.COUNT_SUBMITTED_BETWEEN.contains("callback") &&
                SendSegmentSql.COUNT_SUBMITTED_BETWEEN.contains("submittedAt")
        )
    }
}
