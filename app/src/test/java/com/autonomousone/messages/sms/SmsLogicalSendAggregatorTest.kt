package com.autonomousone.messages.sms

import android.telephony.SmsManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Burst closure and multipart aggregation.
 *
 * Two separate defects are covered here:
 *
 * 1. **Burst** — serialization alone was not enough. `SmsManager.send*` returns in milliseconds while
 *    the radio answers much later, so ten queued messages could all be pushed at a radio that was about
 *    to refuse them. The lane now dispatches ONE logical SMS per SIM and waits for its SENT evidence
 *    (or the bounded timeout) before the next. That is the fix for "one or two messages fail, then
 *    everything fails until a reboot".
 * 2. **Aggregation** — a 3-part message produces three callbacks, and "first RESULT_OK wins" made a
 *    message whose part 3 was rate-limited look sent.
 *
 * NOTE: written and executed as part of this change (pure, no Android runtime).
 */
class SmsLogicalSendAggregatorTest {

    private fun ok() = SmsTransportClassifier.classify(android.app.Activity.RESULT_OK)
    private fun rateLimited() = SmsTransportClassifier.classify(SmsManager.RESULT_RIL_REQUEST_RATE_LIMITED)
    private fun generic() = SmsTransportClassifier.classify(SmsManager.RESULT_ERROR_GENERIC_FAILURE)
    private fun noService() = SmsTransportClassifier.classify(SmsManager.RESULT_ERROR_NO_SERVICE)

    private fun aggregateOf(parts: Int, vararg verdicts: Pair<Int, SmsTransportVerdict>): LogicalSendVerdict {
        val tally = SmsLogicalSendAggregator.Tally(parts)
        verdicts.forEach { (index, verdict) -> tally.record(index, verdict) }
        return SmsLogicalSendAggregator.aggregate(tally)
    }

    // ── the required matrix (mission §7) ─────────────────────────────────────

    @Test
    fun `all parts confirmed is a confirmed logical send`() {
        val verdict = aggregateOf(3, 0 to ok(), 1 to ok(), 2 to ok())

        assertEquals(LogicalSendOutcome.SENT_CONFIRMED, verdict.outcome)
        assertEquals(3, verdict.confirmedParts)
        assertTrue(verdict.healsSimHealth)
        assertEquals(RetrySafety.NOT_APPLICABLE, verdict.retrySafety)
    }

    @Test
    fun `one rate-limited part makes the whole logical send throttled`() {
        val verdict = aggregateOf(3, 0 to ok(), 1 to rateLimited(), 2 to ok())

        assertEquals(LogicalSendOutcome.THROTTLED, verdict.outcome)
        assertEquals("RIL_RATE_LIMITED", verdict.failure?.code)
        assertEquals(1, verdict.throttledParts)
        assertFalse("a throttled send must never look sent", verdict.healsSimHealth)
    }

    @Test
    fun `one generic part makes the whole logical send ambiguous`() {
        val verdict = aggregateOf(3, 0 to ok(), 1 to ok(), 2 to generic())

        assertEquals(LogicalSendOutcome.SENT_AMBIGUOUS, verdict.outcome)
        assertEquals(RetrySafety.POSSIBLE_DUPLICATE, verdict.retrySafety)
        assertFalse(verdict.healsSimHealth)
    }

    @Test
    fun `two generic parts stay ambiguous`() {
        val verdict = aggregateOf(2, 0 to generic(), 1 to generic())

        assertEquals(LogicalSendOutcome.SENT_AMBIGUOUS, verdict.outcome)
        assertEquals(RetrySafety.POSSIBLE_DUPLICATE, verdict.retrySafety)
    }

    @Test
    fun `all parts refused is a definite rejection`() {
        val verdict = aggregateOf(2, 0 to noService(), 1 to noService())

        assertEquals(LogicalSendOutcome.SENT_REJECTED, verdict.outcome)
        assertEquals("NO_SERVICE", verdict.failure?.code)
        assertEquals(RetrySafety.SAFE, verdict.retrySafety)
    }

    @Test
    fun `a rejection outranks an ambiguous part`() {
        val verdict = aggregateOf(3, 0 to generic(), 1 to noService(), 2 to ok())

        assertEquals(LogicalSendOutcome.SENT_REJECTED, verdict.outcome)
        assertEquals("NO_SERVICE", verdict.failure?.code)
    }

