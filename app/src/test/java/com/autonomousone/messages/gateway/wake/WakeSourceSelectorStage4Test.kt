package com.autonomousone.messages.gateway.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** STAGE-4 deployment gate: AUTO stays POLLING until GMweb deploys `/gateway/wake`. */
class WakeSourceSelectorStage4Test {

    private fun inputs(
        mode: WakeMode,
        socketAvailable: Boolean,
        autoEligible: Boolean,
    ) = WakeSourceSelector.Inputs(
        configured = mode,
        fcmAvailable = false,
        socketAvailable = socketAvailable,
        socketAutoEligible = autoEligible,
    )

    @Test
    fun `AUTO with a socket capability still resolves to POLLING while the server is undeployed`() {
        val resolution = WakeSourceSelector.resolve(
            inputs(WakeMode.AUTO, socketAvailable = true, autoEligible = false)
        )
        assertEquals(WakeSourceId.POLLING, resolution.chosen)
        assertNull("a missing server capability is not a reported fallback", resolution.fellBackFrom)
        assertNull(resolution.unavailable)
    }

    @Test
    fun `an explicit SOCKET choice selects the socket when it is configured`() {
        val resolution = WakeSourceSelector.resolve(
            inputs(WakeMode.SOCKET, socketAvailable = true, autoEligible = false)
        )
        assertEquals(WakeSourceId.SOCKET, resolution.chosen)
        assertNull(resolution.fellBackFrom)
    }

    @Test
    fun `an explicit SOCKET choice reports the fallback when it cannot run`() {
        val resolution = WakeSourceSelector.resolve(
            inputs(WakeMode.SOCKET, socketAvailable = false, autoEligible = false)
        )
        assertEquals(WakeSourceId.POLLING, resolution.chosen)
        assertEquals(WakeSourceId.SOCKET, resolution.fellBackFrom)
        assertEquals(WakeUnavailable.NOT_CONFIGURED, resolution.unavailable)
    }
}
