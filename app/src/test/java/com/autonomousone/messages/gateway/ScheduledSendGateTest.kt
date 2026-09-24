package com.autonomousone.messages.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The scheduled-send gate (mission §47/§78).
 *
 * A scheduled send is a *remotely requested* SMS (`POST /api/v1/sms/schedule`), so the mission's
 * absolute invariant applies to it: no retry may cause the same SMS to be sent twice.
 *
 * The defect this pins: `SendWorker.doWork` guarded only on `status != "scheduled"`, so a send that
 * succeeded but returned null — or a process death between the send and the status write — left the
 * entry reading `scheduled` and the next WorkManager attempt sent the message a second time
 * (`docs/gateway-replication-audit.md`, audit §78 risk).
 */
class ScheduledSendGateTest {

    private fun entry(
        scheduleId: String = "sch_1",
        status: String = "scheduled",
        submittedOnce: Boolean = false
    ) = GatewayScheduler.Entry(
        scheduleId = scheduleId,
        phone = "+989120000000",
        message = "hello",
        sendAt = 1_700_000_000_000L,
        createdAt = 1_000L,
        status = status,
        submittedOnce = submittedOnce
    )

    // ── The invariant ────────────────────────────────────────────────────────

    @Test
    fun `anEntryThatMayAlreadyHaveBeenSentIsNeverSentAgain`() {
        // The retry-after-a-lost-result case. This is the one that used to send twice.
        assertEquals(
            GatewayScheduler.ScheduledSendGate.Decision.SkipPossiblyAlreadySent,
            GatewayScheduler.ScheduledSendGate.decide(entry(submittedOnce = true))
        )
    }

    @Test
    fun `aFreshScheduledEntryIsClearedToSend`() {
        assertEquals(
            GatewayScheduler.ScheduledSendGate.Decision.Send,
            GatewayScheduler.ScheduledSendGate.decide(entry())
        )
    }

    // ── Everything else is skipped, never sent ───────────────────────────────

    @Test
    fun `aMissingEntrySendsNothing`() {
        // Cancelled while waiting: the work ran but the entry is gone.
        assertEquals(
            GatewayScheduler.ScheduledSendGate.Decision.SkipNotScheduled,
            GatewayScheduler.ScheduledSendGate.decide(null)
        )
    }

    @Test
    fun `everyTerminalStatusSendsNothing`() {
        // A status that is not "scheduled" means this work is finished, one way or another.
        listOf("sent", "failed", "cancelled").forEach { status ->
            assertEquals(
                "status=$status must not send",
                GatewayScheduler.ScheduledSendGate.Decision.SkipNotScheduled,
                GatewayScheduler.ScheduledSendGate.decide(entry(status = status))
            )
        }
    }

    @Test
    fun `aTerminalEntryWinsOverTheSubmitMarker`() {
        // If it is already finished, the marker is irrelevant — and it must not be pushed back into
        // the "possibly already sent" branch, which would rewrite a completed entry as failed.
        assertEquals(
            GatewayScheduler.ScheduledSendGate.Decision.SkipNotScheduled,
            GatewayScheduler.ScheduledSendGate.decide(
                entry(status = "sent", submittedOnce = true)
            )
        )
    }

    // ── The marker's meaning ─────────────────────────────────────────────────

    @Test
    fun `theMarkerIsOnlyConsultedForAnEntryThatWouldOtherwiseSend`() {
        // Guards against the marker being read as a general "already handled" flag: a cancelled
        // entry must stay cancelled, not become a manual-review failure.
        assertEquals(
            GatewayScheduler.ScheduledSendGate.Decision.SkipNotScheduled,
            GatewayScheduler.ScheduledSendGate.decide(
                entry(status = "cancelled", submittedOnce = false)
            )
        )
        assertEquals(
            GatewayScheduler.ScheduledSendGate.Decision.SkipPossiblyAlreadySent,
            GatewayScheduler.ScheduledSendGate.decide(
                entry(status = "scheduled", submittedOnce = true)
            )
        )
    }

    @Test
    fun `theInterruptedReasonIsNamedDistinctly`() {
        // "The dispatch failed" and "we may already have sent it and cannot tell" are different
        // facts, and only the second needs a human. A shared reason would hide that.
        assertEquals("interrupted_after_submit", GatewayScheduler.REASON_INTERRUPTED_AFTER_SUBMIT)
        assertEquals(3, GatewayScheduler.MAX_SEND_ATTEMPTS)
    }
}
