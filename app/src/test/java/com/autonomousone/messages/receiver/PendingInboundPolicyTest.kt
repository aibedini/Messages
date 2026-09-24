package com.autonomousone.messages.receiver

import com.autonomousone.messages.data.PendingInboundSmsEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What to do with a held inbound message (mission §16/§52).
 *
 * The decision is small and the stakes are not symmetric: inserting when the message is already there
 * duplicates it, and giving up when it is not loses it. Both directions are pinned here.
 */
class PendingInboundPolicyTest {

    private fun decide(
        attempts: Int = 0,
        found: Boolean = false,
        readable: Boolean = true,
    ) = PendingInboundPolicy.decide(attempts = attempts, foundInProvider = found, providerReadable = readable)

    // ── Look before writing ──────────────────────────────────────────────────

    @Test
    fun `aMessageAlreadyInTheProviderIsNotInsertedAgain`() {
        // `insert` can COMMIT and still fail (it throws, or returns a URI with no usable id), so a row
        // held as PENDING may already be in the provider. Inserting again is the CASE C duplicate: one
        // physical SMS, two rows, two bubbles.
        assertEquals(PendingInboundAction.AlreadyThere, decide(found = true))
        assertEquals(PendingInboundAction.AlreadyThere, decide(attempts = 4, found = true))
    }

    @Test
    fun `aMessageThatIsProvablyAbsentIsInserted`() {
        assertEquals(PendingInboundAction.Insert, decide(attempts = 0, found = false))
        assertEquals(PendingInboundAction.Insert, decide(attempts = 1, found = false))
    }

    // ── A failed READ is not evidence ────────────────────────────────────────

    @Test
    fun `anUnreadableProviderIsNeitherAnInsertNorALoss`() {
        // The subtle one. If the provider could not be queried at all, we do not know whether the message
        // is there. Inserting would risk the duplicate; giving up would risk the loss. Neither is
        // justified by ignorance, so the row is left alone for the next pass.
        val action = decide(attempts = 0, found = false, readable = false)

        assertTrue(action is PendingInboundAction.GiveUp)
        assertEquals("provider_unreadable", (action as PendingInboundAction.GiveUp).reason)
        assertFalse(
            "an unread provider must never be read as 'absent'",
            action == PendingInboundAction.Insert
        )
    }

    @Test
    fun `anUnreadableProviderIsReportedAsUnreadableEvenWhenTheLookupSaidFound`() {
        // Contradictory input, resolved conservatively: a caller that could not read the provider has no
        // business reporting a finding from it.
        val action = decide(found = true, readable = false)
        assertEquals("provider_unreadable", (action as PendingInboundAction.GiveUp).reason)
    }

    // ── Giving up is bounded, and it keeps the row ───────────────────────────

    @Test
    fun `retriesAreBounded`() {
        assertEquals(
            PendingInboundAction.Insert,
            decide(attempts = PendingInboundSmsEntity.MAX_ATTEMPTS - 1)
        )
        val exhausted = decide(attempts = PendingInboundSmsEntity.MAX_ATTEMPTS)
        assertEquals(
            PendingInboundPolicy.REASON_ATTEMPTS_EXHAUSTED,
            (exhausted as PendingInboundAction.GiveUp).reason
        )
    }

    @Test
    fun `anUnreadableProviderUsesTheSharedReasonTheWorkerBranchesOn`() {
        // The worker decides whether to record an attempt or leave the row alone by comparing this
        // reason. A literal typed in both places could drift, and drifting here means "we do not know"
        // silently becoming "this message is lost".
        val action = decide(readable = false) as PendingInboundAction.GiveUp
        assertEquals(PendingInboundPolicy.REASON_PROVIDER_UNREADABLE, action.reason)
    }

    // ── The outcome of one attempt ───────────────────────────────────────────

    @Test
    fun `aStoredMessageIsDoneWhateverItTook`() {
        assertEquals(
            PendingInboundPolicy.AttemptOutcome.Done,
            PendingInboundPolicy.afterAttempt(attemptsSoFar = 0, stored = true, error = null)
        )
        assertEquals(
            PendingInboundPolicy.AttemptOutcome.Done,
            PendingInboundPolicy.afterAttempt(attemptsSoFar = 4, stored = true, error = null)
        )
    }