    @Test
    fun `throttling outranks a definite rejection`() {
        val verdict = aggregateOf(2, 0 to noService(), 1 to rateLimited())

        assertEquals(LogicalSendOutcome.THROTTLED, verdict.outcome)
        assertEquals("RIL_RATE_LIMITED", verdict.failure?.code)
    }

    // ── order independence and idempotency ───────────────────────────────────

    @Test
    fun `callback order cannot change the result`() {
        val forwards = aggregateOf(3, 0 to ok(), 1 to rateLimited(), 2 to noService())
        val backwards = aggregateOf(3, 2 to noService(), 1 to rateLimited(), 0 to ok())

        assertEquals(forwards.outcome, backwards.outcome)
        assertEquals(forwards.failure?.code, backwards.failure?.code)
    }

    @Test
    fun `a duplicate callback cannot complete the send twice or inflate the counts`() {
        val tally = SmsLogicalSendAggregator.Tally(3)
        assertTrue("first callback for part 0", tally.record(0, ok()))
        assertFalse("a duplicate is not a new part", tally.record(0, ok()))
        assertFalse("still incomplete with only one part", tally.isComplete())

        tally.record(1, ok())
        tally.record(2, ok())
        val verdict = SmsLogicalSendAggregator.aggregate(tally)

        assertEquals(LogicalSendOutcome.SENT_CONFIRMED, verdict.outcome)
        assertEquals("counts must reflect parts, not callbacks", 3, verdict.confirmedParts)
        assertEquals(3, verdict.totalParts)
    }

    @Test
    fun `an incomplete multipart send without a timeout is not confirmed`() {
        val tally = SmsLogicalSendAggregator.Tally(3)
        tally.record(0, ok())

        val verdict = SmsLogicalSendAggregator.aggregate(tally)

        assertEquals(LogicalSendOutcome.SENT_AMBIGUOUS, verdict.outcome)
        assertFalse(verdict.healsSimHealth)
    }

    // ── the timeout rule (mission §4) ───────────────────────────────────────

    @Test
    fun `a missing callback becomes an ambiguous timeout, never a failure`() {
        val tally = SmsLogicalSendAggregator.Tally(2)
        tally.record(0, ok())

        val verdict = SmsLogicalSendAggregator.aggregate(tally, timedOut = true)

        assertEquals(LogicalSendOutcome.SENT_AMBIGUOUS, verdict.outcome)
        assertEquals(SmsSendFailure.SendCallbackTimeout.code, verdict.failure?.code)
        assertEquals(RetrySafety.POSSIBLE_DUPLICATE, verdict.retrySafety)
        assertFalse("a missing callback must not be reported as not-sent", verdict.healsSimHealth)
    }
}

/**
 * The lane: one logical SMS per SIM at a time, closed bursts, bounded timeout, and aggregate-only
 * recovery.
 */
class SmsTransportGateLaneTest {

    @After
    fun tearDown() = SmsTransportGate.resetForTest()

    private val ok = android.app.Activity.RESULT_OK
    private val rateLimited = SmsManager.RESULT_RIL_REQUEST_RATE_LIMITED
    private val generic = SmsManager.RESULT_ERROR_GENERIC_FAILURE

