package com.autonomousone.messages

import com.autonomousone.messages.observer.SmsContentObserver
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Timing contract of the SMS observer: the FIRST provider change must reach the
 * UI immediately (millisecond-live), not after a fixed debounce delay.
 *
 * These tests exercise the dispatch decision only — Handler/Looper work is not
 * available on the JVM, so the leading-edge path (which calls the callback
 * synchronously, before any postDelayed) is what is asserted here.
 */
class SmsObserverTimingTest {

    @Test
    fun `first change fires synchronously with no debounce delay`() {
        var fired = 0
        val observer = SmsContentObserver { fired++ }
        val startedAt = System.nanoTime()
        observer.onChange(false)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue("callback must fire on the leading edge, got fired=$fired", fired == 1)
        assertTrue("leading-edge dispatch must be immediate, took ${elapsedMs}ms", elapsedMs < 50)
    }

    @Test
    fun `a burst does not fire once per notification`() {
        var fired = 0
        val observer = SmsContentObserver { fired++ }
        repeat(10) { observer.onChange(false) }
        // Leading edge fires once; the remaining 9 collapse into a single
        // trailing call scheduled on the Handler (not run in this JVM test).
        assertTrue("burst must collapse, got fired=$fired", fired == 1)
    }
}
