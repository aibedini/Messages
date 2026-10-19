package com.autonomousone.messages.gateway.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Stage 2 — the AUTO ladder, and the rule that an explicit choice which cannot run is REPORTED.
 *
 * In this stage the capability flags are hard-wired false in production, so every device resolves to
 * POLLING. The ladder is still tested now so Stages 4 and 5 only have to flip a flag.
 */
class WakeSourceSelectorTest {

    private fun inputs(mode: WakeMode, fcm: Boolean = false, socket: Boolean = false) =
        WakeSourceSelector.Inputs(configured = mode, fcmAvailable = fcm, socketAvailable = socket)

    @Test
    fun `AUTO with nothing available falls back to polling`() {
        val resolution = WakeSourceSelector.resolve(inputs(WakeMode.AUTO))
        assertEquals(WakeSourceId.POLLING, resolution.chosen)
        assertNull("AUTO is a ladder, not a broken promise", resolution.fellBackFrom)
    }

    @Test
    fun `AUTO prefers socket when only socket is available`() {
        assertEquals(
            WakeSourceId.SOCKET,
            WakeSourceSelector.resolve(inputs(WakeMode.AUTO, socket = true)).chosen
        )
    }

    @Test
    fun `AUTO prefers FCM when both are available`() {
        assertEquals(
            WakeSourceId.FCM,
            WakeSourceSelector.resolve(inputs(WakeMode.AUTO, fcm = true, socket = true)).chosen
        )
    }

    @Test
    fun `an explicit unavailable SOCKET falls back to polling and says so`() {
        val resolution = WakeSourceSelector.resolve(inputs(WakeMode.SOCKET))
        assertEquals(WakeSourceId.POLLING, resolution.chosen)
        assertEquals(WakeSourceId.SOCKET, resolution.fellBackFrom)
        assertEquals(WakeUnavailable.NOT_CONFIGURED, resolution.unavailable)
    }

    @Test
    fun `an explicit unavailable FCM falls back to polling and says so`() {
        val resolution = WakeSourceSelector.resolve(inputs(WakeMode.FCM))
        assertEquals(WakeSourceId.POLLING, resolution.chosen)
        assertEquals(WakeSourceId.FCM, resolution.fellBackFrom)
        assertEquals(WakeUnavailable.NO_PLATFORM_SUPPORT, resolution.unavailable)
    }

    @Test
    fun `an explicit available mode is honoured without a fallback`() {
        val socket = WakeSourceSelector.resolve(inputs(WakeMode.SOCKET, socket = true))
        assertEquals(WakeSourceId.SOCKET, socket.chosen)
        assertNull(socket.fellBackFrom)

        val fcm = WakeSourceSelector.resolve(inputs(WakeMode.FCM, fcm = true))
        assertEquals(WakeSourceId.FCM, fcm.chosen)
        assertNull(fcm.fellBackFrom)
    }

    @Test
    fun `explicit POLLING is never a fallback`() {
        val resolution = WakeSourceSelector.resolve(inputs(WakeMode.POLLING))
        assertEquals(WakeSourceId.POLLING, resolution.chosen)
        assertNull(resolution.fellBackFrom)
    }

    /** Totality: every combination of the four inputs resolves, none throws. */
    @Test
    fun `resolve is total over every input combination`() {
        var combinations = 0
        for (mode in WakeMode.values()) {
            for (fcm in listOf(false, true)) {
                for (socket in listOf(false, true)) {
                    val resolution = WakeSourceSelector.resolve(inputs(mode, fcm, socket))
                    // A fallback must name a DIFFERENT source, or it is not a fallback.
                    if (resolution.fellBackFrom != null) {
                        assertEquals(WakeSourceId.POLLING, resolution.chosen)
                    }
                    combinations++
                }
            }
        }
        assertEquals("4 modes x 2 x 2", 16, combinations)
    }

    // ── Preference parsing (the value survives app upgrades) ─────────────────

    @Test
    fun `an unset or unknown preference reads AUTO`() {
        assertEquals(WakeMode.AUTO, WakeMode.parse(null))
        assertEquals(WakeMode.AUTO, WakeMode.parse(""))
        assertEquals(WakeMode.AUTO, WakeMode.parse("  "))
        assertEquals(WakeMode.AUTO, WakeMode.parse("CARRIER_PIGEON"))
    }

    @Test
    fun `stored values round-trip case-insensitively`() {
        assertEquals(WakeMode.POLLING, WakeMode.parse("POLLING"))
        assertEquals(WakeMode.SOCKET, WakeMode.parse(" socket "))
        assertEquals(WakeMode.FCM, WakeMode.parse("Fcm"))
        assertEquals(WakeMode.AUTO, WakeMode.parse("auto"))
    }

    @Test
    fun `AUTO resolves to POLLING with only polling implemented`() {
        // This is the production wiring in Stage 2: both capability flags are false.
        assertEquals(
            WakeSourceId.POLLING,
            WakeSourceSelector.resolve(
                WakeSourceSelector.Inputs(
                    configured = WakeMode.parse(null),
                    fcmAvailable = false,
                    socketAvailable = false
                )
            ).chosen
        )
    }
}
