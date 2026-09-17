package com.autonomousone.messages

import com.autonomousone.messages.observer.SmsContentObserver
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Timing contract of the SMS observer, driven by an injected clock so the
 * coalescing window is DETERMINISTIC.
 *
 * History: these assertions used to compare wall-clock against the real 150 ms
 * window. On a loaded machine that measured cold-start class loading instead of
 * the contract (the leading-edge check once took 67 ms for pure string work) and
 * the ten-call burst could span the window and produce a second leading edge.
 * A clock-driven test proves the actual rule with no sleeps and no flakiness.
 *
 * Handler/Looper work is not available on the JVM, so the trailing callback is
 * never pumped here — which is exactly why "fired" can only be incremented by
 * the synchronous leading-edge dispatch.
 */
class SmsObserverTimingTest {

    @Test
    fun `first change fires synchronously with no debounce delay`() {
        var now = 1000L
        var fired = 0
        val observer = SmsContentObserver(clock = { now }) { fired++ }

        observer.onChange(false)

        // The callback ran during onChange (nothing pumps the Handler here), and
        // it ran on the FIRST notification: no debounce delay exists.
        assertEquals(1, fired)
    }

    @Test
    fun `a burst inside the window collapses into one trailing call`() {
        var now = 1000L
        var fired = 0
        val observer = SmsContentObserver(clock = { now }) { fired++ }

        repeat(10) {
            observer.onChange(false)
            now += 10L // still inside the 150 ms coalesce window
        }

        assertEquals("leading edge once; the rest collapse", 1, fired)
    }

    @Test
    fun `a later change outside the window is a new leading edge`() {
        var now = 1000L
        var fired = 0
        val observer = SmsContentObserver(clock = { now }) { fired++ }

        observer.onChange(false)
        assertEquals(1, fired)

        now += 200L // past the window
        observer.onChange(false)
        assertEquals(2, fired)
    }

    @Test
    fun `exactly at the window boundary is a new leading edge`() {
        var now = 1000L
        var fired = 0
        val observer = SmsContentObserver(clock = { now }) { fired++ }

        observer.onChange(false)
        now += 150L
        observer.onChange(false)

        assertEquals(2, fired)
    }
}
