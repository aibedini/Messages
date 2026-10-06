package com.autonomousone.messages.sms

import android.telephony.SmsManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The physical-send gate: exactly one logical SMS per SIM in flight, independent SIMs, an
 * evidence-driven cooldown, and recovery only from a fully confirmed logical send.
 *
 * NOTE: written and executed as part of this change (pure, no Android runtime). The per-part
 * heal/cooldown assertions this file used to make were superseded by the AGGREGATE rules — a single
 * `RESULT_OK` part must not heal a SIM — which are covered in [SmsLogicalSendAggregatorTest].
 */
class SmsTransportGateTest {

    private val ok = android.app.Activity.RESULT_OK
    private val rateLimited = SmsManager.RESULT_RIL_REQUEST_RATE_LIMITED
    private val queueLimited = SmsManager.RESULT_ERROR_LIMIT_EXCEEDED
    private val generic = SmsManager.RESULT_ERROR_GENERIC_FAILURE

    @After
    fun tearDown() = SmsTransportGate.resetForTest()

    /** A complete, confirmed logical send on [sub] — the only thing that heals a lane. */
    private fun confirmedSend(sub: Int, rowId: Long, parts: Int = 1) {
        SmsTransportGate.submit(sub, parts, rowId) { }
        repeat(parts) { part ->
            SmsTransportGate.onSentCallback(sub, rowId, part, ok)
        }
    }

    // ── one logical send per SIM ─────────────────────────────────────────────

    @Test
    fun `the submit block's result is returned to its caller`() {
        val result = SmsTransportGate.submit(subscriptionId = 1, partCount = 3, rowId = 42L) { "ok" }

        assertEquals("ok", result)
    }

