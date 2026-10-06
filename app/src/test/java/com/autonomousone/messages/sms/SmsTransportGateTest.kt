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
 * The physical-send gate: one submit per SIM, independent SIMs, and a cooldown only after real
 * throttling evidence.
 *
 * The reliability incident this addresses: after a burst started failing on the real phone, later
 * messages kept failing. Nothing serialized submits per SIM, so the app could feed a radio that was
 * already refusing requests — and no code path recorded that the radio had asked for a pause.
 *
 * NOTE: written and executed as part of this change (pure, no Android runtime).
 */
class SmsTransportGateTest {

    @After
    fun tearDown() = SmsTransportGate.resetForTest()

    private fun throttleVerdict() =
        SmsTransportClassifier.classify(SmsManager.RESULT_RIL_REQUEST_RATE_LIMITED)

    private fun successVerdict() = SmsTransportClassifier.classify(android.app.Activity.RESULT_OK)

    // ── one physical submit per SIM ──────────────────────────────────────────

    @Test
    fun `ten concurrent submits on one SIM never overlap`() {
        val inFlight = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)
        val callers = 10
        val start = CountDownLatch(1)
        val done = CountDownLatch(callers)
        val pool = Executors.newFixedThreadPool(callers)

        repeat(callers) { index ->
            pool.execute {
                start.await()
                try {
                    SmsTransportGate.submit(subscriptionId = 1, partCount = 1, rowId = index.toLong()) {
                        val now = inFlight.incrementAndGet()
                        maxObserved.updateAndGet { max -> maxOf(max, now) }
                        Thread.sleep(5)
                        inFlight.decrementAndGet()
                        index
                    }
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue("all callers must finish", done.await(30, TimeUnit.SECONDS))
        pool.shutdownNow()

        assertEquals("two physical submits must never be in flight on one SIM", 1, maxObserved.get())
    }

    @Test
    fun `the submit block's result is returned to its caller`() {
        val result = SmsTransportGate.submit(subscriptionId = 1, partCount = 3, rowId = 42L) { "ok" }

        assertEquals("ok", result)
    }

    @Test
    fun `a busy lane refuses honestly instead of blocking for ever`() {
        val holder = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        pool.execute {
            SmsTransportGate.submit(subscriptionId = 5, partCount = 1, rowId = 1L) {
                holder.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        assertTrue(holder.await(10, TimeUnit.SECONDS))

        // A second lane is unaffected while the first is held.
        assertEquals("fast", SmsTransportGate.submit(6, 1, 2L) { "fast" })

        release.countDown()
        pool.shutdownNow()
    }

    // ── SIM independence ─────────────────────────────────────────────────────

    @Test
    fun `throttling one SIM does not block the other`() {
        SmsTransportGate.onSentCallback(
            subscriptionId = 1,
            verdict = throttleVerdict(),
            rowId = 1L,
            partIndex = 0,
            partCount = 1,
            errorCode = 42
        )

        assertEquals(
            "SIM 2 must keep working",
            "sent",
            SmsTransportGate.submit(subscriptionId = 2, partCount = 1, rowId = 2L) { "sent" }
        )
        assertEquals(SmsTransportGate.Health.COOLDOWN, SmsTransportGate.health(1).state)
        assertEquals(SmsTransportGate.Health.HEALTHY, SmsTransportGate.health(2).state)
    }

    // ── the cooldown ladder ──────────────────────────────────────────────────

    @Test
    fun `a rate-limited callback opens a cooldown on that SIM`() {
        val before = System.currentTimeMillis()

        SmsTransportGate.onSentCallback(1, throttleVerdict(), rowId = 7L, partIndex = 0, partCount = 2, errorCode = 42)
        val snapshot = SmsTransportGate.health(1)

        assertEquals(SmsTransportGate.Health.COOLDOWN, snapshot.state)
        assertTrue("a cooldown must be recorded", (snapshot.throttledUntil ?: 0L) > before)
        assertEquals("RIL_RATE_LIMITED", snapshot.lastFailureCode)
        assertEquals(SmsManager.RESULT_RIL_REQUEST_RATE_LIMITED, snapshot.lastResultCode)
        assertEquals(42, snapshot.lastRadioErrorCode)
        assertEquals(1, snapshot.consecutiveFailures)
    }

    @Test
    fun `repeated throttling escalates but stays capped`() {
        repeat(6) {
            SmsTransportGate.onSentCallback(3, throttleVerdict(), 1L, 0, 1, null)
        }

        val snapshot = SmsTransportGate.health(3)
        val remaining = (snapshot.throttledUntil ?: 0L) - System.currentTimeMillis()

        assertEquals(SmsTransportGate.Health.THROTTLED, snapshot.state)
        assertTrue("the cooldown must stay bounded, not grow for ever", remaining <= 60_000L)
        assertTrue(remaining > 0L)
    }

    @Test
    fun `the queue-limit code throttles too`() {
        SmsTransportGate.onSentCallback(
            4,
            SmsTransportClassifier.classify(SmsManager.RESULT_ERROR_LIMIT_EXCEEDED),
            1L, 0, 1, null
        )

        assertEquals(SmsTransportGate.Health.COOLDOWN, SmsTransportGate.health(4).state)
        assertEquals("QUEUE_LIMIT_EXCEEDED", SmsTransportGate.health(4).lastFailureCode)
    }

    // ── recovery is evidence-based ───────────────────────────────────────────

    @Test
    fun `only a confirmed success clears the cooldown and the failure streak`() {
        SmsTransportGate.onSentCallback(1, throttleVerdict(), 1L, 0, 1, null)
        assertEquals(1, SmsTransportGate.health(1).consecutiveFailures)

        // A real RESULT_OK callback heals the lane.
        SmsTransportGate.onSentCallback(1, successVerdict(), 2L, 0, 1, null)

        val snapshot = SmsTransportGate.health(1)
        assertEquals(SmsTransportGate.Health.HEALTHY, snapshot.state)
        assertEquals(0, snapshot.consecutiveFailures)
        assertNull("a healed lane must not still advertise a failure", snapshot.lastFailureCode)
        assertTrue((snapshot.lastSuccessAt ?: 0L) > 0L)
    }

    @Test
    fun `an ambiguous failure does not open a cooldown and does not heal`() {
        SmsTransportGate.onSentCallback(
            1,
            SmsTransportClassifier.classify(SmsManager.RESULT_ERROR_GENERIC_FAILURE),
            1L, 0, 1, null
        )

        val snapshot = SmsTransportGate.health(1)
        assertEquals(SmsTransportGate.Health.UNKNOWN, snapshot.state)
        assertNull(snapshot.lastSuccessAt)
        assertEquals("MODEM_FAILURE", snapshot.lastFailureCode)
    }

    @Test
    fun `two consecutive failures mark the transport degraded`() {
        val verdict = SmsTransportClassifier.classify(SmsManager.RESULT_RIL_SMS_SEND_FAIL_RETRY)
        SmsTransportGate.onSentCallback(8, verdict, 1L, 0, 1, null)
        SmsTransportGate.onSentCallback(8, verdict, 2L, 0, 1, null)

        val snapshot = SmsTransportGate.health(8)
        assertEquals(2, snapshot.consecutiveFailures)
        assertEquals(SmsTransportGate.Health.DEGRADED, snapshot.state)
    }

    // ── SIM change resets only transient health ─────────────────────────────

    @Test
    fun `a SIM change clears that lane's transient health`() {
        SmsTransportGate.onSentCallback(1, throttleVerdict(), 1L, 0, 1, null)
        SmsTransportGate.onSubscriptionChanged(1)

        val snapshot = SmsTransportGate.health(1)
        assertEquals(SmsTransportGate.Health.UNKNOWN, snapshot.state)
        assertEquals(0, snapshot.consecutiveFailures)
    }

    @Test
    fun `part count travels through the gate for future segment-aware policy`() {
        var seen = 0
        SmsTransportGate.submit(subscriptionId = 1, partCount = 3, rowId = 1L) { seen = 3 }

        assertEquals(3, seen)
        assertEquals(1, SmsTransportGate.health(1).recentSegments)
        assertFalse(SmsTransportGate.healthSnapshot().isEmpty())
    }
}
