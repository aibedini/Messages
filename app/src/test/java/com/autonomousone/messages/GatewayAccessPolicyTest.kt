package com.autonomousone.messages

import com.autonomousone.messages.gateway.GatewayAccessPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayAccessPolicyTest {
    @Test fun `gateway cannot start without consent`() {
        assertFalse(GatewayAccessPolicy.canStart(false))
        assertTrue(GatewayAccessPolicy.canStart(true))
    }

    @Test fun `transmission requires both current consent and enabled state`() {
        assertFalse(GatewayAccessPolicy.canTransmit(false, false))
        assertFalse(GatewayAccessPolicy.canTransmit(false, true))
        assertFalse(GatewayAccessPolicy.canTransmit(true, false))
        assertTrue(GatewayAccessPolicy.canTransmit(true, true))
    }
}
