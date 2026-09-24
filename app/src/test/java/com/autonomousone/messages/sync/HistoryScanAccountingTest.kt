package com.autonomousone.messages.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mission §70: `Eligible = Enqueued + Skipped + Failed`, and every discrepancy explainable.
 *
 * The defect these tests exist for is a *number that could not be wrong*: the scan logged
 * `SYNC_REPORT eligible=N queued=N`, because `queued` was incremented for every row offered to the
 * enqueue path — including rows the ADR-006 firewall refused. Nothing ever failed; the line simply
 * asserted that nothing had been dropped. That is worse than no measurement, because it was believed.
 */
class HistoryScanAccountingTest {

    private fun session(
        eligible: Long = 0,
        enqueued: Long = 0,
        failed: Long = 0,
        skippedLocalOnly: Long = 0,
        skippedAskPending: Long = 0,
        skippedNoDirection: Long = 0,
        skippedSyncOff: Long = 0,
        scanExhausted: Boolean = false,
        finishedAt: Long = 0,
    ) = HistorySyncSession(
        sessionId = "s1",
        source = "sms",
        generation = 4,
        startedAt = 1,
        finishedAt = finishedAt,
        eligible = eligible,
        enqueued = enqueued,
        failed = failed,
        skippedLocalOnly = skippedLocalOnly,
        skippedAskPending = skippedAskPending,
        skippedNoDirection = skippedNoDirection,
        skippedSyncOff = skippedSyncOff,
        scanExhausted = scanExhausted,
    )

    // ── The classification: the pipeline's answer becomes the mission's ──────

    @Test
    fun `aFirewallRefusalIsASkipAndNotAFailure`() {
        // The heart of the old defect. A message the user's own policy keeps on the device did NOT
        // fail to replicate, and counting it as a failure would make the one number that matters
        // useless by filling it with correct behaviour.
        assertEquals(
            HistoryRowOutcome.SKIPPED_LOCAL_ONLY,
            HistoryScanAccounting.classify(HistoryRowObservation.Attempted(EnqueueAttempt.SKIPPED_LOCAL_ONLY))
        )
        assertEquals(
            HistoryRowOutcome.SKIPPED_ASK_PENDING,
            HistoryScanAccounting.classify(HistoryRowObservation.Attempted(EnqueueAttempt.SKIPPED_ASK_PENDING))
        )
    }

    @Test
    fun `anAwaitingAnswerSkipIsKeptApartFromAFinalLocalOnlyDecision`() {
        // One is provisional and may become eligible once the user answers; the other is final for
        // that message. Merging them would make "how much is waiting on the user?" unanswerable.
        val pending = HistoryScanAccounting.classify(
            HistoryRowObservation.Attempted(EnqueueAttempt.SKIPPED_ASK_PENDING)
        )
        val final = HistoryScanAccounting.classify(
            HistoryRowObservation.Attempted(EnqueueAttempt.SKIPPED_LOCAL_ONLY)
        )

        assertTrue(pending != final)
        assertEquals(HistoryRowOutcome.SKIPPED_ASK_PENDING, pending)
        assertEquals(HistoryRowOutcome.SKIPPED_LOCAL_ONLY, final)
    }

    @Test
    fun `anEventThatWasAlreadyThereCountsAsReplicated`() {
        // The insert raced a concurrent writer of the SAME event id — which is dedup working, not a
        // loss. Reporting it as a failure would make the alarm fire on a busy device.
        assertEquals(
            HistoryRowOutcome.ENQUEUED,
            HistoryScanAccounting.classify(HistoryRowObservation.Attempted(EnqueueAttempt.ALREADY_PRESENT))
        )
    }

    @Test
    fun `adoptingARealtimeRowCountsAsReplicatedAndRereadingOneDoesNot`() {
        // Two different states that look identical from the row: the first attribution of a row
        // realtime had already replicated is a success; a second sighting of an already-attributed
        // row is not a second row.
        assertEquals(
            HistoryRowOutcome.ENQUEUED,
            HistoryScanAccounting.classify(HistoryRowObservation.AdoptedExistingEvent)
        )
        assertEquals(
            HistoryRowOutcome.ALREADY_ACCOUNTED,
            HistoryScanAccounting.classify(HistoryRowObservation.AlreadyAccounted)
        )
        assertFalse(HistoryRowOutcome.ALREADY_ACCOUNTED.isAccounted)
    }

    @Test
    fun `aMissingEventAfterASuccessfulInsertIsTheFailure`() {
        assertEquals(
            HistoryRowOutcome.FAILED,
            HistoryScanAccounting.classify(HistoryRowObservation.EventMissingAfterInsert)
        )
    }

    @Test
    fun `aDraftIsASkipBecauseItWasNeverMeantToReplicate`() {
        assertEquals(
            HistoryRowOutcome.SKIPPED_NO_DIRECTION,
            HistoryScanAccounting.classify(HistoryRowObservation.NoDirection)
        )
    }

    // ── The arithmetic ───────────────────────────────────────────────────────

    @Test
    fun `theTallyBalancesByConstructionWhateverTheOutcomes`() {
        val delta = HistoryScanDelta.of(
            listOf(
                HistoryRowOutcome.ENQUEUED,
                HistoryRowOutcome.ENQUEUED,
                HistoryRowOutcome.FAILED,
                HistoryRowOutcome.SKIPPED_LOCAL_ONLY,
                HistoryRowOutcome.SKIPPED_ASK_PENDING,
                HistoryRowOutcome.SKIPPED_NO_DIRECTION,
                HistoryRowOutcome.SKIPPED_SYNC_OFF,
                HistoryRowOutcome.ALREADY_ACCOUNTED
            )
        )

        assertEquals(7, delta.eligible)
        assertEquals(2, delta.enqueued)
        assertEquals(1, delta.failed)
        assertEquals(4, delta.skipped)
        assertTrue("eligible must equal the sum", delta.balances)
    }