    @Test
    fun `aFailedAttemptIsHeldUntilTheCapThenFailed`() {
        // One transient error must not abandon a message that one more try would have stored; and the
        // cap must not be off by one, or a row is either retried for ever or given up on too early.
        val first = PendingInboundPolicy.afterAttempt(0, stored = false, error = "locked")
        assertEquals(PendingInboundPolicy.AttemptOutcome.Retry(attempts = 1, error = "locked"), first)

        val last = PendingInboundPolicy.afterAttempt(
            PendingInboundSmsEntity.MAX_ATTEMPTS - 1, stored = false, error = "locked"
        )
        assertEquals(
            PendingInboundPolicy.AttemptOutcome.Failed(
                attempts = PendingInboundSmsEntity.MAX_ATTEMPTS, error = "locked"
            ),
            last
        )
    }

    @Test
    fun `theAttemptCountNeverGoesBackwardsAndAlwaysAdvances`() {
        for (soFar in 0 until PendingInboundSmsEntity.MAX_ATTEMPTS + 2) {
            when (val outcome = PendingInboundPolicy.afterAttempt(soFar, stored = false, error = null)) {
                is PendingInboundPolicy.AttemptOutcome.Retry -> assertEquals(soFar + 1, outcome.attempts)
                is PendingInboundPolicy.AttemptOutcome.Failed -> assertEquals(soFar + 1, outcome.attempts)
                PendingInboundPolicy.AttemptOutcome.Done -> throw AssertionError("not stored")
            }
        }
    }

    @Test
    fun `theReasonForAFailedAttemptSurvivesToTheEnd`() {
        // It is the only thing that says whether this was a full disk, a locked provider or a permission
        // problem — and a FAILED row is what a support conversation will be about.
        val outcome = PendingInboundPolicy.afterAttempt(
            PendingInboundSmsEntity.MAX_ATTEMPTS - 1, stored = false, error = "disk full"
        ) as PendingInboundPolicy.AttemptOutcome.Failed

        assertEquals("disk full", outcome.error)
    }

    @Test
    fun `theAttemptCapIsSmallEnoughToSettleAndLargeEnoughToRideOutATransientFault`() {
        // Not a magic number to be tuned blindly: too low abandons a message that one more try would have
        // stored, too high leaves a support report unable to say whether a message is settled.
        assertTrue(PendingInboundSmsEntity.MAX_ATTEMPTS in 3..10)
    }

    // ── Insert outcomes ──────────────────────────────────────────────────────

    @Test
    fun `onlyAWrittenRowCountsAsStored`() {
        assertTrue(PendingInboundPolicy.isStored(InboxWriteAttempt.Wrote(5)))
        assertFalse(PendingInboundPolicy.isStored(InboxWriteAttempt.Threw("database is locked")))
        assertFalse(PendingInboundPolicy.isStored(InboxWriteAttempt.Threw(null)))
    }

    // ── The diagnostic line ──────────────────────────────────────────────────

    @Test
    fun `theDiagnosticLineCarriesNoBodyAndNoNumber`() {
        // A held message is the one case where a report has to say "a message was lost" and be believed,
        // so the row id, attempts and reason travel — and nothing that could leak.
        val row = PendingInboundSmsEntity(
            id = 7,
            pduFingerprint = "pdu-abc",
            address = "+989123456789",
            body = "the secret message body",
            dateMs = 1_700_000_000_000,
            threadId = 3,
            createdAt = 1_700_000_000_500,
            attempts = 2,
            lastError = "database is locked"
        )

        val line = PendingInboundPolicy.describe(row)

        assertTrue(line, line.contains("id=7"))
        assertTrue(line, line.contains("attempts=2"))
        assertTrue(line, line.contains("database is locked"))
        assertFalse("the body must never appear", line.contains("secret"))
        assertFalse("nor the number, nor any digits of it", line.contains("9891"))
        assertFalse(line, line.contains("pdu-abc"))
    }
}