    @Test
    fun `an unresolved send keeps a second dispatch away from the radio`() {
        SmsTransportGate.submit(subscriptionId = 1, partCount = 1, rowId = 1L) { }
        assertEquals(
            SmsTransportGate.LaneState.WAITING_SENT_CALLBACKS,
            SmsTransportGate.health(1).laneState
        )

        val dispatched = AtomicInteger(0)
        val done = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        pool.execute {
            runCatching {
                SmsTransportGate.submit(subscriptionId = 1, partCount = 1, rowId = 2L) {
                    dispatched.incrementAndGet()
                }
            }
            done.countDown()
        }
        Thread.sleep(300)
        assertEquals("the radio must not be reached while evidence is outstanding", 0, dispatched.get())

        // Resolve the first send: the queued one may now proceed.
        SmsTransportGate.onSentCallback(1, 1L, 0, ok)
        assertTrue("the queued send may proceed after resolution", done.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()
    }

    // ── SIM independence ─────────────────────────────────────────────────────

    @Test
    fun `an unresolved send on one SIM never blocks another`() {
        SmsTransportGate.submit(subscriptionId = 1, partCount = 2, rowId = 1L) { }

        assertEquals(SmsTransportGate.LaneState.WAITING_SENT_CALLBACKS, SmsTransportGate.health(1).laneState)
        assertEquals("ok", SmsTransportGate.submit(2, 1, 2L) { "ok" })
    }

    @Test
    fun `throttling one SIM does not block the other`() {
        SmsTransportGate.submit(1, 1, 1L) { }
        SmsTransportGate.onSentCallback(1, 1L, 0, rateLimited)

        assertEquals(SmsTransportGate.Health.COOLDOWN, SmsTransportGate.health(1).state)
        assertEquals("ok", SmsTransportGate.submit(2, 1, 2L) { "ok" })
        assertEquals(SmsTransportGate.Health.HEALTHY, SmsTransportGate.health(2).state)
    }

    // ── the cooldown ladder ──────────────────────────────────────────────────

    @Test
    fun `a rate-limited logical send opens a cooldown on that SIM`() {
        val before = System.currentTimeMillis()
        SmsTransportGate.submit(1, 1, 7L) { }
        SmsTransportGate.onSentCallback(1, 7L, 0, rateLimited, errorCode = 42)

        val snapshot = SmsTransportGate.health(1)
        assertEquals(SmsTransportGate.Health.COOLDOWN, snapshot.state)
        assertTrue("a cooldown must be recorded", (snapshot.throttledUntil ?: 0L) > before)
        assertEquals("RIL_RATE_LIMITED", snapshot.lastFailureCode)
        assertEquals(42, snapshot.lastRadioErrorCode)
        assertEquals(1, snapshot.consecutiveFailures)
    }

    @Test
    fun `repeated throttling escalates but stays capped`() {
        repeat(6) { index ->
            val row = (index + 1).toLong()
            SmsTransportGate.submit(3, 1, row) { }
            SmsTransportGate.onSentCallback(3, row, 0, rateLimited)
        }

        val snapshot = SmsTransportGate.health(3)
        val remaining = (snapshot.throttledUntil ?: 0L) - System.currentTimeMillis()

        assertEquals(SmsTransportGate.Health.THROTTLED, snapshot.state)
        assertTrue("the cooldown must stay bounded, not grow for ever", remaining <= 60_000L)
    }

    @Test
    fun `the queue-limit code throttles too`() {
        SmsTransportGate.submit(4, 1, 1L) { }
        SmsTransportGate.onSentCallback(4, 1L, 0, queueLimited)

        assertEquals(SmsTransportGate.Health.COOLDOWN, SmsTransportGate.health(4).state)
        assertEquals("QUEUE_LIMIT_EXCEEDED", SmsTransportGate.health(4).lastFailureCode)
    }

    // ── recovery is evidence-based ───────────────────────────────────────────

    @Test
    fun `a confirmed logical send clears the cooldown and the failure streak`() {
        SmsTransportGate.submit(1, 1, 1L) { }
        SmsTransportGate.onSentCallback(1, 1L, 0, rateLimited)
        assertEquals(1, SmsTransportGate.health(1).consecutiveFailures)

        confirmedSend(1, 2L)

        val snapshot = SmsTransportGate.health(1)
        assertEquals(SmsTransportGate.Health.HEALTHY, snapshot.state)
        assertEquals(0, snapshot.consecutiveFailures)
        assertNull("a healed lane must not still advertise a failure", snapshot.lastFailureCode)
        assertTrue((snapshot.lastSuccessAt ?: 0L) > 0L)
    }

    @Test
    fun `an ambiguous logical send does not open a cooldown and does not heal`() {
        SmsTransportGate.submit(1, 1, 1L) { }
        SmsTransportGate.onSentCallback(1, 1L, 0, generic)

        val snapshot = SmsTransportGate.health(1)
        assertEquals(SmsTransportGate.LaneState.IDLE, snapshot.laneState)
        assertNull(snapshot.lastSuccessAt)
        assertEquals("MODEM_FAILURE", snapshot.lastFailureCode)
    }

    @Test
    fun `two consecutive failures mark the transport degraded`() {
        val retryRequired = SmsManager.RESULT_RIL_SMS_SEND_FAIL_RETRY
        SmsTransportGate.submit(8, 1, 1L) { }
        SmsTransportGate.onSentCallback(8, 1L, 0, retryRequired)
        SmsTransportGate.submit(8, 1, 2L) { }
        SmsTransportGate.onSentCallback(8, 2L, 0, retryRequired)

        val snapshot = SmsTransportGate.health(8)
        assertEquals(2, snapshot.consecutiveFailures)
        assertEquals(SmsTransportGate.Health.DEGRADED, snapshot.state)
    }

    // ── SIM change resets only transient health ─────────────────────────────

    @Test
    fun `a SIM change clears that lane's transient health`() {
        SmsTransportGate.submit(1, 1, 1L) { }
        SmsTransportGate.onSentCallback(1, 1L, 0, rateLimited)
        SmsTransportGate.onSubscriptionChanged(1)

        val snapshot = SmsTransportGate.health(1)
        assertEquals(SmsTransportGate.Health.UNKNOWN, snapshot.state)
        assertEquals(0, snapshot.consecutiveFailures)
    }

    @Test
    fun `part count and recent submissions are visible for diagnostics`() {
        SmsTransportGate.submit(subscriptionId = 1, partCount = 3, rowId = 1L) { }

        val snapshot = SmsTransportGate.health(1)
        assertEquals(3, snapshot.inFlightParts)
        assertEquals(1L, snapshot.inFlightRowId)
        assertEquals(1, snapshot.recentSegments)
        assertFalse(SmsTransportGate.healthSnapshot().isEmpty())
    }
}