    @Test
    fun `aRowSeenTwiceIsCountedOnce`() {
        // The property that stops a cursor rewind from inflating the totals: only the sighting that
        // ATTRIBUTES the row counts.
        val delta = HistoryScanDelta.of(
            listOf(
                HistoryRowOutcome.ENQUEUED,
                HistoryRowOutcome.ALREADY_ACCOUNTED,
                HistoryRowOutcome.ALREADY_ACCOUNTED
            )
        )

        assertEquals(1, delta.eligible)
    }

    @Test
    fun `anEmptyScanBalancesAtZero`() {
        val delta = HistoryScanDelta.of(emptyList())

        assertEquals(0, delta.eligible)
        assertTrue(delta.balances)
    }

    @Test
    fun `foldingDeltasAccumulatesRatherThanReplacing`() {
        // A scan is many passes. An assignment would report only the last page, and the loss it was
        // meant to detect would be invisible arithmetic-wise.
        val first = HistoryScanAccounting.add(
            session(),
            HistoryScanDelta.of(listOf(HistoryRowOutcome.ENQUEUED, HistoryRowOutcome.SKIPPED_LOCAL_ONLY))
        )
        val second = HistoryScanAccounting.add(
            first,
            HistoryScanDelta.of(listOf(HistoryRowOutcome.ENQUEUED, HistoryRowOutcome.FAILED))
        )

        assertEquals(4, second.eligible)
        assertEquals(2, second.enqueued)
        assertEquals(1, second.skipped)
        assertEquals(1, second.failed)
        assertTrue(second.balances)
    }

    @Test
    fun `theSkipTotalIsDerivedFromTheReasonsItIsNotStored`() {
        // A stored total and stored parts are two copies of one fact and will eventually disagree.
        val s = session(eligible = 6, enqueued = 2, skippedLocalOnly = 3, skippedAskPending = 1)

        assertEquals(4, s.skipped)
        assertTrue(s.balances)
    }

    @Test
    fun `aSessionMissingRowsIsReportedAsUnbalanced`() {
        // The accounting's own bug, and it has to be visible: a residual means the numbers cannot be
        // trusted at all, which is different from "a message was lost".
        val s = session(eligible = 10, enqueued = 7)

        assertEquals(3, s.residual)
        assertFalse(s.balances)
    }

    @Test
    fun `balancedIsNotEnoughTheScanMustAlsoHaveFinished`() {
        // A balanced arithmetic over a scan that stopped halfway is "the part we looked at was
        // replicated", not "history is replicated". Conflating those is Blocker 9's SCAN_COMPLETE
        // reported as CAUGHT_UP, in a new place.
        val balancedButPartial = session(eligible = 10, enqueued = 10, scanExhausted = false)
        val balancedAndComplete = session(eligible = 10, enqueued = 10, scanExhausted = true)

        assertFalse(balancedButPartial.fullyAccounted)
        assertTrue(balancedAndComplete.fullyAccounted)
    }

    @Test
    fun `finishedAtZeroMeansStillScanning`() {
        assertTrue(session(finishedAt = 0).isOpen)
        assertFalse(session(finishedAt = 1).isOpen)
    }

    // ── What a human is told ─────────────────────────────────────────────────

    @Test
    fun `aCleanBalancedScanSaysNothing`() {
        // A diagnostic that always warns is a diagnostic nobody reads.
        assertNull(HistoryScanAccounting.alarm(session(eligible = 10, enqueued = 8, skippedLocalOnly = 2)))
        assertNull(
            HistoryScanAccounting.alarm(
                session(eligible = 10, enqueued = 10, scanExhausted = true, finishedAt = 5)
            )
        )
    }

    @Test
    fun `lostRowsAreNamedAsUnexplainedNotAsPolicy`() {
        val message = HistoryScanAccounting.alarm(session(eligible = 10, enqueued = 8, failed = 2))!!

        assertTrue(message, message.contains("2 message(s)"))
        assertTrue(message, message.contains("unexplained loss"))
        assertTrue(message, message.contains("not a policy decision"))
    }

    @Test
    fun `anUnbalancedScanSaysItsOwnNumbersCannotBeTrusted`() {
        // Distinct from the loss case on purpose: a residual is a bug in the accounting, and saying
        // "messages were lost" when the books do not add up would be a fabricated conclusion.
        val message = HistoryScanAccounting.alarm(session(eligible = 10, enqueued = 7))!!

        assertTrue(message, message.contains("residual 3"))
        assertTrue(message, message.contains("cannot be trusted"))
        assertFalse(message, message.contains("unexplained loss"))
    }

    @Test
    fun `anUnbalancedTallyIsReportedBeforeAnyConclusionAboutLoss`() {
        // Precedence, deliberately: `failed` is counted by the same tally that does not balance, so
        // announcing loss from broken books would be a fabricated conclusion. The accounting bug is
        // the first thing a reader must know.
        val message = HistoryScanAccounting.alarm(
            session(eligible = 10, enqueued = 7, failed = 1)
        )!!

        assertTrue(message, message.contains("cannot be trusted"))
        assertFalse("loss must not be announced from an unbalanced tally", message.contains("unexplained loss"))
    }

    @Test
    fun `lossIsReportedOnceTheTallyBalances`() {
        val message = HistoryScanAccounting.alarm(
            session(eligible = 10, enqueued = 9, failed = 1)
        )!!

        assertTrue(message, message.contains("unexplained loss"))
    }
}
