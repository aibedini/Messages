package com.autonomousone.messages

import com.autonomousone.messages.messaging.ComposerSendAction
import com.autonomousone.messages.messaging.ComposerSendOrchestrator
import com.autonomousone.messages.messaging.ComposerSendOutcome
import com.autonomousone.messages.messaging.ComposerSendRequest
import com.autonomousone.messages.messaging.ComposerSendRouter
import com.autonomousone.messages.messaging.SendResult
import com.autonomousone.messages.sms.DelayedSendState
import com.autonomousone.messages.sms.PendingDelayedSend
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 v3.4.3 — the composer send contract, on the JVM.
 *
 * ── What went wrong in 3.4.3 ─────────────────────────────────────────────────
 * `routeComposerSend` returned a nullable intent id and the caller read
 * `null` as "nothing was sent yet". With Send Delay OFF the coordinator's sink
 * had ALREADY submitted the SMS before returning `SendResult.SentNow`, so the
 * caller submitted it a second time. One tap, two chargeable SMS.
 *
 * These tests drive the PRODUCTION decision code ([ComposerSendRouter] +
 * [ComposerSendOrchestrator]) with counting fakes standing in for the physical
 * sink, because the invariant under test is a count: one tap => at most one
 * physical submission.
 *
 * NOTE: the delay-ON half of the invariant ("at worker execution: exactly one
 * submit") is owned by `DelayedSendPersistenceTest`, which exercises the
 * durable claim in `DelayedSendExecutor`.
 */
class ComposerSendRoutingTest {

    /** Counts what actually reached the radio. */
    private class Radio(var submits: Int = 0, var directSends: Int = 0)

    private fun request(intentId: String = "comp_test") = ComposerSendRequest(
        phone = "+989121234567",
        body = "hello",
        threadId = 7L,
        subscriptionId = null,
        intentId = intentId
    )

    private fun heldDelay(intentId: String = "comp_test", seconds: Long = 5L) =
        SendResult.DelayedSend(
            row = PendingDelayedSend(
                intentId = intentId,
                body = "hello",
                phoneToken = "token",
                threadId = 7L,
                state = DelayedSendState.PENDING,
                dueAt = 1_000L,
                createdAt = 0L
            ),
            delaySeconds = seconds
        )

    private fun orchestrator(
        radio: Radio,
        result: SendResult?,
        ledgerHolds: Boolean = false,
        throwInstead: Boolean = false
    ) = ComposerSendOrchestrator(
        routeSend = {
            if (throwInstead) throw IllegalStateException("ledger wedged")
            // The REAL coordinator's sink physically submits exactly when it
            // does not hold: SendNow means "already submitted".
            if (result is SendResult.SentNow) radio.submits++
            result
        },
        ledgerHolds = { ledgerHolds },
        directSend = {
            radio.submits++
            radio.directSends++
            999L
        }
    )

    // ── 1. delayOff_oneComposerTap_callsPhysicalSinkOnce ────────────────────
    @Test
    fun `delay OFF one composer tap submits exactly once`() = runTest {
        val radio = Radio()
        val outcome = orchestrator(radio, SendResult.SentNow(123L)).execute(request())

        assertEquals(ComposerSendOutcome.AlreadySent(123L), outcome)
        assertEquals("exactly one physical submission per tap", 1, radio.submits)
        assertEquals("the fallback must not run after SentNow", 0, radio.directSends)
    }

    // ── 2. delayOff_returnsSentNow_noFallbackSend ───────────────────────────
    @Test
    fun `SentNow never triggers the fallback direct sender`() = runTest {
        val radio = Radio()
        orchestrator(radio, SendResult.SentNow(500L)).execute(request())

        assertEquals(0, radio.directSends)
        assertEquals(1, radio.submits)
    }

    // ── 3. routeTimeout_fallbackSendsExactlyOnce ────────────────────────────
    @Test
    fun `timeout with no durable row falls back exactly once`() = runTest {
        val radio = Radio()
        val outcome = orchestrator(radio, null, ledgerHolds = false).execute(request())

        assertTrue(outcome is ComposerSendOutcome.FallbackSent)
        assertEquals(1, radio.directSends)
        assertEquals(1, radio.submits)
    }

    @Test
    fun `timeout after the durable insert is held, never a second submit`() = runTest {
        val radio = Radio()
        val outcome = orchestrator(radio, null, ledgerHolds = true).execute(request())

        assertEquals(ComposerSendOutcome.HeldAfterTimeout, outcome)
        assertEquals(0, radio.directSends)
        assertEquals(0, radio.submits)
    }

    @Test
    fun `a throwing route is unknown and falls back exactly once`() = runTest {
        val radio = Radio()
        val outcome = orchestrator(radio, null, throwInstead = true).execute(request())

        assertTrue(outcome is ComposerSendOutcome.FallbackSent)
        assertEquals(1, radio.directSends)
    }

    // ── 4. delayOn_noImmediatePhysicalSend ──────────────────────────────────
    @Test
    fun `delay ON holds the message with no immediate physical submit`() = runTest {
        val radio = Radio()
        val outcome = orchestrator(radio, heldDelay(seconds = 5L)).execute(request())

        assertEquals(ComposerSendOutcome.HeldForDelay(5L), outcome)
        assertEquals("a tap may not reach the radio while the ledger holds it", 0, radio.submits)
        assertEquals(0, radio.directSends)
    }

    @Test
    fun `a blank send is ignored and submits nothing`() = runTest {
        val radio = Radio()
        val outcome = orchestrator(radio, SendResult.Ignored).execute(request())

        assertEquals(ComposerSendOutcome.Ignored, outcome)
        assertEquals(0, radio.submits)
        assertEquals(0, radio.directSends)
    }

    // ── The decision table itself ───────────────────────────────────────────
    @Test
    fun `only a null route may fall back`() {
        assertEquals(ComposerSendAction.HeldForDelay, ComposerSendRouter.decide(heldDelay()))
        assertEquals(ComposerSendAction.AlreadySent(9L), ComposerSendRouter.decide(SendResult.SentNow(9L)))
        assertEquals(ComposerSendAction.Ignored, ComposerSendRouter.decide(SendResult.Ignored))
        assertEquals(ComposerSendAction.DirectFallback, ComposerSendRouter.decide(null))
    }

    @Test
    fun `SentNow with an unknown row id is still already sent`() {
        // A rejected dispatch reports null, but the row was persisted and the
        // outgoing event carries its id: this is NOT a licence to send again.
        assertEquals(
            ComposerSendAction.AlreadySent(null),
            ComposerSendRouter.decide(SendResult.SentNow(null))
        )
    }
}
