package com.autonomousone.messages.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 1.3 — the stall watchdog must not run on every user's phone.
 *
 * The release cost was real and derivable: a foreground gateway service keeps the process alive, and
 * a 250 ms probe cadence is four main-looper posts a second, forever — 345 600 a day. The DEBUG gate
 * is the saving; the 1 s cadence is the residual one.
 */
class StallWatchdogGateTest {

    @Test
    fun `release builds do not install the watchdog by default`() {
        assertFalse(
            "a normal release install must not pay the probe cost",
            StallWatchdogGate.shouldInstall(isDebugBuild = false, preferenceEnabled = false)
        )
    }

    @Test
    fun `debug builds always install it`() {
        assertTrue(StallWatchdogGate.shouldInstall(isDebugBuild = true, preferenceEnabled = false))
        assertTrue(StallWatchdogGate.shouldInstall(isDebugBuild = true, preferenceEnabled = true))
    }

    @Test
    fun `a release build installs it when the developer option is on`() {
        assertTrue(
            "the capability stays available for the ANR work",
            StallWatchdogGate.shouldInstall(isDebugBuild = false, preferenceEnabled = true)
        )
    }
}