    @Test
    fun `ten queued sends cannot all reach the radio before the first one resolves`() {
        val dispatches = Collections.synchronizedList(mutableListOf<Long>())
        val callers = 10
        val done = CountDownLatch(callers)
        val pool = Executors.newFixedThreadPool(callers)

        repeat(callers) { index ->
            pool.execute {
                try {
                    SmsTransportGate.submit(subscriptionId = 1, partCount = 1, rowId = index.toLong()) {
                        dispatches.add(index.toLong())
                    }
                } catch (_: SmsGateRejected) {
                    // A busy lane may refuse honestly; that is still not a burst.
                } finally {
                    done.countDown()
                }
            }
        }

        // The burst would happen in the first milliseconds if the lane did not hold.
        Thread.sleep(400)
        assertTrue(
            "at most ONE submit may be in flight before its SENT evidence arrives (saw ${dispatches.size})",
            dispatches.size <= 1
        )
        assertTrue("the first send did dispatch", dispatches.isNotEmpty())

        // Drain the queue: answer whichever send the lane currently has in flight.
        val deadline = System.currentTimeMillis() + 15_000L
        while (done.count > 0L && System.currentTimeMillis() < deadline) {
            val inFlight = SmsTransportGate.health(1).inFlightRowId
            if (inFlight != null) SmsTransportGate.onSentCallback(1, inFlight, 0, ok)
            Thread.sleep(20)
        }
        assertTrue("every queued caller must finish", done.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()
    }

    @Test
    fun `a single confirmed part does NOT heal the SIM`() {
        // A 3-part send where only part 1 is confirmed: no success may be recorded for the lane yet.
        SmsTransportGate.submit(1, 3, 1L) { }
        SmsTransportGate.onSentCallback(1, 1L, 0, ok)

        val health = SmsTransportGate.health(1)
        assertEquals(
            "a single RESULT_OK part is not health evidence",
            null,
            health.lastSuccessAt
        )
        assertEquals(1, health.inFlightResolvedParts)
        assertEquals(SmsTransportGate.LaneState.WAITING_SENT_CALLBACKS, health.laneState)
    }

    @Test
    fun `only a fully confirmed multipart send heals the lane`() {
        SmsTransportGate.submit(3, 3, 1L) { }
        SmsTransportGate.onSentCallback(3, 1L, 0, rateLimited)
        SmsTransportGate.onSentCallback(3, 1L, 1, ok)
        SmsTransportGate.onSentCallback(3, 1L, 2, ok)
        assertEquals(SmsTransportGate.Health.COOLDOWN, SmsTransportGate.health(3).state)

        // A complete, fully confirmed logical send.
        SmsTransportGate.submit(3, 2, 2L) { }
        SmsTransportGate.onSentCallback(3, 2L, 0, ok)
        SmsTransportGate.onSentCallback(3, 2L, 1, ok)

        val health = SmsTransportGate.health(3)
        assertEquals(SmsTransportGate.Health.HEALTHY, health.state)
        assertEquals(0, health.consecutiveFailures)
    }

    @Test
    fun `a callback for an older row does not resolve the send in flight`() {
        SmsTransportGate.submit(4, 2, 10L) { }

        // A late callback for a PREVIOUS row must be ignored.
        SmsTransportGate.onSentCallback(4, 9L, 0, ok)

        val health = SmsTransportGate.health(4)
        assertEquals("the current send is still awaiting its own evidence", 10L, health.inFlightRowId)
        assertEquals(0, health.inFlightResolvedParts)
    }

    @Test
    fun `duplicate callbacks do not complete a multipart send early`() {
        SmsTransportGate.submit(5, 3, 1L) { }
        SmsTransportGate.onSentCallback(5, 1L, 0, ok)
        SmsTransportGate.onSentCallback(5, 1L, 0, ok)
        SmsTransportGate.onSentCallback(5, 1L, 1, ok)

        assertEquals(
            "the duplicate must not count as part 3",
            2,
            SmsTransportGate.health(5).inFlightResolvedParts
        )
    }

    @Test
    fun `an ambiguous part does not open a cooldown but leaves the lane degraded`() {
        SmsTransportGate.submit(6, 1, 1L) { }
        SmsTransportGate.onSentCallback(6, 1L, 0, generic)

        val health = SmsTransportGate.health(6)
        assertEquals(SmsTransportGate.LaneState.IDLE, health.laneState)
        assertEquals("MODEM_FAILURE", health.lastFailureCode)
    }

    @Test
    fun `SIMs stay independent while one is waiting for evidence`() {
        SmsTransportGate.submit(7, 2, 1L) { }

        assertEquals(SmsTransportGate.LaneState.WAITING_SENT_CALLBACKS, SmsTransportGate.health(7).laneState)
        assertEquals(
            "SIM 8 must not be blocked by SIM 7's unresolved send",
            "ok",
            SmsTransportGate.submit(8, 1, 2L) { "ok" }
        )
    }

    private fun waitForInFlight(timeoutMs: Long = 5_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (SmsTransportGate.health(1).inFlightRowId != null) return
            Thread.sleep(10)
        }
    }
}
