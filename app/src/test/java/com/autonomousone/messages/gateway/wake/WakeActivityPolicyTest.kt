package com.autonomousone.messages.gateway.wake

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * STAGE-4 §3/§6: the ACTIVE/IDLE window is a pure, tested function, and its wiring opens and
 * closes the socket.
 */
class WakeActivityPolicyTest : WakeSocketTestBase() {

    private fun inputs(
        now: Long = 1_000_000L,
        web: Boolean = false,
        foreground: Boolean = false,
        lastMessageAt: Long? = null,
        outbox: Boolean = false,
    ) = WakeActivityInputs(
        webClientLive = web,
        appForeground = foreground,
        lastMessageAt = lastMessageAt,
        outboxPending = outbox,
        now = now,
    )

    @Test
    fun `truth table for all four inputs`() {
        val now = 1_000_000L
        assertEquals(
            WakeActivity.ACTIVE to WakeActivityReason.WEB_CLIENT_WATCHING,
            WakeActivityPolicy.desired(inputs(now, web = true))
        )
        assertEquals(
            WakeActivity.ACTIVE to WakeActivityReason.APP_FOREGROUND,
            WakeActivityPolicy.desired(inputs(now, foreground = true))
        )
        assertEquals(
            WakeActivity.ACTIVE to WakeActivityReason.RECENT_MESSAGE,
            WakeActivityPolicy.desired(inputs(now, lastMessageAt = now - 60_000L))
        )
        assertEquals(
            WakeActivity.ACTIVE to WakeActivityReason.OUTBOX_PENDING,
            WakeActivityPolicy.desired(inputs(now, outbox = true))
        )
        assertEquals(
            WakeActivity.IDLE to WakeActivityReason.NONE,
            WakeActivityPolicy.desired(inputs(now))
        )
    }

    @Test
    fun `the message window is five minutes and its boundary is inclusive`() {
        val now = 2_000_000L
        assertEquals(
            WakeActivity.ACTIVE,
            WakeActivityPolicy.desired(
                inputs(now, lastMessageAt = now - WakeActivityPolicy.SMS_WINDOW_MS)
            ).first
        )
        assertEquals(
            WakeActivity.IDLE,
            WakeActivityPolicy.desired(
                inputs(now, lastMessageAt = now - WakeActivityPolicy.SMS_WINDOW_MS - 1L)
            ).first
        )
    }

    @Test
    fun `precedence is web then foreground then message then outbox`() {
        val now = 3_000_000L
        val everything = inputs(
            now = now,
            web = true,
            foreground = true,
            lastMessageAt = now - 1L,
            outbox = true,
        )
        assertEquals(
            WakeActivityReason.WEB_CLIENT_WATCHING,
            WakeActivityPolicy.desired(everything).second
        )
        assertEquals(
            WakeActivityReason.APP_FOREGROUND,
            WakeActivityPolicy.desired(everything.copy(webClientLive = false)).second
        )
        assertEquals(
            WakeActivityReason.RECENT_MESSAGE,
            WakeActivityPolicy.desired(
                everything.copy(webClientLive = false, appForeground = false)
            ).second
        )
        assertEquals(
            WakeActivityReason.OUTBOX_PENDING,
            WakeActivityPolicy.desired(
                everything.copy(
                    webClientLive = false,
                    appForeground = false,
                    lastMessageAt = null
                )
            ).second
        )
    }

    @Test
    fun `a transition only takes effect after three stable seconds`() {
        val start = WakeActivityPolicy.next(null, inputs(now = 1_000L))
        assertEquals(WakeActivity.IDLE, start.activity)

        val pending = WakeActivityPolicy.next(start, inputs(now = 2_000L, foreground = true))
        assertEquals(WakeActivity.IDLE, pending.activity)
        assertEquals(WakeActivity.ACTIVE, pending.pendingActivity)

        val notYet = WakeActivityPolicy.next(pending, inputs(now = 4_999L, foreground = true))
        assertEquals(WakeActivity.IDLE, notYet.activity)

        val switched = WakeActivityPolicy.next(notYet, inputs(now = 5_000L, foreground = true))
        assertEquals(WakeActivity.ACTIVE, switched.activity)
        assertEquals(WakeActivityReason.APP_FOREGROUND, switched.reason)
        assertEquals(2_000L, switched.since)
    }

    @Test
    fun `a flap shorter than the debounce does not transition`() {
        val start = WakeActivityPolicy.next(null, inputs(now = 1_000L))
        val wantActive = WakeActivityPolicy.next(start, inputs(now = 2_000L, foreground = true))
        val wantIdle = WakeActivityPolicy.next(wantActive, inputs(now = 2_500L))

        assertEquals(WakeActivity.IDLE, wantIdle.activity)
        assertEquals(null, wantIdle.pendingActivity)
    }

    @Test
    fun `the wiring opens the socket when the window becomes active and closes it when idle`() {
        val clock = MutableClock(500_000L)
        var foreground = false
        val server = newServer()
        val runtime = newRuntime(
            server = server,
            clock = clock,
            activityPollMs = 40L,
            signals = { WakeActivitySignals(appForeground = foreground) },
        )
        Thread.sleep(150L)
        assertEquals("an idle window must not open a socket", 0, server.requestCount.get())

        foreground = true
        Thread.sleep(150L) // let the policy record the pending ACTIVE transition
        clock.now += WakeActivityPolicy.DEBOUNCE_MS + 100L
        WakeTestHarness.awaitUntil(description = "socket opened on ACTIVE") {
            server.upgradeCount.get() == 1
        }

        foreground = false
        Thread.sleep(150L)
        clock.now += WakeActivityPolicy.DEBOUNCE_MS + 100L
        WakeTestHarness.awaitUntil(description = "socket closed on IDLE") {
            runtime.lastHealth.get().socketState == WakeSocketState.CLOSED
        }
        assertEquals(WakeActivity.IDLE, runtime.lastHealth.get().activity)
    }
}
