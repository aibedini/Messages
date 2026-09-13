package com.autonomousone.messages

import com.autonomousone.messages.data.SegmentCallbackState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.sql.Connection

/**
 * The Home "SMS today" counter is COUNT(*) over the IMMUTABLE submission half of
 * the send_segments ledger. These tests run the real statement sequences of both
 * writers (SmsSender's submission write and SmsStatusReceiver's callback) against
 * a real SQLite engine.
 */
class SendSegmentLedgerTest {

    // Activity.RESULT_OK / SmsManager result codes (android.jar is a stub here).
    private val resultOk = -1
    private val genericFailure = 1
    private val noService = 2

    private val dayStart = 1_700_000_000_000L
    private val dayEnd = dayStart + 86_400_000L
    private val submittedAt = dayStart + 3_600_000L
    private val callbackAt = submittedAt + 500L

    private lateinit var db: Connection

    @Before fun setUp() { db = ledgerDb() }

    @After fun tearDown() { db.close() }

    private fun count(): Long = db.submittedBetween(dayStart, dayEnd)

    private fun rows(): Long = db.queryLong("SELECT COUNT(*) FROM `send_segments`")

    @Test
    fun oneSubmittedSegmentCountsOnce() {
        db.submitSegment(1, 0, 1, submittedAt)
        assertEquals(1L, count())
    }

    @Test
    fun aThreePartMultipartSendCountsThree() {
        repeat(3) { db.submitSegment(1, it, 3, submittedAt) }
        assertEquals(3L, count())
        assertEquals("one logical message, three billable segments", 1L, db.queryLong(
            "SELECT COUNT(DISTINCT `rowId`) FROM `send_segments`"
        ))
    }

    @Test
    fun aSuccessfulCallbackDoesNotCountTheSegmentTwice() {
        db.submitSegment(1, 0, 1, submittedAt)
        db.applyCallback(1, 0, 1, callbackAt, resultOk, SegmentCallbackState.CONFIRMED)
        assertEquals(1L, count())
        assertEquals(1L, rows())
    }

    @Test
    fun aFailedCallbackNeverMakesTheSubmittedCountGoBackwards() {
        db.submitSegment(1, 0, 1, submittedAt)
        db.submitSegment(2, 0, 1, submittedAt)
        assertEquals(2L, count())

        db.applyCallback(1, 0, 1, callbackAt, noService, SegmentCallbackState.FAILED)
        db.applyCallback(2, 0, 1, callbackAt, genericFailure, SegmentCallbackState.AMBIGUOUS)

        assertEquals("submissions are immutable facts", 2L, count())
        // …while the verdicts stay available as diagnostics.
        assertEquals(1L, db.queryLong(
            "SELECT COUNT(*) FROM `send_segments` WHERE `callbackState` = 'FAILED'"
        ))
        assertEquals(1L, db.queryLong(
            "SELECT COUNT(*) FROM `send_segments` WHERE `callbackState` = 'AMBIGUOUS'"
        ))
    }

    @Test
    fun aCallbackAfterMidnightDoesNotMoveYesterdaysSubmissionIntoToday() {
        val lastMomentOfDay = dayEnd - 100L
        val justAfterMidnight = dayEnd + 100L

        db.submitSegment(1, 0, 1, lastMomentOfDay)
        db.applyCallback(1, 0, 1, justAfterMidnight, resultOk, SegmentCallbackState.CONFIRMED)

        assertEquals("counts in the day it was submitted", 1L, db.submittedBetween(dayStart, dayEnd))
        assertEquals("never migrates into the next day", 0L, db.submittedBetween(dayEnd, dayEnd + 86_400_000L))
        assertEquals(lastMomentOfDay, db.queryLong("SELECT `submittedAt` FROM `send_segments`"))
        assertEquals(justAfterMidnight, db.queryLong("SELECT `callbackAt` FROM `send_segments`"))
    }

    @Test
    fun aDuplicateCallbackChangesNothing() {
        db.submitSegment(1, 0, 1, submittedAt)
        db.applyCallback(1, 0, 1, callbackAt, resultOk, SegmentCallbackState.CONFIRMED)
        db.applyCallback(1, 0, 1, callbackAt, resultOk, SegmentCallbackState.CONFIRMED)

        assertEquals(1L, count())
        assertEquals(1L, rows())
    }

    @Test
    fun bothWriteOrdersProduceTheIdenticalFinalRow() {
        // Native first.
        db.submitSegment(1, 0, 1, submittedAt)
        db.applyCallback(1, 0, 1, callbackAt, resultOk, SegmentCallbackState.CONFIRMED)
        // Callback first.
        db.applyCallback(2, 0, 1, callbackAt, resultOk, SegmentCallbackState.CONFIRMED)
        db.submitSegment(2, 0, 1, submittedAt)

        assertEquals(2L, count())
        assertEquals(
            "both rows carry the same submission time and verdict",
            2L,
            db.queryLong(
                "SELECT COUNT(*) FROM `send_segments` " +
                    "WHERE `submittedAt` = " + submittedAt +
                    " AND `callbackAt` = " + callbackAt +
                    " AND `callbackState` = 'CONFIRMED'"
            )
        )
    }

    @Test
    fun alternatingWriteOrdersAlwaysConvergeAndNeverFlipTheCount() {
        for (i in 0 until 200) {
            val rowId = 100L + i
            if (i % 2 == 0) {
                db.submitSegment(rowId, 0, 1, submittedAt)
                db.applyCallback(rowId, 0, 1, callbackAt, resultOk, SegmentCallbackState.CONFIRMED)
            } else {
                db.applyCallback(rowId, 0, 1, callbackAt, resultOk, SegmentCallbackState.CONFIRMED)
                db.submitSegment(rowId, 0, 1, submittedAt)
            }
            assertEquals("count stable at iteration " + i, (i + 1).toLong(), count())
        }
        assertEquals(
            "every row is identical regardless of which writer ran first",
            0L,
            db.queryLong(
                "SELECT COUNT(*) FROM `send_segments` WHERE `submittedAt` <> " +
                    submittedAt + " OR `callbackAt` <> " + callbackAt +
                    " OR `callbackState` <> 'CONFIRMED'"
            )
        )
    }

    @Test
    fun reopeningTheDatabasePreservesTheCount() {
        val file = Files.createTempFile("send-segments", ".db").toFile()
        file.delete()
        try {
            rawDb(file.absolutePath).migrateV11toV12().use { c ->
                c.submitSegment(1, 0, 2, submittedAt)
                c.submitSegment(1, 1, 2, submittedAt)
            }
            rawDb(file.absolutePath).use { c ->
                assertEquals(2L, c.submittedBetween(dayStart, dayEnd))
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun twoSimsShareTheTotalAndAggregatePerSim() {
        db.submitSegment(1, 0, 1, submittedAt, subId = 1)
        db.submitSegment(2, 0, 1, submittedAt, subId = 1)
        db.submitSegment(3, 0, 2, submittedAt, subId = 2)
        db.submitSegment(3, 1, 2, submittedAt, subId = 2)

        assertEquals(4L, count())
        assertEquals(mapOf(1 to 2L, 2 to 2L), db.submittedBySubscription(dayStart, dayEnd))
    }

    @Test
    fun aCallbackWithoutASubmissionFactIsNotCountedYet() {
        // A callback can win the race; until the submission fact lands the
        // segment is simply not counted — never counted and then un-counted.
        db.applyCallback(1, 0, 1, callbackAt, noService, SegmentCallbackState.FAILED)
        assertEquals(0L, count())

        db.submitSegment(1, 0, 1, submittedAt)
        assertEquals(1L, count())
    }
}
